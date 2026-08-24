package org.zhzssp.memorandum.feature.codex.sediment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.agent.runtime.ConversationMemory;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.repository.KbCheckpointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbSectionRepository;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 沉淀编排（SEDIMENTER）。
 *
 * <h3>它落地的是用户已有的五步工作流</h3>
 * <pre>
 * 1 确认内容   → 从会话取那次被认可的回答（服务端取，不由模型自述）
 * 2 确认挂靠点 → guide 路径 + 章节 anchor
 * 3 写笔记     → docs/notes/&lt;topic&gt;.md，套固定模板
 * 4 加引用     → 在 guide 指定章节末尾插入一行速记引用
 * 5 收尾       → 回报路径与插入位置，★不 commit
 * </pre>
 *
 * <h3>为什么第 5 步刻意不提交</h3>
 * <p>这既是原工作流的明确要求，也是产品上正确的：提交与否是「我认可这份产出」的表达。
 * 让软件替用户表达认可，等于把审阅这一环取消掉。
 * 所以本服务把改动留在工作分支的未提交状态，用户看过 diff 之后再显式提交。</p>
 *
 * <h3>写文件的顺序：先全部校验，最后一次性落盘</h3>
 * <p>笔记与 guide 是两次写入。若边校验边写，anchor 校验失败时就会留下
 * 「笔记写了但引用没插」的半成品——而那正是双向性检查会报 ERROR 的状态。
 * 因此把两份新内容都在内存里算好、校验通过后再连续落盘。</p>
 */
@Service
public class SedimentService {

    private static final Logger log = LoggerFactory.getLogger(SedimentService.class);

    /** 已存在同名笔记时的处置方式。 */
    public enum WriteMode {
        /** 只新建；已存在则拒绝。 */
        CREATE,
        /** 追加到既有笔记末尾（同主题应当追加而非另起一篇）。 */
        APPEND,
        /** 整体替换既有内容。 */
        REPLACE
    }

    /**
     * 沉淀请求。
     *
     * @param sourceExcerpt 被沉淀的原始内容。<strong>sessionId 存在时以会话为准</strong>——
     *                      让模型自述"原文是什么"等于让它自己给自己出考题
     * @param summary       速记引用里的一句话摘要，作用是让扫读者决定是否点开
     */
    public record Request(String repoName, String title, String body, String summary,
                          String guidePath, String anchor, String sectionLabel,
                          String notePath, String sourceExcerpt, String sessionId,
                          WriteMode mode, Boolean createBranch, Boolean insertBackref) {}

    public record Result(boolean ok, String code, String message,
                         String branch, String notePath, String guidePath,
                         Integer backrefLine, String backrefText,
                         List<String> changedFiles, String reindex,
                         String nextStep, List<String> availableAnchors) {

        static Result fail(String code, String message) {
            return new Result(false, code, message, null, null, null, null, null,
                    List.of(), null, null, List.of());
        }

        static Result fail(String code, String message, List<String> anchors) {
            return new Result(false, code, message, null, null, null, null, null,
                    List.of(), null, null, anchors);
        }
    }

    /** 由「通过但预测错」生成的笔记草稿。 */
    public record Draft(String title, String body, String summary,
                        String guidePath, String anchor, String source) {}

    private final RepoRegistryService registry;
    private final RepoWriteService writeService;
    private final RepoSyncService syncService;
    private final DocWriteGuard guard;
    private final NoteTemplate template;
    private final BackrefInserter inserter;
    private final FrontMatterParser fm;
    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;
    private final KbCheckpointRepository cpRepo;
    private final ConversationMemory memory;
    private final CodexMetrics metrics;

    @Value("${codex.write.note-dir:docs/notes}")
    private String noteDir;

    public SedimentService(RepoRegistryService registry,
                           RepoWriteService writeService,
                           RepoSyncService syncService,
                           DocWriteGuard guard,
                           NoteTemplate template,
                           BackrefInserter inserter,
                           FrontMatterParser fm,
                           KbDocumentRepository docRepo,
                           KbSectionRepository sectionRepo,
                           KbCheckpointRepository cpRepo,
                           ConversationMemory memory,
                           CodexMetrics metrics) {
        this.registry = registry;
        this.writeService = writeService;
        this.syncService = syncService;
        this.guard = guard;
        this.template = template;
        this.inserter = inserter;
        this.fm = fm;
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
        this.cpRepo = cpRepo;
        this.memory = memory;
        this.metrics = metrics;
    }

    /* ==================== 主流程 ==================== */

    public Result sediment(Long userId, Request req) {
        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return Result.fail(en.code(), en.message() + " " + nullSafe(en.hint()));
        }
        if (req == null || req.title() == null || req.title().isBlank()) {
            return Result.fail("TITLE_EMPTY", "笔记标题为空。");
        }
        if (req.body() == null || req.body().isBlank()) {
            return Result.fail("BODY_EMPTY", "笔记正文为空。");
        }

        KnowledgeRepo repo = resolveRepo(userId, req.repoName());
        if (repo == null) {
            return Result.fail("REPO_NOT_FOUND",
                    "未找到知识仓库" + (req.repoName() == null ? "" : "：" + req.repoName()));
        }

        metrics.recordSedimentAttempt();

        // ---- 权威原文：会话优先于模型自述 ----
        String source = resolveSource(req);
        if (source == null) {
            return Result.fail("MISSING_SOURCE",
                    "无法确定被沉淀的原始内容。请通过「沉淀这段」入口发起（服务端从会话取原文），"
                            + "或显式提供 sourceExcerpt。");
        }

        // ---- 路径 ----
        String notePath = req.notePath() != null && !req.notePath().isBlank()
                ? req.notePath().replace('\\', '/').strip()
                : trimSlash(noteDir) + "/" + template.slug(req.title()) + ".md";
        DocWriteGuard.Decision pathOk = guard.checkPath(repo, notePath);
        if (!pathOk.allowed()) {
            return Result.fail(pathOk.code(), pathOk.message() + " " + nullSafe(pathOk.hint()));
        }

        // ---- 示例入库（执行层强制）----
        DocWriteGuard.Decision ex = guard.checkExamples(source, req.body());
        if (!ex.allowed()) {
            metrics.recordExampleGateRejection();
            return Result.fail(ex.code(), ex.message() + " " + nullSafe(ex.hint()));
        }

        boolean wantBackref = !Boolean.FALSE.equals(req.insertBackref());
        KbDocument guide = null;
        if (wantBackref) {
            if (req.guidePath() == null || req.guidePath().isBlank()) {
                return Result.fail("GUIDE_REQUIRED",
                        "未指定挂靠的知识文档。没有回挂的笔记是检索不到的孤岛——"
                                + "内容再好也很难被再次找到。"
                                + "若确实只想建笔记，请显式传 insertBackref=false。");
            }
            guide = findDoc(userId, req.guidePath());
            if (guide == null) {
                return Result.fail("GUIDE_NOT_FOUND",
                        "索引中没有这篇知识文档：" + req.guidePath()
                                + "。可先用 doc.search 确认路径，或 repo.sync 更新索引。");
            }
            if (req.anchor() == null || req.anchor().isBlank()) {
                return Result.fail("ANCHOR_REQUIRED",
                        "未指定插入位置的章节 anchor。请先 doc.outline 查看目录再指定——"
                                + "凭标题猜 anchor 会把引用插到无关章节。",
                        anchorsOf(guide));
            }
            // 先用索引快速否掉明显错误的 anchor，避免为一次注定失败的调用建分支
            if (!sectionRepo.existsByDocumentIdAndAnchor(guide.getId(), req.anchor().strip())) {
                boolean caseInsensitiveHit = anchorsOf(guide).stream()
                        .anyMatch(a -> a.equalsIgnoreCase(req.anchor().strip()));
                if (!caseInsensitiveHit) {
                    return Result.fail("ANCHOR_NOT_FOUND",
                            "文档 " + guide.getPath() + " 中不存在 anchor「" + req.anchor() + "」。",
                            anchorsOf(guide));
                }
            }
        }

        Path root = registry.rootOf(repo);
        Path noteFile = root.resolve(notePath);
        WriteMode mode = req.mode() == null ? WriteMode.CREATE : req.mode();
        boolean noteExists = Files.isRegularFile(noteFile);
        if (noteExists && mode == WriteMode.CREATE) {
            return Result.fail("NOTE_EXISTS",
                    "笔记已存在：" + notePath
                            + "。同主题应当追加或修订既有文件，而不是另起一篇——"
                            + "同一概念散在两处会让以后两处都不敢信。"
                            + "请用 mode=APPEND 追加，或 mode=REPLACE 覆盖。");
        }

        // ★P4 引入 create-only 白名单后必须补的一道校验。
        //
        // checkPath 现在也会放行 create-only 路径（docs/paper-notes、docs/checkpoints），
        // 而本方法随后是用 TRUNCATE_EXISTING 写盘的。若只靠 checkPath，
        // 一次 doc.write(path=docs/paper-notes/已有文件.md, mode=REPLACE) 就能
        // 覆盖掉用户手写的论文精读——这正是 create-only 要防的事，
        // 却会从这条更早存在的路径漏过去。
        //
        // 教训是通用的：放宽一处白名单，必须回头把所有只做过路径校验的写入路径都补上覆盖权校验。
        DocWriteGuard.Decision creatable = guard.checkCreatable(
                repo, notePath, mode != WriteMode.CREATE || noteExists);
        if (!creatable.allowed()) {
            return Result.fail(creatable.code(),
                    creatable.message() + " " + nullSafe(creatable.hint()));
        }

        // ---- 分支（切分支会改动工作副本，必须在读取 guide 之前完成）----
        String branch = null;
        if (!Boolean.FALSE.equals(req.createBranch())) {
            String name = writeService.branchNameFor("sediment", template.slug(req.title()));
            RepoWriteService.BranchResult br = writeService.ensureBranch(repo, name);
            if (!br.ok()) {
                return Result.fail(br.code(), br.message());
            }
            branch = br.branch();
        } else {
            DocWriteGuard.Decision b = guard.checkBranch(repo);
            if (!b.allowed()) {
                return Result.fail(b.code(), b.message() + " " + nullSafe(b.hint()));
            }
            try {
                branch = writeService.diff(repo, null).branch();
            } catch (Exception ignored) {
                branch = null;
            }
        }

        // ---- 组装两份新内容（尚未落盘）----
        String noteContent;
        try {
            noteContent = buildNoteContent(noteFile, notePath, req, guide, mode, noteExists);
        } catch (Exception e) {
            return Result.fail("NOTE_BUILD_FAILED", "生成笔记内容失败：" + e.getMessage());
        }
        DocWriteGuard.Decision size = guard.checkSize(noteContent);
        if (!size.allowed()) {
            return Result.fail(size.code(), size.message() + " " + nullSafe(size.hint()));
        }

        String guideNewContent = null;
        Integer backrefLine = null;
        String backrefText = null;
        String backrefNote = null;
        if (wantBackref) {
            Path guideFile = root.resolve(guide.getPath());
            String guideContent;
            try {
                guideContent = Files.readString(guideFile, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return Result.fail("GUIDE_READ_FAILED",
                        "读取知识文档失败：" + guide.getPath() + " — " + e.getMessage());
            }
            backrefText = template.backrefLine(guide.getPath(), notePath, req.summary());
            int bodyStart = fm.parse(guideContent).bodyStart();
            BackrefInserter.Result ins = inserter.insert(
                    guideContent, bodyStart, req.anchor(), backrefText, notePath);
            switch (ins.outcome()) {
                case ANCHOR_NOT_FOUND -> {
                    return Result.fail("ANCHOR_NOT_FOUND", ins.message(), ins.availableAnchors());
                }
                case ALREADY_PRESENT -> backrefNote = ins.message();
                case INSERTED -> {
                    guideNewContent = ins.newContent();
                    backrefLine = ins.line();
                }
            }
        }

        // ---- 落盘 ----
        Set<String> changed = new LinkedHashSet<>();
        try {
            Files.createDirectories(noteFile.getParent());
            Files.writeString(noteFile, noteContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            changed.add(notePath);
            if (guideNewContent != null) {
                Files.writeString(root.resolve(guide.getPath()), guideNewContent,
                        StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                changed.add(guide.getPath());
                metrics.recordBackrefInserted();
            }
        } catch (Exception e) {
            return Result.fail("WRITE_FAILED", "写入文件失败：" + e.getMessage()
                    + "。已写入的部分保留在工作分支上，可通过丢弃分支撤销。");
        }

        // ---- 重建索引：不做的话新笔记检索不到，用户会以为沉淀没生效 ----
        String reindex;
        try {
            RepoSyncService.SyncResult sr = syncService.sync(repo, false, false);
            reindex = "已增量重建索引：重建 " + sr.report().docsReindexed()
                    + " 篇，跳过 " + sr.report().docsSkipped() + " 篇";
        } catch (Exception e) {
            // 索引失败不该让沉淀算失败——文件已经在仓库里了，这才是权威源
            reindex = "索引重建失败（文件已写入，可稍后手动 repo.sync）：" + e.getMessage();
            log.warn("[Codex] 沉淀后重建索引失败：{}", e.getMessage());
        }

        metrics.recordSedimentSuccess();
        String msg = "已沉淀笔记 " + notePath
                + (backrefLine != null ? "，并在 " + guide.getPath() + " 第 " + backrefLine + " 行插入速记引用" : "")
                + (backrefNote != null ? "（" + backrefNote + "）" : "");
        return new Result(true, "SEDIMENTED", msg, branch, notePath,
                guide == null ? null : guide.getPath(), backrefLine, backrefText,
                new ArrayList<>(changed), reindex,
                "改动停在工作分支的未提交状态。请先审阅 diff，确认后再提交——"
                        + "提交与否表达的是「我认可这份产出」，这一步刻意留给你。",
                List.of());
    }

    /* ==================== 由「通过但预测错」生成草稿 ==================== */

    /**
     * 把 P1 采集的「通过但预测错」转成笔记草稿。
     *
     * <p>这是整套设计里少有的、别的工具拿不到的原料：
     * {@code divergence} 记录的是<strong>心智模型被修正的那一瞬间</strong>
     * （「我原以为…实际…」）。这种内容的沉淀价值远高于事后复述正确结论——
     * 正确结论到处都能查到，而"我曾经错在哪里"只有自己这一份。</p>
     *
     * <p>刻意只产草稿不直接写入：正文必须由用户过一遍，
     * 否则就变成了机器替人总结自己的认知错误。</p>
     */
    public Optional<Draft> divergenceDraft(Long userId, String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        KbCheckpoint cp = cpRepo.findByUserIdAndCode(userId, code.strip()).orElse(null);
        if (cp == null) return Optional.empty();
        if (cp.getDivergence() == null || cp.getDivergence().isBlank()) return Optional.empty();

        KbDocument doc = cp.getDocumentId() == null ? null
                : docRepo.findById(cp.getDocumentId()).orElse(null);

        String title = cp.getTitle() == null || cp.getTitle().isBlank()
                ? cp.getCode() + " 的认知修正" : cp.getTitle().strip();

        StringBuilder body = new StringBuilder();
        body.append("## 我原以为\n\n").append(cp.getDivergence().strip()).append("\n\n");
        if (cp.getChecksWhat() != null && !cp.getChecksWhat().isBlank()) {
            body.append("## 这条检验在检验什么\n\n")
                    .append(cp.getChecksWhat().strip()).append("\n\n");
        }
        body.append("## 示例\n\n")
                .append("<!-- 把验收时的实际输出 / 关键 diff 粘在这里；")
                .append("示例被丢掉的笔记，半年后自己也读不懂 -->\n");

        String summary = "预测与实际不一致：" + shorten(cp.getDivergence(), 60);
        return Optional.of(new Draft(title, body.toString(), summary,
                doc == null ? null : doc.getPath(), cp.getSectionAnchor(),
                "checkpoint:" + cp.getCode()));
    }

    /* ==================== 内部 ==================== */

    private String buildNoteContent(Path noteFile, String notePath, Request req,
                                    KbDocument guide, WriteMode mode, boolean exists)
            throws Exception {
        if (exists && mode == WriteMode.APPEND) {
            String old = Files.readString(noteFile, StandardCharsets.UTF_8);
            String addition = req.body().strip();
            StringBuilder sb = new StringBuilder(old);
            if (!old.endsWith("\n")) sb.append('\n');
            sb.append('\n').append(addition);
            if (!addition.endsWith("\n")) sb.append('\n');
            return sb.toString();
        }
        NoteTemplate.Spec spec = new NoteTemplate.Spec(
                req.title(), notePath,
                guide == null ? null : guide.getPath(),
                req.sectionLabel(), req.body());
        return template.render(spec);
    }

    /**
     * 解析被沉淀的原文——<strong>取两个来源的并集</strong>。
     *
     * <h3>为什么是并集而不是「会话优先」</h3>
     * <p>会话来源的价值在于服务端读取、模型无法伪造，示例门禁的可靠性依赖它。
     * 但「最后一条 assistant 消息」不一定就是那次被认可的回答——
     * 也可能是「好的，我来记下来」这种短回复。若只取会话，门禁会因为
     * 拿到一段没有代码块的文本而<strong>静默放宽</strong>，
     * 从而放过一篇丢了示例的笔记。这类失效比误拒严重得多，因为它无声无息。</p>
     *
     * <p>反过来只取模型自述则等于让它自己出考题：删掉原文里的代码块就能绕过。</p>
     *
     * <p>并集在两个方向上都安全：任一来源出现代码块，正文就必须有代码块。
     * 模型无法通过<em>添加</em>内容放宽门禁（只会让要求更严），
     * 也无法通过<em>删除</em>绕过（它删不掉会话那一半）。</p>
     */
    private String resolveSource(Request req) {
        StringBuilder sb = new StringBuilder();
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            List<ConversationMemory.Msg> history = memory.history(req.sessionId().strip());
            for (int i = history.size() - 1; i >= 0; i--) {
                ConversationMemory.Msg m = history.get(i);
                if ("assistant".equalsIgnoreCase(m.role())
                        && m.content() != null && !m.content().isBlank()) {
                    sb.append(m.content()).append('\n');
                    break;
                }
            }
            if (sb.length() == 0) {
                log.debug("[Codex] 会话 {} 中没有可用的 assistant 消息", req.sessionId());
            }
        }
        if (req.sourceExcerpt() != null && !req.sourceExcerpt().isBlank()) {
            sb.append(req.sourceExcerpt());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private KbDocument findDoc(Long userId, String pathOrTitle) {
        String p = pathOrTitle.strip().replace('\\', '/');
        for (KnowledgeRepo r : registry.listEnabled(userId)) {
            KbDocument d = docRepo.findByRepoIdAndPath(r.getId(), p).orElse(null);
            if (d != null) return d;
        }
        List<KbDocument> byTitle = docRepo.searchByTitle(userId, p);
        return byTitle.isEmpty() ? null : byTitle.get(0);
    }

    private List<String> anchorsOf(KbDocument doc) {
        return sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId()).stream()
                .limit(80).map(KbSection::getAnchor).toList();
    }

    private static String trimSlash(String s) {
        String t = (s == null || s.isBlank()) ? "docs/notes" : s.strip().replace('\\', '/');
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String shorten(String s, int max) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
