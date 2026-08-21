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
import java.util.Objects;

/**
 * 用户级向量 LRU 缓存（PKM-RAG Stage 3；P0a 改造为「按 scope 分桶 + 按向量数计权」）。
 *
 * <h3>背景</h3>
 * <p>RagSearchService 原本每次 search 都要：
 * ① {@code findByUserId} 命中 N 行 → ② 每行 deserialize JSON → float[]（真正的瓶颈）
 * → ③ 全表 cosine。单用户万级 chunk 时第 ② 步可达 100ms+。</p>
 *
 * <h3>P0a 为什么必须改（Codex 接入的前置条件）</h3>
 * <p>原实现有两个在「只有手写笔记」时无害、但接入 Git 仓库后致命的问题：</p>
 * <ol>
 *   <li><strong>容量口径错</strong>：{@code maximumSize(maxUsers)} 计的是<em>用户数</em>，
 *       而单个用户的向量体积可以差三个数量级。一个 172 万字符的知识仓库约 2900 chunk，
 *       × 1024 维 float ≈ 12 MB；32 个这样的用户 ≈ 380 MB+，JVM 默认堆直接 OOM。
 *       <strong>瓶颈是内存而非算力</strong>，所以改为 {@code maximumWeight} 按向量条数计权。</li>
 *   <li><strong>无法按来源过滤</strong>：{@code Entry} 只有 noteId/sourcePath，
 *       查一个仓库的文档必须扫描该用户的全部向量（含全部笔记）。
 *       新增 {@code scopeKey} 分桶后，笔记与各仓库互不干扰。</li>
 * </ol>
 *
 * <h3>兼容性</h3>
 * <p>笔记路径统一走 {@link #SCOPE_NOTE} 桶，{@code load(userId)} 旧签名保留并委托，
 * 行为与改造前等价；{@code Entry} 新增的 repoId/documentId/sectionId 对笔记为 null。</p>
 *
 * <h3>失败安全</h3>
 * <p>load 阶段异常抛回调用方，由 RagSearchService 现有 try/catch 降级处理（仅少一路结果）。</p>
 */
@Component
public class EmbeddingVectorCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingVectorCache.class);

    /** 手写笔记 + LOCAL_DOC 摄取所在的桶（历史行为）。 */
    public static final String SCOPE_NOTE = "note";

    /** Git 仓库文档的桶前缀，形如 {@code repo:7}。 */
    public static String scopeOfRepo(Long repoId) {
        return "repo:" + repoId;
    }

    /**
     * 反序列化后的向量条目。
     *
     * @param source     NOTE | LOCAL_DOC | GIT_DOC
     * @param noteId     NOTE 时为 note.id；其余为 0
     * @param sourcePath LOCAL_DOC 的路径 / GIT_DOC 的仓库内相对路径
     * @param chunkIdx   chunk 序号
     * @param content    片段正文
     * @param vec        向量
     * @param repoId     GIT_DOC 所属仓库；其余 null
     * @param documentId GIT_DOC 所属文档；其余 null
     * @param headingPath GIT_DOC 的章节路径（用于精确引用定位）；其余 null
     * @param anchor     GIT_DOC 的章节 anchor；其余 null
     */
    public record Entry(String source,
                        Long noteId,
                        String sourcePath,
                        Integer chunkIdx,
                        String content,
                        float[] vec,
                        Long repoId,
                        Long documentId,
                        String headingPath,
                        String anchor) {

        /** 笔记 / LOCAL_DOC 用的简化构造（保持原 6 参语义）。 */
        public static Entry ofNote(String source, Long noteId, String sourcePath,
                                   Integer chunkIdx, String content, float[] vec) {
            return new Entry(source, noteId, sourcePath, chunkIdx, content, vec,
                    null, null, null, null);
        }
    }

    /** 缓存键：用户 + scope 桶。 */
    private record CacheKey(Long userId, String scopeKey) {}

    private final NoteEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;

    /** Caffeine LRU，权重 = 桶内向量条数。 */
    private final Cache<CacheKey, List<Entry>> cache;

    /** 外部注册的 scope 加载器（Codex 用：把 kb_chunk 的加载注入进来，避免本类反向依赖 codex 模块）。 */
    private volatile ScopeLoader externalLoader;

    /** 按 scopeKey 加载向量的扩展点。 */
    public interface ScopeLoader {
        /** @return null 表示本加载器不认识该 scopeKey */
        List<Entry> load(Long userId, String scopeKey);
    }

    public EmbeddingVectorCache(NoteEmbeddingRepository embeddingRepository,
                                EmbeddingClient embeddingClient,
                                @Value("${pkm.rag.cache.max-vectors:20000}") int maxVectors,
                                @Value("${pkm.rag.cache.expire-minutes:30}") int expireMinutes) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
        this.cache = Caffeine.newBuilder()
                // 按向量条数计权，而非按用户数——单用户体积差异可达三个数量级
                .maximumWeight(Math.max(1000, maxVectors))
                .weigher((CacheKey k, List<Entry> v) -> Math.max(1, v.size()))
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, expireMinutes)))
                .recordStats()
                .build();
        log.info("[PKM] 向量缓存初始化：maxVectors={}, expireMinutes={}", maxVectors, expireMinutes);
    }

    /** 注册外部 scope 加载器（由 Codex 模块在启动时调用）。 */
    public void registerScopeLoader(ScopeLoader loader) {
        this.externalLoader = loader;
    }

    /**
     * 取该用户笔记桶的全部向量（旧签名，行为与改造前等价）。
     */
    public List<Entry> load(Long userId) {
        return load(userId, SCOPE_NOTE);
    }

    /**
     * 取该用户指定 scope 桶的全部向量。命中缓存直接返回；未命中则加载 + 反序列化后放入。
     */
    public List<Entry> load(Long userId, String scopeKey) {
        if (userId == null) return List.of();
        String scope = (scopeKey == null || scopeKey.isBlank()) ? SCOPE_NOTE : scopeKey;
        List<Entry> cached = cache.get(new CacheKey(userId, scope), this::doLoad);
        return cached == null ? List.of() : cached;
    }

    /** 写时失效：失效该用户的<strong>全部</strong> scope 桶。 */
    public void invalidate(Long userId) {
        if (userId == null) return;
        cache.asMap().keySet().removeIf(k -> Objects.equals(k.userId(), userId));
    }

    /** 写时失效：只失效该用户的某个 scope 桶（Codex 单仓库重索引用，避免误清笔记桶）。 */
    public void invalidate(Long userId, String scopeKey) {
        if (userId == null || scopeKey == null) return;
        cache.invalidate(new CacheKey(userId, scopeKey));
    }

    /** 暴露简易统计便于运维 / 排查。 */
    public String stats() {
        long vectors = cache.asMap().values().stream().mapToLong(List::size).sum();
        return cache.stats() + ", buckets=" + cache.estimatedSize() + ", vectors=" + vectors;
    }

    private List<Entry> doLoad(CacheKey key) {
        // Git 文档等非笔记桶交给外部加载器
        if (!SCOPE_NOTE.equals(key.scopeKey())) {
            ScopeLoader loader = this.externalLoader;
            if (loader == null) return List.of();
            List<Entry> out = loader.load(key.userId(), key.scopeKey());
            return out == null ? List.of() : out;
        }
        List<NoteEmbedding> rows = embeddingRepository.findByUserId(key.userId());
        if (rows.isEmpty()) return List.of();
        List<Entry> out = new ArrayList<>(rows.size());
        int bad = 0;
        for (NoteEmbedding e : rows) {
            try {
                float[] v = embeddingClient.deserialize(e.getEmbedding());
                out.add(Entry.ofNote(e.getSource(), e.getNoteId(), e.getSourcePath(),
                        e.getChunkIdx(), e.getContent(), v));
            } catch (Exception ex) {
                bad++;
            }
        }
        if (bad > 0) {
            log.warn("[PKM] 用户 {} 加载向量缓存：成功 {} 条，失败 {} 条（被跳过）",
                    key.userId(), out.size(), bad);
        }
        return out;
    }
}
