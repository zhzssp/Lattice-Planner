package org.zhzssp.memorandum.feature.pkm.crag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.feature.pkm.serving.RagServingService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRAG 状态机编排器（C3）。
 *
 * <p>协调检索 → 评估 → 纠错（重检索/降级）的完整闭环：
 * <pre>
 *   search → grade
 *     CORRECT    → 直接使用
 *     AMBIGUOUS  → 改写 1~2 个 query，重检索，RRF 合并去重
 *     INCORRECT  → 改写重检索；仍不达标则 degraded=true（告知 LLM 走通用知识）
 * </pre>
 *
 * <p>检索后端优先使用 RagServingService（含缓存+rerank），不可用时回退 RagSearchService。</p>
 * <p>重检索次数上限 1，防止循环放大延迟。</p>
 */
@Service
public class CorrectiveRetriever {

    private static final Logger log = LoggerFactory.getLogger(CorrectiveRetriever.class);

    private final RagSearchService rag;
    private final RetrievalEvaluator evaluator;
    private final QueryRewriter rewriter;
    private final RagServingService serving; // 可选，null 时回退 rag
    private final CragMetrics metrics;

    /**
     * CRAG 总开关。false 时退化为一次普通检索（不改写、不重检索、degraded 恒 false），
     * 对应计划验收标准「pkm.crag.enabled=false 时 kb.semantic_search 行为与现状一致」。
     */
    @Value("${pkm.crag.enabled:true}")
    private boolean enabled;

    public CorrectiveRetriever(RagSearchService rag,
                               RetrievalEvaluator evaluator,
                               QueryRewriter rewriter,
                               RagServingService serving,
                               CragMetrics metrics) {
        this.rag = rag;
        this.evaluator = evaluator;
        this.rewriter = rewriter;
        this.serving = serving;
        this.metrics = metrics;
    }

    public boolean enabled() { return enabled; }

    public record CragResult(
            List<RagSearchService.Hit> hits,
            RetrievalEvaluator.Grade grade,
            boolean degraded,
            List<String> usedQueries) {}

    /** 执行检索（优先 serving 门面，不可用时走 rag 直连）。 */
    private List<RagSearchService.Hit> doSearch(User user, String query, int k) {
        if (serving != null) {
            return serving.search(user, query, k);
        }
        return rag.search(user, query, k);
    }

    /**
     * CRAG 检索入口。
     *
     * @param user  当前用户
     * @param query 原始查询
     * @param topK  最终返回结果数
     */
    public CragResult retrieve(User user, String query, Integer topK) {
        if (user == null || query == null || query.isBlank()) {
            return new CragResult(List.of(), RetrievalEvaluator.Grade.INCORRECT, true, List.of());
        }

        int k = (topK == null || topK <= 0) ? 6 : Math.min(topK, 20);
        metrics.recordRetrieve();

        // 0) 开关关闭 → 一次普通检索，不做改写/重检索，degraded 恒 false
        if (!enabled) {
            metrics.recordBypass();
            List<RagSearchService.Hit> plain = doSearch(user, query, k);
            RetrievalEvaluator.Grade g = evaluator.grade(plain);
            metrics.recordGrade(g);
            return new CragResult(truncate(plain, k), g, false, List.of(query));
        }

        // 1) 首次检索
        List<RagSearchService.Hit> hits = doSearch(user, query, k * 4); // 取候选池
        RetrievalEvaluator.Grade grade = evaluator.grade(hits);
        List<String> usedQueries = new ArrayList<>();
        usedQueries.add(query);

        if (grade == RetrievalEvaluator.Grade.CORRECT) {
            metrics.recordGrade(grade);
            return new CragResult(truncate(hits, k), grade, false, usedQueries);
        }

        // 2) AMBIGUOUS / INCORRECT → 改写一次并合并（重检索次数上限 1）
        log.debug("[CRAG] 检索质量={}（topScore={}），触发改写重检索",
                grade, hits.isEmpty() ? 0.0 : hits.get(0).score());
        metrics.recordRewrite();

        List<String> rewrites = rewriter.rewrite(query, 2);
        for (String rw : rewrites.subList(1, rewrites.size())) {
            usedQueries.add(rw);
            List<RagSearchService.Hit> rwHits = doSearch(user, rw, k * 2);
            hits = mergeByRrf(hits, rwHits);
        }

        RetrievalEvaluator.Grade g2 = evaluator.grade(hits);
        boolean degraded = (g2 == RetrievalEvaluator.Grade.INCORRECT || hits.isEmpty());
        metrics.recordGrade(g2);
        if (degraded) {
            metrics.recordDegraded();
            log.info("[CRAG] 改写重检索后仍未达标，标记 degraded，建议 LLM 走通用知识");
        }

        return new CragResult(truncate(hits, k), g2, degraded, usedQueries);
    }

    /**
     * RRF（Reciprocal Rank Fusion）合并两份命中列表。
     *
     * <p>去重 key 为 {@code (source, noteId/path, chunkIdx)}（见 {@link #hitKey}），
     * <strong>不含 rank</strong>——否则同一文档在两个列表中排名不同时会被当成两条不同结果，
     * 既无法去重也无法融合分数。</p>
     *
     * <p>融合公式为标准 RRF：{@code score = Σ 1/(k + rank)}，k=60 为业界常用常数，
     * 让排名靠前的贡献更大且对绝对分数尺度不敏感（两路检索的 score 量纲可能不同）。
     * 同时保留各路原始 score 的最大值，避免 RRF 的量级（约 0.016~0.033）泄漏到
     * 对外可见的分数上。</p>
     *
     * <p>{@code relevance} 同样取最大值传下去——它是 {@link RetrievalEvaluator#grade}
     * 的唯一依据，合并时丢了它，改写重检索召回的好结果就白捡了。</p>
     */
    private List<RagSearchService.Hit> mergeByRrf(List<RagSearchService.Hit> a, List<RagSearchService.Hit> b) {
        final int k = 60;
        Map<String, RagSearchService.Hit> byKey = new LinkedHashMap<>();
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, Double> maxScore = new LinkedHashMap<>();
        Map<String, Double> maxRelevance = new LinkedHashMap<>();

        for (List<RagSearchService.Hit> list : List.of(a, b)) {
            int rank = 0;
            for (RagSearchService.Hit h : list) {
                String key = hitKey(h);
                rrf.merge(key, 1.0 / (k + rank), Double::sum);
                maxScore.merge(key, h.score(), Math::max);
                if (h.relevance() != null) maxRelevance.merge(key, h.relevance(), Math::max);
                // 首次出现时登记；重复出现时用内容更完整的那条
                byKey.merge(key, h, (old, nu) -> {
                    boolean oldBlank = old.content() == null || old.content().isBlank();
                    boolean nuHasContent = nu.content() != null && !nu.content().isBlank();
                    return (oldBlank && nuHasContent) ? nu : old;
                });
                rank++;
            }
        }

        // 按 RRF 降序排列，但 score / relevance 回填为各路的最大值
        return byKey.entrySet().stream()
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, RagSearchService.Hit> e) -> -rrf.getOrDefault(e.getKey(), 0.0)))
                .map(e -> {
                    RagSearchService.Hit h = e.getValue();
                    double bestScore = maxScore.getOrDefault(e.getKey(), h.score());
                    Double bestRel = RagSearchService.Hit.higherRelevance(
                            maxRelevance.get(e.getKey()), h.relevance());
                    if (bestScore == h.score() && java.util.Objects.equals(bestRel, h.relevance())) return h;
                    return new RagSearchService.Hit(
                            h.source(), h.noteId(), h.sourcePath(), h.chunkIdx(),
                            h.content(), bestScore, bestRel, h.reason());
                })
                .toList();
    }

    private String hitKey(RagSearchService.Hit h) {
        if ("NOTE".equals(h.source())) {
            return "N:" + h.noteId() + "#" + (h.chunkIdx() != null ? h.chunkIdx() : "kw");
        }
        return "L:" + h.sourcePath() + "#" + (h.chunkIdx() != null ? h.chunkIdx() : 0);
    }

    private List<RagSearchService.Hit> truncate(List<RagSearchService.Hit> hits, int k) {
        if (hits.size() <= k) return hits;
        return hits.stream().limit(k).toList();
    }
}
