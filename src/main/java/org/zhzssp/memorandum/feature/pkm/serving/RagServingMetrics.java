package org.zhzssp.memorandum.feature.pkm.serving;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG Serving 可观测指标（R4）。
 *
 * <p>线程安全的简易计数器，覆盖计划中的
 * {@code queryCacheHit / queryCacheMiss / rerankInvoked / rerankFallback / avgLatencyMs}，
 * 并额外统计 searchCount / prefetchCount 作为分母。
 * 通过 {@code GET /api/pkm/rag/stats} 暴露。</p>
 */
@Component
public class RagServingMetrics {

    private final AtomicLong queryCacheHit = new AtomicLong(0);
    private final AtomicLong queryCacheMiss = new AtomicLong(0);
    private final AtomicLong rerankInvoked = new AtomicLong(0);
    private final AtomicLong rerankFallback = new AtomicLong(0);
    private final AtomicLong searchCount = new AtomicLong(0);
    private final AtomicLong prefetchCount = new AtomicLong(0);

    /* 延迟统计：总耗时 + 命中/未命中分别累计，用于量化缓存收益 */
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicLong hitLatencyMs = new AtomicLong(0);
    private final AtomicLong missLatencyMs = new AtomicLong(0);

    public void recordCacheHit() { queryCacheHit.incrementAndGet(); }
    public void recordMiss() { queryCacheMiss.incrementAndGet(); }
    public void recordRerank() { rerankInvoked.incrementAndGet(); }
    public void recordRerankFallback() { rerankFallback.incrementAndGet(); }
    public void recordSearch() { searchCount.incrementAndGet(); }
    public void recordPrefetch() { prefetchCount.incrementAndGet(); }

    /** 记录一次检索的端到端耗时；cacheHit 用于区分命中/未命中两条路径的延迟。 */
    public void recordLatency(long ms, boolean cacheHit) {
        long v = Math.max(0, ms);
        totalLatencyMs.addAndGet(v);
        if (cacheHit) {
            hitLatencyMs.addAndGet(v);
        } else {
            missLatencyMs.addAndGet(v);
        }
    }

    public long cacheHit() { return queryCacheHit.get(); }
    public long cacheMiss() { return queryCacheMiss.get(); }
    public long rerankOk() { return rerankInvoked.get(); }
    public long rerankFb() { return rerankFallback.get(); }
    public long searches() { return searchCount.get(); }
    public long prefetches() { return prefetchCount.get(); }

    /** 计算命中率（0-1），无搜索时返回 0。 */
    public double cacheHitRate() {
        long total = queryCacheHit.get() + queryCacheMiss.get();
        return total == 0 ? 0.0 : (double) queryCacheHit.get() / total;
    }

    /** 全部检索的平均耗时（ms）。 */
    public double avgLatencyMs() {
        long n = searchCount.get();
        return n == 0 ? 0.0 : (double) totalLatencyMs.get() / n;
    }

    /** 缓存命中路径的平均耗时（ms）。 */
    public double avgHitLatencyMs() {
        long n = queryCacheHit.get();
        return n == 0 ? 0.0 : (double) hitLatencyMs.get() / n;
    }

    /** 缓存未命中路径的平均耗时（ms）。 */
    public double avgMissLatencyMs() {
        long n = queryCacheMiss.get();
        return n == 0 ? 0.0 : (double) missLatencyMs.get() / n;
    }

    /** 结构化快照，供 stats 端点输出。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("searchCount", searchCount.get());
        m.put("prefetchCount", prefetchCount.get());

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hit", queryCacheHit.get());
        cache.put("miss", queryCacheMiss.get());
        cache.put("hitRate", round4(cacheHitRate()));
        m.put("queryCache", cache);

        Map<String, Object> rerank = new LinkedHashMap<>();
        rerank.put("invoked", rerankInvoked.get());
        rerank.put("fallback", rerankFallback.get());
        rerank.put("fallbackRate", round4(fallbackRate()));
        m.put("rerank", rerank);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("avgMs", round2(avgLatencyMs()));
        latency.put("avgCacheHitMs", round2(avgHitLatencyMs()));
        latency.put("avgCacheMissMs", round2(avgMissLatencyMs()));
        // 缓存带来的单次节省（未命中均值 - 命中均值）
        latency.put("avgSavedPerHitMs", round2(Math.max(0, avgMissLatencyMs() - avgHitLatencyMs())));
        m.put("latency", latency);
        return m;
    }

    private double fallbackRate() {
        long total = rerankInvoked.get() + rerankFallback.get();
        return total == 0 ? 0.0 : (double) rerankFallback.get() / total;
    }

    @Override
    public String toString() {
        return String.format(
                "searches=%d queryCacheHit=%d miss=%d rate=%.2f rerank=%d fb=%d prefetch=%d avgMs=%.1f",
                searchCount.get(), queryCacheHit.get(), queryCacheMiss.get(),
                cacheHitRate(), rerankInvoked.get(), rerankFallback.get(),
                prefetchCount.get(), avgLatencyMs());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
