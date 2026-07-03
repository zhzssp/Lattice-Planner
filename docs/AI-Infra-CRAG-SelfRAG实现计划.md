# CRAG / Self-RAG（纠正式检索）实现计划

> 目标：在现有检索基础上引入"检索质量自评 + 纠错重检索"闭环，让 Agent 在知识不足时**主动改写查询、重检索或明示降级**，而非把低质片段直接喂给 LLM。
>
> 复用点：现有 Agent 已具备 **Reflexion**（工具失败回灌 LLM 自纠）机制（见 `AgentOrchestrator` 注释），CRAG 本质是把同一思想搬到"检索评估"上，实现成本极低。

---

## 1. 理论对齐

| 范式 | 核心思想 | 在本项目的落点 |
|---|---|---|
| **CRAG** (Corrective RAG) | 检索后用一个轻量评估器判断相关性 → {Correct / Ambiguous / Incorrect}，据此决定"直接用 / 精炼 / 重检索或走外部" | 评估器 = 复用 `RagSearchService.Hit.score` 阈值 + 可选 LLM 判定 |
| **Self-RAG** | 让模型自省"是否需要检索""检索到的是否相关""生成是否被证据支撑" | 用反思式 token/标签在 prompt 协议里表达；主 Agent 循环内自评 |

现状里已经有一半的 CRAG 思想：`PromptBuilder` 的系统提示明确写了
> "kb.semantic_search 命中条目最高 score < 0.4 时视为弱相关：最终回答需明示'未找到强相关笔记'"。

本计划把它从"提示词软约束"升级为"运行时硬闭环"。

---

## 2. 目标流程（CRAG 状态机）

```
query
  │
  ▼
kb.semantic_search（现有 hybrid）
  │  取 topHit.score
  ▼
┌───────────────── 评估器 Evaluator ─────────────────┐
│ score ≥ upper(0.6)      → CORRECT   直接使用         │
│ lower(0.4)≤score<upper  → AMBIGUOUS 精炼后使用       │
│ score < lower(0.4)      → INCORRECT 触发纠错         │
└────────────────────────────────────────────────────┘
   CORRECT ──────────────► 用命中片段回答
   AMBIGUOUS ────────────► 保留 + query 改写重检索一次，合并去重
   INCORRECT ────────────► ①query 改写重检索  ②仍失败→明示降级"基于通用知识"
```

- 阈值 `upper/lower` 复用并显式化现有 `0.6 / 0.4` 直觉。
- 纠错动作里的"query 改写"用一次 LLM（`LlmGateway.generateText`）生成 1~3 个改写 query，取并集再检索，RRF 合并。
- **重检索次数上限 1**（防止循环放大延迟），与 `agent.chat.max-steps` 解耦。

---

## 3. 分阶段实现

### C1：检索质量评估器 `RetrievalEvaluator`

**新增文件**：`feature/pkm/crag/RetrievalEvaluator.java`

```java
@Component
public class RetrievalEvaluator {
    public enum Grade { CORRECT, AMBIGUOUS, INCORRECT }

    @Value("${pkm.crag.upper:0.6}") double upper;
    @Value("${pkm.crag.lower:0.4}") double lower;

    /** 轻量：基于最高分快速判级（零 LLM 成本，默认路径）。 */
    public Grade gradeByScore(List<RagSearchService.Hit> hits) {
        double top = hits.isEmpty() ? 0.0 : hits.get(0).score();
        if (top >= upper) return Grade.CORRECT;
        if (top >= lower) return Grade.AMBIGUOUS;
        return Grade.INCORRECT;
    }

    /** 可选增强：AMBIGUOUS 时调用 LLM 二次判定相关性（默认关）。 */
    public Grade gradeByLlm(String query, List<RagSearchService.Hit> hits) { ... }
}
```

**配置**：
```properties
pkm.crag.enabled=true
pkm.crag.upper=0.6
pkm.crag.lower=0.4
pkm.crag.llm-grade=false     # AMBIGUOUS 时是否用 LLM 精判
```

---

### C2：查询改写器 `QueryRewriter`

**新增文件**：`feature/pkm/crag/QueryRewriter.java`

```java
@Component
public class QueryRewriter {
    private final LlmGateway llm;

    /** 生成 1~3 个语义等价/扩展的改写查询；LLM 不可用时返回原 query。 */
    public List<String> rewrite(String query, int n) {
        // prompt: "把下面的检索问题改写成 n 个不同表述（同义/拆解/补充上下文），只输出 JSON 数组"
        // 解析失败 → return List.of(query)
    }
}
```

**要点**：温度 0.4（要多样性），输出 JSON 数组，解析失败回退原 query。

---

### C3：CRAG 编排器 `CorrectiveRetriever`

**新增文件**：`feature/pkm/crag/CorrectiveRetriever.java`

```java
@Service
public class CorrectiveRetriever {
    private final RagServingService serving;   // 复用 Serving 门面（若已实现）；否则直连 RagSearchService
    private final RetrievalEvaluator evaluator;
    private final QueryRewriter rewriter;

    public record CragResult(List<RagSearchService.Hit> hits,
                             RetrievalEvaluator.Grade grade,
                             boolean degraded,      // 是否触发"基于通用知识"降级
                             List<String> usedQueries) {}

    public CragResult retrieve(User user, String query, Integer topK) {
        List<Hit> hits = serving.search(user, query, topK);
        Grade g = evaluator.gradeByScore(hits);
        if (g == CORRECT) return new CragResult(hits, g, false, List.of(query));

        // AMBIGUOUS / INCORRECT → 改写一次并合并
        List<String> rewrites = rewriter.rewrite(query, 2);
        List<Hit> merged = mergeByRrf(hits, rewrites.stream()
                .flatMap(q -> serving.search(user, q, topK).stream()).toList());
        Grade g2 = evaluator.gradeByScore(merged);
        boolean degraded = (g2 == INCORRECT);   // 仍不行 → 让 LLM 走通用知识并明示
        List<String> used = new ArrayList<>(); used.add(query); used.addAll(rewrites);
        return new CragResult(merged, g2, degraded, used);
    }
}
```

去重合并沿用现有 `RagSearchService` 里的 `(source,noteId/path,chunkIdx)` key 思路 + RRF。

---

### C4：接入 Agent（Self-RAG 侧）

有两种接入方式，**推荐 A（工具层，改动最小）**：

**方式 A：升级 `kb.semantic_search` 工具**
- `KnowledgeTools.semanticSearch` 改为调用 `correctiveRetriever.retrieve(...)`。
- 返回结果里附加 `grade` 与 `degraded` 字段，LLM 可据此在回答中明示降级。
- 对 LLM 完全透明，无需改主循环。

**方式 B：Self-RAG 协议标签（可选增强）**
- 在 `PromptBuilder` 的输出协议中引入自省标签，让模型显式判断"是否需要检索/证据是否充分"：
  ```
  【Self-RAG 自省】回答前先判断：
  - [需检索] 涉及"我"的经验/笔记 → 必须先 kb.semantic_search
  - [证据充分?] 若检索 grade=INCORRECT/degraded=true，须在答复首句写明
    "未找到强相关笔记，以下基于通用知识："
  ```
- 与现有系统提示的"知识检索原则"融合，把软约束升级为结构化协议。

---

## 4. 与 Reflexion 的关系

| 机制 | 现状触发条件 | 处理 | 本计划复用 |
|---|---|---|---|
| Reflexion | 工具执行**失败**（异常 JSON 回灌） | LLM 自纠重试 | ✔ 同一"回灌自纠"思想 |
| CRAG | 工具**成功但质量低**（score 低） | 改写重检索 / 降级明示 | 新增，补 Reflexion 盲区 |

即：Reflexion 管"工具坏了"，CRAG 管"工具好了但没查到好东西"。二者互补。

---

## 5. 改动清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `feature/pkm/crag/RetrievalEvaluator.java` | 质量分级（C1） |
| 新增 | `feature/pkm/crag/QueryRewriter.java` | 查询改写（C2） |
| 新增 | `feature/pkm/crag/CorrectiveRetriever.java` | CRAG 编排（C3） |
| 改 | `KnowledgeTools.java` | `semanticSearch` 走 `CorrectiveRetriever`，返回附 `grade/degraded`（C4-A） |
| 改（可选） | `PromptBuilder.java` | 加入 Self-RAG 自省协议（C4-B） |
| 改 | `application.properties` | `pkm.crag.*` 配置段 |

---

## 6. 验收标准

- `pkm.crag.enabled=false` 时，`kb.semantic_search` 行为与现状一致。
- 构造一个明显不相关 query：应触发改写重检索；两次都失败时返回 `degraded=true`，最终答复首句明示"基于通用知识"。
- 改写器/评估器 LLM 调用失败时，整体回退为一次普通检索（不劣化）。
- 重检索次数严格 ≤ 1，端到端新增延迟可控（AMBIGUOUS/INCORRECT 分支才付出改写成本）。

---

## 7. 风险与取舍

| 风险 | 缓解 |
|---|---|
| 改写重检索放大延迟 | 仅低分分支触发；重检索上限 1；改写并行 |
| LLM 判级不稳定 | 默认走零成本 `gradeByScore`，LLM 判级为可选开关 |
| 阈值 0.6/0.4 经验值 | 提为配置项，可按语料调参 |
| 与 Serving 计划耦合 | `CorrectiveRetriever` 依赖门面接口，Serving 未实现时可临时直连 `RagSearchService` |

---

## 8. 创新点小结

- **把提示词软约束升级为运行时硬闭环**：现有系统提示里的 "score<0.4 明示降级" 变成可度量、可纠错的状态机。
- **Reflexion × CRAG 双闭环**：工具失败走 Reflexion、检索低质走 CRAG，覆盖 Agent 两类失败模式。
- **零成本默认路径**：默认仅用已有的融合分数判级，不额外烧 LLM；需要时才开 LLM 判级/改写。
