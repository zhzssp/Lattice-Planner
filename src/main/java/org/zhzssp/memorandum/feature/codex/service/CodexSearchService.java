package org.zhzssp.memorandum.feature.codex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KbChunk;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbChunkRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;

import java.util.*;

/**
 * Git 文档检索（Codex 的检索通路）。
 *
 * <h3>为什么是独立通路而不是塞进 RagSearchService</h3>
 * <p>{@code kb.semantic_search} 的返回结构被方案 A 的评测 cassette 依赖
 * （prompt 与工具 schema 的字节稳定性是回放命中的前提）。
 * 若把 Git 命中混进它的结果，既有 47 个评测用例会全部失效——
 * 那是这个项目最有价值的工程资产，不能为了少写一个类而砸掉。</p>
 *
 * <p>因此：Git 检索走独立的 {@code doc.search} 工具，
 * 笔记检索 {@code kb.semantic_search} <strong>逐字节不变</strong>。
 * 语义上也更清晰：kb = 我随手记的，doc = 我沉淀的知识仓库。</p>
 *
 * <h3>双通路与降级</h3>
 * <p>沿用既有 hybrid 设计：FULLTEXT ngram + 向量 cosine，RRF + alpha 加权融合。
 * 任一通路故障不影响另一路——未跑 V8 SQL 时 FULLTEXT 抛异常仅走向量，
 * 未配 embedding 时仅走关键字。</p>
 */
@Service
public class CodexSearchService {

    private static final Logger log = LoggerFactory.getLogger(CodexSearchService.class);

    /**
     * 一条命中。
     *
     * @param repoId      仓库
     * @param documentId  文档
     * @param path        仓库内相对路径
     * @param title       文档标题
     * @param anchor      章节 anchor（可为 null）
     * @param headingPath 章节祖先链
     * @param chunkIdx    切片序号
     * @param content     片段正文
     * @param score       融合分数
     * @param reason      命中来源（kw / vec 0.812 / kw+vec 0.701）
     */
    public record GitHit(Long repoId,
                         Long documentId,
                         String path,
                         String title,
                         String anchor,
                         String headingPath,
                         Integer chunkIdx,
                         String content,
                         double score,
                         String reason) {

        /** 可点击的引用定位串，如 {@code docs/x.md#46-timeline-semaphore}。 */
        public String locator() {
            return (anchor == null || anchor.isBlank()) ? path : path + "#" + anchor;
        }
    }

    @Value("${pkm.rag.git.enabled:false}")
    private boolean gitSearchEnabled;

    @Value("${pkm.rag.git.alpha:0.4}")
    private double alpha;

    @Value("${pkm.rag.candidates:50}")
    private int candidates;

    private final KbChunkRepository chunkRepo;
    private final KbDocumentRepository docRepo;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingVectorCache vectorCache;
    private final RepoRegistryService registry;
    private final CodexMetrics metrics;

    public CodexSearchService(KbChunkRepository chunkRepo,
                              KbDocumentRepository docRepo,
                              EmbeddingClient embeddingClient,
                              EmbeddingVectorCache vectorCache,
                              RepoRegistryService registry,
                              CodexMetrics metrics) {
        this.chunkRepo = chunkRepo;
        this.docRepo = docRepo;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
        this.registry = registry;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return gitSearchEnabled;
    }

    /**
     * 把 kb_chunk 的向量加载注册到共享向量缓存。
     *
     * <p>用注册回调而非让缓存类直接依赖 codex 模块：
     * {@code pkm} 是被依赖的底层模块，让它反向 import {@code codex} 会形成循环，
     * 也会破坏「Codex 全关时 pkm 行为不变」这条降级约束。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerVectorLoader() {
        vectorCache.registerScopeLoader((userId, scopeKey) -> {
            if (scopeKey == null || !scopeKey.startsWith("repo:")) return null;
            Long repoId;
            try {
                repoId = Long.parseLong(scopeKey.substring("repo:".length()));
            } catch (NumberFormatException e) {
                return null;
            }
            List<KbChunk> rows = chunkRepo.findByUserIdAndRepoId(userId, repoId);
            List<EmbeddingVectorCache.Entry> out = new ArrayList<>(rows.size());
            int bad = 0;
            for (KbChunk c : rows) {
                if (c.getEmbedding() == null || c.getEmbedding().isBlank()) continue;
                try {
                    float[] v = embeddingClient.deserialize(c.getEmbedding());
                    out.add(new EmbeddingVectorCache.Entry(
                            "GIT_DOC", 0L, null, c.getChunkIdx(), c.getContent(), v,
                            c.getRepoId(), c.getDocumentId(), c.getHeadingPath(), c.getAnchor()));
                } catch (Exception e) {
                    bad++;
                }
            }
            if (bad > 0) {
                log.warn("[Codex] 仓库 {} 加载向量：成功 {} 条，失败 {} 条", repoId, out.size(), bad);
            }
            return out;
        });
        log.info("[Codex] 已注册 Git 文档向量加载器");
    }

    /**
     * 检索用户全部启用仓库。
     *
     * @param topK 返回条数
     */
    public List<GitHit> search(Long userId, String query, Integer topK) {
        if (!gitSearchEnabled || userId == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<KnowledgeRepo> repos = registry.listEnabled(userId);
        if (repos.isEmpty()) return List.of();

        int k = (topK == null || topK <= 0) ? 6 : Math.min(topK, 20);
        Map<String, GitHit> merged = new LinkedHashMap<>();
        Map<Long, KbDocument> docCache = new HashMap<>();
        // 两路都失败才算真降级；单路失败虽仍有结果，但质量下降，需计入指标
        boolean kwFailed = false;
        boolean vecFailed = false;

        // ---- 关键字通路 ----
        try {
            int rank = 0;
            for (KbChunk c : chunkRepo.fulltextSearchAllRepos(userId, query, candidates)) {
                double s = (1.0 - alpha) * (1.0 / (1.0 + rank++));
                KbDocument d = doc(docCache, c.getDocumentId());
                if (d == null) continue;
                merged.merge(keyOf(c), toHit(c, d, s, "kw"),
                        (a, b) -> b.score() > a.score() ? b : a);
            }
        } catch (Exception ex) {
            kwFailed = true;
            log.debug("[Codex] FULLTEXT 检索不可用（多半是未跑 V8__codex_git_repo.sql），降级仅向量：{}",
                    ex.getMessage());
        }

        // ---- 向量通路（逐仓库走各自的缓存桶）----
        try {
            float[] qv = embeddingClient.embed(List.of(query)).get(0);
            List<GitHit> vecHits = new ArrayList<>();
            for (KnowledgeRepo repo : repos) {
                String scope = EmbeddingVectorCache.scopeOfRepo(repo.getId());
                for (EmbeddingVectorCache.Entry e : vectorCache.load(userId, scope)) {
                    double sim = EmbeddingClient.cosine(qv, e.vec());
                    KbDocument d = doc(docCache, e.documentId());
                    if (d == null) continue;
                    vecHits.add(new GitHit(e.repoId(), e.documentId(), d.getPath(), d.getTitle(),
                            e.anchor(), e.headingPath(), e.chunkIdx(), e.content(),
                            alpha * sim, String.format(Locale.ROOT, "vec %.3f", sim)));
                }
            }
            vecHits.sort(Comparator.comparingDouble((GitHit h) -> -h.score()));
            vecHits.stream().limit(candidates).forEach(h -> {
                String key = h.documentId() + "#" + h.chunkIdx();
                merged.merge(key, h, (a, b) -> new GitHit(
                        a.repoId(), a.documentId(), a.path(), a.title(), a.anchor(),
                        a.headingPath(), a.chunkIdx(),
                        (a.content() == null || a.content().isBlank()) ? b.content() : a.content(),
                        a.score() + b.score(),
                        a.reason() + "+" + b.reason()));
            });
        } catch (Exception ex) {
            vecFailed = true;
            log.debug("[Codex] 向量通路不可用（多半是 EMBED_API_KEY 未配置），降级仅关键字：{}",
                    ex.getMessage());
        }

        List<GitHit> result = merged.values().stream()
                .sorted(Comparator.comparingDouble((GitHit h) -> -h.score()))
                .limit(k)
                .toList();
        metrics.recordSearch(result.size(), kwFailed || vecFailed);
        return result;
    }

    private String keyOf(KbChunk c) {
        return c.getDocumentId() + "#" + c.getChunkIdx();
    }

    private GitHit toHit(KbChunk c, KbDocument d, double score, String reason) {
        return new GitHit(c.getRepoId(), c.getDocumentId(), d.getPath(), d.getTitle(),
                c.getAnchor(), c.getHeadingPath(), c.getChunkIdx(), c.getContent(),
                score, reason);
    }

    private KbDocument doc(Map<Long, KbDocument> cache, Long id) {
        if (id == null) return null;
        return cache.computeIfAbsent(id, k -> docRepo.findById(k).orElse(null));
    }
}
