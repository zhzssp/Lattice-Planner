package org.zhzssp.memorandum.feature.pkm.crag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   search → gradeByScore
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

    public CorrectiveRetriever(RagSearchService rag,
                               RetrievalEvaluator evaluator,
                               QueryRewriter rewriter,
                               RagServingService serving) {
        this.rag = rag;
        this.evaluator = evaluator;
        this.rewriter = rewriter;
        this.serving = serving;
    }

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

        // 1) 首次检索
        List<RagSearchService.Hit> hits = doSearch(user, query, k * 4); // 取候选池
        RetrievalEvaluator.Grade grade = evaluator.gradeByScore(hits);
        List<String> usedQueries = new ArrayList<>();
        usedQueries.add(query);

        if (grade == RetrievalEvaluator.Grade.CORRECT) {
            return new CragResult(truncate(hits, k), grade, false, usedQueries);
        }

        // 2) AMBIGUOUS / INCORRECT → 改写一次并合并
        log.debug("[CRAG] 检索质量={}（topScore={}），触发改写重检索",
                grade, hits.isEmpty() ? 0.0 : hits.get(0).score());

        List<String> rewrites = rewriter.rewrite(query, 2);
        for (String rw : rewrites.subList(1, rewrites.size())) {
            usedQueries.add(rw);
            List<RagSearchService.Hit> rwHits = doSearch(user, rw, k * 2);
            hits = mergeByRrf(hits, rwHits);
        }

        RetrievalEvaluator.Grade g2 = evaluator.gradeByScore(hits);
        boolean degraded = (g2 == RetrievalEvaluator.Grade.INCORRECT || hits.isEmpty());
        if (degraded) {
            log.info("[CRAG] 改写重检索后仍未达标，标记 degraded，建议 LLM 走通用知识");
        }

        return new CragResult(truncate(hits, k), g2, degraded, usedQueries);
    }

    /** RRF 合并两份命中列表（复用 RagSearchService 里的 (source,noteId/path,chunkIdx) key 思路）。 */
    private List<RagSearchService.Hit> mergeByRrf(List<RagSearchService.Hit> a, List<RagSearchService.Hit> b) {
        Map<String, RagSearchService.Hit> merged = new LinkedHashMap<>();
        int rank = 0;
        for (RagSearchService.Hit h : a) {
            String key = hitKey(h) + "#" + rank++;
            merged.put(key, h);
        }
        rank = 0;
        for (RagSearchService.Hit h : b) {
            String key = hitKey(h) + "#" + rank++;
            // 累加分数（简化的 RRF：取平均）
            merged.merge(key, h, (old, nu) -> new RagSearchService.Hit(
                    old.source(), old.noteId(), old.sourcePath(), old.chunkIdx(),
                    nu.content() != null && !nu.content().isBlank() ? nu.content() : old.content(),
                    (old.score() + nu.score()) / 2.0,
                    old.reason() + "|" + nu.reason()));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((RagSearchService.Hit hit) -> -hit.score()))
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
