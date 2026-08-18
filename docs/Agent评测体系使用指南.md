# Agent 评测体系使用指南

> 位置：`src/test/java/org/zhzssp/memorandum/agenteval/`
> 状态：**框架已完成并验证通过**，待补录制盒
> 对应方案：`docs/Agent优化方案候选.md` 方案 A

---

## 1. 它解决什么问题

改一行 system prompt、调一次工具描述、动一处 CRAG 阈值 —— **怎么知道 Agent 没变差？**

此前无从得知。我们已经因此吃过两次亏：

| 真实事故 | 为何没被发现 |
|---|---|
| CRAG 的 `grade`/`degraded` 只在零命中时返回，Self-RAG 链路实际断开 | 三个组件单独都对，但**集成层的信息传递断了** |
| `RagServingMetrics` 有 4 个计数器从无调用点，恒为 0 | 没有消费方，恒为 0 也不会报错 |

这套体系就是为了让这类问题**在提交前被自动捕获**。

---

## 2. 核心难题与解法

Agent 测试的根本矛盾：**LLM 是非确定的，同样输入可能走出不同的工具调用路径，无法写稳定断言。**

### 解法：把可替换点设在 HTTP 边界

```
LlmGateway  ← 业务逻辑：模型路由、usage 统计、参数默认值（测试中真实执行）
    ↓
LlmTransport  ← 抽象接口（唯一被替换的地方）
    ├── HttpLlmTransport       生产：真实 HTTP
    ├── RecordingLlmTransport  录制：透传 + 落盘
    └── ReplayLlmTransport     回放：离线，返回预录响应
```

**为什么不直接 mock 掉 `LlmGateway`？**

因为那样会把路由、usage 解析、前缀缓存一起 mock 掉，测出来的就不是真实链路了。设在 HTTP 边界后，测试中**真实执行**的部分包括：

- ReAct 主循环与步数控制
- 工具注册表的反射调用与参数转换
- `ToolCallParser` 的 JSON 解析与 think 段剥离
- CRAG 状态机与 `_meta` 元信息组装
- 前缀缓存的 key 计算与命中
- 工具授权策略
- 多用户上下文隔离

**唯一被固定的是"模型说了什么"。** 于是轨迹变得确定、可断言。

附带收益：`LlmGateway` 的 9 个注入点**零改动** —— 这正是隔离层级选对了的证明。

### 录制盒的寻址策略

回放时怎么把"当前这次 LLM 调用"对应到"录制里的哪条记录"？两种做法各有致命缺陷：

| 做法 | 优点 | 缺陷 |
|---|---|---|
| 内容寻址（对 messages 做 hash） | 精确 | **极脆弱**：prompt 改一个字，全部录制失效。而改 prompt 是 Agent 开发最频繁的操作 |
| 顺序寻址（按第几次调用） | 稳定 | 无法感知"prompt 已经变了" |

**本实现取两者之长**：用 `(caseId, callIndex)` 主键回放，同时记录 `fingerprint`。指纹不匹配时**不报错、但发出漂移警告**，报告里标注"录制可能过期"。

效果：改 prompt 后测试仍能跑（不阻塞开发），但你会明确知道"有 3 个用例的录制已漂移，建议重录"。

指纹计算还做了**易变片段规范化**（日期/时间/UUID 替换为占位符）—— 否则 system prompt 里的「今天是 2026-08-18」会让每过一天所有指纹都漂移，警告全是噪声。

---

## 3. 测试边界（必须明确）

**评测的是 Agent 的决策质量**：给定用户意图，是否选对工具、顺序是否合理、错误能否自纠、质量信号是否正确传递、是否编造工具。

**不评测**：数据库 CRUD 正确性、向量检索召回率、MySQL 原生 SQL。

因此这些组件一律 mock：

| Mock 对象 | 原因 |
|---|---|
| `RagSearchService` | 含 MySQL ngram FULLTEXT 原生 SQL，H2 无法执行 |
| `EmbeddingClient` | 会调用外部 embedding 服务 |
| `AgentChatWebSocketHandler` | 评测中发帧无意义 |
| `UserRepository` | 提供固定测试用户 |

数据库用 H2 内存库，**仅用于装配 Spring 上下文**，不承载业务断言。

这个边界划分是有意的：它让评测能在**无 MySQL、无网络、无 API Key** 的 CI 环境常态运行。

---

## 4. 三层测试结构

| 层 | 位置 | 依赖 | 速度 | 覆盖 |
|---|---|---|---|---|
| **L1 单元** | `agenteval/unit/` | 无 | 毫秒 | `ToolCallParser` 解析鲁棒性等纯逻辑 |
| **L2 轨迹** | `agenteval/cases/` | Spring + H2 + 录制盒 | 秒级 | Agent 决策质量（**核心**） |
| L3 集成 | 标 `@Tag("integration")` | 真实 MySQL + API Key | 分钟 | 默认跳过，`-Pintegration` 开启 |

---

## 5. 使用方式

### 日常回归（离线、零成本）

```bash
./gradlew agentEval
```

输出示例：

```
══════════════════════════════════════════════════════════════
  Agent 评测报告   mode=replay   用例数=9
══════════════════════════════════════════════════════════════
  收敛率        88.9%    (收敛 8 / 步数耗尽 1 / LLM失败 0)
  工具幻觉率    0.0%     (0 次编造 / 0 个用例受影响)
  含工具失败    1 个用例
  步数 P50/P95  2 / 5   最大 6
  LLM 调用      共 21 次，均 2.33 次/用例
──────────────────────────────────────────────────────────────
  逐用例明细
   PASS    create_task_basic       steps=1 llm=2 tools=task.create
   PASS    kb_search_degraded      steps=1 llm=2 tools=kb.semantic_search
   ...
══════════════════════════════════════════════════════════════
```

同时写入 `build/agent-eval/report.json` 供 CI 归档与趋势对比。

### 录制（需 API Key，产生真实调用与费用）

```bash
set DEEPSEEK_API_KEY=sk-xxx
./gradlew agentEval -Dagent.eval.mode=record
```

录制盒写入 `src/test/resources/agent-eval/cassettes/<caseId>.json`，**随代码提交** —— 这是让 CI 无需 API Key 的前提。

### 只跑 L1

```bash
./gradlew test --tests "*ToolCallParserTest"
```

---

## 6. 当前状态

**已验证通过**：

- L1 单元测试 **9/9 PASSED**
- L2 框架端到端跑通：Spring 上下文在无 MySQL/无网络下成功装配
- 2 个手工 fixture 用例 **PASSED**（`no_tool_hallucination`、`mode_isolation_learn`）
- 报告生成正常

**待你完成**：其余 7 个用例需录制。

```bash
# 一次性录制全部（约 20 次 LLM 调用，成本极低）
set DEEPSEEK_API_KEY=sk-xxx
gradlew agentEval -Dagent.eval.mode=record
```

录制后逐个检查 `cassettes/*.json` 的 `responseContent`，确认模型行为合理（比如 `kb_search_degraded` 里它是否真的说了"未找到强相关笔记"）。**若模型行为本身不对，那是 prompt 需要改，不是测试需要改** —— 这正是评测体系的价值。

> 注意：两个 fixture 是**手工编写**的（`fingerprint: null` 跳过漂移检测），用于在无 API Key 时验证框架。建议也一并重新录制为真实数据。

---

## 7. 用例设计原则

只断言**决策层面的不变量**，不断言模型的具体措辞：

| 好的断言 | 为什么 |
|---|---|
| `calledTool("task.create")` | 稳定，反映意图理解正确 |
| `noHallucination()` | 稳定，反映工具 schema 清晰 |
| `cragMetaReachedLlm()` | 稳定，防集成层信息断裂 |
| `stepsAtMost(4)` | 稳定，防工具循环 |

| 坏的断言 | 为什么 |
|---|---|
| `finalAnswerEquals("已为你创建任务")` | 脆弱，模型换个说法就误报 |
| 精确匹配完整工具序列 | 脆弱，多调一次检索确认是合理行为 |

因此 `calledToolsInOrder()` 刻意做成**相对顺序**匹配（允许中间夹杂其它工具），而非严格相等。

---

## 8. 当前用例清单

| 用例 | 验证什么 |
|---|---|
| `create_task_basic` | 意图 → 工具选择正确 |
| `query_tasks` | 查询类意图不过度调用工具 |
| `kb_search_hit` | 命中质量好时正常引用，且 `_meta` 存在 |
| **`kb_search_degraded`** | **质量差时必须明示"基于通用知识"，不编造** |
| `tool_error_recovery` | Reflexion：消化错误并合理答复，不泄漏异常栈 |
| `no_tool_hallucination` | 能力边界外不编造工具名 |
| `mode_isolation_learn` | learn 模式下 `task.create` 不可见 |
| `no_internal_leak` | 不泄漏 `<think>` 段与 tool-call JSON |
| `prefix_stability_within_turn` | 同轮内前缀 hash 唯一（上游缓存命中前提） |

`kb_search_degraded` 是最重要的一个 —— 它守护的是"Agent 不会拿着低相关片段编出看似有据的错误答案"这条底线。

---

## 9. 附带产出：ReAct 层可观测

搭建过程中发现 ReAct 循环此前是**完全黑盒** —— 不知道平均几步收敛、哪些工具常失败、多久编一次工具名。

因此顺带补了 `AgentTraceListener` 机制与 `AgentTraceMetrics`，新增端点：

```
GET /api/agent/trace/stats
{
  "turns": 12, "llmCalls": 31, "avgLlmCallsPerTurn": 2.58,
  "outcome": { "convergenceRate": 0.9167, "avgStepsToConverge": 1.82 },
  "tools": { "calls": 19, "errorRate": 0.0526, "hallucinationRate": 0.0 },
  "stepHistogram": { "1": 7, "2": 3, "4": 1 },
  "byTool": { "task.create": { "invocations": 8, "errorRate": 0.0, "avgMs": 14.2 } }
}
```

生产与测试**共用同一套埋点** —— 测试用它断言，生产用它统计。

---

## 10. 面试话术更新

原手册 Part 6 有一问是"改了 prompt 怎么知道没变差"，现在可以这样答：

> 我搭了一套轨迹回放评测体系。核心难点是 LLM 非确定性导致无法写稳定断言，我的解法是把可替换点设在 **HTTP 边界**而不是 mock 掉整个 LLM 网关 —— 这样模型路由、usage 解析、ReAct 循环、工具反射、CRAG 分支全部真实执行，唯一被固定的是"模型说了什么"。
>
> 录制盒的寻址我用了混合策略：主键按调用序号保证回放稳定，同时记录请求指纹做漂移检测。改了 prompt 测试仍能跑，但报告会告诉我"有几个用例的录制已过期"。指纹计算还做了日期规范化，否则过一天全是伪漂移警告。
>
> 指标上跑一遍能拿到收敛率、工具幻觉率、步数 P50/P95、平均 LLM 调用数。CI 里是完全离线的，不需要 API Key。

**Part 7 边界表要新增一行**（诚实划界）：

| 我做的 | 我没做的 |
|---|---|
| 轨迹回放 + 决策层断言 + 指标报告 | **LLM-as-Judge 的答案质量自动评分**、多轮对话的长程一致性评测、对抗性测试集 |

被问到时可以说：

> 我评的是"决策路径对不对"，还没做"答案写得好不好"。后者需要 LLM-as-Judge 或人工标注评分集，属于下一步。
