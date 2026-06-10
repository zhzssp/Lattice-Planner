package org.zhzssp.memorandum.feature.pkm.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.NoteEmbedding;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户级向量 LRU 缓存（PKM-RAG Stage 3）。
 *
 * 背景：
 *   RagSearchService 原本每次 search 都要：
 *     1. embeddingRepository.findByUserId(userId) → 命中 N 行 NoteEmbedding；
 *     2. 对每行 deserialize JSON → float[]（这是真正的瓶颈，jackson + 1024 浮点）；
 *     3. 全表 cosine。
 *   单用户万级 chunk 时第 2 步可达 100ms+，并发时直接打满 CPU。
 *
 * 设计：
 *   - 以 userId 为 key 缓存 List<Entry>，Entry 已经是反序列化好的 float[]；
 *   - 写穿透：保存 / 摄取 / 删除时由 NoteIndexService 主动 invalidate；
 *   - 兜底过期：expireAfterAccess 30 分钟，防止离线用户长期占内存；
 *   - 容量上限：可配 pkm.rag.cache.max-users（默认 32），LRU 淘汰；
 *   - 失败安全：load 阶段任何异常都抛回调用方，由 RagSearchService 现有 try/catch 处理。
 *
 * 与 V1 关系：
 *   RagSearchService 切换到 cache.load(userId)；纯关键字通路不变。
 *   embedding API 不可用时 EmbeddingClient.embed() 仍抛错，向量通路仍降级，
 *   缓存只影响"读侧"，不引入新的失败路径。
 */
@Component
public class EmbeddingVectorCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingVectorCache.class);

    /** 反序列化后的向量条目（与 NoteEmbedding 主要字段对齐，省去再次 deserialize）。 */
    public record Entry(String source,
                        Long noteId,
                        String sourcePath,
                        Integer chunkIdx,
                        String content,
                        float[] vec) {}

    private final NoteEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;

    /** Caffeine LRU；启用时由构造函数初始化。 */
    private final Cache<Long, List<Entry>> cache;

    public EmbeddingVectorCache(NoteEmbeddingRepository embeddingRepository,
                                EmbeddingClient embeddingClient,
                                @Value("${pkm.rag.cache.max-users:32}") int maxUsers,
                                @Value("${pkm.rag.cache.expire-minutes:30}") int expireMinutes) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxUsers))
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, expireMinutes)))
                .recordStats()
                .build();
    }

    /**
     * 取该用户已反序列化的所有向量。命中缓存直接返回；未命中则查库 + 反序列化后放入缓存。
     * 抛错语义：load 失败（如 DB 异常）会抛回调用方。
     */
    public List<Entry> load(Long userId) {
        if (userId == null) return List.of();
        List<Entry> cached = cache.get(userId, this::doLoad);
        return cached == null ? List.of() : cached;
    }

    /** 写时失效：由 NoteIndexService 在 rebuildForNote / rebuildForLocalDoc / deleteLocalDoc 末尾调用。 */
    public void invalidate(Long userId) {
        if (userId == null) return;
        cache.invalidate(userId);
    }

    /** 暴露简易统计便于运维 / 排查（hit ratio 不达预期时可调大 maxUsers）。 */
    public String stats() {
        return cache.stats().toString() + ", size=" + cache.estimatedSize();
    }

    private List<Entry> doLoad(Long userId) {
        List<NoteEmbedding> rows = embeddingRepository.findByUserId(userId);
        if (rows.isEmpty()) return List.of();
        List<Entry> out = new ArrayList<>(rows.size());
        int bad = 0;
        for (NoteEmbedding e : rows) {
            try {
                float[] v = embeddingClient.deserialize(e.getEmbedding());
                out.add(new Entry(e.getSource(), e.getNoteId(), e.getSourcePath(),
                        e.getChunkIdx(), e.getContent(), v));
            } catch (Exception ex) {
                bad++;
            }
        }
        if (bad > 0) {
            log.warn("[PKM] 用户 {} 加载向量缓存：成功 {} 条，失败 {} 条（被跳过）",
                    userId, out.size(), bad);
        }
        return out;
    }
}
