# Agent Token 计量与会话成本预算（方案 F 设计文档）

> 状态：**设计待审核**，尚未实施
> 关联：为方案 B 提供 token 估算器的校准数据；把已有 Prefix Caching 的收益从「token 数」翻译成「金额」
> 前置依赖：无。已有 `LlmTransport.ChatResponse.usage` 通道，改动面很小

---

## 1. 现状与问题

### 1.1 已有的基础

`LlmTransport` 已经把上游的 `usage` 节点透传上来了：

```59:63:src/main/java/org/zhzssp/memorandum/feature/agent/llm/transport/LlmTransport.java
    record ChatResponse(String content, JsonNode usage) {
        public static ChatResponse of(String content) {
            return new ChatResponse(content, null);
        }
    }
```

`LlmGateway` 也已经在解析它——但**只取了 prompt cache 相关的两个字段**：

```181:194:src/main/java/org/zhzssp/memorandum/feature/agent/service/LlmGateway.java
    private void recordPromptCacheUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        try {
            long hit = usage.path("prompt_cache_hit_tokens").asLong(-1);
            long miss = usage.path("prompt_cache_miss_tokens").asLong(-1);
            if (hit >= 0 || miss >= 0) {
                prefixMetrics.recordPromptCacheTokens(Math.max(0, hit), Math.max(0, miss));
                return;
            }
```

### 1.2 四个问题

| # | 问题 | 后果 |
|---|---|---|
| P1 | **`prompt_tokens` / `completion_tokens` 从未被累计** | 拿不到"这个 Agent 一天烧了多少 token"，更算不出成本 |
| P2 | **`generateText` 路径的 usage 被完全丢弃** | 见 §1.3——这条路径上的成本目前**完全不可见** |
| P3 | **无成本模型** | 已经知道 cache hit/miss 的 token 分布，却没有单价表把它换算成金额。Prefix Caching 的收益只能用 token 数表达，说服力弱 |
| P4 | **无任何预算约束** | 一次跑偏的子代理并行 fan-out（4 worker × 6 步）可以在一分钟内烧掉几十次调用，没有任何刹车 |

### 1.3 P2 是最被低估的问题：看不见的成本

`generateChat`（会经 `LlmRouter`，且有 usage 上报）只是 LLM 调用的一部分。`generateText` 路径同样在花钱，而且**完全没有观测**：

| 调用方 | 用途 | 触发频率 |
|---|---|---|
| `QueryRewriter` | CRAG 查询改写 | 每次检索质量不佳时（`AMBIGUOUS`/`INCORRECT`） |
| `Reranker` | 二阶段精排 | 每次检索（若开启） |
| `SessionArchiveScheduler` | 会话归档为长期记忆 | 每 5 分钟扫描，命中即调用 |
| 晨报/晚报 Scheduler | 主动式报告生成 | 每日定时 |
| `PlannerTools` | 规划草稿生成 | 用户触发 |
| 方案 B 的滚动摘要（若实施） | 上下文压缩 | 超预算时 |

这些是**后台自动触发**的调用，用户感知不到，恰恰最容易失控。F 必须覆盖 `generateText`，否则统计出来的"总成本"是失真的。

> 顺带说明：这也是 F 与 B 的一个耦合点——B 的摘要会新增 LLM 调用，若没有 F，就无法回答"压缩省下的钱是否超过摘要花的钱"这个必然会被追问的问题。

---

## 2. 设计目标与非目标

### 目标

1. 累计全部 LLM 调用的 token 用量，**含 `generateText` 路径**（P1/P2）
2. 建立**三段式成本模型**（cache hit 输入 / cache miss 输入 / 输出），算出金额（P3）
3. **会话级预算**：接近上限时警告，超限时拒绝新一轮并给出明确提示（P4）
4. 副产品：为 B 的 token 估算器提供真实值做校准

### 非目标

| 不做 | 原因 |
|---|---|
| 精确计费/对账 | 单价表是本地配置，汇率、阶梯价、优惠都不覆盖。定位是**量级感知与失控保护**，不是财务系统 |
| 持久化用量（落库） | 与 `ConversationMemory` 一致的进程内语义，重启归零。做持久化要建表 + 定时聚合，属独立课题 |
| 按用户配额与计费 | 单用户本地应用，没有多租户场景 |
| 主动限流/排队 | 超限直接拒绝并提示，比排队更符合本地工具的预期 |

---

## 3. 架构

```
LlmGateway.generateChat / generateText
   │
   ├─ (调用前) costGuard.check(sessionId, modelId)   ← 预算闸门【新增】
   │     └─ 超限 → 抛 BudgetExceededException
   │
   ├─ transport.chat(...)
   │
   └─ (调用后) tokenMeter.record(modelId, purpose, sessionId, usage)  ← 计量【新增】
         ├─ UsageSnapshot 解析（兼容 DeepSeek / OpenAI 两种格式）
         ├─ CostModel 换算金额
         └─ 累计到 全局 / per-model / per-purpose / per-session
```

新增位于 `feature/agent/llm/cost/`：

| 类 | 职责 |
|---|---|
| `UsageParser` | `JsonNode usage` → `UsageSnapshot` record，兼容多种上游格式 |
| `ModelPricing` / `CostModel` | 单价表 + 三段式金额换算 |
| `TokenMeter` | 多维累计器（全局 / model / purpose / session） |
| `CostGuard` | 预算判定与拦截 |
| `CostMetrics` | 快照输出，接入 stats 端点 |

**为什么计量点在 `LlmGateway` 而不是 `LlmTransport`**：

- `LlmGateway` 同时知道 **modelId**（路由结果）与 **purpose**（TEXT/CHAT），而 Transport 只知道请求参数、不知业务归属
- 更重要的是：**Transport 在测试中会被替换成回放实现**（方案 A 的隔离点）。如果把计量放在 Transport，评测时就统计不到——而 §7.2 恰恰要用评测跑批来产出成本数据

---

## 4. 详细设计

### 4.1 UsageSnapshot 与多格式兼容

```java
public record UsageSnapshot(
        long promptTokens,
        long completionTokens,
        long cacheHitTokens,     // 命中上游 prompt cache 的输入 token
        long cacheMissTokens,    // 未命中的输入 token
        boolean cacheInfoPresent // 上游是否提供了 cache 明细
) {
    public long totalTokens() { return promptTokens + completionTokens; }
}
```

解析规则（复用并扩展 `LlmGateway.recordPromptCacheUsage` 的现有逻辑）：

| 上游 | 字段 |
|---|---|
| DeepSeek | `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` |
| OpenAI 系 | `prompt_tokens_details.cached_tokens`，miss = `prompt_tokens − cached` |
| 其它兼容端点 | 仅有 `prompt_tokens` / `completion_tokens` → `cacheInfoPresent=false`，全部按 miss 价计（**保守估算，宁可高报成本**） |

`cacheInfoPresent` 字段必须存在：否则无法区分"真的没命中缓存"和"上游没告诉我们"，前者是事实、后者是数据缺失，混在一起会让 Prefix Caching 的收益评估失真。

### 4.2 三段式成本模型（关键设计）

DeepSeek 对**缓存命中的输入 token 单独定价，且远低于未命中**。这使成本模型必须分三段，而不是简单的"输入价 + 输出价"：

```
cost = cacheHitTokens  / 1e6 × inputCacheHitPerMTok
     + cacheMissTokens / 1e6 × inputCacheMissPerMTok
     + completionTokens/ 1e6 × outputPerMTok
```

**这正是 F 与已有 Prefix Caching 优化的接合点。** 有了三段模型就能算出：

```
若无缓存的假设成本 = (cacheHit + cacheMiss)/1e6 × inputCacheMissPerMTok + 输出成本
实际成本          = 上式三段计算
缓存节省金额      = 二者之差
```

于是 P4/P5 阶段做的"前缀字节稳定化"从"命中了 12 万 token"变成"省了 X 元 / 节省率 Y%"。**这是一个量级不同的表述。**

单价配置挂在已有的 `ModelDef` 上（方案 B 同样要扩展它，应合并为一次改动）：

```java
public static class ModelDef {
    // ...existing: id / displayName / providerId / enabled
    private int contextTokens = 65536;      // 方案 B 需要
    private Price price = new Price();      // 方案 F 需要

    public static class Price {
        private double inputCacheHitPerMTok  = 0.0;
        private double inputCacheMissPerMTok = 0.0;
        private double outputPerMTok         = 0.0;
        private String currency = "CNY";
    }
}
```

```properties
agent.llm.models[0].id=deepseek-chat
agent.llm.models[0].context-tokens=65536
agent.llm.models[0].price.input-cache-hit-per-mtok=0.5
agent.llm.models[0].price.input-cache-miss-per-mtok=2.0
agent.llm.models[0].price.output-per-mtok=8.0
```

> **单价必须由使用者自行填写，代码里不硬编码任何价格。** 厂商调价是常态，硬编码的价格表过期后会给出错误的成本数字，比没有数字更糟。未配置单价时（全为 0）→ 只统计 token、金额显示为 `null` 并标注 `pricingConfigured: false`。

### 4.3 TokenMeter：多维累计

四个维度，全部 `AtomicLong` / `ConcurrentHashMap`：

| 维度 | 用途 |
|---|---|
| 全局 | 总量与总成本 |
| per-model | 对比不同模型的实际单位成本，支撑"该用哪个模型"的决策 |
| per-purpose（TEXT/CHAT） | **暴露 §1.3 的隐性成本占比**——若 TEXT 占了 40%，就说明后台任务是主要开销 |
| per-session | 预算判定的依据 |

per-session 需要防内存泄漏：

- 会话结束（`ConversationMemory.clear(sid)`）时同步清理
- 保底：`SessionArchiveScheduler` 归档空闲会话时一并清
- 再保底：LRU 上限 `agent.cost.max-tracked-sessions=200`

**session 归属的取法与边界**：`AgentContext.sessionId()`。但 `generateText` 会被 Scheduler / 异步索引线程调用，此时 `AgentContext` 未初始化 → 归属为固定 key `"__system__"`。这个降级很重要：不能因为拿不到 session 就丢掉计量，否则又回到 P2。

### 4.4 CostGuard：会话预算

```java
public enum Decision { ALLOW, WARN, DENY }
public Decision check(String sessionId, String modelId);
```

| 状态 | 条件 | 行为 |
|---|---|---|
| `ALLOW` | 已用 < warn 阈值 | 正常 |
| `WARN` | warn 阈值 ≤ 已用 < 上限 | 正常执行，但**在本轮结束时通过 WS 给用户一条提示**（每会话只提示一次，避免刷屏） |
| `DENY` | 已用 ≥ 上限 | 拒绝，给用户明确提示：已用金额 / 上限 / 如何继续 |

```properties
# 单会话预算（元）。0 或负数 = 不限制
agent.cost.session-budget=1.0
# 达到预算多少比例时警告
agent.cost.warn-ratio=0.8
# false = 只观测不拦截（推荐先用 false 跑一段时间摸清真实量级再开启）
agent.cost.enforce=false
```

**`enforce` 默认 `false` 是刻意的**：在不知道真实单轮成本量级的情况下就设硬上限，极可能把正常对话拦掉。先观测、再定阈值、最后开启，是唯一负责的顺序。

#### 拦截点选择（重要）

两个候选位置：

| 位置 | 优点 | 缺点 |
|---|---|---|
| `AgentOrchestrator.handleUserTurn` 入口 | 用户体验好——**整轮不启动**，不会执行一半就断 | 只能拦主对话，拦不住后台任务 |
| `LlmGateway` 每次调用前 | 覆盖全部路径，粒度细 | 可能在 ReAct 第 5 步突然中断，工具已产生副作用（如已建了任务），**留下不一致状态** |

**决策：两处都做，但语义不同。**

- `handleUserTurn` 入口：`DENY` → 直接拒绝整轮，友好提示（这是主要防线）
- `LlmGateway`：只在**远超**上限时（`> 2×budget`）作为熔断，防止单轮内失控爆炸；正常超限不在此拦截

这个区分是必要的：ReAct 中途中断的代价（副作用已发生但没有收尾说明）比多花一点钱更高。

#### 子代理是最大的成本放大器

`subagent.parallel_research` 会 fan-out 4 个 worker，每个最多 6 步 → **单次工具调用可产生 24 次 LLM 调用**。所以：

- `SubAgentRunner.run` 入口也要 `check`，`DENY` 时返回一个"因预算限制未执行"的 `SubAgentResult`（而非抛异常，否则主 Agent 收到的是异常栈而不是可读结论）
- 并行 fan-out 前检查一次，**避免已经超限还起 4 个 worker**

---

## 5. 改动清单

### 新增（5 个文件）

```
feature/agent/llm/cost/UsageParser.java
feature/agent/llm/cost/CostModel.java
feature/agent/llm/cost/TokenMeter.java
feature/agent/llm/cost/CostGuard.java
feature/agent/llm/cost/CostMetrics.java
```

### 改动（6 个文件）

| 文件 | 改动 |
|---|---|
| `LlmGateway` | `generateChat` / `generateText` 调用后 `tokenMeter.record(...)`；`generateText` 现在必须消费 `resp.usage()`（修 P2）；熔断检查 |
| `LlmProperties.ModelDef` | 新增 `price`（与 B 的 `contextTokens` 合并改动） |
| `AgentOrchestrator` | `handleUserTurn` 入口预算闸门 + `WARN` 时的 WS 提示 |
| `SubAgentRunner` | `run` 入口与并行 fan-out 前的预算检查 |
| `ConversationMemory` / `SessionArchiveScheduler` | 会话清理时同步清 per-session 用量 |
| `ObservabilityController` | 新增 `cost` 分区与 `GET /api/agent/cost/stats` |

### 可复用的既有逻辑

`LlmGateway.recordPromptCacheUsage` 的多格式解析逻辑应**整体迁入 `UsageParser`**，`prefixMetrics.recordPromptCacheTokens` 的调用改为从 `UsageSnapshot` 派发。避免两处各自解析 usage——那会重演之前 `jsonType` 两处映射漂移的老问题。

---

## 6. 可观测指标

`GET /api/agent/cost/stats`，并汇入 `/api/observability/stats` 的 `cost` 分区：

```json
{
  "cost": {
    "config": {
      "pricingConfigured": true,
      "currency": "CNY",
      "sessionBudget": 1.0,
      "warnRatio": 0.8,
      "enforce": false
    },
    "totals": {
      "calls": 412,
      "promptTokens": 1840220,
      "completionTokens": 96410,
      "totalTokens": 1936630,
      "cost": 2.4713
    },
    "cacheSavings": {
      "cacheHitTokens": 1210400,
      "cacheMissTokens": 629820,
      "cacheHitRatio": 0.6577,
      "costWithoutCache": 3.3791,
      "costActual": 2.4713,
      "savedAmount": 0.9078,
      "savedRatio": 0.2686
    },
    "byPurpose": {
      "CHAT": {"calls": 268, "totalTokens": 1710300, "cost": 2.1902, "share": 0.886},
      "TEXT": {"calls": 144, "totalTokens": 226330, "cost": 0.2811, "share": 0.114}
    },
    "byModel": {
      "deepseek-chat": {"calls": 380, "cost": 2.2104, "avgCostPerCall": 0.0058}
    },
    "perTurn": {
      "turns": 96,
      "avgCostPerTurn": 0.0257,
      "avgTokensPerTurn": 20173,
      "maxCostInOneTurn": 0.1840
    },
    "budget": {
      "trackedSessions": 12,
      "warnings": 1,
      "denials": 0,
      "circuitBreaks": 0
    },
    "estimatorCalibration": {
      "samples": 210,
      "avgAbsErrorRate": 0.061,
      "underestimateRate": 0.19
    }
  }
}
```

各分区的设计意图：

- **`cacheSavings`** —— 全文档最有价值的一段。它把已有 Prefix Caching 的收益翻译成金额（§4.2）
- **`byPurpose`** —— 直接回答 §1.3：`TEXT.share` 就是"看不见的后台成本"占比
- **`perTurn.avgCostPerTurn`** —— 唯一能用于设定 `session-budget` 的依据。**必须先看这个数字再设预算**，否则纯属拍脑袋
- **`perTurn.maxCostInOneTurn`** —— 尾部风险。若它是均值的 10 倍，说明存在失控轮次（大概率是子代理 fan-out 或工具循环），比均值更值得关注
- **`estimatorCalibration`** —— 给方案 B 的估算器校准（§7.1）。此分区在 B 未实施时为空

---

## 7. 与其它方案的关系

### 7.1 F → B：校准 token 估算器

B 的 `TokenEstimator` 是启发式的。F 拿到真实 `promptTokens` 后：

```
误差率 = |估算值 − 真实 promptTokens| / 真实 promptTokens
```

需要在 `ContextFitter` 里把"本次估算值"暂存，`TokenMeter.record` 时与真实值配对。实现上用一个小的 `ThreadLocal<Integer> lastEstimate`——ReAct 单轮在单线程内串行推进，够用且无锁。

产出 `avgAbsErrorRate` 与 `underestimateRate` 两个数字，后者是安全指标（低估才会超窗）。

### 7.2 F ← A：用评测跑批产出成本数据

F 的数字需要一定调用量才有意义。方案 A 的评测套件正好是**可重复的批量调用**：

```bash
gradlew agentEval -Dagent.eval.mode=record   # 真实调用，产生 usage
gradlew agentEval                            # 回放，零成本
```

注意：**回放模式下无法产出成本数据**（`ReplayLlmTransport` 不联网，也没有真实 usage）。所以成本基线必须用录制模式跑一次采集。

> 这也是为什么计量点必须在 `LlmGateway` 而非 `Transport`（§3）：录制模式下 `RecordingLlmTransport` 装饰真实实现并透传 usage，`LlmGateway` 层的计量照常工作。

### 7.3 F → 多模型路由：让"选哪个模型"有依据

已有 `ModelCatalog` / `LlmRouter` 支持用户切换模型，但**没有任何数据支撑该选哪个**。`byModel.avgCostPerCall` 配合 A 的任务完成率，可以给出"性价比"判断：

```
性价比 ≈ 任务完成率 / 单轮平均成本
```

这是一个远比"我支持多模型切换"更有分量的表述。

---

## 8. 验收标准

| # | 验收项 | 判定标准 |
|---|---|---|
| V1 | 全路径计量 | 跑一次含 CRAG 改写的对话后，`byPurpose` 中 `TEXT` 与 `CHAT` 的 `calls` 均 > 0（证明修掉了 P2） |
| V2 | token 数与上游一致 | 单次调用后，`promptTokens` 与上游响应 `usage.prompt_tokens` 完全相等（不是估算） |
| V3 | 三段成本正确 | 手工用单价表验算一次调用金额，与 `totals.cost` 增量一致 |
| V4 | 缓存节省可信 | `cacheSavings.savedAmount > 0` 且 `costWithoutCache > costActual`；`cacheHitTokens` 与 `prefixCache/stats` 的对应字段一致 |
| V5 | 未配单价时不报假数 | 清空 `price.*` 后 `pricingConfigured=false`、金额为 `null`，token 统计仍正常 |
| V6 | 上游无 cache 明细时保守计价 | mock 一个只返回 `prompt_tokens` 的响应，`cacheInfoPresent=false` 且全额按 miss 价计 |
| V7 | 预算警告 | `session-budget` 设为极小值，触发 `WARN` 后用户收到一次 WS 提示，且**不重复提示** |
| V8 | 预算拒绝在轮次边界 | `enforce=true` 且超限时，新一轮**在入口被拒**并给出可读提示；已进行中的轮次不被中途打断 |
| V9 | 子代理受约束 | 超限时 `subagent.parallel_research` 返回"因预算限制未执行"的可读结论，而非异常栈 |
| V10 | 会话清理无泄漏 | 归档/清理会话后 `budget.trackedSessions` 递减 |
| V11 | 可降级 | `agent.cost.enabled=false` 后完全旁路；`enforce=false` 时只统计不拦截 |
| V12 | 观测不影响主链路 | mock `UsageParser` 抛异常，对话仍正常完成 |

---

## 9. 风险与降级

| 风险 | 等级 | 缓解 |
|---|---|---|
| 单价表过期 → 成本数字错误 | **中高** | 不硬编码任何价格；stats 回显 `pricingConfigured`；文档注明"单价需自行核对厂商当前定价" |
| 预算误拦正常对话 | 中 | `enforce` 默认 `false`；先用 `avgCostPerTurn` 定阈值；拦截只在轮次边界 |
| per-session Map 内存泄漏 | 低 | 三重清理（会话清除 / 归档 / LRU 上限） |
| 计量异常影响主链路 | 低 | 全部 `try-catch` 静默降级（与既有 `recordPromptCacheUsage` 一致的约定） |
| `AgentContext` 缺失导致归属丢失 | 低 | 降级到 `__system__` key，不丢计量 |

---

## 10. 实施阶段

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| **F1** | `UsageParser` + `UsageSnapshot`，迁移 `recordPromptCacheUsage` 逻辑 | ✅ 单测覆盖三种上游格式 |
| **F2** | `TokenMeter` 四维累计 + `generateText` 接入（修 P2） | ✅ V1/V2，此时已能回答"烧了多少 token" |
| **F3** | `ModelDef.price` + `CostModel` 三段换算 + `cacheSavings` | ✅ V3/V4，**产出"缓存省了多少钱"** |
| **F4** | `CostMetrics` + stats 端点 + `byPurpose` / `perTurn` | ✅ 可设定预算阈值 |
| **F5** | `CostGuard` + 双拦截点 + 子代理约束 | ✅ V7~V9 |
| **F6** | 估算器校准联动（依赖 B） | ✅ 为 B 提供 V7 数据 |

**F1~F4 是核心，半天量级，且不改变任何运行时行为（纯观测）。** F5 才引入行为变更，可按需推后——先观测一段时间拿到真实量级，再决定阈值，本身就是更稳妥的顺序。

---

## 11. 面试要点

### 一句话概括

> 把 LLM 调用的 token 与成本变成可观测量，并用三段式成本模型把已有的前缀缓存优化从"命中了多少 token"翻译成"省了多少钱"。

### 三个有区分度的点

**① 三段式成本模型，而不是"输入价 + 输出价"**

DeepSeek 对缓存命中的输入 token 单独定价且远低于未命中。所以成本必须按 `cacheHit / cacheMiss / output` 三段算。这样做的直接好处是能计算反事实成本——"如果没有前缀缓存，这些调用要花多少钱"，二者相减就是缓存的真实收益。**这让之前做的前缀字节稳定化第一次有了金额层面的证据。**

**② 我先统计了"看不见的成本"**

大多数人只统计主对话。但这个项目里 CRAG 查询改写、reranker、会话归档、晨报生成都在调 LLM，而且是**后台自动触发、用户无感**的。所以我按 `purpose`（CHAT/TEXT）分维统计，`TEXT.share` 直接暴露这部分占比——**失控最容易发生在没人看的地方。**

**③ 拦截点的选择比拦截本身更需要想清楚**

预算超限有两个可拦的地方。放在 `LlmGateway` 每次调用前粒度最细，但可能在 ReAct 第 5 步突然中断——此时工具可能已经建了任务，留下"副作用已发生但没有收尾说明"的不一致状态。所以我把主防线放在**轮次入口**（整轮不启动），`LlmGateway` 只做 `2×budget` 的熔断。

**ReAct 中途中断的代价，比多花一点钱更高**——这是个纯粹的工程判断，跟省钱没关系。

### 主动交代的边界

- **这不是计费系统**。单价是本地配置，不覆盖汇率/阶梯价/优惠；定位是量级感知与失控保护
- **单价我不硬编码**。厂商调价是常态，过期的价格表比没有价格更危险 —— 未配置时只报 token、金额显示 `null`
- **成本是进程内累计，重启归零**。要做趋势分析得落库 + 定时聚合，那是独立课题
- **`enforce` 默认关闭**。在不知道真实单轮成本量级前就设硬上限，极可能拦掉正常对话 —— 正确顺序是先观测、看 `avgCostPerTurn`、再定阈值、最后开启
- **回放测试拿不到成本数据**（回放不联网、无真实 usage），成本基线必须用录制模式采集
