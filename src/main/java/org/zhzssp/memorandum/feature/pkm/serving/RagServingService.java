package org.zhzssp.memorandum.feature.pkm.serving;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG Serving 门面（R3）。
 *
 * <p>在 RagSearchService 之上提供：
 * <ol>
 *   <li>语义查询缓存（QueryResultCache.lookup/put）</li>
 *   <li>二阶段精排（Reranker.rerank）</li>
 *   <li>异步检索（searchAsync）与预取（prefetch）</li>
 *   <li>指标记录（RagServingMetrics）</li>
 * </ol>
 *
 * <p>所有能力均可通过配置开关按需启用/关闭，关时等价于直连 RagSearchService。</p>
 */
@Service
public class RagServingService {

    private static final Logger log = LoggerFactory.getLogger(RagServingService.class);

    private final RagSearchService rag;
    private final EmbeddingClient embeddingClient;
    private final QueryResultCache queryCache;
    private final Reranker reranker;
    private final RagServingMetrics metrics;
    private final Executor ragExecutor;

    public RagServingService(RagSearchService rag,
                              EmbeddingClient embeddingClient,
                              QueryResultCache queryCache,
                              Reranker reranker,
                              RagServingMetrics metrics,
                              @Qualifier("ragExecutor") Executor ragExecutor) {
        this.rag = rag;
        this.embeddingClient = embeddingClient;
        this.queryCache = queryCache;
        this.reranker = reranker;
        this.metrics = metrics;
        this.ragExecutor = ragExecutor;
    }

    /** 同步检索（对 KnowledgeTools / CorrectiveRetriever 透明替换 rag.search）。 */
    public List<RagSearchService.Hit> search(User user, String query, Integer topK) {
        if (user == null || query == null || query.isBlank()) return List.of();
        long t0 = System.currentTimeMillis();
        metrics.recordSearch();

        // 1) embed query
        float[] qv;
        try {
            qv = embeddingClient.embed(List.of(query)).get(0);
        } catch (Exception e) {
            log.debug("[RAG Serving] Embedding 失败，降级纯关键字：{}", e.getMessage());
            List<RagSearchService.Hit> hits = rag.search(user, query, topK);
            metrics.recordMiss();
            metrics.recordLatency(System.currentTimeMillis() - t0, false);
            return hits;
        }

        // 2) 语义缓存查找
        if (queryCache.enabled()) {
            var cached = queryCache.lookup(user.getId(), qv);
            if (cached.isPresent()) {
                metrics.recordCacheHit();
                long ms = System.currentTimeMillis() - t0;
                metrics.recordLatency(ms, true);
                log.debug("[RAG Serving] 查询缓存命中，{}ms", ms);
                List<RagSearchService.Hit> hits = cached.get();
                return truncate(hits, resolveTopK(topK));
            }
        }
        metrics.recordMiss();

        // 3) 粗排（candidates 上限）
        int k = resolveTopK(topK);
        int c = Math.min(k * 4, 20); // 粗排取 topK*4 或 20
        List<RagSearchService.Hit> candidates = rag.search(user, query, c);

        // 4) 可选二阶段精排（Reranker 内部自行记录 invoked / fallback）
        List<RagSearchService.Hit> result;
        if (reranker.enabled() && candidates.size() > k) {
            result = reranker.rerank(query, candidates, k);
        } else {
            result = truncate(candidates, k);
        }

        // 5) 回填缓存
        if (queryCache.enabled() && !result.isEmpty()) {
            queryCache.put(user.getId(), qv, result);
        }

        long ms = System.currentTimeMillis() - t0;
        metrics.recordLatency(ms, false);
        if (ms > 200) log.debug("[RAG Serving] 检索耗时 {}ms", ms);
        return result;
    }

    /** 异步检索（供预取 / 并发扇出）。 */
    public CompletableFuture<List<RagSearchService.Hit>> searchAsync(User user, String query, Integer topK) {
        return CompletableFuture.supplyAsync(() -> search(user, query, topK), ragExecutor);
    }

    /** 预取：fire-and-forget，用于 RESEARCH 子代理启动前。 */
    public void prefetch(User user, String query) {
        if (query == null || query.isBlank()) return;
        metrics.recordPrefetch();
        searchAsync(user, query, null).exceptionally(e -> {
            log.debug("[RAG Serving] 预取失败：{}", e.getMessage());
            return List.of();
        });
    }

    /** 暴露统计。 */
    public RagServingMetrics metrics() { return metrics; }
    public QueryResultCache queryCache() { return queryCache; }

    private int resolveTopK(Integer topK) {
        return (topK == null || topK <= 0) ? 6 : Math.min(topK, 20);
    }

    private List<RagSearchService.Hit> truncate(List<RagSearchService.Hit> hits, int k) {
        return hits.size() <= k ? hits : hits.stream().limit(k).toList();
    }
}
