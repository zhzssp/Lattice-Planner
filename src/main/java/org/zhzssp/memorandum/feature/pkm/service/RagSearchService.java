package org.zhzssp.memorandum.feature.pkm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG hybrid 检索：关键字（FULLTEXT ngram）+ 向量（cosine）双通路。
 *
 * Stage 3 改动：
 *  - 向量通路接 EmbeddingVectorCache，避免每次 search 全表 deserialize；
 *  - embeddingRepository 字段保留，用于 V1 的兜底路径（缓存层未启用时可直接读）。
 *
 * 设计要点：
 *  1. 任一通路异常不影响另一路：
 *     - 没跑 V4 SQL 时 FULLTEXT 抛 SQLException，被 catch 后仅走向量；
 *     - 没配 EMBED_API_KEY 时 embed() 抛 IllegalStateException，被 catch 后仅走关键字。
 *  2. 用户级隔离：FULLTEXT where user_id=?；向量端只取本用户 note_embedding。
 *  3. 加权融合：向量分量权重 = alpha，关键字 RRF 1/(1+rank) 占 1-alpha。
 *  4. 单用户万级 chunk × 1024dim 全表 cosine ~10ms（缓存层已反序列化），满足 V1。
 *
 * <p><b>已知限制：两条通路的命中不会真正融合。</b>关键字通路是笔记级（chunkIdx 为 null，
 * key 后缀 "kw"），向量通路是 chunk 级（key 后缀为 chunkIdx），两种 key 永不相等，
 * 因此同一篇笔记被两路同时命中时分数<b>不会相加</b>，而是作为两条独立结果竞争排序。
 * 这只影响排序质量，不影响判级——判级走的是与通路无关的 {@code relevance}。
 * 真要修需要把关键字通路也对齐到 chunk 粒度，而 H2 跑不了 FULLTEXT，
 * 改了无法离线验证，故留待接入 Testcontainers MySQL 后再动。
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    /**
     * 一条命中。
     *
     * <p><b>{@code score} 与 {@code relevance} 是两个不同量纲的东西，不要混用。</b>
     * 前者只负责排序，其绝对值取决于 alpha 权重与命中通路，跨查询不可比；
     * 后者是与配置无关的语义相关度，是判级（{@link org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator}）
     * 的唯一合法依据。曾经用 {@code score} 判级，导致关键字通路缺席时
     * 上限恒为 alpha，恰好压在降级阈值上——多跳问题因此被 100% 误判为「没检索到」。
     *
     * @param source     "NOTE" | "LOCAL_DOC"
     * @param noteId     NOTE 时为对应 note.id；LOCAL_DOC 时为 0
     * @param sourcePath LOCAL_DOC 时为路径；NOTE 时为 null
     * @param chunkIdx   chunk 序号（关键字通路无 chunk 概念，置 null）
     * @param content    片段内容（关键字通路给笔记内容前 200 字预览）
     * @param score      融合排序分数，越大越靠前；<b>不可用于判级</b>
     * @param relevance  语义相关度 [0,1]，与 alpha 及命中通路无关。
     *                   关键字通路为 {@code null}——FULLTEXT 只给出排序，
     *                   不给出可跨查询比较的相关度
     * @param reason     命中来源说明，例如 "kw"、"vec 0.812"、"kw+vec 0.701"
     */
    public record Hit(String source,
                      Long noteId,
                      String sourcePath,
                      Integer chunkIdx,
                      String content,
                      double score,
                      Double relevance,
                      String reason) {

        /** 取两条命中中较高的相关度；都没有则为 null。 */
        public static Double higherRelevance(Double a, Double b) {
            if (a == null) return b;
            if (b == null) return a;
            return Math.max(a, b);
        }
    }

    @Value("${pkm.rag.alpha:0.4}")
    private double alpha;

    @Value("${pkm.rag.candidates:50}")
    private int candidates;

    @Value("${pkm.rag.topK:6}")
    private int topK;

    /**
     * 向量命中的最低相关度，低于此值直接丢弃。
     *
     * <p>没有下限时结尾的 {@code .limit(k)} 是无条件的：库里只要有 k 条笔记就一定返回 k 条，
     * 哪怕后几条余弦为 0。代价是双份的——占用上下文预算，还给模型提供了编造的素材。
     *
     * <p>取 0.15 的依据：金标集里真正不相关的笔记余弦<b>恒为 0</b>，
     * 而相关笔记的最低余弦是 0.566，中间是一段很宽的空隙。
     * 取在空隙内偏低的位置，能滤掉噪声又给真实语料的分数波动留足余量。
     */
    @Value("${pkm.rag.min-relevance:0.15}")
    private double minRelevance;

    private final NoteRepository noteRepository;
    @SuppressWarnings("unused") // 保留以便后续 V2 直读旁路或诊断接口使用
    private final NoteEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingVectorCache vectorCache;

    public RagSearchService(NoteRepository noteRepository,
                            NoteEmbeddingRepository embeddingRepository,
                            EmbeddingClient embeddingClient,
                            EmbeddingVectorCache vectorCache) {
        this.noteRepository = noteRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
    }

    public List<Hit> search(User user, String query, Integer overrideTopK) {
        if (user == null || query == null || query.isBlank()) return List.of();
        int k = (overrideTopK == null || overrideTopK <= 0) ? topK : Math.min(overrideTopK, 20);
        Map<String, Hit> merged = new LinkedHashMap<>();

        // ---- 关键字通路（FULLTEXT ngram）----
        try {
            int rank = 0;
            for (Note n : noteRepository.fulltextSearch(user.getId(), query, candidates)) {
                double s = (1.0 - alpha) * (1.0 / (1.0 + rank++));
                String key = "N:" + n.getId() + "#kw";
                Hit h = new Hit("NOTE", n.getId(), null, null,
                        preview(n.getContent()), s, null, "kw");
                merged.merge(key, h, (a, b) -> b.score > a.score ? b : a);
            }
        } catch (Exception ex) {
            log.debug("[PKM] FULLTEXT 检索不可用（多半是未跑 V4__pkm_rag.sql），降级仅向量：{}",
                    ex.getMessage());
        }

        // ---- 向量通路（走 LRU 缓存：避免每次 search 全表 deserialize）----
        try {
            float[] qv = embeddingClient.embed(List.of(query)).get(0);
            vectorCache.load(user.getId()).stream().map(e -> {
                double sim = EmbeddingClient.cosine(qv, e.vec());
                return new Hit(e.source(),
                        e.noteId(),
                        e.sourcePath(),
                        e.chunkIdx(),
                        e.content(),
                        alpha * sim,
                        sim,
                        String.format(Locale.ROOT, "vec %.3f", sim));
            }).filter(h -> h.relevance() >= minRelevance)
              .sorted(Comparator.comparingDouble((Hit h) -> -h.score()))
              .limit(candidates)
              .forEach(h -> {
                  String key = ("NOTE".equals(h.source())
                                  ? "N:" + h.noteId()
                                  : "L:" + h.sourcePath())
                              + "#" + h.chunkIdx();
                  merged.merge(key, h, (a, b) -> new Hit(
                          a.source(), a.noteId(), a.sourcePath(), a.chunkIdx(),
                          (a.content() == null || a.content().isBlank()) ? b.content() : a.content(),
                          a.score() + b.score(),
                          Hit.higherRelevance(a.relevance(), b.relevance()),
                          a.reason() + "+" + b.reason()));
              });
        } catch (Exception ex) {
            log.debug("[PKM] 向量通路不可用（多半是 EMBED_API_KEY 未配置），降级仅关键字：{}",
                    ex.getMessage());
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble((Hit h) -> -h.score()))
                .limit(k)
                .toList();
    }

    private static String preview(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
