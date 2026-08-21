package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbLink;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbLinkRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbSectionRepository;
import org.zhzssp.memorandum.feature.codex.service.CodexSearchService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识仓库文档读取工具集。
 *
 * <h3>为什么 doc.search 与 kb.semantic_search 并存而不合并</h3>
 * <p>{@code kb.semantic_search} 的返回结构已被方案 A 的评测录制依赖。
 * 改它的输出格式会让既有 47 个用例的 cassette 全部失效——那是这个项目
 * 最有价值的工程资产。用新工具承载 Git 检索，既零回归又语义更清晰：
 * <strong>kb = 我随手记的笔记，doc = 我沉淀的知识仓库</strong>。</p>
 *
 * <h3>引用定位</h3>
 * <p>每条命中都返回 {@code locator}（形如 {@code docs/x.md#46-timeline-semaphore}），
 * 让 LLM 能给出可点击的精确引用，而不是含糊地说「你的资料里提过」。
 * 这是 P0a 做章节感知切片的直接收益。</p>
 */
@Component
public class DocTools {

    /** 单次返回给 LLM 的正文上限：防止一篇 10 万字的 guide 直接打爆上下文。 */
    private static final int READ_MAX_CHARS = 12000;

    private final CodexSearchService search;
    private final RepoRegistryService registry;
    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;
    private final KbLinkRepository linkRepo;
    private final org.zhzssp.memorandum.feature.codex.service.CodexMetrics metrics;

    @Value("${codex.enabled:false}")
    private boolean codexEnabled;

    public DocTools(CodexSearchService search,
                    RepoRegistryService registry,
                    KbDocumentRepository docRepo,
                    KbSectionRepository sectionRepo,
                    KbLinkRepository linkRepo,
                    org.zhzssp.memorandum.feature.codex.service.CodexMetrics metrics) {
        this.search = search;
        this.registry = registry;
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
        this.linkRepo = linkRepo;
        this.metrics = metrics;
    }

    @AgentTool(name = "doc.search", tags = {"codex", "read"},
            description = "在用户的知识仓库（Git 管理的学习/工作知识体系）中做语义 + 关键字 hybrid 检索。" +
                    "涉及「我的知识库/学习资料/我整理过的文档/我的学习路线」等问题时必须先调用本工具。" +
                    "返回每条命中的 locator（形如 docs/x.md#anchor），" +
                    "回答时应以「文档标题 §章节」形式给出出处，便于用户跳转核对。" +
                    "注意与 kb.semantic_search 的分工：本工具查仓库文档，后者查随手写的笔记。")
    public List<Map<String, Object>> searchDocs(
            @ToolParam(value = "query", desc = "自然语言查询", required = true) String query,
            @ToolParam(value = "topK", desc = "返回条数（1~20，默认 6）") Integer topK
    ) {
        User u = AgentContext.requireUser();
        if (!codexEnabled) {
            return List.of(Map.of("error", "CODEX_DISABLED",
                    "message", "知识仓库功能未启用（codex.enabled=false）。"));
        }
        if (!search.enabled()) {
            return List.of(Map.of("error", "GIT_SEARCH_DISABLED",
                    "message", "知识仓库检索未启用（pkm.rag.git.enabled=false）。"));
        }
        List<CodexSearchService.GitHit> hits = search.search(u.getId(), query, topK);
        if (hits.isEmpty()) {
            return List.of(Map.of("_meta", "codex",
                    "hitCount", 0,
                    "message", "知识仓库中未找到相关内容。若确信写过，可能是索引未更新（可 repo.sync）"
                            + "或该文档被截断（可 repo.status 查看 truncatedDocs）。"
                            + "回答时应明示「未在知识仓库中找到，以下基于通用知识」。"));
        }
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (CodexSearchService.GitHit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", h.title());
            row.put("path", h.path());
            row.put("locator", h.locator());
            if (h.headingPath() != null) row.put("section", h.headingPath());
            row.put("score", round3(h.score()));
            row.put("reason", h.reason());
            row.put("content", h.content());
            out.add(row);
        }
        return out;
    }

    @AgentTool(name = "doc.read", tags = {"codex", "read"},
            description = "读取知识仓库中某篇文档的正文（可只读指定章节 anchor）。" +
                    "仅当 doc.search 已返回该 path，或用户明确指定文件路径时调用。" +
                    "长文档会被截断并标注，需要更多内容时可指定 anchor 精确读取某一节。")
    public Map<String, Object> read(
            @ToolParam(value = "path", desc = "仓库内相对路径，如 docs/learning-guides/mlir-learning-guide.md",
                    required = true) String path,
            @ToolParam(value = "anchor", desc = "章节 anchor；指定时只返回该章节正文") String anchor
    ) {
        User u = AgentContext.requireUser();
        if (!codexEnabled) {
            return Map.of("error", "CODEX_DISABLED", "message", "知识仓库功能未启用。");
        }
        KbDocument doc = findDoc(u, path);
        if (doc == null) {
            return Map.of("error", "NOT_FOUND", "path", path,
                    "message", "索引中无此文档。可先 doc.search 确认路径，或 repo.sync 更新索引。");
        }
        KnowledgeRepo repo = registry.find(u.getId(), doc.getRepoId()).orElse(null);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "path", path);
        }
        metrics.recordDocRead();

        String content;
        try {
            Path file = registry.rootOf(repo).resolve(doc.getPath());
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Map.of("error", "READ_FAILED", "path", path, "message", e.getMessage());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", doc.getPath());
        m.put("title", doc.getTitle());
        m.put("kind", doc.getKind().label());

        if (anchor != null && !anchor.isBlank()) {
            KbSection sec = sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId()).stream()
                    .filter(s -> s.getAnchor().equalsIgnoreCase(anchor.strip()))
                    .findFirst().orElse(null);
            if (sec == null) {
                m.put("error", "ANCHOR_NOT_FOUND");
                m.put("availableAnchors", sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId())
                        .stream().limit(40).map(KbSection::getAnchor).toList());
                return m;
            }
            int from = Math.min(sec.getCharStart(), content.length());
            int to = Math.min(sec.getCharEnd(), content.length());
            String body = content.substring(from, to);
            m.put("section", sec.getHeadingPath());
            m.put("anchor", sec.getAnchor());
            m.put("content", clip(body, m));
            return m;
        }

        m.put("charCount", doc.getCharCount());
        if (Boolean.TRUE.equals(doc.getTruncated())) {
            // 明确告知模型：索引不全，"检索不到" 不等于 "原文没写"
            m.put("_indexWarning", "该文档索引时被截断（约丢失 "
                    + Math.round((doc.getLossRatio() == null ? 0 : doc.getLossRatio()) * 100)
                    + "%），检索可能漏掉后半部分内容。");
        }
        m.put("outline", sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId()).stream()
                .limit(60)
                .map(s -> Map.of("anchor", s.getAnchor(), "heading", s.getHeading(),
                        "level", s.getLevel()))
                .toList());
        m.put("content", clip(content, m));
        return m;
    }

    @AgentTool(name = "doc.outline", tags = {"codex", "read"},
            description = "列出某篇文档的章节目录（anchor + 标题 + 层级）。" +
                    "读长文档前先看目录，再用 doc.read 指定 anchor 精读，可显著节省上下文。")
    public Map<String, Object> outline(
            @ToolParam(value = "path", desc = "仓库内相对路径", required = true) String path
    ) {
        User u = AgentContext.requireUser();
        if (!codexEnabled) {
            return Map.of("error", "CODEX_DISABLED", "message", "知识仓库功能未启用。");
        }
        KbDocument doc = findDoc(u, path);
        if (doc == null) {
            return Map.of("error", "NOT_FOUND", "path", path);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", doc.getPath());
        m.put("title", doc.getTitle());
        m.put("sections", sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId()).stream()
                .map(s -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("anchor", s.getAnchor());
                    r.put("heading", s.getHeading());
                    r.put("level", s.getLevel());
                    return r;
                }).toList());
        return m;
    }

    @AgentTool(name = "doc.backlinks", tags = {"codex", "read"},
            description = "列出指向某篇文档的反向链接（谁引用了它）。" +
                    "用于判断一篇笔记是否已被挂回知识文档——无反链的笔记是检索不到的孤岛。")
    public Map<String, Object> backlinks(
            @ToolParam(value = "path", desc = "仓库内相对路径", required = true) String path
    ) {
        User u = AgentContext.requireUser();
        if (!codexEnabled) {
            return Map.of("error", "CODEX_DISABLED", "message", "知识仓库功能未启用。");
        }
        KbDocument doc = findDoc(u, path);
        if (doc == null) {
            return Map.of("error", "NOT_FOUND", "path", path);
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (KbLink l : linkRepo.findByTargetDocumentId(doc.getId())) {
            if (l.getSrcDocumentId().equals(doc.getId())) continue;   // 同文档内跳转不算反链
            KbDocument src = docRepo.findById(l.getSrcDocumentId()).orElse(null);
            if (src == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", src.getPath());
            r.put("title", src.getTitle());
            r.put("kind", l.getKind().name());
            refs.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", doc.getPath());
        m.put("backlinkCount", refs.size());
        m.put("backlinks", refs);
        if (refs.isEmpty()) {
            m.put("_hint", "该文档无任何入链，属于孤岛——即便内容很好也很难被再次找到。"
                    + "若它是一篇 notes/ 笔记，通常应在对应 guide 里加一条「速记」引用。");
        }
        return m;
    }

    /* ---------------- 内部 ---------------- */

    /** 按路径查文档；找不到时退回标题模糊匹配（模型常给标题而非路径）。 */
    private KbDocument findDoc(User u, String pathOrTitle) {
        if (pathOrTitle == null || pathOrTitle.isBlank()) return null;
        String p = pathOrTitle.strip().replace('\\', '/');
        for (KnowledgeRepo repo : registry.listEnabled(u.getId())) {
            KbDocument d = docRepo.findByRepoIdAndPath(repo.getId(), p).orElse(null);
            if (d != null) return d;
        }
        List<KbDocument> byTitle = docRepo.searchByTitle(u.getId(), p);
        return byTitle.isEmpty() ? null : byTitle.get(0);
    }

    /** 截断长正文并明示——不静默丢内容。 */
    private String clip(String body, Map<String, Object> meta) {
        if (body == null) return "";
        if (body.length() <= READ_MAX_CHARS) return body;
        meta.put("_truncatedForContext", true);
        meta.put("_fullCharCount", body.length());
        meta.put("_readHint", "正文过长已截断至前 " + READ_MAX_CHARS
                + " 字符。可先 doc.outline 看目录，再用 anchor 精读所需章节。");
        return body.substring(0, READ_MAX_CHARS);
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
