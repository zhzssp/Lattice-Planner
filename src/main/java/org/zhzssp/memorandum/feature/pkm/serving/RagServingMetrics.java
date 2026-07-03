package org.zhzssp.memorandum.feature.pkm.serving;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG Serving 可观测指标（R4）。
 *
 * <p>线程安全的简易计数器，供 RagServingService 记录检索流量特征。
 * 后续可通过 MCP 端点或管理页面暴露。</p>
 */
@Component
public class RagServingMetrics {

    private final AtomicLong queryCacheHit = new AtomicLong(0);
    private final AtomicLong queryCacheMiss = new AtomicLong(0);
    private final AtomicLong rerankInvoked = new AtomicLong(0);
    private final AtomicLong rerankFallback = new AtomicLong(0);
    private final AtomicLong searchCount = new AtomicLong(0);
    private final AtomicLong prefetchCount = new AtomicLong(0);

    public void recordCacheHit() { queryCacheHit.incrementAndGet(); }
    public void recordMiss() { queryCacheMiss.incrementAndGet(); }
    public void recordRerank() { rerankInvoked.incrementAndGet(); }
    public void recordRerankFallback() { rerankFallback.incrementAndGet(); }
    public void recordSearch() { searchCount.incrementAndGet(); }
    public void recordPrefetch() { prefetchCount.incrementAndGet(); }

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

    @Override
    public String toString() {
        return String.format("searches=%d queryCacheHit=%d miss=%d rate=%.2f rerank=%d fb=%d prefetch=%d",
                searchCount.get(), queryCacheHit.get(), queryCacheMiss.get(),
                cacheHitRate(), rerankInvoked.get(), rerankFallback.get(), prefetchCount.get());
    }
}
