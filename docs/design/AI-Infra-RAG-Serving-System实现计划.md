# RAG Serving System 实现计划

> 目标：在现有 hybrid 检索（`RagSearchService`）之上补齐"查询侧 serving 能力"，把一次性同步检索升级为带**查询缓存 + 二阶段 rerank + 异步流水线 + 可观测**的服务化检索层。
>
> 约束：不引入向量数据库、不引入 LangChain4j / Spring AI，复用现有 `LlmGateway` / `EmbeddingClient` / Caffeine，保持所有对外接口签名不变、降级语义不变。

---

## 1. 现状基线（实测事实）

| 维度 | 现状 | 文件 |
|---|---|---|
| 检索 | Hybrid：MySQL ngram `FULLTEXT` + 应用层 cosine，`alpha=0.4` 加权融合 | `RagSearchService.search(User, String, Integer)` |
| 向量存储 | `NoteEmbedding.embedding` = `MEDIUMTEXT` JSON，1024 维 | `NoteEmbedding` |
| 已有缓存 | `EmbeddingVectorCache`（Caffeine，按 userId 缓存**反序列化后的向量**） | `EmbeddingVectorCache.load(Long)` |
| Embedding | `bge-m3`，OpenAI-Compatible `/v1/embeddings` | `LlmGateway.generateEmbedding(List<String>)` |
| 入口 | Agent 工具 `kb.semantic_search` → `rag.search(u, query, topK)` | `KnowledgeTools.semanticSearch` |
| 规模/性能 | 万级 chunk × 1024 维全表 cosine ~10ms | `docs/PKM-RAG实施成果.md` |

### 三个 serving 缺口
1. **无查询→结果缓存**：只缓存了向量本身，同一/相似 query 每次都要重新 embed + 全表 cosine。
2. **无 rerank**：只有加权融合排序，缺二阶段精排。
3. **同步阻塞**：`search()` 全同步，Agent 主循环 + RESEARCH 子代理都会被阻塞。

---

## 2. 目标架构

```
kb.semantic_search / RESEARCH 子代理
        │
        ▼
┌─────────────────────────────────────────────┐
│  RagServingService （新增，门面）               │
│  ┌───────────┐  miss  ┌──────────────────┐   │
│  │ QueryCache │──────▶│ RagSearchService  │   │  ① 语义查询缓存
│  │ (语义命中) │◀──hit──│ (现有 hybrid 检索) │   │
│  └───────────┘        └──────────────────┘   │
│        │ 候选 top-N（N=candidates 50）          │
│        ▼                                       │
│  ┌──────────────────┐                          │
│  │ Reranker          │  LLM-as-Ranker（可关）   │  ② 二阶段精排
│  │ (top-N → top-K)   │  复用 LlmGateway        │
│  └──────────────────┘                          │
│        │ top-K                                  │
│        ▼                                       │
│  写回 QueryCache + 记录 metrics                 │  ③ 异步预取 / ④ 可观测
└─────────────────────────────────────────────┘
```

保持 `RagSearchService` 不动，新增门面 `RagServingService` 承载 serving 逻辑；`KnowledgeTools` 改为注入门面。

---

## 3. 分阶段实现

### R1：查询侧语义缓存 `QueryResultCache`

**动机**：个人 KB 场景同一用户高频重复/近似提问，语义命中率远高于通用场景。

**新增文件**：`feature/pkm/serving/QueryResultCache.java`

```java
@Component
public class QueryResultCache {
    // key: userId ；value: 该用户最近若干条 (queryVec, topK结果, ts)
    public record CachedQuery(float[] queryVec, List<RagSearchService.Hit> hits, long ts) {}

    private final Cache<Long, java.util.Deque<CachedQuery>> cache; // Caffeine，maxUsers 可配

    /** 语义命中：新 query 向量与缓存内任一 query 向量 cosine ≥ threshold 即复用。 */
    public Optional<List<RagSearchService.Hit>> lookup(Long userId, float[] queryVec) { ... }

    public void put(Long userId, float[] queryVec, List<RagSearchService.Hit> hits) { ... }
    public void invalidate(Long userId) { ... }   // 与 EmbeddingVectorCache 同步失效
    public String stats() { ... }
}
```

**命中逻辑**：复用 `EmbeddingClient.cosine`，`sim ≥ pkm.rag.serving.query-cache.threshold`（默认 `0.93`）视为等价查询。每用户保留最近 `N=32` 条，LRU。

**失效**：在 `NoteIndexService` 已有的 `vectorCache.invalidate(userId)` 调用点旁边，追加 `queryResultCache.invalidate(userId)`（笔记变更即缓存脏）。

**配置**：
```properties
pkm.rag.serving.query-cache.enabled=true
pkm.rag.serving.query-cache.threshold=0.93
pkm.rag.serving.query-cache.max-per-user=32
pkm.rag.serving.query-cache.max-users=64
```

---

### R2：二阶段 Rerank（LLM-as-Ranker）

**动机**：不引入 cross-encoder 模型，用现有 LLM 对粗排 top-N 精排，抬升召回质量上限。

**新增文件**：`feature/pkm/serving/Reranker.java`

```java
@Component
public class Reranker {
    private final LlmGateway llm;

    /** 输入粗排候选（top-N），输出重排后 top-K；LLM 不可用/超时时原样返回。 */
    public List<RagSearchService.Hit> rerank(String query, List<RagSearchService.Hit> candidates, int topK) {
        // 1. 组装紧凑 prompt：query + 编号候选（截断 content 到 ~200 字）
        // 2. 要求 LLM 只输出相关度降序的候选编号数组 JSON，如 [3,1,7,...]
        // 3. 解析编号 → 按新序取 topK；解析失败/超时 → return candidates.subList(0, topK)
    }
}
```

**要点**：
- Rerank 是**尽力而为**：任何异常（超时/解析失败）都回退到原融合序，绝不劣化可用性。
- Prompt 只让模型输出编号数组（token 少、易解析），不让它复述内容。
- 温度 0，走 `LlmGateway.generateText` 或新增一个轻量方法。

**配置**：
```properties
pkm.rag.serving.rerank.enabled=false   # 默认关，联调稳定后开
pkm.rag.serving.rerank.candidate-n=20  # 送入 rerank 的粗排条数
pkm.rag.serving.rerank.timeout-ms=4000
```

---

### R3：异步检索流水线 + 预取

**动机**：Agent 主循环与 RESEARCH 子代理同步等待检索，放大 P99。

**新增文件**：`feature/pkm/serving/RagServingService.java`（门面，整合 R1/R2）

```java
@Service
public class RagServingService {
    private final RagSearchService ragSearchService;
    private final EmbeddingClient embeddingClient;
    private final QueryResultCache queryCache;
    private final Reranker reranker;
    private final Executor ragExecutor;  // 独立线程池，避免占用 Web / WS 线程

    /** 同步检索（对 KnowledgeTools 透明替换 rag.search）。 */
    public List<RagSearchService.Hit> search(User user, String query, Integer topK) {
        // 1. embed(query)  → queryVec（同一向量给缓存 & 检索复用，避免二次 embed）
        // 2. queryCache.lookup(userId, queryVec) 命中直接返回
        // 3. miss → ragSearchService.search(...) 取 candidate-n 候选
        // 4. rerank.enabled ? reranker.rerank(...) : 截断 topK
        // 5. queryCache.put(...) + metrics.record(...)
    }

    /** 异步版本，供并发扇出/预取。 */
    public CompletableFuture<List<RagSearchService.Hit>> searchAsync(User user, String query, Integer topK) {
        return CompletableFuture.supplyAsync(() -> search(user, query, topK), ragExecutor);
    }

    /** 预取：委派 RESEARCH 子代理前，按当前 query 预热缓存（fire-and-forget）。 */
    public void prefetch(User user, String query) {
        searchAsync(user, query, null);
    }
}
```

**线程池**：新增 `RagServingConfig` 提供 `@Bean("ragExecutor")`（有界队列 + CallerRuns 拒绝策略，防止过载）。

**预取接入点**：`SubAgentRunner` 启动 `RESEARCH` 角色前调用 `ragServingService.prefetch(user, query)`，子代理真正检索时大概率命中缓存。

**注**：`RagSearchService.search` 的第三参数上限为 20；门面需把 candidate-n 与最终 topK 解耦——粗排取 candidate-n（≤ `pkm.rag.candidates`=50），精排后再截 topK。为此在 `RagSearchService` 增一个**重载** `search(user, query, topK, candidateN)` 或直接在门面里复用现有 `candidates` 配置读取候选，避免改动核心签名。推荐：门面调用现有 `search(user, query, candidateN)`，其中 `candidateN` 走 `Math.min(candidateN, 20)` 上限——如需 >20 候选，则给 `RagSearchService` 加一个不封顶的内部方法（见风险项）。

---

### R4：可观测（metrics）

**新增文件**：`feature/pkm/serving/RagServingMetrics.java`

- 计数：`queryCacheHit / queryCacheMiss / rerankInvoked / rerankFallback / avgLatencyMs`。
- 暴露：扩展现有 MCP 设置页或新增 `GET /api/pkm/rag/stats`（需登录），返回 `queryCache.stats()` + `EmbeddingVectorCache.stats()` + 上述计数。

---

## 4. 改动清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `feature/pkm/serving/QueryResultCache.java` | 语义查询缓存（R1） |
| 新增 | `feature/pkm/serving/Reranker.java` | LLM-as-Ranker（R2） |
| 新增 | `feature/pkm/serving/RagServingService.java` | serving 门面（R3） |
| 新增 | `feature/pkm/serving/RagServingConfig.java` | `ragExecutor` 线程池（R3） |
| 新增 | `feature/pkm/serving/RagServingMetrics.java` | 指标（R4） |
| 改 | `KnowledgeTools.java` | 注入 `RagServingService` 替换直连 `rag` |
| 改 | `NoteIndexService.java` | 失效点追加 `queryResultCache.invalidate(userId)` |
| 改 | `SubAgentRunner.java` | RESEARCH 前 `prefetch` |
| 改 | `application.properties` | `pkm.rag.serving.*` 配置段 |
| 改（可选） | `RagSearchService.java` | 增内部不封顶候选方法（仅 rerank 需 >20 候选时） |

---

## 5. 验收标准

- 关闭所有开关（enabled=false）时，行为与现状**逐条一致**（回归基线）。
- 开 query-cache：同一 query 二次调用命中缓存，不再触发 embed；笔记更新后缓存失效。
- 开 rerank：LLM 超时/异常时结果与"仅融合序"完全一致（无劣化）。
- 预取：RESEARCH 子代理检索命中率在 metrics 中可见提升。

---

## 6. 风险与取舍

| 风险 | 缓解 |
|---|---|
| 查询缓存返回过期结果 | 笔记写路径同步 invalidate；兜底 `expireAfterAccess` |
| LLM rerank 增加延迟/成本 | 默认关闭；超时回退；仅在 candidate-n 上做 |
| candidate-n > 20 需改核心 | 优先复用现有 `candidates=50` 粗排，仅在确需时加内部方法 |
| 线程池过载 | 有界队列 + CallerRuns，最坏退化为同步 |

---

## 7. 创新点小结

- **语义查询缓存 + 写失效**：个人 KB 高重复率场景下命中率显著优于通用 RAG serving。
- **角色感知预取**：利用"谁在用、什么子代理角色"的私域信息做预热，是通用 serving 做不到的。
- **零新依赖 LLM-as-Ranker**：复用已有 LLM 通道实现二阶段精排，不引入 cross-encoder。
