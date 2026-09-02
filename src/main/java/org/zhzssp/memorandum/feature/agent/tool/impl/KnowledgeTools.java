package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.zhzssp.memorandum.feature.pkm.crag.CorrectiveRetriever;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalDegradedEvent;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 个人知识库 Agent 工具集（PKM-RAG）。
 *
 * Stage 2：4 个工具
 *  - kb.semantic_search   ：hybrid 检索个人笔记 + 已摄取本地文档（read）
 *  - kb.lookup_by_title   ：按精确标题取整篇笔记（read）
 *  - kb.list_backlinks    ：列出指向该标题的反向链接（read）
 *  - kb.ingest_local_doc  ：摄取本地 md/txt/pdf 进知识库（write+local，需用户确认）
 *
 * Stage 3：补足管理面 2 个
 *  - kb.list_ingested_docs：列出已摄取本地文档清单（read，含 chunks/最近摄取时间）
 *  - kb.delete_local_doc  ：按 path 反摄取（write+local，需用户确认）
 *
 * 多用户隔离：所有工具入口走 AgentContext.requireUser()，再传入 RagSearchService /
 *           NoteRepository.findFirstByUserAndTitle / 仓储 userId 过滤，越权读他人笔记天然不可达。
 */
@Component
public class KnowledgeTools {

    private final CorrectiveRetriever correctiveRetriever;
    private final NoteRepository noteRepository;
    private final NoteEmbeddingRepository embeddingRepository;
    private final LinkRepository linkRepository;
    private final ApplicationEventPublisher events;

    public KnowledgeTools(CorrectiveRetriever correctiveRetriever,
                          NoteRepository noteRepository,
                          NoteEmbeddingRepository embeddingRepository,
                          LinkRepository linkRepository,
                          ApplicationEventPublisher events) {
        this.correctiveRetriever = correctiveRetriever;
        this.noteRepository = noteRepository;
        this.embeddingRepository = embeddingRepository;
        this.linkRepository = linkRepository;
        this.events = events;
    }

    @AgentTool(name = "kb.semantic_search", tags = {"kb", "read"},
            description = "在用户笔记 + 已摄取本地文档中做语义+关键字 hybrid 检索；" +
                    "涉及'我之前/我的笔记/我学过/上次我们说过 X'等问题时必须先调用本工具，" +
                    "命中条目可在最终回答中以 [[标题]] 形式引用。" +
                    "返回数组的第一项是 _meta=\"crag\" 的元信息行（非命中内容），" +
                    "含 grade（CORRECT/AMBIGUOUS/INCORRECT）、degraded（true 表示检索质量差，应走通用知识）" +
                    "与 message（行动指引），其余各项才是命中片段。")
    public List<Map<String, Object>> semanticSearch(
            @ToolParam(value = "query", desc = "自然语言查询", required = true) String query,
            @ToolParam(value = "topK", desc = "返回条数（1~20，默认 6）") Integer topK
    ) {
        User u = AgentContext.requireUser();
        // CRAG 纠错检索
        CorrectiveRetriever.CragResult cr = correctiveRetriever.retrieve(u, query, topK);
        List<RagSearchService.Hit> hits = cr.hits();

        List<Map<String, Object>> out = new java.util.ArrayList<>(hits.size());
        for (RagSearchService.Hit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", h.source());
            row.put("score", round3(h.score()));
            // 判级依据是 relevance 而非 score，两者量纲不同；不带出来就没法解释为什么判了这一级
            if (h.relevance() != null) row.put("relevance", round3(h.relevance()));
            row.put("reason", h.reason());
            if ("NOTE".equals(h.source())) {
                row.put("noteId", h.noteId());
                noteRepository.findById(h.noteId())
                        .filter(n -> n.getUser().getId().equals(u.getId()))
                        .ifPresent(n -> row.put("title", n.getTitle()));
            } else {
                row.put("sourcePath", h.sourcePath());
            }
            row.put("chunkIdx", h.chunkIdx());
            row.put("content", h.content());
            out.add(row);
        }

        // 附 CRAG 元信息给 LLM 自省（Self-RAG 侧）。
        // 必须无条件返回：否则"有命中但质量差（AMBIGUOUS / degraded=true）"这一最关键场景
        // 的降级信号会丢失，LLM 无法按系统提示的【知识检索原则】调整措辞。
        // 放在首位，让 LLM 先看到质量评级再读命中内容。
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_meta", "crag");
        meta.put("grade", cr.grade().name());
        meta.put("degraded", cr.degraded());
        meta.put("hitCount", hits.size());
        meta.put("usedQueries", cr.usedQueries());
        meta.put("message", cragMessage(cr.grade(), cr.degraded(), hits.isEmpty()));
        out.add(0, meta);

        // 把「问了但库里答不上来」这个事实广播出去（P3 缺口三源之一）。
        //
        // 为什么在工具层发而不是在 CorrectiveRetriever 里发：只有走到这里的检索
        // 才是「用户在对话里真的问了」。后台批量检索与评测套件同样会经过
        // CorrectiveRetriever，在那里埋点会让一次 agentEval 往缺口台账灌进
        // 几十条来自测试用例的假缺口——而缺口表是唯一不可重建的表。
        //
        // 事件本身只陈述事实，不含「该拿它做什么」的判断；订阅方可有可无。
        try {
            events.publishEvent(new RetrievalDegradedEvent(
                    u.getId(), query, cr.grade().name(), cr.degraded(), hits.size(),
                    RetrievalDegradedEvent.CHANNEL_NOTE, null));
        } catch (Exception ignored) {
            // 检索结果已经算出来了，绝不能因为一个附加信号发布失败而丢掉它
        }
        return out;
    }

    /** 依据 CRAG 判级生成给 LLM 的行动指引（与 PromptBuilder 的【知识检索原则】对齐）。 */
    private String cragMessage(RetrievalEvaluator.Grade grade, boolean degraded, boolean empty) {
        if (empty) {
            return "未找到任何相关内容"
                    + (degraded ? "，请在答复首句明示\"未找到强相关笔记，以下基于通用知识：\"" : "");
        }
        if (degraded || grade == RetrievalEvaluator.Grade.INCORRECT) {
            return "检索质量差（改写重检索后仍未命中强相关内容）："
                    + "请在答复首句明示\"未找到强相关笔记，以下基于通用知识：\"，不要假装命中。";
        }
        if (grade == RetrievalEvaluator.Grade.AMBIGUOUS) {
            return "检索结果相关性一般：可谨慎引用，但须提醒用户\"检索结果相关性一般，仅供参考\"。";
        }
        return "检索质量良好：可直接引用命中片段，引用笔记用 [[标题]] 写法。";
    }

    @AgentTool(name = "kb.lookup_by_title", tags = {"kb", "read"},
            description = "按精确标题取一篇笔记的全文。仅当 semantic_search 返回结果中已包含该标题时调用。")
    public Map<String, Object> lookupByTitle(
            @ToolParam(value = "title", desc = "笔记标题（精确匹配）", required = true) String title
    ) {
        User u = AgentContext.requireUser();
        return noteRepository.findFirstByUserAndTitle(u, title)
                .<Map<String, Object>>map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("title", n.getTitle());
                    m.put("tags", n.getTags() == null ? "" : n.getTags());
                    m.put("type", n.getType() == null ? "SCRATCH" : n.getType().name());
                    m.put("content", n.getContent() == null ? "" : n.getContent());
                    return m;
                })
                .orElseGet(() -> Map.of("error", "NOT_FOUND", "title", title));
    }

    @AgentTool(name = "kb.list_backlinks", tags = {"kb", "read"},
            description = "列出指向该标题笔记的反向链接（其他笔记里出现 [[本标题]] 的位置）。")
    public List<Map<String, Object>> listBacklinks(
            @ToolParam(value = "title", desc = "目标笔记标题（精确）", required = true) String title
    ) {
        User u = AgentContext.requireUser();
        return noteRepository.findFirstByUserAndTitle(u, title)
                .map(n -> linkRepository
                        .findByTargetTypeAndTargetId(Link.LinkTargetType.NOTE, n.getId()).stream()
                        .filter(l -> l.getSourceType() == Link.LinkSourceType.NOTE)
                        .map(l -> noteRepository.findById(l.getSourceId()).orElse(null))
                        .filter(Objects::nonNull)
                        .filter(x -> x.getUser().getId().equals(u.getId()))
                        .<Map<String, Object>>map(x -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", x.getId());
                            m.put("title", x.getTitle());
                            return m;
                        })
                        .toList())
                .orElse(List.of());
    }

    @AgentTool(name = "kb.ingest_local_doc", tags = {"kb", "write", "local"}, requiresConfirm = true,
            description = "把一份本地 md/txt/pdf 文档摄取进个人知识库。" +
                    "扩展名/路径必须在 Electron 白名单内；同 path 重复摄取会先清旧 chunk 再重建。" +
                    "v3 起仅只读模式：写功能暂未启用，调用将返回 WRITE_DISABLED。")
    public Map<String, Object> ingestLocal(
            @ToolParam(value = "path", desc = "绝对路径（受 Electron 白名单约束）", required = true) String path
    ) throws Exception {
        // v3：只读模式，写能力暂未启用（避免依赖 Electron LocalBridge 出现"桥接不可用"）
        return Map.of("error", "WRITE_DISABLED",
                "message", "v3 当前为只读模式，本地文档摄取（写）功能暂未启用。" +
                          "如需启用，请单独排期并实现桥接替换。");
    }

    /**
     * v3 已禁用：原 write 实现保留在下方注释，供后续启用时参考。
     *
     * <p><strong>恢复时需注意</strong>：本类已移除 {@code NoteIndexService indexService} 与
     * {@code LocalBridgeProxy localBridge} 两个注入（原先只被本注释块引用，属死注入）。
     * 启用写能力时需要：
     * <ol>
     *   <li>重新注入 {@code NoteIndexService}（{@code rebuildForLocalDoc} 已实现且完整可用）；</li>
     *   <li>把取文逻辑从 {@code localBridge.call("read_file"/"read_pdf")} 换成后端直读
     *       （{@code DocumentExtractorRegistry.extract(Path)}）——Electron LocalBridge 通道
     *       在 v3 已被 MCP 后端直读取代，{@code LocalDocTools} 亦已下线；</li>
     *   <li>同时恢复 {@code java.util.Locale} import。</li>
     * </ol>
     */
    /*
    public Map<String, Object> ingestLocalImpl(
            @ToolParam(value = "path", desc = "绝对路径（受 Electron 白名单约束）", required = true) String path
    ) throws Exception {
        User u = AgentContext.requireUser();
        if (path == null || path.isBlank()) {
            return Map.of("error", "EMPTY_PATH");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String content;
        if (lower.endsWith(".pdf")) {
            content = localBridge.call("read_pdf", Map.of("path", path)).path("content").asText("");
        } else {
            content = localBridge.call("read_file", Map.of("path", path)).path("content").asText("");
        }
        if (content == null || content.isBlank()) {
            return Map.of("path", path, "chunks", 0, "warning", "EMPTY_OR_UNREADABLE");
        }
        int chunks = indexService.rebuildForLocalDoc(u.getId(), path, content);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", path);
        r.put("chunks", chunks);
        if (chunks == 0) {
            r.put("warning", "EMBEDDING_UNAVAILABLE_OR_EMPTY");
        }
        return r;
    }
    */

    @AgentTool(name = "kb.list_ingested_docs", tags = {"kb", "read"},
            description = "列出当前用户已摄取的本地文档清单（路径 + chunks 数 + 最近摄取时间）。" +
                    "用户问'我摄取过哪些资料'/'我导入了什么文件'时调用。")
    public List<Map<String, Object>> listIngestedDocs() {
        User u = AgentContext.requireUser();
        return embeddingRepository.listLocalDocs(u.getId()).stream()
                .<Map<String, Object>>map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", s.getPath());
                    m.put("chunks", s.getChunks());
                    m.put("latest", s.getLatest() == null ? null : s.getLatest().toString());
                    return m;
                })
                .toList();
    }

    @AgentTool(name = "kb.delete_local_doc", tags = {"kb", "write", "local"}, requiresConfirm = true,
            description = "按 path 反摄取本地文档：清空该路径在个人知识库中的所有 chunk。" +
                    "用户明确表达'删掉/移除/反摄取 X'时调用；删除后该文档不再出现在 kb.semantic_search。" +
                    "v3 起仅只读模式：写功能暂未启用，调用将返回 WRITE_DISABLED。")
    public Map<String, Object> deleteLocalDoc(
            @ToolParam(value = "path", desc = "要反摄取的绝对路径（与 ingest 时一致）", required = true) String path
    ) {
        // v3：只读模式，写能力暂未启用
        return Map.of("error", "WRITE_DISABLED",
                "message", "v3 当前为只读模式，本地文档反摄取（写）功能暂未启用。");
    }

    /** v3 已禁用：原 write 实现保留在下方注释，供后续启用时参考。 */
    /*
    public Map<String, Object> deleteLocalDocImpl(
            @ToolParam(value = "path", desc = "要反摄取的绝对路径（与 ingest 时一致）", required = true) String path
    ) {
        User u = AgentContext.requireUser();
        if (path == null || path.isBlank()) {
            return Map.of("error", "EMPTY_PATH");
        }
        int deleted = indexService.deleteLocalDoc(u.getId(), path);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", path);
        r.put("deleted", deleted);
        if (deleted == 0) r.put("warning", "NOT_INGESTED_OR_ALREADY_REMOVED");
        return r;
    }
    */

    /** 输出给 LLM 的分数保留 3 位，防止 JSON 噪音过大。 */
    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
