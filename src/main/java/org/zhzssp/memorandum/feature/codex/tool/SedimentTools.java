package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbSectionRepository;
import org.zhzssp.memorandum.feature.codex.sediment.BackrefInserter;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.sediment.NoteTemplate;
import org.zhzssp.memorandum.feature.codex.sediment.SedimentService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沉淀工具集（仅 curate 模式可见）。
 *
 * <h3>为什么把「写笔记 + 加引用」做成一个工具而不是两个</h3>
 * <p>{@code doc.write} 内部就完成了回挂：因为<strong>只写笔记不加引用等于没沉淀</strong>。
 * 一篇没有入链的笔记只能靠全文检索碰运气找到，而用户当初记下它就是为了以后能找到。
 * 若拆成两个工具，模型完全可能只调第一个就宣布完成——
 * 这正是原工作流靠人自觉、结果经常漏掉第 4 步的地方。</p>
 *
 * <p>{@code doc.insert_backref} 单独保留，用于给<em>已存在但漏挂</em>的旧笔记补引用
 * （知识 CI 的双向性检查会把这些找出来）。</p>
 */
@Component
public class SedimentTools {

    private final SedimentService sediment;
    private final RepoRegistryService registry;
    private final RepoSyncService syncService;
    private final DocWriteGuard guard;
    private final NoteTemplate template;
    private final BackrefInserter inserter;
    private final FrontMatterParser fm;
    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;

    public SedimentTools(SedimentService sediment,
                         RepoRegistryService registry,
                         RepoSyncService syncService,
                         DocWriteGuard guard,
                         NoteTemplate template,
                         BackrefInserter inserter,
                         FrontMatterParser fm,
                         KbDocumentRepository docRepo,
                         KbSectionRepository sectionRepo) {
        this.sediment = sediment;
        this.registry = registry;
        this.syncService = syncService;
        this.guard = guard;
        this.template = template;
        this.inserter = inserter;
        this.fm = fm;
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
    }

    @AgentTool(name = "doc.write", tags = {"codex", "doc", "write"}, requiresConfirm = true,
            description = "把用户认可的一次问答沉淀为知识仓库里的一篇笔记，并自动在对应知识文档的"
                    + "指定章节插入速记引用。仅在用户明确要求「写笔记/记下来/沉淀这段」时调用，不要主动调用。"
                    + "调用前必须先用 doc.search 或 doc.outline 确认挂靠的文档路径与章节 anchor。"
                    + "硬性要求：问答里用于讲清概念的代码/IR/对象树/对照表必须原样写入 body，"
                    + "删成一句话摘要会被拒绝（返回 MISSING_EXAMPLES）。"
                    + "本工具不会提交 git——改动停在工作分支的未提交状态，由用户审阅后自行提交。")
    public Map<String, Object> write(
            @ToolParam(value = "title", desc = "笔记标题", required = true) String title,
            @ToolParam(value = "body", desc = "笔记正文 Markdown（不含 H1 标题与来源行，"
                    + "建议结构：## 是什么 / ## 关键规则 / ## 示例 / ## 对照）", required = true) String body,
            @ToolParam(value = "guidePath", desc = "挂靠的知识文档仓库内相对路径，"
                    + "如 docs/learning-guides/llvm-learning-guide.md", required = true) String guidePath,
            @ToolParam(value = "anchor", desc = "在该文档哪个章节 anchor 之后插入速记引用"
                    + "（必须来自 doc.outline 的输出，不要猜）", required = true) String anchor,
            @ToolParam(value = "summary", desc = "速记引用里的一句话摘要，"
                    + "要能让扫读者决定是否点开", required = true) String summary,
            @ToolParam(value = "sourceExcerpt", desc = "被沉淀的原始回答全文（含其中的代码块）；"
                    + "用于校验示例是否被保留") String sourceExcerpt,
            @ToolParam(value = "sectionLabel", desc = "来源行标注的章节号，如 2.3") String sectionLabel,
            @ToolParam(value = "notePath", desc = "自定义笔记路径；缺省为 docs/notes/<标题 slug>.md")
            String notePath,
            @ToolParam(value = "mode", desc = "CREATE（默认，已存在则拒绝）/ APPEND（追加到既有笔记）"
                    + "/ REPLACE（覆盖）") String mode,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        SedimentService.WriteMode wm;
        try {
            wm = (mode == null || mode.isBlank())
                    ? SedimentService.WriteMode.CREATE
                    : SedimentService.WriteMode.valueOf(mode.strip().toUpperCase());
        } catch (Exception e) {
            return Map.of("error", "BAD_MODE",
                    "message", "mode 只能是 CREATE / APPEND / REPLACE，收到：" + mode);
        }

        SedimentService.Request req = new SedimentService.Request(
                repoName, title, body, summary, guidePath, anchor, sectionLabel,
                notePath, sourceExcerpt, AgentContext.sessionId(), wm, true, true);
        SedimentService.Result r = sediment.sediment(u.getId(), req);

        Map<String, Object> m = new LinkedHashMap<>();
        if (!r.ok()) {
            m.put("error", r.code());
            m.put("message", r.message());
            if (!r.availableAnchors().isEmpty()) {
                m.put("availableAnchors", r.availableAnchors());
                m.put("hint", "从 availableAnchors 中选一个真实存在的 anchor 重试。");
            }
            return m;
        }
        m.put("ok", true);
        m.put("notePath", r.notePath());
        m.put("guidePath", r.guidePath());
        if (r.backrefLine() != null) m.put("backrefLine", r.backrefLine());
        if (r.backrefText() != null) m.put("backrefText", r.backrefText());
        m.put("branch", r.branch());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("nextStep", r.nextStep());
        return m;
    }

    @AgentTool(name = "doc.insert_backref", tags = {"codex", "doc", "write"}, requiresConfirm = true,
            description = "给一篇已存在的笔记补上速记引用：在指定知识文档的指定章节末尾插入一行"
                    + "「> **速记**：[相对路径](相对路径) —— 摘要」。"
                    + "用于修复知识 CI 报出的「笔记无回挂」问题。"
                    + "本工具只插入一行，不改写文档任何既有内容。")
    public Map<String, Object> insertBackref(
            @ToolParam(value = "guidePath", desc = "被插入的知识文档相对路径", required = true) String guidePath,
            @ToolParam(value = "anchor", desc = "插入位置的章节 anchor（来自 doc.outline）",
                    required = true) String anchor,
            @ToolParam(value = "notePath", desc = "被引用的笔记相对路径", required = true) String notePath,
            @ToolParam(value = "summary", desc = "一句话摘要", required = true) String summary,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();

        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return Map.of("error", en.code(), "message", en.message(), "hint", nz(en.hint()));
        }
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        DocWriteGuard.Decision br = guard.checkBranch(repo);
        if (!br.allowed()) {
            return Map.of("error", br.code(), "message", br.message(), "hint", nz(br.hint()));
        }

        KbDocument guide = findDoc(u.getId(), guidePath);
        if (guide == null) {
            return Map.of("error", "GUIDE_NOT_FOUND", "message",
                    "索引中没有这篇文档：" + guidePath);
        }
        KbDocument note = findDoc(u.getId(), notePath);
        if (note == null) {
            return Map.of("error", "NOTE_NOT_FOUND", "message",
                    "索引中没有这篇笔记：" + notePath
                            + "。若刚创建，先 repo.sync 更新索引；若想新建笔记请用 doc.write。");
        }

        Path root = registry.rootOf(repo);
        Path guideFile = root.resolve(guide.getPath());
        String content;
        try {
            content = Files.readString(guideFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Map.of("error", "READ_FAILED", "message", e.getMessage());
        }

        // 工作副本必须干净：本工具只改这一个文件，不该与用户手上的编辑混在一次提交里
        DocWriteGuard.Decision clean = guard.checkWorkingTree(repo, java.util.Set.of());
        if (!clean.allowed()) {
            return Map.of("error", clean.code(), "message", clean.message(),
                    "hint", nz(clean.hint()));
        }

        String line = template.backrefLine(guide.getPath(), note.getPath(), summary);
        BackrefInserter.Result ins = inserter.insert(
                content, fm.parse(content).bodyStart(), anchor, line, note.getPath());

        Map<String, Object> m = new LinkedHashMap<>();
        switch (ins.outcome()) {
            case ANCHOR_NOT_FOUND -> {
                m.put("error", "ANCHOR_NOT_FOUND");
                m.put("message", ins.message());
                m.put("availableAnchors", ins.availableAnchors());
                return m;
            }
            case ALREADY_PRESENT -> {
                m.put("ok", true);
                m.put("changed", false);
                m.put("message", ins.message());
                return m;
            }
            case INSERTED -> {
                try {
                    Files.writeString(guideFile, ins.newContent(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return Map.of("error", "WRITE_FAILED", "message", e.getMessage());
                }
            }
        }

        String reindex;
        try {
            RepoSyncService.SyncResult sr = syncService.sync(repo, false, false);
            reindex = "已重建索引：" + sr.report().docsReindexed() + " 篇";
        } catch (Exception e) {
            reindex = "索引重建失败（文件已写入）：" + e.getMessage();
        }

        m.put("ok", true);
        m.put("changed", true);
        m.put("guidePath", guide.getPath());
        m.put("line", ins.line());
        m.put("inserted", line);
        m.put("reindex", reindex);
        m.put("nextStep", "改动未提交。审阅 diff 后调用 repo.commit 提交。");
        return m;
    }

    @AgentTool(name = "doc.anchors", tags = {"codex", "read"},
            description = "列出某篇知识文档全部章节 anchor（比 doc.outline 更精简，只给 anchor 列表）。"
                    + "在调用 doc.write / doc.insert_backref 之前用它确认 anchor 真实存在——"
                    + "anchor 猜错会把引用插到无关章节。")
    public Map<String, Object> anchors(
            @ToolParam(value = "path", desc = "文档相对路径", required = true) String path
    ) {
        User u = AgentContext.requireUser();
        if (!registry.enabled()) {
            return Map.of("error", "CODEX_DISABLED", "message", "知识仓库功能未启用。");
        }
        KbDocument doc = findDoc(u.getId(), path);
        if (doc == null) {
            return Map.of("error", "NOT_FOUND", "path", path);
        }
        List<KbSection> secs = sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", doc.getPath());
        m.put("count", secs.size());
        m.put("anchors", secs.stream()
                .map(s -> Map.of("anchor", s.getAnchor(), "heading", s.getHeading()))
                .toList());
        return m;
    }

    /* ---------------- 内部 ---------------- */

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private KbDocument findDoc(Long userId, String pathOrTitle) {
        if (pathOrTitle == null || pathOrTitle.isBlank()) return null;
        String p = pathOrTitle.strip().replace('\\', '/');
        for (KnowledgeRepo r : registry.listEnabled(userId)) {
            KbDocument d = docRepo.findByRepoIdAndPath(r.getId(), p).orElse(null);
            if (d != null) return d;
        }
        List<KbDocument> byTitle = docRepo.searchByTitle(userId, p);
        return byTitle.isEmpty() ? null : byTitle.get(0);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
