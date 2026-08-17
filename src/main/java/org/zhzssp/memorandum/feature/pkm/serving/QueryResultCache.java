package org.zhzssp.memorandum.feature.pkm.serving;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 语义查询结果缓存（R1）。
 *
 * <p>以用户为 key，每用户保留最近 N 条 (queryVec, tops, ts)，新 query 的向量
 * 与缓存内任意条 cosine ≥ threshold 即视为命中（语义等价），直接复用结果。</p>
 *
 * <p>个人 KB 场景同一用户反复问相似问题，语义命中率远高于通用 RAG serving。
 * 笔记/文档变更时由 NoteIndexService 主动 invalidate 用户缓存。</p>
 */
@Component
public class QueryResultCache {

    private static final Logger log = LoggerFactory.getLogger(QueryResultCache.class);

    public record CachedQuery(float[] queryVec, List<RagSearchService.Hit> hits, long ts) {}

    private final Cache<Long, Deque<CachedQuery>> cache;

    @Value("${pkm.rag.serving.query-cache.threshold:0.93}")
    private double threshold;

    @Value("${pkm.rag.serving.query-cache.max-per-user:32}")
    private int maxPerUser;

    @Value("${pkm.rag.serving.query-cache.max-users:64}")
    private int maxUsers;

    public QueryResultCache(
            @Value("${pkm.rag.serving.query-cache.enabled:false}") boolean enabled,
            @Value("${pkm.rag.serving.query-cache.threshold:0.93}") double threshold,
            @Value("${pkm.rag.serving.query-cache.max-per-user:32}") int maxPerUser,
            @Value("${pkm.rag.serving.query-cache.max-users:64}") int maxUsers) {
        this.threshold = threshold;
        this.maxPerUser = maxPerUser;
        this.maxUsers = maxUsers;
        if (enabled) {
            this.cache = Caffeine.newBuilder()
                    .maximumSize(Math.max(1, maxUsers))
                    .expireAfterAccess(Duration.ofMinutes(30))
                    .recordStats()
                    .build();
            log.info("[RAG Serving] 查询缓存已启用，threshold={}, maxPerUser={}", threshold, maxPerUser);
        } else {
            this.cache = null;
            log.info("[RAG Serving] 查询缓存已禁用");
        }
    }

    public boolean enabled() { return cache != null; }

    /**
     * 语义命中查找：计算 queryVec 与用户缓存各条向量的 cosine 相似度，
     * ≥ threshold 即返回对应结果。
     */
    public Optional<List<RagSearchService.Hit>> lookup(Long userId, float[] queryVec) {
        if (!enabled() || userId == null || queryVec == null) return Optional.empty();
        Deque<CachedQuery> q = cache.getIfPresent(userId);
        if (q == null || q.isEmpty()) return Optional.empty();
        for (CachedQuery cq : q) {
            double sim = EmbeddingClient.cosine(queryVec, cq.queryVec());
            if (sim >= threshold) {
                log.debug("[RAG Serving] 查询缓存命中：sim={}", String.format("%.3f", sim));
                return Optional.of(cq.hits());
            }
        }
        return Optional.empty();
    }

    /** 存入当前查询 + 结果。 */
    public void put(Long userId, float[] queryVec, List<RagSearchService.Hit> hits) {
        if (!enabled() || userId == null || queryVec == null) return;
        Deque<CachedQuery> q = cache.get(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new CachedQuery(queryVec, hits, System.currentTimeMillis()));
            while (q.size() > maxPerUser) q.pollFirst();
        }
    }

    /** 笔记变更时失效用户缓存。 */
    public void invalidate(Long userId) {
        if (!enabled() || userId == null) return;
        cache.invalidate(userId);
    }

    public String stats() {
        if (cache == null) return "disabled";
        return cache.stats().toString() + ", size=" + cache.estimatedSize();
    }
}
