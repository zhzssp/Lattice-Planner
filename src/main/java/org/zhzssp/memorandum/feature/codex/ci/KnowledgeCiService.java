package org.zhzssp.memorandum.feature.codex.ci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck.CheckId;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck.CheckResult;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck.Finding;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck.Report;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck.Status;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;
import org.zhzssp.memorandum.feature.codex.entity.KbLink;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;
import org.zhzssp.memorandum.feature.codex.repository.KbCheckpointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbEntityRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbLinkRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbSectionRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识 CI（本地版）。
 *
 * <h3>为什么先做本地版而不是直接上 GitHub Actions</h3>
 * <p>三个理由，按重要性排序：本地版立刻可用、不依赖任何远端与凭证、
 * 且它就是 Actions 版的实现本体（Actions 只是换个触发入口调同一套检查）。
 * 反过来先做 Actions 则要求用户必须有 GitHub 远端，违背「LOCAL 是一等公民」。</p>
 *
 * <h3>全部检查只读</h3>
 * <p>本服务<strong>绝不修改任何文件</strong>。理由不是保守，而是职责边界：
 * 「发现问题」与「修改内容」混在一起时，用户无法信任报告——
 * 他会怀疑报告是为了让修改看起来必要。修改一律走 curate 模式下的显式确认工具。</p>
 *
 * <h3>一条贯穿的取舍：宁可 SKIPPED，不要假 OK</h3>
 * <p>好几项检查有前提（要有实体表、要有检验册、要有 protagonist 声明文件）。
 * 前提不满足时状态是 {@link Status#SKIPPED} 并给出缺什么，
 * 而<strong>不是</strong>报 OK。把「没检查」显示成「通过」，
 * 会让用户以为已经验过——那比不做这项检查更有害。</p>
 */
@Service
public class KnowledgeCiService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCiService.class);

    /** fenced code block 围栏。 */
    private static final Pattern FENCE = Pattern.compile("(?m)^\\s*(```|~~~)");

    /** Markdown 表格分隔行，如 {@code |---|---|} 或 {@code |:--|--:|}。 */
    private static final Pattern TABLE_SEP =
            Pattern.compile("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");

    /** 笔记的「来源」行：{@code > 来源：[`x.md`](../learning-guides/x.md) §2.3}。 */
    private static final Pattern SOURCE_LINE =
            Pattern.compile("(?m)^\\s*>\\s*来源[：:]\\s*(.*)$");

    /** 行内链接的 target。 */
    private static final Pattern MD_LINK_TARGET =
            Pattern.compile("\\[[^\\]]*\\]\\(\\s*([^)\\s]+)");

    /** 命令中的脚本 token：{@code ./x.sh}、{@code scripts/run.py}、{@code bash a/b.sh}。 */
    private static final Pattern SCRIPT_TOKEN =
            Pattern.compile("(?:^|\\s)(\\.{0,2}/?[\\w./\\-]+\\.(?:sh|py|bash))(?=\\s|$)");

    /** protagonist.yml 的候选位置（按优先级）。 */
    private static final List<String> PROTAGONIST_CANDIDATES = List.of(
            ".lattice/protagonist.yml",
            "protagonist.yml",
            "docs/protagonist.yml");

    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;
    private final KbLinkRepository linkRepo;
    private final KbCheckpointRepository cpRepo;
    private final KbEntityRepository entityRepo;
    private final RepoRegistryService registry;
    private final GitClient git;
    private final FrontMatterParser fm;
    private final ObjectMapper om;
    private final org.zhzssp.memorandum.feature.codex.service.CodexMetrics metrics;

    /** 上一轮报告缓存：让仪表盘刷新时不必重跑（CI 会读全部文件）。 */
    private final Map<Long, Report> lastReports = new ConcurrentHashMap<>();

    @Value("${codex.ci.max-findings-per-check:200}")
    private int maxFindingsPerCheck;

    public KnowledgeCiService(KbDocumentRepository docRepo,
                              KbSectionRepository sectionRepo,
                              KbLinkRepository linkRepo,
                              KbCheckpointRepository cpRepo,
                              KbEntityRepository entityRepo,
                              RepoRegistryService registry,
                              GitClient git,
                              FrontMatterParser fm,
                              ObjectMapper om,
                              org.zhzssp.memorandum.feature.codex.service.CodexMetrics metrics) {
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
        this.linkRepo = linkRepo;
        this.cpRepo = cpRepo;
        this.entityRepo = entityRepo;
        this.registry = registry;
        this.git = git;
        this.fm = fm;
        this.om = om;
        this.metrics = metrics;
    }

    public Report lastReport(Long repoId) {
        return lastReports.get(repoId);
    }

    public Report run(KnowledgeRepo repo) {
        return run(repo, EnumSet.allOf(CheckId.class));
    }

    /**
     * 跑一轮检查。
     *
     * <p>单项检查抛异常时记为 {@link Status#FAILED} 并继续——
     * 一项实现缺陷不该让整份报告消失，那会让用户失去全部可见性。</p>
     */
    public Report run(KnowledgeRepo repo, Set<CheckId> only) {
        long t0 = System.currentTimeMillis();
        Ctx ctx = new Ctx(repo);

        List<CheckResult> results = new ArrayList<>();
        for (CheckId id : CheckId.values()) {
            if (only != null && !only.contains(id)) continue;
            long s = System.currentTimeMillis();
            try {
                results.add(runOne(id, ctx, s));
            } catch (Exception e) {
                log.warn("[Codex CI] 检查 {} 执行异常：{}", id, e.toString());
                results.add(new CheckResult(id, Status.FAILED,
                        "检查自身异常：" + e.getClass().getSimpleName() + " " + e.getMessage(),
                        List.of(), 0, System.currentTimeMillis() - s));
            }
        }

        int errors = 0;
        int warns = 0;
        int infos = 0;
        for (CheckResult r : results) {
            errors += (int) r.errors();
            warns += (int) r.warns();
            infos += (int) r.infos();
        }
        Report report = new Report(repo.getId(), repo.getName(), results,
                errors, warns, infos, errors == 0, System.currentTimeMillis() - t0);
        lastReports.put(repo.getId(), report);
        metrics.recordCiRun(errors, warns);
        log.info("[Codex CI] 仓库「{}」检查完成：ERROR={} WARN={} INFO={}（{}ms）",
                repo.getName(), errors, warns, infos, report.durationMs());
        return report;
    }

    private CheckResult runOne(CheckId id, Ctx ctx, long startedAt) {
        return switch (id) {
            case DEAD_LINK -> deadLinks(ctx, startedAt);
            case DEAD_ANCHOR -> deadAnchors(ctx, startedAt);
            case BACKREF_BIDIRECTIONAL -> backrefBidirectional(ctx, startedAt);
            case NOTE_EXAMPLES -> noteExamples(ctx, startedAt);
            case FRONT_MATTER -> frontMatter(ctx, startedAt);
            case SCOPE_DANGLING -> scopeDangling(ctx, startedAt);
            case CHECKPOINT_EXECUTABLE -> checkpointExecutable(ctx, startedAt);
            case PROTAGONIST_CONSISTENCY -> protagonist(ctx, startedAt);
            case ORPHAN_DOC -> orphans(ctx, startedAt);
        };
    }

    /* ==================== ① 死链 ==================== */

    private CheckResult deadLinks(Ctx ctx, long t0) {
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbLink l : ctx.links) {
            if (!Boolean.TRUE.equals(l.getBroken())) continue;
            if (l.getBrokenReason() != KbLink.BrokenReason.NO_FILE) continue;
            scanned++;
            KbDocument src = ctx.docById.get(l.getSrcDocumentId());
            String srcPath = src == null ? null : src.getPath();
            out.add(Finding.error(CheckId.DEAD_LINK, srcPath,
                    ctx.lineOf(srcPath, l.getRawTarget()),
                    "链接目标不存在：" + l.getRawTarget(),
                    "确认文件是否被重命名或移动。Markdown 相对链接失效时 IDE 与渲染都不报错，"
                            + "只有点击才 404，所以必须靠本项检查兜住。"));
        }
        return finish(CheckId.DEAD_LINK, out, ctx.links.size(), t0);
    }

    /* ==================== ② 锚点失效 ==================== */

    private CheckResult deadAnchors(Ctx ctx, long t0) {
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbLink l : ctx.links) {
            if (!Boolean.TRUE.equals(l.getBroken())) continue;
            if (l.getBrokenReason() != KbLink.BrokenReason.NO_ANCHOR) continue;
            scanned++;
            KbDocument src = ctx.docById.get(l.getSrcDocumentId());
            String srcPath = src == null ? null : src.getPath();
            String suggestion = suggestAnchors(ctx, l);
            out.add(Finding.error(CheckId.DEAD_ANCHOR, srcPath,
                    ctx.lineOf(srcPath, l.getRawTarget()),
                    "文件存在但章节 anchor 不存在：" + l.getRawTarget(),
                    "这是「改了标题」的典型后果：一次改名会静默打断所有指向它的链接。"
                            + (suggestion == null ? "" : "目标文档中相近的 anchor：" + suggestion)));
        }
        return finish(CheckId.DEAD_ANCHOR, out, ctx.links.size(), t0);
    }

    /** 给出目标文档中与失效 anchor 最相似的几个候选，让修复不必手动翻目录。 */
    private String suggestAnchors(Ctx ctx, KbLink l) {
        if (l.getTargetDocumentId() == null || l.getTargetAnchor() == null) return null;
        List<KbSection> secs = sectionRepo.findByDocumentIdOrderByOrdAsc(l.getTargetDocumentId());
        if (secs.isEmpty()) return null;
        String want = l.getTargetAnchor().toLowerCase(Locale.ROOT);
        return secs.stream()
                .sorted(Comparator.comparingInt(
                        (KbSection s) -> -commonPrefix(want, s.getAnchor().toLowerCase(Locale.ROOT))))
                .limit(3)
                .map(KbSection::getAnchor)
                .reduce((a, b) -> a + " / " + b)
                .orElse(null);
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /* ==================== ③ 引用双向性（★核心） ==================== */

    /**
     * 校验「笔记必须挂回知识文档」这条方法论硬约束。
     *
     * <p>这是整套 CI 里<strong>最有价值的一项</strong>，因为它检查的东西
     * 此前完全无人监测：SKILL.md 第 4 步要求写完笔记后回 guide 插一条速记引用，
     * 漏了不会有任何反馈，笔记就变成检索不到的孤岛——
     * 内容写得再好也等于没写。</p>
     *
     * <p>判据分两档，强弱如实区分：
     * <ul>
     *   <li>笔记<strong>声明了</strong>来源 guide → 必须在<em>那一篇</em>里找到指回来的链接。
     *       这是精确判据（ERROR）。</li>
     *   <li>笔记<strong>未声明</strong>来源 → 只能退化成「有没有任意入链」。
     *       无入链是 ERROR，有入链但无来源行是 WARN（提示补来源行以获得精确校验）。</li>
     * </ul>
     */
    private CheckResult backrefBidirectional(Ctx ctx, long t0) {
        List<Finding> out = new ArrayList<>();
        int scanned = 0;

        for (KbDocument note : ctx.docs) {
            if (note.getKind() != KbDocument.DocKind.NOTE) continue;
            scanned++;

            Set<Long> incoming = new LinkedHashSet<>();
            for (KbLink l : ctx.linksByTarget.getOrDefault(note.getId(), List.of())) {
                if (l.getSrcDocumentId().equals(note.getId())) continue;
                incoming.add(l.getSrcDocumentId());
            }

            KbDocument declared = declaredSource(ctx, note);
            if (declared != null) {
                if (!incoming.contains(declared.getId())) {
                    out.add(Finding.error(CheckId.BACKREF_BIDIRECTIONAL, note.getPath(), null,
                            "笔记声明来源为 " + declared.getPath()
                                    + "，但该文档中不存在指回本笔记的引用",
                            "在 " + declared.getPath() + " 讲解该概念的位置插入一行："
                                    + "「> **速记**：[" + relativeLink(declared.getPath(), note.getPath())
                                    + "](" + relativeLink(declared.getPath(), note.getPath())
                                    + ") —— 一句话摘要。」"
                                    + "可用 doc.insert_backref 工具自动插入。"));
                }
                continue;
            }

            if (incoming.isEmpty()) {
                out.add(Finding.error(CheckId.BACKREF_BIDIRECTIONAL, note.getPath(), null,
                        "笔记既未声明来源，也没有任何入链——它是一座孤岛",
                        "补一行「> 来源：[`guide.md`](../learning-guides/guide.md) §x」，"
                                + "并在对应 guide 插入速记引用。"));
            } else {
                out.add(Finding.warn(CheckId.BACKREF_BIDIRECTIONAL, note.getPath(), null,
                        "笔记缺少「来源」行，双向性只能弱校验（当前有 "
                                + incoming.size() + " 个入链）",
                        "补来源行后，本项检查才能精确到「声明的那一篇 guide 里是否真有回链」。"));
            }
        }
        return finish(CheckId.BACKREF_BIDIRECTIONAL, out, scanned, t0);
    }

    /** 从笔记正文的「来源」行解析出它声明挂靠的文档。 */
    private KbDocument declaredSource(Ctx ctx, KbDocument note) {
        String content = ctx.content(note.getPath());
        if (content == null) return null;
        Matcher m = SOURCE_LINE.matcher(content);
        if (!m.find()) return null;
        Matcher lm = MD_LINK_TARGET.matcher(m.group(1));
        while (lm.find()) {
            String target = lm.group(1);
            if (target == null || target.isBlank()) continue;
            if (target.startsWith("http") || target.startsWith("#")) continue;
            int hash = target.indexOf('#');
            String p = hash >= 0 ? target.substring(0, hash) : target;
            String normalized = RepoIndexer.normalizeTarget(note.getPath(), p);
            KbDocument d = ctx.docByPath.get(normalized);
            if (d != null) return d;
        }
        return null;
    }

    /* ==================== ④ 示例入库 ==================== */

    /**
     * 「示例必须入库」的机器化。
     *
     * <p>SKILL.md 把它列为硬性约束：问答里的 IR / 代码 / 对象树 / 对照表
     * 「不得在记笔记时删成一句话摘要」。原因很实在——示例正是让人重新看懂的那部分，
     * 丢了示例的笔记，半年后自己也读不明白。</p>
     *
     * <p>严重度按声明强度分档：显式写了 {@code has_examples: true} 却没有示例是 ERROR
     * （自己承诺了没做到）；未声明的按 WARN。现存 19 篇笔记都没有 front-matter，
     * 若一律判 ERROR 会在启用第一天刷出满屏红色，用户随后就会永久无视这份报告。</p>
     */
    private CheckResult noteExamples(Ctx ctx, long t0) {
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbDocument note : ctx.docs) {
            if (note.getKind() != KbDocument.DocKind.NOTE) continue;
            String content = ctx.content(note.getPath());
            if (content == null) continue;
            scanned++;

            FrontMatterParser.Result r = fm.parse(content);
            String declared = r.str("has_examples");
            if ("false".equalsIgnoreCase(declared)) continue;   // 明确声明无需示例

            String body = content.substring(Math.min(r.bodyStart(), content.length()));
            if (hasExample(body)) continue;

            boolean explicit = "true".equalsIgnoreCase(declared);
            String msg = "笔记正文既无代码块也无对照表，示例可能在沉淀时被丢弃";
            String hint = "把问答里用于讲清概念的 IR / 代码 / 对象树 / 对照表补回正文。"
                    + "篇幅要短应当砍空话与重复，不是砍示例。";
            out.add(explicit
                    ? Finding.error(CheckId.NOTE_EXAMPLES, note.getPath(), null,
                            msg + "（front-matter 声明了 has_examples: true）", hint)
                    : Finding.warn(CheckId.NOTE_EXAMPLES, note.getPath(), null, msg, hint));
        }
        return finish(CheckId.NOTE_EXAMPLES, out, scanned, t0);
    }

    /** 是否含「说明性示例」：fenced code block 或 Markdown 表格。 */
    public static boolean hasExample(String body) {
        if (body == null || body.isBlank()) return false;
        return FENCE.matcher(body).find() || TABLE_SEP.matcher(body).find();
    }

    /* ==================== ⑤ front-matter ==================== */

    private CheckResult frontMatter(Ctx ctx, long t0) {
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        int missing = 0;
        for (KbDocument d : ctx.docs) {
            String content = ctx.content(d.getPath());
            if (content == null) continue;
            scanned++;
            FrontMatterParser.Result r = fm.parse(content);
            if (!r.present() && r.issues().isEmpty()) {
                missing++;
                continue;
            }
            for (FrontMatterParser.Issue i : r.issues()) {
                switch (i.severity()) {
                    case ERROR -> out.add(Finding.error(CheckId.FRONT_MATTER, d.getPath(), null,
                            i.field() + "：" + i.message(),
                            "front-matter 语法错会让整块元数据不可用，且正文可能被误当作元数据。"));
                    case WARN -> out.add(Finding.warn(CheckId.FRONT_MATTER, d.getPath(), null,
                            i.field() + "：" + i.message(), "补齐该字段可让后续自动化更准确。"));
                    default -> { /* INFO 逐条列出会淹没报告，统一由下面的聚合项承载 */ }
                }
            }
        }
        if (missing > 0) {
            // 刻意聚合成一条：61 篇各报一条 INFO 会把有价值的 ERROR 冲掉
            out.add(Finding.info(CheckId.FRONT_MATTER, null, null,
                    missing + " 篇文档没有 front-matter（全部字段可选，不影响索引与检索）",
                    "元数据是渐进补齐的：先索引起来产生价值，再由 Agent 在后续 PR 里回填。"
                            + "现在就要求补齐，等于把第一步门槛抬成「先学一套 schema」。"));
        }
        return finish(CheckId.FRONT_MATTER, out, scanned, t0);
    }

    /* ==================== ⑥ 止损线悬空 ==================== */

    private CheckResult scopeDangling(Ctx ctx, long t0) {
        List<KbEntity> entities = entityRepo.findByRepoId(ctx.repo.getId());
        if (entities.isEmpty()) {
            return skipped(CheckId.SCOPE_DANGLING, t0,
                    "知识点表为空。scope 校验需要先有实体定义（由后续「蒸馏」阶段产出）。"
                            + "此时逐条校验会把所有 scope 判成悬空，产生全量假报告，"
                            + "因此跳过而非判过。");
        }
        Set<String> names = new LinkedHashSet<>();
        for (KbEntity e : entities) {
            names.add(norm(e.getName()));
            if (e.getAliases() != null) {
                for (String a : e.getAliases().split(",")) names.add(norm(a));
            }
        }

        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbDocument d : ctx.docs) {
            String content = ctx.content(d.getPath());
            if (content == null) continue;
            FrontMatterParser.Result r = fm.parse(content);
            Map<String, Object> scope = r.map("scope");
            if (scope.isEmpty()) continue;
            scanned++;
            for (String field : List.of("must", "skip")) {
                for (String raw : toList(scope.get(field))) {
                    if (names.contains(norm(raw))) continue;
                    out.add(Finding.warn(CheckId.SCOPE_DANGLING, d.getPath(), null,
                            "scope." + field + " 引用了未定义的知识点：" + raw,
                            "要么在知识点表中定义它，要么修正拼写。悬空的止损线无法被召回——"
                                    + "而「先跳过」的项目本该在被反复问到时提醒你回来学。"));
                }
            }
        }
        return finish(CheckId.SCOPE_DANGLING, out, scanned, t0);
    }

    /* ==================== ⑦ 检验可执行 ==================== */

    /**
     * 验收命令引用的脚本是否存在且被 git 跟踪。
     *
     * <p>「被跟踪」这条不是洁癖：受限执行器（{@code CommandGuard}）会<strong>拒绝</strong>
     * 运行未被 git 跟踪的脚本，因为未纳入版本控制就无法审计其来源与变更。
     * 所以本项检查实际上是在<em>提前预告运行时会被拒</em>——
     * 比让用户在点「运行」之后才发现要好。</p>
     */
    private CheckResult checkpointExecutable(Ctx ctx, long t0) {
        List<KbCheckpoint> cps = cpRepo.findByRepoIdOrderByCodeAsc(ctx.repo.getId());
        if (cps.isEmpty()) {
            return skipped(CheckId.CHECKPOINT_EXECUTABLE, t0,
                    "尚未解析出任何检验条目。可在「知识落地检验」面板点『从检验册重新解析』。");
        }
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbCheckpoint cp : cps) {
            if (cp.getVerifyJson() == null || cp.getVerifyJson().isBlank()) continue;
            List<String> commands;
            String cwd;
            try {
                JsonNode node = om.readTree(cp.getVerifyJson());
                commands = commandsOf(node);
                cwd = node.hasNonNull("cwd") ? node.get("cwd").asText() : null;
            } catch (Exception e) {
                out.add(Finding.warn(CheckId.CHECKPOINT_EXECUTABLE, null, null,
                        cp.getCode() + "：verify 元数据无法解析（" + e.getMessage() + "）",
                        "该条目无法自动运行，需要人工补验收命令。"));
                continue;
            }
            if (commands.isEmpty()) continue;
            scanned++;

            for (String cmd : commands) {
                Matcher m = SCRIPT_TOKEN.matcher(cmd);
                while (m.find()) {
                    String token = m.group(1);
                    String rel = RepoIndexer.normalizeSlashes(
                            (cwd == null || cwd.isBlank() ? "" : cwd + "/") + stripDotSlash(token));
                    Path file = ctx.root.resolve(rel);
                    if (!Files.isRegularFile(file)) {
                        out.add(Finding.error(CheckId.CHECKPOINT_EXECUTABLE, rel, null,
                                cp.getCode() + " 的验收命令引用了不存在的脚本：" + token,
                                "该条检验永远跑不起来。确认脚本是否被移动，或修正检验册里的命令。"));
                    } else if (!git.isTracked(ctx.root, rel)) {
                        out.add(Finding.warn(CheckId.CHECKPOINT_EXECUTABLE, rel, null,
                                cp.getCode() + " 引用的脚本未被 git 跟踪：" + token,
                                "受限执行会拒绝运行未跟踪脚本（无法审计来源）。"
                                        + "请 git add 该脚本，或改为引用已入库的脚本。"));
                    }
                }
            }
        }
        return finish(CheckId.CHECKPOINT_EXECUTABLE, out, scanned, t0);
    }

    /** 从 verify 元数据里收集全部候选命令串。 */
    private List<String> commandsOf(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null) return out;
        for (String field : List.of("cmd", "command")) {
            JsonNode n = node.get(field);
            if (n == null || n.isNull()) continue;
            if (n.isTextual()) out.add(n.asText());
            else if (n.isArray()) n.forEach(x -> { if (x.isTextual()) out.add(x.asText()); });
        }
        for (String field : List.of("alternatives", "cmds")) {
            JsonNode n = node.get(field);
            if (n != null && n.isArray()) {
                n.forEach(x -> { if (x.isTextual()) out.add(x.asText()); });
            }
        }
        return out;
    }

    private static String stripDotSlash(String s) {
        String t = s;
        while (t.startsWith("./")) t = t.substring(2);
        return t;
    }

    /* ==================== ⑧ 主角一致性 ==================== */

    /**
     * 「主角」数值在全仓库保持一致。
     *
     * <p>用同一组具体数字贯穿全部教程是很有效的教学手法（读者不必每章重建心智模型），
     * 但它有个隐性代价：<strong>改一处就得改全部</strong>，漏改的那一处会让读者
     * 在两章之间对不上号而怀疑自己理解错了。人工核对全仓库不现实，机器一秒完成。</p>
     */
    private CheckResult protagonist(Ctx ctx, long t0) {
        String declFile = null;
        String declContent = null;
        for (String cand : PROTAGONIST_CANDIDATES) {
            Path p = ctx.root.resolve(cand);
            if (Files.isRegularFile(p)) {
                declFile = cand;
                declContent = ctx.readRaw(p);
                break;
            }
        }
        if (declContent == null) {
            return skipped(CheckId.PROTAGONIST_CONSISTENCY, t0,
                    "未找到 protagonist.yml（查找位置：" + String.join("、", PROTAGONIST_CANDIDATES)
                            + "）。一致性校验必须有一个权威声明文件作为比对基准，"
                            + "否则只能靠猜哪个数值是对的。");
        }

        Map<String, List<String>> declared = parseFlatYaml(declContent);
        if (declared.isEmpty()) {
            return skipped(CheckId.PROTAGONIST_CONSISTENCY, t0,
                    declFile + " 中未解析出任何 key: value 声明。");
        }

        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (KbDocument d : ctx.docs) {
            String content = ctx.content(d.getPath());
            if (content == null) continue;
            FrontMatterParser.Result r = fm.parse(content);
            String key = r.str("protagonist");
            if (key == null || key.isBlank()) continue;
            List<String> expected = declared.get(key.strip());
            if (expected == null) {
                out.add(Finding.warn(CheckId.PROTAGONIST_CONSISTENCY, d.getPath(), null,
                        "声明的 protagonist「" + key + "」在 " + declFile + " 中不存在",
                        "确认 key 拼写，或在声明文件中补上这一组数值。"));
                continue;
            }
            scanned++;
            for (String v : expected) {
                if (v == null || v.isBlank()) continue;
                if (!content.contains(v.strip())) {
                    out.add(Finding.error(CheckId.PROTAGONIST_CONSISTENCY, d.getPath(), null,
                            "主角数值缺失或不一致：" + declFile + " 声明 " + key
                                    + " 含「" + v.strip() + "」，本文未出现",
                            "同一组数字贯穿全部教程时，漏改一处会让读者在两章之间对不上号，"
                                    + "并且往往会怀疑是自己理解错了。"));
                }
            }
        }
        if (scanned == 0 && out.isEmpty()) {
            return skipped(CheckId.PROTAGONIST_CONSISTENCY, t0,
                    "已找到 " + declFile + "，但没有文档在 front-matter 里声明 protagonist: <key>，"
                            + "无可比对对象。");
        }
        return finish(CheckId.PROTAGONIST_CONSISTENCY, out, scanned, t0);
    }

    /** 极简 YAML：{@code key: value} 与 {@code key: [a, b, c]}。 */
    public static Map<String, List<String>> parseFlatYaml(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).strip();
            String val = line.substring(colon + 1).strip();
            if (val.isEmpty()) continue;
            List<String> values = new ArrayList<>();
            if (val.startsWith("[") && val.endsWith("]")) {
                for (String part : val.substring(1, val.length() - 1).split(",")) {
                    String v = unquote(part.strip());
                    if (!v.isEmpty()) values.add(v);
                }
            } else {
                values.add(unquote(val));
            }
            if (!values.isEmpty()) out.put(key, values);
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /* ==================== ⑨ 孤岛 ==================== */

    private CheckResult orphans(Ctx ctx, long t0) {
        List<Long> ids = linkRepo.findOrphanDocumentIds(ctx.repo.getId());
        List<Finding> out = new ArrayList<>();
        int scanned = 0;
        for (Long id : ids) {
            KbDocument d = ctx.docById.get(id);
            if (d == null) continue;
            scanned++;
            // 入口文档天生无入链；SOURCE 是原始资料；NOTE 已由双向性检查覆盖，
            // 在这里再报一次只会让同一个问题出现两条发现，用户会开始怀疑报告在灌水
            if (d.getKind() == KbDocument.DocKind.ROADMAP
                    || d.getKind() == KbDocument.DocKind.SOURCE
                    || d.getKind() == KbDocument.DocKind.NOTE) {
                continue;
            }
            if (isEntryPath(d.getPath())) continue;
            out.add(Finding.warn(CheckId.ORPHAN_DOC, d.getPath(), null,
                    "没有任何入链（" + d.getKind().label() + "）",
                    "无入链的文档只能靠全文检索碰运气找到。考虑在路线图或相关 guide 里链上它。"));
        }
        return finish(CheckId.ORPHAN_DOC, out, scanned, t0);
    }

    private static boolean isEntryPath(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return p.equals("readme.md") || p.endsWith("/readme.md");
    }

    /* ==================== 通用 ==================== */

    private CheckResult finish(CheckId id, List<Finding> findings, int scanned, long t0) {
        List<Finding> capped = findings;
        if (findings.size() > maxFindingsPerCheck) {
            capped = new ArrayList<>(findings.subList(0, maxFindingsPerCheck));
            // 截断必须自报，否则用户会以为只有这些问题——与项目「截断不可静默」一致
            capped.add(Finding.warn(id, null, null,
                    "还有 " + (findings.size() - maxFindingsPerCheck) + " 条同类发现未列出",
                    "先修完当前列出的部分再重跑；或调大 codex.ci.max-findings-per-check。"));
        }
        Status st = capped.isEmpty() ? Status.OK : Status.FINDINGS;
        return new CheckResult(id, st, null, List.copyOf(capped), scanned,
                System.currentTimeMillis() - t0);
    }

    private CheckResult skipped(CheckId id, long t0, String reason) {
        return new CheckResult(id, Status.SKIPPED, reason, List.of(), 0,
                System.currentTimeMillis() - t0);
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    private static List<String> toList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object x : l) if (x != null) out.add(String.valueOf(x));
            return out;
        }
        return List.of(String.valueOf(o));
    }

    /** 相对链接串：从 {@code fromPath} 所在目录指向 {@code toPath}。 */
    public static String relativeLink(String fromPath, String toPath) {
        String fromDir = fromPath.contains("/")
                ? fromPath.substring(0, fromPath.lastIndexOf('/')) : "";
        if (fromDir.isEmpty()) return toPath;
        try {
            return Path.of(fromDir).relativize(Path.of(toPath)).toString().replace('\\', '/');
        } catch (Exception e) {
            return toPath;
        }
    }

    /**
     * 一轮检查的共享上下文。
     *
     * <p>文件内容按需读取并缓存：整仓 172 万字符全量读入约 3.4MB，
     * 但只有 4 项检查需要正文，按需读能让只跑链接类检查时完全不碰磁盘。</p>
     */
    private final class Ctx {
        final KnowledgeRepo repo;
        final Path root;
        final List<KbDocument> docs;
        final Map<Long, KbDocument> docById = new HashMap<>();
        final Map<String, KbDocument> docByPath = new HashMap<>();
        final List<KbLink> links;
        final Map<Long, List<KbLink>> linksByTarget = new HashMap<>();
        private final Map<String, String> contentCache = new HashMap<>();

        Ctx(KnowledgeRepo repo) {
            this.repo = repo;
            this.root = registry.rootOf(repo);
            this.docs = docRepo.findByRepoId(repo.getId());
            for (KbDocument d : docs) {
                docById.put(d.getId(), d);
                docByPath.put(d.getPath(), d);
            }
            this.links = linkRepo.findByRepoId(repo.getId());
            for (KbLink l : links) {
                if (l.getTargetDocumentId() == null) continue;
                linksByTarget.computeIfAbsent(l.getTargetDocumentId(), k -> new ArrayList<>()).add(l);
            }
        }

        String content(String relPath) {
            if (relPath == null) return null;
            // 用空串表示「读过但读不到」，避免对同一个坏路径反复尝试读盘
            String cached = contentCache.computeIfAbsent(relPath, p -> {
                String s = readRaw(root.resolve(p));
                return s == null ? "" : s;
            });
            return cached.isEmpty() ? null : cached;
        }

        String readRaw(Path file) {
            try {
                if (!Files.isRegularFile(file)) return null;
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("[Codex CI] 读取失败 {}：{}", file, e.getMessage());
                return null;
            }
        }

        /** 在源文件中定位包含该链接串的行号；找不到返回 null（不猜）。 */
        Integer lineOf(String srcPath, String needle) {
            if (srcPath == null || needle == null || needle.isBlank()) return null;
            String c = content(srcPath);
            if (c == null) return null;
            String[] lines = c.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i] != null && lines[i].contains(needle)) return i + 1;
            }
            return null;
        }
    }
}
