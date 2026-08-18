# Agent 上下文工程：Token 预算 + 分级压缩 + 滚动摘要（方案 B 设计文档）

> 状态：**设计待审核**，尚未实施
> 关联：方案 A（评测体系）提供效果度量手段；方案 F（Token 计量）提供估算器校准数据
> 前置依赖：无（可独立实施），但**强烈建议 F 同期或先做**，理由见 §7.2

---

## 1. 现状与问题

### 1.1 当前实现

上下文管理由两处朴素逻辑构成：

```29:29:src/main/java/org/zhzssp/memorandum/feature/agent/runtime/ConversationMemory.java
    private static final int WINDOW = 30;
```

```40:47:src/main/java/org/zhzssp/memorandum/feature/agent/runtime/ConversationMemory.java
    public void append(String sid, String role, String content) {
        Deque<Msg> q = store.computeIfAbsent(sid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Msg(role, content));
            while (q.size() > WINDOW) q.pollFirst();
        }
        lastActiveAt.put(sid, Instant.now());
    }
```

工具结果按字符数硬切（`AgentOrchestrator.appendToolTrace`）：

```java
memory.append(sid, "user", "[tool_result " + tool + "]\n" + truncate(resultJson, 4000));
```

### 1.2 五个具体问题

| # | 问题 | 后果 |
|---|---|---|
| P1 | **条数 ≠ token 数** | 30 条长消息（每条 4000 字符工具结果）可达 8 万 token，**超过 deepseek-chat 的 64K 上下文直接 400 报错**；30 条短消息又浪费了可用窗口 |
| P2 | **硬截断会切断 JSON 字符串** | `substring(0, 4000)` 可能切在 `"content":"...` 中间，模型收到的是**语法非法的 JSON**，只能猜。这是当前最隐蔽的质量损耗 |
| P3 | **丢弃是静默的** | `pollFirst()` 直接丢最老消息，模型完全不知道丢了什么。用户第 1 轮说的"用中文回答、别建任务"这类**全局约束**会在第 16 轮后凭空消失 |
| P4 | **配置项 `agent.chat.history-window` 是死配置** | `application.properties` 里配了 30，但 `ConversationMemory.WINDOW` 是 `static final` 硬编码，**代码从未读取该配置**。与之前修掉的 `pkm.crag.enabled` 是同一类问题 |
| P5 | **所有内容同等重要** | FIFO 不区分"用户原始意图"与"一条已消费完的工具结果"，而后者在被读取后价值几乎为零 |

> **P4 需要在实施 B 时一并修掉**，否则文档写着可配、实际改了没用。

### 1.3 与已有优化的耦合点（必须注意）

项目已有 Prefix Caching（P1~P5）与上游 prompt cache 观测。上下文压缩会与之交互：

```
messages = [ system 前缀 (3~5k token, 工具 schema 占大头) ] + [ history ]
             ↑ 已做字节稳定化，PrefixCache + 上游 context caching 都靠它命中
                                                    ↑ B 只改动这一段
```

**关键约束：压缩只能作用于 history，绝不能触碰 system 前缀。** 前缀一旦变化，`PrefixCache` 与上游 prompt cache 双双失效，代价远大于压缩收益。

次级影响：滚动摘要会**重写 history 的头部**，使上游 prompt cache 在 system 之后的匹配中断。由于 system 前缀占据绝大部分可缓存 token，净损失可接受，但**必须实测**（验收项 V6）。

---

## 2. 设计目标与非目标

### 目标

1. 按 **token 预算**而非条数管理上下文，杜绝超窗报错（P1）
2. 截断保持**结构合法**，不产生非法 JSON（P2）
3. 丢弃改为**有损但显式**：被压缩的内容以摘要/占位形式留痕（P3）
4. 按**信息价值分级**决定压缩顺序，而非 FIFO（P5）
5. 修掉死配置（P4）

### 非目标（明确不做，面试时主动说）

| 不做 | 原因 |
|---|---|
| 引入真实 tokenizer（HuggingFace tokenizers / jtokkit） | 多一个原生依赖 + 每个模型一套词表；启发式估算误差 10% 内，配合 §4.3 的预算留白足够安全 |
| 语义去重（把重复的检索结果合并） | 需要向量比对，成本高于收益；工具结果本身已有 topK 限制 |
| 跨会话上下文共享 | 已有长期记忆机制（`SessionArchiveScheduler`）覆盖该场景 |
| 精确的 per-message token 缓存 | 消息不可变，重复估算成本是纯 CPU 且极低（字符扫描） |

---

## 3. 架构

```
AgentOrchestrator.handleUserTurn
   │
   ├─ prefix = promptBuilder.buildPrefix(mode, memo)      ← 不受影响
   │
   └─ 每步：
        history = memory.history(sid)
        fitted  = contextFitter.fit(sid, prefix, history)  ← 【新增】
        msgs    = promptBuilder.assemble(prefix, fitted)
```

新增四个类，全部位于 `feature/agent/runtime/context/`：

| 类 | 职责 | 是否有状态 |
|---|---|---|
| `TokenEstimator` | 字符 → token 启发式估算 | 无状态 |
| `ContextBudget` | 按当前模型上下文窗口计算各段预算 | 无状态（读配置 + ModelCatalog） |
| `ToolResultCompactor` | 结构感知地压缩单条工具结果 | 无状态 |
| `ContextFitter` | 编排：分级 → 压缩 → 摘要 → 装配 | 有状态（缓存每会话的滚动摘要） |

**为什么不把逻辑放进 `ConversationMemory`**：`ConversationMemory` 是纯存储（还被 `SessionArchiveScheduler` 用于归档判定），存什么就该拿到什么。压缩是**呈现给 LLM 时的视图变换**，不该污染存储层——归档需要的是完整原文，不是压缩后的版本。

---

## 4. 详细设计

### 4.1 TokenEstimator

```java
public int estimate(String text);
public int estimate(List<ConversationMemory.Msg> msgs);   // 含 per-message overhead
```

算法：按字符分类累加权重。

| 字符类别 | 权重（token/字符） | 依据 |
|---|---|---|
| CJK（含中日韩、全角标点） | 0.667（≈ 1/1.5） | 中文 BPE 常见 1 字 ≈ 0.6~0.7 token |
| ASCII 字母数字 | 0.25（≈ 1/4） | 英文 1 token ≈ 4 字符 |
| 其它（空白/符号/emoji） | 0.5 | 保守取中间值 |

额外规则：
- 每条消息加 **4 token** 固定开销（role 字段 + 分隔符，OpenAI 系通用经验值）
- **向上取整**，且整体乘以 `1.05` 安全系数 —— 宁可高估。低估会导致超窗 400 报错（硬失败），高估只是少用一点窗口（软损失）。这个不对称性决定了估算必须偏保守。

**校准**：方案 F 会拿到上游返回的真实 `prompt_tokens`，可算出估算误差并暴露为指标 `estimatorErrorRate`。这是把"我用了启发式估算"从心里没底变成有数据支撑的关键（见 §7.2）。

### 4.2 ModelDef 扩展：上下文窗口

`ContextBudget` 需要知道当前模型的窗口大小。当前 `LlmProperties.ModelDef` 只有 `id/displayName/providerId/enabled`，需新增：

```java
public static class ModelDef {
    // ...existing
    private int contextTokens = 65536;   // 默认按 deepseek-chat 64K
}
```

```properties
agent.llm.models[0].id=deepseek-chat
agent.llm.models[0].context-tokens=65536
agent.llm.models[1].id=deepseek-reasoner
agent.llm.models[1].context-tokens=65536
```

> 方案 F 同样要扩展 `ModelDef`（加价格），两者应合并为一次改动。

窗口取值路径：`LlmRouter.resolveForCurrentUser()` → modelId → `ModelCatalog.find(id)` → `contextTokens`。取不到时回落 `agent.context.default-context-tokens=32768`（保守值）。

### 4.3 ContextBudget：预算分配

```
总窗口 W（如 65536）
  ├─ 输出预留 R = agent.context.reserve-output-tokens（默认 2048）
  ├─ 安全余量 = W × (1 - ratio)，ratio = agent.context.budget.ratio（默认 0.7）
  └─ 输入可用 A = W × ratio − R

A 再分配：
  ├─ system 前缀 S   = 实测值（不可压缩，直接扣除）
  ├─ 剩余 H = A − S  ← 全部给 history
       ├─ 保护区（不可压缩）：最近 keep-recent-turns(3) 轮 + 首条用户消息
       ├─ 滚动摘要：上限 summary.max-tokens（400）
       └─ 其余可压缩区
```

`ratio=0.7` 的取值理由（面试会被问）：

- 不用 1.0：估算有 ±10% 误差，且上游对"prompt + completion 总和"限流，留白防硬失败
- 不用 0.5：太浪费，多轮工具调用场景很快就会触发压缩，增加不必要的 LLM 摘要成本
- **0.7 是可配的**，且 `estimatorErrorRate` 指标可以指导调整——误差稳定在 3% 以内时可上调到 0.85

**极端情况**：若 `S > A`（工具 schema 本身就超预算，例如 MCP 接了大量远程工具），说明配置有问题，此时**不压缩 history 也无济于事**。处理：记录 `ERROR` 日志 + 指标 `prefixOverBudget` + 退化为只保留最近 1 轮，让请求至少能发出去。

### 4.4 分级压缩策略（核心设计）

四级优先级，从"最先压"到"永不压"：

| 级别 | 内容 | 处理 | 理由 |
|---|---|---|---|
| L1 最先压 | 已消费的工具结果（非最近 1 轮） | `ToolResultCompactor` 结构化压缩 | 工具结果一旦被模型读过并据此做了下一步决策，其原文价值急剧下降。这里是**最大的 token 池**且**最低的信息损失** |
| L2 次之 | 早期对话轮次 | 滚动摘要（一次 LLM 调用） | 早期对话有上下文价值但可压缩表达 |
| L3 保护 | 最近 `keep-recent-turns`(3) 轮全部内容 | 原样保留 | 模型当前的推理依赖它们，压缩会直接破坏 ReAct 链 |
| L4 永不压 | **首条用户消息** | 原样保留 | 承载原始意图与全局约束（"用中文"、"不要建任务"）。P3 描述的失效场景就出在这里 |

> **L4 单独成级是这套设计相对朴素 FIFO 的最大差异。** FIFO 恰好会最先丢掉最重要的东西——因为它最老。

执行顺序（贪心，逐级尝试直到满足预算）：

```
1. 全量估算 → 若 ≤ H，直接返回原 history（零成本快路径，绝大多数轮次走这里）
2. 压 L1：对可压缩区的工具结果逐条 compact，从最老开始 → 重估
3. 仍超 → 压 L2：把 L1 压完仍超预算的最老 N 条（L3/L4 之外）交给摘要器
4. 仍超 → 兜底：从最老开始丢弃（L4 与最近 1 轮除外），并插入
   "[已省略 k 条早期消息]" 占位，保证丢弃对模型可见
```

### 4.5 ToolResultCompactor：结构感知压缩（解决 P2）

不做字符截断，先 `readTree` 再按形状压缩：

| 结果形状 | 压缩策略 |
|---|---|
| **数组**（`kb.semantic_search` 等） | 保留首元素（CRAG `_meta` 行必须留，它承载 grade/degraded 决策信号）+ 前 k 个命中，末尾追加 `{"_omitted": n, "note":"另有 n 条命中已省略"}` |
| **对象含长文本字段**（`read_document` 的 `content`） | 只截断该字段，保留全部元字段（`path`/`isSummarized`/`chars`），并在字段值尾部加 `...[已截断，原文 N 字符]` |
| **对象含数组字段** | 递归对该数组用数组策略 |
| **错误对象**（含 `error` 键） | **原样保留，永不压缩**——错误信息与 D 注入的策略提示是模型自修复的唯一依据，压缩它等于自毁 Reflexion |
| 解析失败（非 JSON） | 退回字符截断，但**在 UTF-16 码元边界安全切分**且标注 `...[truncated]` |

产出**始终是合法 JSON**。这是相对现状的实质改进。

单条工具结果预算：`agent.context.tool-result.max-tokens`（默认 800）。注意与现状 4000 字符（≈ 1300~2600 token）相比是**收紧**的——因为现在有分级机制，最近一轮的工具结果不受此限（L3 保护）。

### 4.6 滚动摘要

```java
record Summary(String text, int coveredUpTo, int estimatedTokens) {}
```

`coveredUpTo` 是被摘要覆盖的消息序号上界。

**必须解决的三个陷阱**：

1. **摘要不能每步重算。** 一轮 ReAct 可能有 8 步，每步都调一次摘要 LLM 既昂贵又会让 history 头部每步都变（彻底毁掉 prompt cache，且引入非确定性）。
   → 摘要**按会话缓存**，仅当 `coveredUpTo` 需要前移（即又有新消息落入可压缩区）时才重算。实测下一轮内通常只算 0~1 次。

2. **摘要失败必须可降级。** 摘要要调一次 `llm.generateText()`，可能超时/报错。
   → 失败时退回 §4.4 第 4 步的"丢弃 + 占位"，并计 `summaryFailures`。绝不能因为摘要失败而让整轮对话失败。

3. **摘要自身会被再次摘要（摘要漂移）。** 长会话中摘要会被反复重写，信息逐代衰减。
   → 摘要 prompt 显式要求**保留用户约束与已确定的实体 id**；并限制**最多 3 代**，超过则冻结最早那段摘要不再重写。

摘要 prompt 要点（不是"总结一下"）：

```
把以下 Agent 对话片段压缩为不超过 200 字的中文要点，必须保留：
1) 用户提出的所有约束与偏好（语言、格式、禁止事项）
2) 已创建/修改的对象及其真实 id
3) 已确认失败或被禁止的路径（避免后续重复尝试）
可以丢弃：工具结果原文、中间推理过程、寒暄
输出纯文本，不要 JSON，不要 Markdown 标题。
```

摘要以 `role=user`、内容前缀 `[早期对话摘要]` 插入 history 头部（紧跟 system 之后）。用 `user` 而非 `system` 是因为 system 只能有一条且要保持字节稳定（§1.3）。

### 4.7 与 ConversationMemory 的关系（修 P4）

```java
@Value("${agent.chat.history-window:30}")
private int window;                    // 改：不再 static final
```

窗口语义随之变化：**从"上下文控制手段"降级为"内存防泄漏上限"**。真正的上下文控制交给 `ContextFitter`。因此默认值应上调（如 60），否则 token 预算还没用满、消息就已经被存储层丢了——这会让 B 的分级策略失去作用空间。

> 这是个容易踩的坑：只做压缩不改 window，会出现"预算允许 40 条但存储只留 30 条"，压缩逻辑永远触发不到。

---

## 5. 改动清单

### 新增（5 个文件）

```
feature/agent/runtime/context/TokenEstimator.java
feature/agent/runtime/context/ContextBudget.java
feature/agent/runtime/context/ToolResultCompactor.java
feature/agent/runtime/context/ContextFitter.java
feature/agent/runtime/context/ContextMetrics.java
```

### 改动（5 个文件）

| 文件 | 改动 |
|---|---|
| `AgentOrchestrator` | 循环内 `memory.history(sid)` → `contextFitter.fit(sid, prefix, history)` |
| `SubAgentRunner` | 局部 `msgs` 同样过 fitter（子代理读长文档更容易爆窗） |
| `ConversationMemory` | `WINDOW` 改为读配置（修 P4） |
| `LlmProperties.ModelDef` | 新增 `contextTokens`（与 F 的价格字段合并改动） |
| `ObservabilityController` | `trace/stats` 增加 `context` 分区 |

### 配置项

```properties
# 上下文预算总开关；false 时行为与改造前一致（仅按条数窗口 + 字符硬截断）
agent.context.budget.enabled=true
# 可用窗口占比。留白应对估算误差与上游总量限流
agent.context.budget.ratio=0.7
# 为模型输出预留的 token
agent.context.reserve-output-tokens=2048
# 取不到模型窗口时的保守回落值
agent.context.default-context-tokens=32768
# 最近 N 轮不参与任何压缩（保护 ReAct 推理链）
agent.context.keep-recent-turns=3
# 单条工具结果压缩后上限（最近一轮不受限）
agent.context.tool-result.max-tokens=800
# 滚动摘要开关与上限；关闭时超预算直接走"丢弃 + 占位"
agent.context.summary.enabled=true
agent.context.summary.max-tokens=400
agent.context.summary.max-generations=3
# 存储层窗口（内存防泄漏上限，非上下文控制手段）——需上调，见 §4.7
agent.chat.history-window=60
```

---

## 6. 可观测指标

进 `GET /api/agent/trace/stats` 的 `context` 分区：

```json
{
  "context": {
    "config": {"enabled": true, "ratio": 0.7, "keepRecentTurns": 3},
    "budget": {
      "avgWindowTokens": 65536,
      "avgPrefixTokens": 4210,
      "avgHistoryTokensBefore": 9840,
      "avgHistoryTokensAfter": 6120,
      "avgSavedTokens": 3720
    },
    "compaction": {
      "fitCalls": 320,
      "fastPathRate": 0.78,
      "toolResultsCompacted": 96,
      "messagesDropped": 4,
      "prefixOverBudget": 0
    },
    "summary": {
      "generated": 11,
      "reusedFromCache": 47,
      "failures": 1,
      "avgTokens": 268,
      "maxGenerationReached": 0
    },
    "estimator": {
      "samples": 210,
      "avgAbsErrorRate": 0.061,
      "underestimateRate": 0.19
    }
  }
}
```

几个指标的设计意图：

- **`fastPathRate`** —— 未触发任何压缩的比例。若长期接近 1，说明预算给太松、B 基本没生效；若接近 0，说明 window 或 tool-result 上限配置不合理，每轮都在压。健康值约 0.6~0.85
- **`underestimateRate`** —— 估算值低于真实值的比例。这是**唯一的安全指标**：低估才会超窗。若 > 0.3 必须调高安全系数
- **`prefixOverBudget`** —— 前缀本身超预算的次数，非 0 说明工具集过大（如 MCP 接了太多远程工具），需要收紧 tag 过滤
- **`summary.reusedFromCache`** —— 证明 §4.6 陷阱 1 的缓存真的生效了；若为 0 说明每步都在重算摘要

---

## 7. 与其它方案的关系

### 7.1 与已有 Prefix Caching 的相互影响

| 影响 | 方向 | 处理 |
|---|---|---|
| system 前缀不被压缩 | 无影响 | 设计约束（§1.3） |
| 滚动摘要重写 history 头部 | **负向**：上游 prompt cache 在 system 之后中断 | 摘要按会话缓存，一轮内至多重算 1 次；净收益需实测（V6） |
| 压缩使总 token 下降 | **正向**：单次调用成本与延迟下降 | 由 F 量化为金额 |

### 7.2 为什么建议 F 同期或先做

B 的核心弱点是**"我用启发式估算 token"这句话没有数据支撑**。F 会累计上游返回的真实 `prompt_tokens`，二者相减即得估算误差（`estimator.avgAbsErrorRate`）。

没有 F：只能说"误差大概 10% 以内"。
有了 F：可以说"实测平均绝对误差 6.1%，低估率 19%，所以我把安全系数设为 1.05 且 ratio 留到 0.7"。

**后者是完全不同量级的回答。** 且两者都要改 `ModelDef`（B 加窗口、F 加价格），合并一次改完更省事。

### 7.3 与方案 A 的关系

B 是**必须靠 A 验证的典型改动**——压缩会改变模型看到的内容，从而可能改变决策路径。没有回归集，"压缩后效果没变差"就只是一句主观判断。

但要注意 A 的回放机制限制：录制盒按 `(caseId, callIndex)` 回放**固定响应**，模型不会因为上下文变化而改变输出。所以：

- ✅ A 能验证的：压缩后 messages 仍合法、token 数确实下降、CRAG 信号未被压掉、错误对象未被压缩
- ❌ A 不能验证的：压缩后模型的**决策质量**是否下降

后者只能靠真实调用做 A/B，或人工评估固定题目集。**这个限制要在面试中主动说明。**

---

## 8. 验收标准

| # | 验收项 | 判定标准 |
|---|---|---|
| V1 | 超窗不再发生 | 构造一个含 20 条大工具结果的会话，正常完成，无 HTTP 400 `context length exceeded` |
| V2 | 截断结果合法 | 所有压缩后的 `[tool_result ...]` 内容可被 `readTree` 成功解析 |
| V3 | CRAG 信号不丢 | 压缩含 20 条命中的 `kb.semantic_search` 结果后，`_meta` 行仍在首位且 `grade`/`degraded` 完整 |
| V4 | 错误信息不被压缩 | 含 `error` 键的工具结果与 D 的 `[策略提示]` 段在任何预算下都完整保留 |
| V5 | 首条用户意图不丢 | 第 1 轮说"全程用中文、不要创建任务"，跑到第 20 轮仍生效 |
| V6 | 前缀缓存未被拖垮 | 开启摘要前后对比 `prefixCache.hitRate` 与 `promptCacheHitTokens`，命中率下降 < 5% |
| V7 | 估算误差可接受 | `estimator.avgAbsErrorRate < 0.15` 且 `underestimateRate < 0.30` |
| V8 | 可降级 | `agent.context.budget.enabled=false` 后行为与改造前完全一致；`summary.enabled=false` 时退化为丢弃 + 占位，不报错 |
| V9 | 摘要失败不影响主链路 | mock 摘要调用抛异常，对话仍正常完成，`summary.failures` 递增 |
| V10 | 死配置已修 | 改 `agent.chat.history-window=10` 后重启，`memory.history()` 实际只返回 10 条 |

---

## 9. 风险与降级

| 风险 | 等级 | 缓解 |
|---|---|---|
| 摘要引入额外 LLM 调用成本与延迟 | 中 | 仅超预算时触发 + 按会话缓存；F 可量化摘要占总成本比例 |
| 压缩导致模型决策变差 | **中高** | 分级策略保护关键信息；靠 A 回归 + 固定题目集人工评估；开关可一键回退 |
| 估算低估导致超窗 | 中 | 1.05 安全系数 + ratio 留白 + `underestimateRate` 监控 |
| 摘要代际衰减 | 低 | 限制 3 代 + prompt 强制保留约束与 id |
| `ContextFitter` 的会话状态泄漏 | 低 | 复用 `ConversationMemory.clear(sid)` 的时机同步清理；`SessionArchiveScheduler` 归档后一并清 |

---

## 10. 实施阶段

| 阶段 | 内容 | 可独立验证 |
|---|---|---|
| **B1** | `TokenEstimator` + 单元测试（中英混排、emoji、空串、超长） | ✅ 纯单测 |
| **B2** | `ModelDef.contextTokens` + `ContextBudget` + 死配置 P4 修复 | ✅ 单测 + V10 |
| **B3** | `ToolResultCompactor` + 单元测试（五种形状 × 合法性断言） | ✅ 纯单测，覆盖 V2/V3/V4 |
| **B4** | `ContextFitter` 分级编排（**不含摘要**，超预算走丢弃 + 占位） | ✅ 单测 + V1/V5 |
| **B5** | 滚动摘要 + 缓存 + 代际限制 | ✅ V9 |
| **B6** | `ContextMetrics` + stats 分区 + 估算器校准（依赖 F） | ✅ V6/V7 |

**B1~B4 是核心价值所在，且全部可用纯单元测试覆盖。** B5 成本最高、收益边际（超预算本身应是少数情况），若时间紧可只做到 B4——此时"分级压缩 + 结构感知截断"的故事已经完整，摘要作为"下一步计划"说明即可。

---

## 11. 面试要点

### 一句话概括

> 把上下文管理从"按条数 FIFO 截断"升级为"按 token 预算 + 按信息价值分级压缩"，并让截断保持结构合法。

### 三个有区分度的点

**① FIFO 恰好会最先丢掉最重要的东西**

用户的原始意图和全局约束在第 1 条，而 FIFO 从第 1 条开始丢。所以我把"首条用户消息"单独设为永不压缩级——这不是优化，是修 bug。

**② 结构感知压缩 vs 字符截断**

`substring(0, 4000)` 可能切在 JSON 字符串中间，模型收到语法非法的 JSON 只能猜。我改成先 parse 再按形状压：数组保留首元素（CRAG 的 `_meta` 决策信号必须留）+ 前 k 条 + 省略计数；长文本对象只截文本字段、保留全部元字段。**产出始终是合法 JSON。**

**③ 错误信息永不压缩**

这条来自方案 D 的教训：错误对象和注入的策略提示是模型自修复的唯一依据，压缩它等于自毁 Reflexion 链路。这体现的是"压缩要看信息在系统里的用途，不能只看长度"。

### 主动交代的边界

- **估算不是精确计数**。我用字符分类启发式（CJK 0.667、ASCII 0.25），没引 tokenizer。但我用真实 `prompt_tokens` 反向校准出了误差率（`avgAbsErrorRate`），并据此设定安全系数——**不是拍脑袋**
- **低估比高估危险得多**，所以整体乘 1.05 且 ratio 只给 0.7。这个不对称性是设计里唯一"故意浪费"的地方
- **压缩对决策质量的影响无法用回放测试证明**（回放会固定模型响应），只能靠固定题目集人工评估
- 滚动摘要会**削弱**上游 prompt cache（重写了 history 头部），这是与已有 Prefix Caching 优化的直接冲突，我用"按会话缓存摘要"把重算频率压到一轮至多 1 次，但净收益仍需实测
