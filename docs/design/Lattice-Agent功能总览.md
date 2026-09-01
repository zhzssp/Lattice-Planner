# Lattice-Agent 功能总览

> 面向「快速理解 / 二次开发 / 面试讲解」，聚焦**当前已落地的能力**与**关键设计点**。
> 逐文件实现方案见同目录 `Agent实现方案.md`——本文是"做完了什么"，那份是"为什么这么做"。
>
> **本文合并了原先的 `Lattice-Agent-功能完整说明.md`**（两份内容重叠且都已过时）。
> 校对基准：2026-09-01，对应 `V4.0.0` 分支。所有数字均为当日实测，改代码后请回来同步。

---

## 1. 一句话概括

**Lattice-Agent** 是内嵌在 Lattice-Planner 里的对话式智能体：用户在任意业务页面点开抽屉式聊天面板，就能用自然语言驱动 Agent **读写自己的目标 / 任务 / 笔记 / 复盘数据**、检索个人知识库、读取本地文档、委派子代理协作。

整套运行时是自实现的——ReAct 主循环、注解反射工具注册、子代理隔离、上下文工程、双向 MCP，**未引入 LangChain / Spring AI**。

---

## 2. 分层结构

```
┌─ 对话层 chat ────────────────────────────────────────┐
│ AgentChatWebSocketHandler  /ws/agent/{sid}           │
├─ 编排层 runtime ────────────────────────────────────┤
│ AgentOrchestrator（ReAct 主循环，≤24 步）             │
│ PromptBuilder + PrefixCache / ToolCallParser         │
│ ReflexionAdvisor / TurnStopping / TurnOutcome        │
├─ 上下文层 runtime + memory ─────────────────────────┤
│ ConversationMemory（窗口 30）                         │
│ ContextCompactor（滚动摘要）/ FactService（Facts 层）  │
│ LongTermMemoryService（跨会话归档）                    │
├─ 子代理层 subagent ─────────────────────────────────┤
│ SubAgentRunner / SubAgentExecutor（并行 fan-out）     │
├─ 工具层 tool ───────────────────────────────────────┤
│ ToolRegistry（@AgentTool 注解 → 反射）                │
│ 可见性 ToolVisibilityResolver（四层 scope 链）         │
├─ 策略层 policy ─────────────────────────────────────┤
│ ToolApprovalPolicy / ToolConfirmCoordinator          │
├─ 知识层 pkm ────────────────────────────────────────┤
│ RagServing → CorrectiveRetriever(CRAG) → RagSearch   │
├─ 知识仓库 codex ────────────────────────────────────┤
│ 索引 / 检验 / 沉淀 / 缺口 / 蒸馏 五期能力              │
└─ 接入层 mcp + llm ─────────────────────────────────┘
  MCP Server + Client（含 loopback）/ LlmGateway → Router
```

**核心与插件解耦**：任务/目标/笔记是核心业务层，Agent 通过 Spring 事件驱动扩展（Agent 写库 → `TaskCompletedEvent` → 目标进度自动重算）。**Agent 永远调业务真服务，业务代码 0 修改**。

---

## 3. 核心推理引擎（ReAct 主循环）

`AgentOrchestrator.handleUserTurn` 单轮最多 `agent.chat.max-steps`（**默认 24**）步。每步 LLM 输出要么是工具调用 JSON，要么是终态自然语言。

**工具执行前的三重闸门**（都是执行层强制，不是提示层劝告）：

1. **存在吗** → 否则回灌 `UNKNOWN_TOOL`（幻觉自纠）
2. **可见吗** → 当前 scope 不可见则回灌 `TOOL_NOT_VISIBLE`（越界拦截）
3. **封禁了吗** → 本轮失败超阈值则短路，不真正执行

**收尾不是直接返回**：终态先问一圈 `TurnStoppingAdvisor`，顾问可返回 steer 让循环继续（单轮上限由 Bus 强制，防无限循环）。

**流式事件**：WebSocket 下发 `assistant / toolStart / toolResult / confirmReq / done / error`。

---

## 4. 工具系统

`ToolRegistry` 在 `ApplicationReadyEvent` 时反射扫描所有 `@AgentTool`（用 ready 而非 `@PostConstruct`，因为 MCP 远程工具要等客户端连上才注册）。

**当前共 64 个工具**：

| 域 | 代表工具 | 说明 |
|---|---|---|
| task | `task.create` / `search` / `today` / `complete` / `archive` | 6 个 |
| goal | `goal.list` / `create` / `archive` / `link_task` | 5 个 |
| note | `note.list` / `create` | 2 个（自动屏蔽 `AGENT_MEMO`） |
| planner | `planner.draft_goal_plan` / `apply_goal_plan` | 2 个 |
| insight | `insight.daily_scores` / `summarize_period` | 2 个 |
| kb | `kb.semantic_search` / `lookup_by_title` / `list_backlinks` / `ingest_local_doc` … | 6 个 |
| subagent | `subagent.plan` / `reflect` / `research` / `parallel_research` | 5 个 |
| codex | `doc.*` `repo.*` `git.*` `ci.*` `gap.*` `distill.*` `route.*` `checkpoint.*` `sediment.*` | 36 个 |
| MCP 远程 | `mcp.<server>.<tool>` | 运行时动态注册 |

**参数必须打 `@ToolParam`**，否则启动直接失败——Java 编译后参数名会丢，没注解就无法生成正确 JSON Schema。**宁可启动失败，也不要运行时给 LLM 一个错的 schema**。

**统一入口**：`registry.invoke(name, args)` 对 LLM 完全透明——它不知道背后是本地反射还是跨进程 JSON-RPC。

> ⚠️ **`local.*` 工具已下线**。本地文件访问从 Electron 反向 IPC 迁移到 MCP 后端直读，现统一走 `mcp.loopback.local.read_document`。`LocalDocTools.java` 保留为空壳（无任何 `@AgentTool`）。历史文档里提到的 `local.list_dir` / `local.read_file` / `local.read_pdf` **均已不存在**。

---

## 5. 工具可见性（四层 scope 链）

```
GLOBAL（全量）→ MODE（模式）→ ROLE（子代理角色）→ SESSION（会话）
```

支持 `allow`（收窄）/ `deny`（累积剔除）/ `pin`（破例）/ 结构性保留（不可被 pin 解除，如 `subagent.*` 对子代理）。

**为什么不能只靠 tag 过滤**（两个真实缺陷）：
- tag 是 OR 语义，只能"放行"不能"禁止"——`note.create` 同带 `note` 和 `write`，learn 模式放行 `note` 后它照样能写笔记
- 过滤只影响给 LLM 的 schema，**不影响执行**——模型凭记忆调不可见工具照样调得通，因为 `registry.get()` 查的是全量表

所以在执行前再拦一道，返回 `TOOL_NOT_VISIBLE`。这与 Reflexion 封禁组成两层权限治理：**可见性决定"你本来能碰什么"，封禁决定"你这轮已经把什么碰坏了"**。

---

## 6. 子代理系统

`SubAgentRunner` 在**独立局部上下文**跑完整 ReAct，只把压缩结论回主 Agent，中间过程全部丢弃。

| 角色 | 定位 | 工具 tag 子集 |
|---|---|---|
| PLANNER | 读文档 → 拆目标 → 建任务落库 | mcp + kb + planner + task + goal |
| REFLECTION | 聚合周期数据产出结构化复盘 | insight + task + goal + note + kb |
| RESEARCH | 多跳知识库检索，给有出处的答案 | kb + note + mcp |

**关键约束**：
- 结论截断 `agent.subagent.result-max-chars=4000`
- 每角色步数 `agent.subagent.max-steps=12`（独立于主循环的 24）
- **子代理不能再委派子代理**：`guardTopLevel()` 查 `depth > 0` 抛异常 + 可见性层结构性保留 `subagent.*`，双保险
- 工具 tag 取**最小必要子集**——tag 是 OR 语义，放通用的 read/write 会命中所有工具，隔离失去意义

**并行 fan-out**：`subagent.parallel_research` 把问题拆成多个子问题并发研究，线程池 size=4，`allOf().orTimeout(120s)`，超时的 worker 标注降级而**不整体失败**。仅绑定只读角色，写角色保持串行。

**ThreadLocal 跨线程显式传播**：worker 线程手动 `AgentContext.set` + `finally clear`。**不能用 `InheritableThreadLocal`**——固定线程池会复用线程，继承语义会让上个任务的用户身份泄漏。

---

## 7. 上下文工程

| 层 | 载体 | 生命周期 | 解决什么 |
|---|---|---|---|
| 短期 | `ConversationMemory`（内存，窗口 30） | 会话内 | 多轮连贯 |
| 折叠 | `ContextCompactor`（滚动摘要） | 窗口将满时 | 关键约束不随窗口静默滑出 |
| 事实 | `FactService`（`agent_fact` 表） | 跨会话 / 会话内 | 硬约束可核对、可纠正 |
| 长期 | `LongTermMemoryService` → `AGENT_MEMO` 笔记 | 跨会话 | "记住我的习惯" |
| 工作 | 子代理局部 `List` | 单次子任务 | 隔离长文档 |
| 前缀 | `PrefixCache`（Caffeine） | turn 内 + 跨 turn | 省构造 CPU + 让上游 prompt cache 命中 |

**滚动摘要**（`agent.context.compaction.enabled=true`，已开启）：
- 窗口用到 80% 时把最老 10 条折叠成一条摘要（`role=user`，**不能进 system**，否则破坏前缀字节稳定）
- **纯工具噪声短路**：待折叠段剔除 tool trace 后不足 6 条真实对话 → 直接丢弃，不付 LLM 调用
- **失败回退等价旧行为**：折叠失败则直接丢弃 + 置 `CAUSE_TRUNCATED`，绝不阻断对话
- 触发点在**轮首**和**每次工具结果回灌后**——只挂后者的话，纯聊天会话永远折叠不到

**Facts 层**（`agent.context.facts.enabled=false`，**默认关**）：
- 每轮异步从用户原话抽取事实，按变更频率分流注入：稳定 → system prompt，易变 → history 首条
- 每条必存 `source_quote` + `source_turn`，用户能核对"凭什么说我有这条约束"
- 覆盖而非追加（同 key 新值把旧值标 `SUPERSEDED`）；`REJECTED` 永不再抽
- 只收 `MEDIUM` 以上置信度——facts 注入每一轮，抽错的污染面比一次错误回答大得多
- `stable-apply-granularity=DAY`：稳定 facts 只取今天零点前创建的，让 system 段全天字节恒定

> **为什么 facts 默认关**：它写进 system prompt，抽错会污染每一轮，而"抽取准确率"目前没有离线证据——回放套件用的是录制响应，量不出真实模型的抽取质量。放开前需在 record 模式下用真 API 验证。

**效果实测**见 `build/agent-eval/context-engineering.md`（`./gradlew test --tests '*ContextEngineeringBenchmark*'` 可复现）。

**前缀缓存 key**：`(mode, toolsetHash, memoHash, dateBucket)`。`dateBucket` 用**天级**而非精确时间——精确到秒的话每次请求前缀都不同，上游 automatic prefix caching 永远无法命中。`exportSchemas()` 输出**按名排序**，因为 `ConcurrentHashMap` 迭代顺序规范上不保证。

---

## 8. 安全与权限

- **多用户隔离**：`AgentContext` ThreadLocal 存 user/sid/depth，所有工具第一行 `requireUser()`，仓储查询带 userId 过滤——**越权读他人数据在架构上不可达**
- **高危工具确认**：`requiresConfirm` 工具先发 `confirmReq`，60s 超时按拒绝
- **授权策略分层**：`requiresConfirm` 是工具固有属性（注解里，编译期固定）；用户的 auto-approve 是运行时降权（存 `UserPreference`）。判断式 `requiresConfirm && !autoApproved(user, tool)` 抽成 `ToolApprovalPolicy`，主循环与子代理共用
- **深度护栏**：`AgentContext.depth` 防子代理递归

---

## 9. 知识库与 RAG

```
RagServingService（门面：语义缓存 / 精排 / 异步预取 / 指标）
  ▼
CorrectiveRetriever（CRAG）
  ├─ 分级：CORRECT(≥0.6) / AMBIGUOUS(≥0.4) / INCORRECT
  ├─ QueryRewriter 改写重检索（RRF 合并，最多 1 次）
  └─ 仍不达标 → degraded=true，结构化信号回传 LLM
  ▼
RagSearchService（Hybrid 双通路）
  ├─ 关键字：MySQL FULLTEXT ngram（RRF 1/(1+rank)）
  ├─ 向量：bge-m3 1024 维 cosine
  └─ 加权融合 alpha=0.4，任一通路异常自动降级另一路
```

**关键决策**：
- **零外部向量组件**：MySQL JSON + Caffeine LRU，万级 chunk 全表余弦约 10ms。清楚天花板——十万级以上必须换 pgvector / Milvus
- **降级矩阵完整**：没配 embedding key 时自动退回纯关键字通路，功能不失效
- **降级信号无条件返回并置于数组首位**——曾因"只在零命中时返回"导致"有命中但质量差"场景信号丢失
- **语义查询缓存**：cosine ≥ 0.93 命中，按用户 LRU（32 条/用户，64 用户），笔记更新时主动 invalidate
- **角色感知预取**：委派 RESEARCH 子代理前 fire-and-forget 预取，因为确定它接下来一定会检索
- **LLM 精排默认关闭**：额外一次 LLM 调用，收益不确定时不开

---

## 10. MCP 双向集成

- **Server**：`/sse` + `/mcp/message`，把本地工具与资源暴露给外部 MCP 客户端（Claude Desktop、Cursor、Cline）
- **Client**：`McpClientManager` 连接远程 MCP Server，把远端工具代理为本地 `@AgentTool`
- **loopback（招牌特性）**：Agent 读本地文档时不走同进程直调的捷径，而是作为 MCP Client 连本机自己的 Server，完整走 SSE + JSON-RPC 握手。多一次本机回环 <5ms，换来协议栈完整复用
- **健康检查**：30s 周期自动重连

> **已知限制**：loopback 连接启动时建立并长期复用，绑定 token 持有者身份，与"当前对话用户"是两个独立身份。对"读本机白名单目录"可接受（白名单本身是全局配置）；多用户隔离部署需改成按对话用户动态签发临时 token。

---

## 11. 可观测与评测

- **轨迹埋点**：`AgentTraceListener` + `AgentTraceMetrics`，生产与测试**共用同一套埋点**
- **端点**：`GET /api/agent/trace/stats`、`/api/agent/prefix-cache/stats`、`/api/observability/stats`
- **评测体系**：录制回放，9 个轨迹用例 + 3 个上下文工程基准，离线零成本。详见 [`../Agent评测体系使用指南.md`](../Agent评测体系使用指南.md)

**一条硬约束**（从"指标恒为 0"那个坑沉淀的）：**没有消费方的指标等于没有指标**。每个新能力必须同时有开关、有指标、有暴露端点。

---

## 12. 关键配置项

| 配置 | 当前值 | 含义 |
|---|---|---|
| `agent.chat.max-steps` | 24 | 主循环步数上限 |
| `agent.chat.history-window` | 30 | 短期记忆窗口 |
| `agent.subagent.max-steps` | 12 | 子代理步数上限 |
| `agent.subagent.result-max-chars` | 4000 | 子代理结论截断 |
| `agent.subagent.parallel.size` / `timeout-seconds` | 4 / 120 | 并行 fan-out |
| `agent.context.compaction.enabled` | **true** | 滚动摘要 |
| `agent.context.facts.enabled` | **false** | Facts 层（见 §7 说明） |
| `agent.prefix-cache.max-entries` / `expire-minutes` | 256 / 120 | 前缀缓存 |
| `pkm.crag.upper` / `lower` | 0.6 / 0.4 | CRAG 分级阈值 |
| `pkm.rag.serving.query-cache.threshold` | 0.93 | 语义缓存命中阈值 |

技术栈：**Java 21** · Spring Boot 3.5.6 · WebSocket · MySQL · H2（评测）· Caffeine · Jackson · DeepSeek · bge-m3 · Electron · PDFBox · POI

---

## 13. 已知限制

- `ConversationMemory` 是进程内内存，**重启即清空**（生产需替换为 Redis / DB）
- 工具调用未并行，一次只调一个——设计为线性 ReAct 便于审计，且工具间常有数据依赖
- 滚动摘要对**中段约束**的保护弱于早期约束（折叠放回队头 + 尾部截断的固有结果，实测散布场景留存 40%）
- Facts 抽取准确率无离线证据，故默认关闭
- 评测只覆盖"决策路径对不对"，未做 LLM-as-Judge 的答案质量评分
- 浏览器壳里本地文档功能不可用（需 MCP loopback 且白名单配置）
