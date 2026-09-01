# Agent 评测体系使用指南

> 位置：`src/test/java/org/zhzssp/memorandum/agenteval/`
> 状态：**框架完成，9 个轨迹用例全部就位**；`./gradlew agentEval` 离线全绿
> 对应方案：`docs/Agent优化方案候选.md` 方案 A
>
> 最近一次校对：2026-09-01（修复两个让评测「假绿」的台账缺陷，见 §6）

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

数据库用 H2 内存库装配 Spring 上下文。**注意它不是纯摆设**：`task.create` 这类写工具会真的往 H2 里插数据，所以评测用户必须真实落库（`AgentEvalBase.ensureUserRow()`），否则外键约束会让写工具全部静默失败 —— 详见 §6.2。

这个边界划分是有意的：它让评测能在**无 MySQL、无网络、无 API Key** 的 CI 环境常态运行。

---

## 4. 三层测试结构

| 层 | 位置 | 依赖 | 速度 | 覆盖 |
|---|---|---|---|---|
| **L1 单元** | `agenteval/unit/` | 无 | 毫秒 | `ToolCallParser` 解析鲁棒性等纯逻辑 |
| **L2 轨迹** | `agenteval/cases/` | Spring + H2 + 录制盒 | 秒级 | Agent 决策质量（**核心**） |
| **B 基准** | `agenteval/bench/` | 无 | 毫秒 | 上下文工程的开关前后对比（见 §11） |
| L3 集成 | 标 `@Tag("integration")` | 真实 MySQL + API Key | 分钟 | 默认跳过，`-Pintegration` 开启 |

### 关于 `MemorandumApplicationTests.contextLoads()`

`./gradlew test` 跑全量时，唯一一个会红的用例是它。原因不是缺陷，是**它是全仓唯一依赖真实 MySQL 的测试**：

```java
@SpringBootTest          // 裸注解，不带 @ActiveProfiles
class MemorandumApplicationTests { void contextLoads() { ... } }
```

不指定 profile 就走 `src/main/resources/application.properties`，那里的 `spring.datasource.url` 指向 `jdbc:mysql://localhost:3306/memo_db`。本机没起 MySQL 时，Hibernate 拿不到 JDBC metadata，报 `Unable to determine Dialect`，上下文装配失败。

**它和 Agent 评测完全无关** —— `agentEval` 走 `agenteval` profile + H2，不受影响。本机起了 MySQL 就能通过。

> 若希望 `./gradlew test` 在干净环境下也全绿，可选：给它加 `@ActiveProfiles` 指向一个 H2 profile，或改用 Testcontainers 拉一个真 MySQL。前者快但测不到真实方言，后者慢但更接近生产 —— 尚未选型。

---

## 5. 使用方式

### 日常回归（离线、零成本）

```bash
./gradlew agentEval
```

当前实际输出（2026-09-01）：

```
══════════════════════════════════════════════════════════════
  Agent 评测报告   mode=replay   用例数=9
══════════════════════════════════════════════════════════════
  收敛率       100.0%    (收敛 9 / 步数耗尽 0 / LLM失败 0)
  工具幻觉率     0.0%    (0 次编造 / 0 个用例受影响)
  含工具失败       1 个用例   ← tool_error_recovery，用例故意制造
  步数 P50/P95   1 / 2    最大 2
  LLM 调用      共 18 次，均 2.00 次/用例
  录制新鲜度    0 个用例漂移（全部录制与当前 prompt 一致）
──────────────────────────────────────────────────────────────
  逐用例明细
   PASS    create_task_basic       steps=1 llm=2 tools=task.create
   PASS    kb_search_hit           steps=1 llm=2 tools=kb.semantic_search
   PASS    kb_search_degraded      steps=1 llm=2 tools=kb.semantic_search
   PASS    tool_error_recovery     steps=2 llm=3 failed=task.search
   ...
══════════════════════════════════════════════════════════════
```

同时写入 `build/agent-eval/report.json` 供 CI 归档与趋势对比。

> **步数 P50=1 偏低是回放的性质，不是模型很聪明**：录制盒固定了"模型说了什么"，所以每个用例的路径是录下来那一条。这个指标在 record 模式下才反映真实决策效率。

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

### 6.1 跑通情况

| 命令 | 结果 |
|---|---|
| `./gradlew agentEval` | **9/9 用例通过**，收敛率 100%，工具幻觉率 0% |
| `./gradlew test` | 367 个用例，1 个失败（`contextLoads`，需真实 MySQL，见 §4） |

9 个轨迹用例的录制盒**全部就位**。其中 `no_tool_hallucination`、`mode_isolation_learn` 及后补的 7 个均为**手工编写的 fixture**（`fingerprint: null`，跳过漂移检测），用于在无 API Key 时验证框架。

> **建议尽快用真实 API 重录一遍**。手工 fixture 固定的是「我认为模型会这么答」，而评测的价值恰恰在于发现「模型实际不这么答」。手工盒能验证链路，不能验证模型行为。
>
> ```bash
> set DEEPSEEK_API_KEY=sk-xxx
> gradlew agentEval -Dagent.eval.mode=record
> ```
>
> 录制后逐个检查 `cassettes/*.json` 的 `responseContent`，确认模型行为合理（比如 `kb_search_degraded` 里它是否真的说了"未找到强相关笔记"）。**若模型行为本身不对，那是 prompt 需要改，不是测试需要改** —— 这正是评测体系的价值。

### 6.2 ★曾让评测「假绿」的两个台账缺陷（已修）

这两个都属于**评测本身坏了，但它报告自己是好的** —— 比被测代码有 bug 更危险，因为它让所有其它结论都不可信。记录在这里是为了下次别再犯。

**缺陷一：H2 把 `user` 当保留字，DB 层静默损坏**

实体表名就叫 `user`，而 `USER` 是 H2 2.x 的保留字。任何 JOIN 到它的查询都抛 `42001` 语法错，而多数调用点把异常吞成「查无结果」。于是评测跑在一个**静默损坏的 DB 层**上，看起来还全绿。

修复：H2 URL 加 `NON_KEYWORDS=USER`。

```properties
spring.datasource.url=jdbc:h2:mem:agenteval;...;MODE=MySQL;NON_KEYWORDS=USER
```

**缺陷二：评测用户从不落库，写工具全部失败却不报错**

`UserRepository` 是 mock，测试用户只活在内存里。而 `task.create` 会插入指向 `user` 的外键 —— 缺行就是 `23506` 外键冲突。**所有写工具在评测里其实一直执行失败**，但断言只检查「工具被调用过」，所以 9 个用例照样全绿。

唯一能看出来的地方是报告里的 `failedTools` 字段，而在发现之前没人看它。

修复：`AgentEvalBase.ensureUserRow()` 用 `JdbcTemplate` 把用户真正 MERGE 进 H2。

**教训（值得在面试里讲）**：

> 一个只断言「工具被调用了」的评测套件，测的是**模型选对了工具**，不是**这次调用真的成功了**。两者差一个数量级的信息量。现在的做法是把 `failedTools` 纳入报告并逐用例核对 —— 目前唯一非空的是 `tool_error_recovery`，那是用例**故意**制造的失败，用来测反思重试。

### 6.3 怎么判断报告是否可信

跑完后先看这三个字段，任何一个异常都说明是台账问题而非模型问题：

| 字段 | 期望 | 异常时说明 |
|---|---|---|
| `failedTools` | 仅 `tool_error_recovery` 非空 | 工具执行真的失败了，多半是环境/DB 问题 |
| `driftWarnings` | 空 | prompt 改过，录制盒已过期，需重录 |
| 控制台 `SQL Error` | 无 | DB 层静默损坏，见 §6.2 |

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

**Part 7 边界表要新增两行**（诚实划界）：

| 我做的 | 我没做的 |
|---|---|
| 轨迹回放 + 决策层断言 + 指标报告 | **LLM-as-Judge 的答案质量自动评分**、多轮对话的长程一致性评测、对抗性测试集 |
| 上下文工程的机制级基准（见 §11） | **真实模型的摘要质量评测** —— 基准里的摘要器是抽取式桩 |

被问到时可以说：

> 我评的是"决策路径对不对"，还没做"答案写得好不好"。后者需要 LLM-as-Judge 或人工标注评分集，属于下一步。

**另外强烈建议讲 §6.2 那两个台账缺陷** —— 这是本项目最有说服力的一段，因为它证明的不是"我写了测试"，而是"我怀疑过自己的测试"：

> 我的评测套件有段时间是假绿的：写工具因为外键约束一直执行失败，但断言只检查「工具被调用过」，所以 9 个用例全通过。是我去核对报告里 `failedTools` 字段才发现的。这件事让我意识到，**断言的粒度决定了测试的信息量** —— 「调用了」和「调用成功了」差一个数量级。

---

## 11. 上下文工程基准（`agenteval/bench/`）

轨迹评测回答的是「单轮决策对不对」，回答不了「跑几十轮之后上下文还剩什么」。后者是滚动摘要与 Facts 层要解决的问题，所以单开一组基准。

```bash
./gradlew test --tests '*ContextEngineeringBenchmark*'
# 报告写入 build/agent-eval/context-engineering.md
```

**三个基准**：

| 基准 | 量什么 | 当前结果 |
|---|---|---|
| 1 · 长对话约束留存 | 40 轮后，第 1..5 轮说的硬约束还剩几条 | 关闭 0/5；开启 5/5（约束集中在开头）或 2/5（散布到第 17 轮） |
| 2 · 纯工具噪声短路 | 整段是工具 trace 时实付多少次摘要调用 | 60 次折叠判定，0 次 LLM 调用 |
| 3 · facts 前缀稳定性 | 一天内 system 段出现几种字节版本 | IMMEDIATE 9 种；DAY 恒为 1 种 |

**口径声明（读结论前必看）**：窗口淘汰、折叠触发与级联、200 字截断、噪声短路、DAY 粒度卡点都是被测代码的真实行为；**但摘要器本身是抽取式桩**，不是真实 LLM。所以留存率量的是「折叠机制能否把约束带过窗口边界」，不是「某个模型的摘要写得好不好」。后者要在 record 模式下用真 API 单独量。

**基准 1 那个 2/5 是刻意补的对照**，值得单独讲：第一版只测了约束集中在开头的情况，跑出 5/5。深究后发现是折叠把最老一段压成一条**放回队头**、而摘要超长时从**尾部**截断，所以越早说的越受保护 —— 场景恰好挑在了最有利的位置。把同样 5 条约束散开，留存立刻掉到 40%。

> 这是机制的真实边界，不是实现 bug。写进文档而不是藏起来，因为「早期约束保得住、中段约束会衰减」才是可以拿去做决策的结论，「开了 compaction 就不丢信息」不是。
