package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.mcp.McpLocalFileService;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.distill.DistillGuard;
import org.zhzssp.memorandum.feature.codex.distill.DistillService;
import org.zhzssp.memorandum.feature.codex.distill.ExamService;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蒸馏与出题工具集（P4）。
 *
 * <h3>为什么起草与落盘是两个工具，而不是一个「一步到位」的工具</h3>
 * <p>合成一个当然更省事，但那样用户在看到产物之前，文件就已经写进仓库了。
 * 蒸馏产物的质量波动很大（取决于 PDF 的排版与论文的写法），
 * 先看后写这一步不能省。这与 P2 的沉淀刻意不提交是同一条原则：
 * <strong>凡是质量不稳定的产出，人要在落地之前有一次看的机会。</strong></p>
 *
 * <h3>草稿存在进程内，不落库</h3>
 * <p>草稿是一次会话内的中间物，落库会引出一张要维护生命周期的表。
 * 代价是重启后草稿丢失——重新起草一次而已，而它本来就不该被长期保存：
 * 保存草稿等于鼓励「以后再核对」，而「以后」通常不会到来。</p>
 */
@Component
public class DistillTools {

    /** 会话内草稿暂存：key = 会话 id + 路径。上限很小，超出即淘汰最早的。 */
    private static final int MAX_CACHED = 8;

    private final DistillService distill;
    private final ExamService exam;
    private final RepoRegistryService registry;
    private final McpLocalFileService localFiles;

    private final Map<String, DistillService.Draft> guideDrafts = new ConcurrentHashMap<>();
    private final Map<String, ExamService.Draft> examDrafts = new ConcurrentHashMap<>();

    public DistillTools(DistillService distill,
                        ExamService exam,
                        RepoRegistryService registry,
                        McpLocalFileService localFiles) {
        this.distill = distill;
        this.exam = exam;
        this.registry = registry;
        this.localFiles = localFiles;
    }

    /* ==================== 蒸馏 ==================== */

    @AgentTool(name = "distill.draft", tags = {"codex", "doc", "read"}, requiresConfirm = true,
            description = "从一份原料（PDF / docx / md / txt）起草一篇 guide。"
                    + "★不写任何文件，只返回草稿与结构校验结果。"
                    + "需要确认是因为它会对原料分段做多次 LLM 调用，成本不低。"
                    + "起草完成后必须把「待人工核对」清单原样转述给用户，"
                    + "尤其是止损线那一条——跳错了几周后会变成挡路的盲区。")
    public Map<String, Object> draftGuide(
            @ToolParam(value = "sourcePath", desc = "原料路径。可以是仓库内相对路径"
                    + "（如 paper/flashattention.pdf），也可以是 MCP 白名单内的绝对路径",
                    required = true) String sourcePath,
            @ToolParam(value = "title", desc = "guide 标题；留空则用文件名") String title,
            @ToolParam(value = "domain", desc = "所属域，写进 front-matter") String domain,
            @ToolParam(value = "repoName", desc = "仓库名；省略则用第一个启用的仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (!distill.enabled()) {
            return err("DISTILL_DISABLED", "蒸馏未启用（codex.distill.enabled=false）。");
        }
        Path file = resolveSource(u.getId(), repoName, sourcePath);
        if (file == null) {
            return err("SOURCE_DENIED",
                    "找不到该原料，或它不在允许访问的范围内：" + sourcePath
                            + "。允许的位置有两处：已登记仓库内的相对路径，"
                            + "或 MCP 本地文件白名单内的绝对路径"
                            + "（后者需要 mcp.server.local-files-enabled=true）。"
                            + "★本工具刻意复用 MCP 那套白名单而不另立一套——"
                            + "两套白名单里必有一套会被忘记维护。");
        }

        DistillService.Draft d = distill.draft(u.getId(), file, title, domain);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", d.ok());
        m.put("code", d.code());
        m.put("message", d.message());
        m.put("llmCalls", d.llmCalls());
        m.put("elapsedMs", d.elapsedMs());
        if (d.source() != null) {
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("fileName", d.source().fileName());
            src.put("pages", d.source().pageCount());
            src.put("chars", d.source().charCount());
            src.put("charsPerPage", d.source().charsPerPage());
            src.put("chunks", d.source().chunks().size());
            m.put("source", src);
        }
        if (d.verdict() != null) {
            m.put("structureCheck", verdictOf(d.verdict()));
        }
        if (!d.ok()) {
            m.put("_hint", "起草未通过结构校验，没有产生任何文件。"
                    + "把上面 errors 里的原因如实告诉用户，不要自己改写产物去凑合格——"
                    + "凑合格的产物同样会被写进他的知识库。");
            return m;
        }

        m.put("title", d.title());
        m.put("targetPath", d.targetPath());
        m.put("contentPreview", preview(d.content(), 2500));
        m.put("draftKey", cacheGuide(d));
        m.put("_nextStep", "把草稿要点与「可以先跳过」清单转述给用户，等他确认后"
                + "再调用 distill.write 落盘。不要替他确认。");
        return m;
    }

    @AgentTool(name = "distill.write", tags = {"codex", "doc", "write"}, requiresConfirm = true,
            description = "把已起草的 guide 草稿写入仓库（只新建，绝不覆盖既有文件）。"
                    + "必须先 distill.draft 拿到 draftKey。刻意不接受直接传内容——"
                    + "否则「必须有止损线」这条约束只要换个入口就能绕过。写入后不提交。")
    public Map<String, Object> writeGuide(
            @ToolParam(value = "draftKey", desc = "distill.draft 返回的 draftKey",
                    required = true) String draftKey,
            @ToolParam(value = "path", desc = "覆盖默认目标路径（可选）") String path,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        DistillService.Draft d = guideDrafts.get(key(draftKey));
        if (d == null) {
            return err("DRAFT_NOT_FOUND",
                    "找不到该草稿（可能已过期或服务重启）。请重新 distill.draft。");
        }
        DistillService.WriteResult r = distill.write(u.getId(), repoName, d, path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("skipTermsRegistered", r.skipTerms());
        if (r.ok()) {
            m.put("_important", "这些止损线术语已进入知识点表，将来被反复问到会触发 skip 召回。"
                    + "请提醒用户核对它们是否真的该跳过。");
            m.put("_nextStep", r.nextStep());
        }
        return m;
    }

    /* ==================== 出题 ==================== */

    @AgentTool(name = "exam.draft", tags = {"codex", "doc", "read"}, requiresConfirm = true,
            description = "为一篇知识文档起草落地检验题目（L0~L3）。不写文件。"
                    + "必须同时指定对应的动手项目目录——没有 lab 时任何验收命令都只能是编的。"
                    + "★返回里的 discarded 是被丢弃的题及原因，必须如实转述："
                    + "丢弃通常意味着模型引用了不存在的脚本，而那种题会污染验收通过率。")
    public Map<String, Object> draftExam(
            @ToolParam(value = "guidePath", desc = "知识文档的仓库内相对路径", required = true)
            String guidePath,
            @ToolParam(value = "labDir", desc = "对应动手项目目录（仓库内相对路径），必须真实存在",
                    required = true) String labDir,
            @ToolParam(value = "count", desc = "期望题数，默认 3") Integer count,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (!exam.enabled()) {
            return err("EXAM_DISABLED", "出题未启用（codex.exam.enabled=false）。");
        }
        ExamService.Draft d = exam.draft(u.getId(), repoName, guidePath, labDir, count);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", d.ok());
        m.put("message", d.message());
        if (d.errorCode() != null) m.put("code", d.errorCode());
        m.put("guidePath", d.guidePath());
        m.put("labDir", d.labDir());
        m.put("accepted", d.accepted());
        List<Map<String, Object>> dis = new ArrayList<>();
        for (ExamService.Discarded x : d.discarded()) {
            dis.add(Map.of("code", nvl(x.code()), "reason", x.reason()));
        }
        m.put("discarded", dis);
        if (d.ok()) {
            m.put("targetPath", d.targetPath());
            m.put("contentPreview", preview(d.content(), 2500));
            m.put("draftKey", cacheExam(d));
            m.put("_important", "这些题会被标记为 AGENT_DRAFT（判据未经人验证），"
                    + "统计通过率时与用户手写的题分开计算。"
                    + "总结时必须说明这一点——不说清楚会让他以为自己的通过率变了。");
        }
        return m;
    }

    @AgentTool(name = "exam.write", tags = {"codex", "doc", "write"}, requiresConfirm = true,
            description = "把已起草的检验题目写入仓库并载入检验表（只新建，绝不覆盖既有检验册）。"
                    + "写入后不提交。")
    public Map<String, Object> writeExam(
            @ToolParam(value = "draftKey", desc = "exam.draft 返回的 draftKey", required = true)
            String draftKey,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        ExamService.Draft d = examDrafts.get(key(draftKey));
        if (d == null) {
            return err("DRAFT_NOT_FOUND", "找不到该草稿，请重新 exam.draft。");
        }
        ExamService.WriteResult r = exam.write(u.getId(), repoName, d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("loadedIntoDb", r.loadedIntoDb());
        if (r.ok()) m.put("_nextStep", r.nextStep());
        return m;
    }

    /* ==================== 原料定位 ==================== */

    /**
     * 解析原料路径。
     *
     * <p>两条来源：仓库内相对路径、MCP 白名单内的绝对路径。
     * <strong>刻意复用 MCP 那套白名单</strong>而不为蒸馏新建一套——
     * 两套并存时必有一套会被忘记维护，而被忘记的那一套通常是更宽松的那个。</p>
     */
    private Path resolveSource(Long userId, String repoName, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace('\\', '/').strip();

        // ① 仓库内相对路径
        if (!s.startsWith("/") && !s.matches("^[A-Za-z]:/.*")) {
            for (KnowledgeRepo r : registry.listEnabled(userId)) {
                if (repoName != null && !repoName.isBlank()
                        && !repoName.strip().equalsIgnoreCase(r.getName())) {
                    continue;
                }
                try {
                    Path root = registry.rootOf(r).toRealPath();
                    Path f = root.resolve(s).normalize();
                    // 仓库内也要防越界：paper/../../../etc 这类形状
                    if (f.startsWith(root) && Files.isRegularFile(f)) return f;
                } catch (Exception ignored) {
                    // 该仓库不可访问，试下一个
                }
            }
            return null;
        }

        // ② 绝对路径 → 交给 MCP 白名单裁决
        try {
            Path f = Path.of(s).normalize();
            if (Files.isRegularFile(f) && localFiles.isAllowed(f)) return f;
        } catch (Exception ignored) {
            // 非法路径形状
        }
        return null;
    }

    /* ==================== 内部 ==================== */

    private Map<String, Object> verdictOf(DistillGuard.Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pass", v.pass());
        m.put("summary", v.summary());
        m.put("skipTerms", v.skipTerms());
        List<Map<String, Object>> errs = new ArrayList<>();
        for (DistillGuard.Finding f : v.errors()) {
            errs.add(Map.of("code", f.code(), "message", f.message(), "hint", nvl(f.hint())));
        }
        m.put("errors", errs);
        List<Map<String, Object>> warns = new ArrayList<>();
        for (DistillGuard.Finding f : v.warns()) {
            warns.add(Map.of("code", f.code(), "message", f.message(), "hint", nvl(f.hint())));
        }
        m.put("warns", warns);
        return m;
    }

    private String cacheGuide(DistillService.Draft d) {
        evict(guideDrafts);
        String k = "g" + Math.abs(System.nanoTime() % 1_000_000);
        guideDrafts.put(key(k), d);
        return k;
    }

    private String cacheExam(ExamService.Draft d) {
        evict(examDrafts);
        String k = "e" + Math.abs(System.nanoTime() % 1_000_000);
        examDrafts.put(key(k), d);
        return k;
    }

    /** key 里带会话 id：同一进程多会话不会互相拿到对方的草稿。 */
    private String key(String draftKey) {
        String sid = AgentContext.sessionId();
        return (sid == null ? "-" : sid) + "|" + draftKey;
    }

    private void evict(Map<String, ?> map) {
        if (map.size() < MAX_CACHED) return;
        // 简单粗暴地清空而不是精确 LRU：草稿是短命中间物，
        // 为它实现淘汰策略是把复杂度花在了错误的地方
        map.clear();
    }

    private String preview(String content, int max) {
        if (content == null) return null;
        return content.length() <= max ? content
                : content.substring(0, max) + "\n…（预览截断，共 " + content.length() + " 字符）";
    }

    private Map<String, Object> err(String code, String message) {
        return Map.of("error", code, "message", message);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
