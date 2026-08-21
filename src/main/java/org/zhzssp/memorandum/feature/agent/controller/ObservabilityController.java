package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCache;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCacheMetrics;
import org.zhzssp.memorandum.feature.agent.runtime.ReflexionAdvisor;
import org.zhzssp.memorandum.feature.agent.runtime.trace.AgentTraceMetrics;
import org.zhzssp.memorandum.feature.agent.tool.ToolArgumentValidator;
import org.zhzssp.memorandum.feature.pkm.crag.CorrectiveRetriever;
import org.zhzssp.memorandum.feature.pkm.crag.CragMetrics;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator;
import org.zhzssp.memorandum.feature.pkm.serving.QueryResultCache;
import org.zhzssp.memorandum.feature.pkm.serving.RagServingMetrics;
import org.zhzssp.memorandum.feature.pkm.serving.Reranker;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;
import org.zhzssp.memorandum.feature.codex.service.CodexMetrics;
import org.zhzssp.memorandum.feature.codex.service.CodexSearchService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

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
    private final AgentTraceMetrics traceMetrics;
    private final ReflexionAdvisor reflexionAdvisor;
    private final ToolArgumentValidator argValidator;
    private final CodexMetrics codexMetrics;
    private final CodexSearchService codexSearch;
    private final RepoRegistryService codexRegistry;

    public ObservabilityController(RagServingMetrics ragMetrics,
                                   QueryResultCache queryCache,
                                   Reranker reranker,
                                   EmbeddingVectorCache vectorCache,
                                   CragMetrics cragMetrics,
                                   CorrectiveRetriever correctiveRetriever,
                                   RetrievalEvaluator evaluator,
                                   PrefixCache prefixCache,
                                   PrefixCacheMetrics prefixMetrics,
                                   AgentTraceMetrics traceMetrics,
                                   ReflexionAdvisor reflexionAdvisor,
                                   ToolArgumentValidator argValidator,
                                   CodexMetrics codexMetrics,
                                   CodexSearchService codexSearch,
                                   RepoRegistryService codexRegistry) {
        this.ragMetrics = ragMetrics;
        this.queryCache = queryCache;
        this.reranker = reranker;
        this.vectorCache = vectorCache;
        this.cragMetrics = cragMetrics;
        this.correctiveRetriever = correctiveRetriever;
        this.evaluator = evaluator;
        this.prefixCache = prefixCache;
        this.prefixMetrics = prefixMetrics;
        this.traceMetrics = traceMetrics;
        this.reflexionAdvisor = reflexionAdvisor;
        this.argValidator = argValidator;
        this.codexMetrics = codexMetrics;
        this.codexSearch = codexSearch;
        this.codexRegistry = codexRegistry;
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

    /**
     * ReAct 循环层指标：步数分布、收敛率、工具失败率、工具幻觉率，
     * 以及显式 Reflexion（D）与参数前置校验（E）的效果指标。
     *
     * <p>这一层此前是黑盒——只有日志和 WS 推送，无法统计。</p>
     *
     * <p>关键可汇报数字：
     * <ul>
     *   <li>{@code reflexion.selfRepairRate} — 工具失败后重试成功的比例，
     *       直接度量「回灌给 LLM 的错误信息是否可操作」，是 E 的效果证明</li>
     *   <li>{@code reflexion.bannedToolCallsBlocked} — 模型无视封禁提示被强制拦下的次数，
     *       不为 0 即证明「只做提示层不够」</li>
     *   <li>{@code argValidation.topRejectedParams} — 最常被填错的参数，
     *       指向需要改进描述的 schema</li>
     * </ul>
     */
    @GetMapping("/api/agent/trace/stats")
    public Map<String, Object> traceStats() {
        return traceSection();
    }

    /** 全部指标汇总，一次拉取。 */
    @GetMapping("/api/observability/stats")
    public Map<String, Object> allStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentTrace", traceSection());
        out.put("ragServing", ragServingSection());
        out.put("crag", cragSection());
        out.put("prefixCache", prefixCacheSection());
        out.put("codex", codexSection());
        return out;
    }

    /** Codex 知识仓库指标（V4）。 */
    @GetMapping("/api/codex/stats")
    public Map<String, Object> codexStats() {
        return codexSection();
    }

    /* ---- 分区组装 ---- */

    /**
     * ReAct 轨迹分区。
     *
     * <p>必须带 config 回显：看到 {@code selfRepairRate=0.8} 却不知道
     * 参数校验是开还是关，这个数字就没有解释力（也无法做前后对比）。</p>
     */
    private Map<String, Object> traceSection() {
        Map<String, Object> section = new LinkedHashMap<>(traceMetrics.snapshot());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("reflexionEnabled", reflexionAdvisor.enabled());
        config.put("reflexionFailThreshold", reflexionAdvisor.failThreshold());
        config.put("argValidationEnabled", argValidator.enabled());
        section.put("config", config);
        return section;
    }

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

    /**
     * Codex 分区（V4）。
     *
     * <p>三个数字最值得盯：
     * <ul>
     *   <li>{@code index.skipRate} —— 长期为 0 说明增量索引失效（多为 blobHash 计算问题）；</li>
     *   <li>{@code index.truncatedDocs} —— &gt; 0 说明有文档只被部分索引，检索会漏；</li>
     *   <li>{@code notFoundRate} —— 随知识体系变完整应当下降，是「体系在长」的量化信号。</li>
     * </ul>
     */
    private Map<String, Object> codexSection() {
        Map<String, Object> section = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", codexRegistry.enabled());
        config.put("operational", codexRegistry.operational());
        config.put("gitSearchEnabled", codexSearch.enabled());
        config.put("gitVersion", codexRegistry.gitVersion());
        section.put("config", config);

        section.put("metrics", codexMetrics.snapshot());
        return section;
    }

    private Map<String, Object> prefixCacheSection() {        Map<String, Object> section = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", prefixCache.enabled());
        config.put("size", prefixCache.size());
        section.put("config", config);

        section.put("metrics", prefixMetrics.snapshot());
        section.put("caffeineStats", prefixCache.stats());
        return section;
    }
}
