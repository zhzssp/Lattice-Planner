package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCache;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCacheMetrics;
import org.zhzssp.memorandum.feature.pkm.crag.CorrectiveRetriever;
import org.zhzssp.memorandum.feature.pkm.crag.CragMetrics;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator;
import org.zhzssp.memorandum.feature.pkm.serving.QueryResultCache;
import org.zhzssp.memorandum.feature.pkm.serving.RagServingMetrics;
import org.zhzssp.memorandum.feature.pkm.serving.Reranker;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI-Infra 可观测端点：把三大计划（RAG Serving / CRAG / Prefix Caching）的
 * 运行时指标从黑盒变为可度量数据。
 *
 * <p>端点（均需登录，走 WebSecurityConfig 的 anyRequest().authenticated()）：
 * <ul>
 *   <li>{@code GET /api/pkm/rag/stats}            — RAG Serving（R4）+ CRAG 指标 + 缓存 stats</li>
 *   <li>{@code GET /api/agent/prefix-cache/stats} — Prefix Caching（P5）+ 上游 prompt cache（P4）</li>
 *   <li>{@code GET /api/observability/stats}      — 上述两者汇总，便于一次拉取</li>
 * </ul>
 *
 * <p>所有指标为进程内累计值（应用重启归零），用于验证各计划的验收标准，
 * 不做持久化，也不引入 Micrometer/Prometheus 依赖。</p>
 */
@RestController
public class ObservabilityController {

    private final RagServingMetrics ragMetrics;
    private final QueryResultCache queryCache;
    private final Reranker reranker;
    private final EmbeddingVectorCache vectorCache;
    private final CragMetrics cragMetrics;
    private final CorrectiveRetriever correctiveRetriever;
    private final RetrievalEvaluator evaluator;
    private final PrefixCache prefixCache;
    private final PrefixCacheMetrics prefixMetrics;

    public ObservabilityController(RagServingMetrics ragMetrics,
                                   QueryResultCache queryCache,
                                   Reranker reranker,
                                   EmbeddingVectorCache vectorCache,
                                   CragMetrics cragMetrics,
                                   CorrectiveRetriever correctiveRetriever,
                                   RetrievalEvaluator evaluator,
                                   PrefixCache prefixCache,
                                   PrefixCacheMetrics prefixMetrics) {
        this.ragMetrics = ragMetrics;
        this.queryCache = queryCache;
        this.reranker = reranker;
        this.vectorCache = vectorCache;
        this.cragMetrics = cragMetrics;
        this.correctiveRetriever = correctiveRetriever;
        this.evaluator = evaluator;
        this.prefixCache = prefixCache;
        this.prefixMetrics = prefixMetrics;
    }

    /**
     * RAG Serving（R4）+ CRAG 指标。
     *
     * <p>响应示例：<pre>
     * {
     *   "ragServing": {
     *     "config": {"queryCacheEnabled":true, "rerankEnabled":false},
     *     "metrics": {"searchCount":42, "queryCache":{"hit":18,"miss":24,"hitRate":0.4286}, ...},
     *     "caches": {"queryResultCache":"CacheStats{...}", "embeddingVectorCache":"..."}
     *   },
     *   "crag": {
     *     "config": {"enabled":true, "upper":0.6, "lower":0.4},
     *     "metrics": {"retrieveCount":42, "gradeDistribution":{...}, "degradedRate":0.07, ...}
     *   }
     * }
     * </pre>
     */
    @GetMapping("/api/pkm/rag/stats")
    public Map<String, Object> ragStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ragServing", ragServingSection());
        out.put("crag", cragSection());
        return out;
    }

    /** Prefix Caching（P5）+ 上游 prompt cache 透传观测（P4）。 */
    @GetMapping("/api/agent/prefix-cache/stats")
    public Map<String, Object> prefixCacheStats() {
        return prefixCacheSection();
    }

    /** 三大计划指标汇总，一次拉取。 */
    @GetMapping("/api/observability/stats")
    public Map<String, Object> allStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ragServing", ragServingSection());
        out.put("crag", cragSection());
        out.put("prefixCache", prefixCacheSection());
        return out;
    }

    /* ---- 分区组装 ---- */

    private Map<String, Object> ragServingSection() {
        Map<String, Object> section = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("queryCacheEnabled", queryCache.enabled());
        config.put("rerankEnabled", reranker.enabled());
        section.put("config", config);

        section.put("metrics", ragMetrics.snapshot());

        Map<String, Object> caches = new LinkedHashMap<>();
        caches.put("queryResultCache", queryCache.stats());
        caches.put("embeddingVectorCache", vectorCache.stats());
        section.put("caches", caches);
        return section;
    }

    private Map<String, Object> cragSection() {
        Map<String, Object> section = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", correctiveRetriever.enabled());
        config.put("upper", evaluator.getUpper());
        config.put("lower", evaluator.getLower());
        section.put("config", config);

        section.put("metrics", cragMetrics.snapshot());
        return section;
    }

    private Map<String, Object> prefixCacheSection() {
        Map<String, Object> section = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", prefixCache.enabled());
        config.put("size", prefixCache.size());
        section.put("config", config);

        section.put("metrics", prefixMetrics.snapshot());
        section.put("caffeineStats", prefixCache.stats());
        return section;
    }
}
