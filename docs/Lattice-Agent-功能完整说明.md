# Lattice-Planner Agent 功能完整说明

> 本文档全面概述当前 Agent 的功能特性与所用技术，每个要点仅作简述。
> 适用分支：`V3.0.0`。涉及包：`feature.agent.*`、`feature.pkm.*`。

---

## 1. 总体架构

- **定位**：内置于 Lattice-Planner 的「个人知识库 AI 助手」，基于 ReAct 循环，可调用本地工具读写笔记、知识库、目标/任务，并支持委派子代理。
- **运行形态**：Spring Boot 后端 + WebSocket 实时对话（前端经 Electron 桌面客户端调用）。
- **分层**：推理层（`runtime`）、工具层（`tool`）、子代理层（`subagent`）、策略层（`policy`）、对话层（`chat`）、MCP 层（`mcp`）、知识层（`pkm`）。

---

## 2. 核心推理引擎（ReAct 循环）

- **`AgentOrchestrator`**：精简版 ReAct 主循环，单轮最多 `agent.chat.max-steps`（默认 8）步；每步 LLM 输出要么终态自然语言，要么调用一个工具。
- **`ToolCallParser`**：解析 LLM 返回的 JSON 工具调用；无法解析时把模型输出当作最终回答。
- **Reflexion 自纠**：工具执行失败时将错误 JSON 回灌给 LLM，让模型自我纠正后重试。
- **流式事件**：通过 WebSocket 实时下发 `assistant / toolStart / toolResult / done / error` 事件。

---

## 3. 对话与交互

- **`AgentChatWebSocketHandler`**：端点 `/ws/agent/{sessionId}`；基于 `TextWebSocketHandler`，鉴权失败即断开。
- **协议**：客户端发 `chat / localResult / confirmReply`；服务端发 `assistant / toolStart / toolResult / localCall / confirmReq / done / error`。
- **模式（mode）**：`chat / plan / reflect` 等，影响系统前缀与工具集。
- **本地桥接回调**：需要读本地文件时，经 `LocalBridgeProxy` 向 Electron 发 `localCall`，等前端 `localResult` 回传（如读 md/txt/pdf）。

---

## 4. 工具系统

- **`ToolRegistry`**：集中注册所有 `@AgentTool`，按工具名查表并按 tag 导出 schema；导出按工具名排序以保证字节稳定（利于前缀缓存）。
- **`AgentTool` / `ToolParam`**：声明式注解，描述工具名、标签（read/write/local/subagent）、是否需确认、参数与说明。
- **知识库工具（`KnowledgeTools`）**：`kb.semantic_search`（hybrid 检索）、`kb.lookup_by_title`、`kb.list_backlinks`、`kb.ingest_local_doc`（写，需确认）、`kb.list_ingested_docs`、`kb.delete_local_doc`（写，需确认）。
- **笔记工具（`NoteTools`）**：`note.list`、`note.create`（不允许 LLM 写系统类 `AGENT_MEMO`）。
- **规划/任务/目标/复盘/洞察工具**：`PlannerTools`、`TaskTools`、`GoalTools`、`InsightTools`、`LocalDocTools`，覆盖建目标、拆任务、记分数、读本地文档等。
- **子代理工具（`SubAgentTools`）**：把子代理包装成工具（`subagent.plan / reflect / research / parallel_research`），主循环零改动即可委派。

---

## 5. 子代理系统

- **`SubAgentRunner`**：在独立局部上下文跑精简 ReAct，只把最终结论回主 Agent，中间过程全部丢弃，避免污染主对话。
- **角色（`SubAgentRole`）**：`PLANNER`（规划专家）、`REFLECTION`（复盘专家）、`RESEARCH`（检索专家），各含专属 system prompt + 最小工具 tag 子集 + 步数预算（默认 6）。
- **并行检索（`SubAgentExecutor.fanOut`）**：`subagent.parallel_research` 可并行委派多个检索专家汇总。
- **递归防护**：`AgentContext.depth` + `guardTopLevel` 双重禁止子代理再起子代理。

---

## 6. 记忆系统

- **短期记忆（`ConversationMemory`）**：进程内按 session 的滑动窗口（30 条），记录最后活跃时间供空闲归档判定。
- **长期记忆（`LongTermMemoryService`）**：用 `NoteType.AGENT_MEMO` 笔记持久化用户画像；会话归档时让 LLM 凝练 3~6 行，并剔除工具调用噪声。
- **前缀缓存（`PrefixCache`）**：Caffeine LRU 缓存 system prompt 构造结果，key = `(mode, 工具集 hash, 长记 hash, 当日 bucket)`；同一 turn 多步复用，跨 turn 命中降低延迟。
- **`SessionArchiveScheduler`**：按空闲时长自动触发会话归档到长期记忆。

---

## 7. 安全与权限

- **多用户隔离（`AgentContext`）**：`ThreadLocal` 保存当前用户/会话/层级，工具内 `requireUser()` 强制越权不可达。
- **高危工具确认（`ToolConfirmCoordinator`）**：`requiresConfirm` 工具先发 `confirmReq`，等 UI 点允许/拒绝；60 秒超时按拒绝处理，避免推理线程阻塞。
- **深度护栏**：`AgentContext.depth` 防止子代理递归嵌套。

---

## 8. 知识库与 RAG 检索

- **Hybrid 检索（`RagSearchService`）**：关键字（MySQL FULLTEXT ngram，RRF 1/(1+rank)）与向量（cosine）双通路，权重 `alpha=0.4`；任一通路异常自动降级另一路。
- **向量化（`EmbeddingClient`）**：接 bge-m3（1024 维，经 SiliconFlow），提供 `float[]` 序列化与静态 cosine；单用户万级 chunk 全表余弦约 10ms。
- **向量缓存（`EmbeddingVectorCache`）**：LRU 缓存反序列化向量，避免每次检索全表 deserialize。
- **CRAG 纠错检索（`CorrectiveRetriever` + `RetrievalEvaluator` + `QueryRewriter`）**：检索→分级（CORRECT≥0.6 / AMBIGUOUS≥0.4 / INCORRECT）→改写重检索（RRF 合并，最多 1 次）→仍不达标则 `degraded` 提示走通用知识；Self-RAG 元信息回传给 LLM 自省。
- **RAG Serving 门面（`RagServingService`）**：在检索之上提供语义查询缓存、二阶段精排、异步检索/预取、指标记录；各能力可配置开关，关闭即等价直连 `RagSearchService`。
- **语义查询缓存（`QueryResultCache`）**：cosine≥0.93 命中，按用户 LRU，降低重复检索开销。
- **LLM 精排（`Reranker`）**：LLM-as-Ranker 对粗排 top-N 重排（默认关闭），任何异常回退融合序。
- **异步与指标（`RagServingConfig` / `RagServingMetrics`）**：独立有界线程池 + 原子计数器统计命中率/延迟。

---

## 9. MCP 集成

- **服务端（`McpSseEndpoint` / `McpResourceAdapter` / `McpToolAdapter`）**：以 SSE 暴露本地工具与资源，供外部 MCP 客户端（Claude Desktop、Cursor、Cline 等）调用。
- **客户端（`McpClientManager` / `McpSseClient` / `McpRemoteTool`）**：连接远程 MCP Server，把远端工具代理为本地 `@AgentTool`（`McpToolProxy`），扩展 Agent 能力边界。
- **鉴权与文件（`McpAuthService` / `McpLocalFileService`）**：管理 MCP 连接凭证与本地文件访问白名单。

---

## 10. 所用技术栈

- **语言/框架**：Java 17 + Spring Boot（WebSocket、`@Component` 依赖注入、`@Value` 配置）。
- **LLM 接入**：`LlmGateway` 抽象 DeepSeek（chat：`deepseek-reasoner` / `deepseek-chat`；embedding：SiliconFlow bge-m3）。
- **检索/存储**：MySQL（FULLTEXT ngram + 向量表）、Caffeine（前缀缓存/向量缓存）。
- **序列化**：Jackson（`ObjectMapper`）。
- **日志**：SLF4J + Logback。
- **前端桥接**：Electron 桌面客户端（本地文件读写的 `localCall/localResult` 协议）。
- **协议**：Model Context Protocol（SSE）用于内外部工具互通。

---

## 11. 关键配置项（`application.properties`）

- `agent.chat.max-steps` / `agent.subagent.max-steps`：主/子代理步数上限。
- `agent.llm.*`：DeepSeek base-url / model / api-key（环境变量）。
- `agent.embedding.*`：embedding 端点（SiliconFlow bge-m3）。
- `agent.prefix-cache.*`：前缀缓存开关/容量/过期。
- `pkm.rag.*`：alpha / candidates / topK。
- `pkm.crag.*`：CRAG 阈值与改写配置。
- `pkm.rag.serving.*`：查询缓存、rerank、异步线程池配置。
