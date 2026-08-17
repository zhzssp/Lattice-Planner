# Agent 多模型 / 多提供方 · 模型切换与路由设计方案

> 目标：在 Agent 聊天面板中像常见 IDE 一样切换可选模型，支持多个提供方（DeepSeek / OpenAI-Compatible / 预留 others），
> 并实现多用户分别使用不同模型时的**路由机制 + 简单负载均衡**。

---

## 1. 现状分析（基于实际代码）

| 关注点 | 现状 | 问题 |
|--------|------|------|
| LLM 调用入口 | `LlmGateway`（`@Component` 单例） | 所有 Agent 共用一套配置 |
| 配置来源 | `@Value` 字段：`agent.llm.base-url` / `api-key` / `model`、`agent.chat.model` | **编译期绑定、启动时读取一次**，无法按用户运行时切换 |
| chat 调用点 | `AgentOrchestrator.generateChat`、`SubAgentRunner.generateChat` | 硬编码走 `chatModelOverride` 单模型 |
| 其他调用 | `generateText`（Planner/Reranker/QueryRewriter/AiSummary/LongTermMemory）、`generateEmbedding` | 需保持向后兼容，不能被本次改造破坏 |
| 多用户上下文 | `AgentContext.requireUser()`（ThreadLocal，WS 派生线程内已 set） | **✅ 天然的按用户路由入口** |
| 用户偏好持久化 | `UserPreference` 实体 + `UserPreferenceService.getOrCreatePreference` | **✅ 可复用**，刚加过 `agentAutoApproveTools` 字段 |
| 迁移脚本 | `V1__ ~ V6__` | 下一个为 `V7__` |

**核心结论**：
1. `LlmGateway` 当前是「单配置单例」，要支持多提供方必须引入**提供方注册表 + 运行时模型解析**。
2. 路由的钥匙是 `AgentContext.requireUser()`——每次 `generateChat` 时按当前线程用户解析其选中的模型。
3. 负载均衡指的是**同一逻辑模型可绑定多个上游端点/Key**（例如同模型配 2 个 Key 或 2 个兼容站点），调用时在其中挑一个，摊薄单 Key 的限流/额度压力。

---

## 2. 概念模型

```
Provider（提供方）
  ├─ id: "deepseek" | "openai-compat" | "custom-xxx"
  ├─ displayName: "DeepSeek"
  ├─ type: DEEPSEEK | OPENAI_COMPATIBLE     // 报文格式，目前二者都是 OpenAI 风格
  └─ endpoints: List<Endpoint>              // ★ 负载均衡单元：同一 provider 下多个上游
        ├─ baseUrl
        ├─ apiKey
        └─ weight (可选，默认 1)

Model（可选模型 = 展示给用户的条目）
  ├─ id: "deepseek-chat" | "deepseek-reasoner" | "gpt-4o-mini" | ...
  ├─ displayName: "DeepSeek Chat"
  ├─ providerId: 归属提供方
  └─ enabled: 是否上架给用户选择

用户选择（持久化在 UserPreference）
  └─ agentChatModelId: 用户当前选中的 Model.id（null → 用系统默认）
```

- **Provider/Model/Endpoint 全部来自配置（`application.properties`）**，非硬编码，新增提供方=改配置。
- **DeepSeek 已有真实模型**；OpenAI-Compatible 提供方**先建好骨架**（可配 0~N 个模型），当前无具体模型时列表为空，但代码路径已打通，后续加模型仅改配置。

---

## 3. 配置设计（`application.properties`）

采用「provider 列表 + 每 provider 多 endpoint + model 列表」的层级配置。用 `@ConfigurationProperties` 绑定。

```properties
# ==============================
# Agent 多模型 / 多提供方（LLM Providers & Models）
# 系统默认模型（用户未选时回落）
agent.llm.default-model=deepseek-chat

# ---------- Provider: deepseek ----------
agent.llm.providers[0].id=deepseek
agent.llm.providers[0].display-name=DeepSeek
agent.llm.providers[0].type=OPENAI_COMPATIBLE
# 多 endpoint = 负载均衡单元（同 provider 可配多 Key / 多站点）
agent.llm.providers[0].endpoints[0].base-url=https://api.deepseek.com
agent.llm.providers[0].endpoints[0].api-key=${DEEPSEEK_API_KEY:}
agent.llm.providers[0].endpoints[0].weight=1
# 示例：追加第二个 Key 做负载均衡（可选）
#agent.llm.providers[0].endpoints[1].base-url=https://api.deepseek.com
#agent.llm.providers[0].endpoints[1].api-key=${DEEPSEEK_API_KEY_2:}
#agent.llm.providers[0].endpoints[1].weight=1

# ---------- Provider: openai-compat（骨架，暂无默认模型）----------
agent.llm.providers[1].id=openai-compat
agent.llm.providers[1].display-name=OpenAI Compatible
agent.llm.providers[1].type=OPENAI_COMPATIBLE
agent.llm.providers[1].endpoints[0].base-url=${OPENAI_COMPAT_BASE_URL:}
agent.llm.providers[1].endpoints[0].api-key=${OPENAI_COMPAT_API_KEY:}

# ---------- Models（上架给用户的可选模型）----------
agent.llm.models[0].id=deepseek-chat
agent.llm.models[0].display-name=DeepSeek Chat（推荐）
agent.llm.models[0].provider-id=deepseek
agent.llm.models[0].enabled=true

agent.llm.models[1].id=deepseek-reasoner
agent.llm.models[1].display-name=DeepSeek Reasoner（深度推理）
agent.llm.models[1].provider-id=deepseek
agent.llm.models[1].enabled=true

# 未来在 openai-compat 上加模型示例（当前不填 → 列表里就没有 OpenAI 模型）
#agent.llm.models[2].id=gpt-4o-mini
#agent.llm.models[2].display-name=GPT-4o mini
#agent.llm.models[2].provider-id=openai-compat
#agent.llm.models[2].enabled=true
```

> **兼容性**：保留现有 `agent.llm.*` / `agent.chat.model` / `agent.embedding.*` 键不动，
> 让 `generateText` / `generateEmbedding` 老路径继续用旧字段，避免影响 Planner/RAG/Insight。
> 新增的 `agent.llm.providers/models` 只服务于 chat 多模型路由。

---

## 4. 组件设计（新增 / 改造）

### 4.1 配置绑定（新增）
`feature/agent/llm/LlmProperties.java`（`@ConfigurationProperties(prefix="agent.llm")`）
- 内部类 `Provider { id, displayName, type, List<Endpoint> endpoints }`
- 内部类 `Endpoint { baseUrl, apiKey, weight }`
- 内部类 `ModelDef { id, displayName, providerId, enabled }`
- 字段：`defaultModel`、`List<Provider> providers`、`List<ModelDef> models`

### 4.2 模型目录（新增）
`feature/agent/llm/ModelCatalog.java`（`@Component`）
- `List<ModelDef> availableModels()`：过滤 `enabled=true` 的模型（给前端下拉用）
- `Optional<ModelDef> find(String modelId)`
- `Provider providerOf(String modelId)`
- `String defaultModelId()`
- 启动时校验：每个 model 的 providerId 必须存在，否则日志告警并剔除。

### 4.3 负载均衡器（新增）
`feature/agent/llm/EndpointBalancer.java`（`@Component`）
- 输入：一个 `Provider`；输出：本次调用选用的 `Endpoint`。
- **策略（简单）**：加权轮询（Weighted Round-Robin）。
  - 每个 provider 维护一个 `AtomicInteger` 计数器，`index = counter.getAndIncrement()`，按权重展开的列表取模。
  - endpoint 只有 1 个时直接返回（绝大多数情况）。
- **可选增强（写进"后续增强"，本期可不做）**：失败熔断——某 endpoint 连续失败则短暂降权/剔除。

### 4.4 模型路由器（新增）
`feature/agent/llm/LlmRouter.java`（`@Component`）
- `ResolvedTarget resolveForCurrentUser()`：
  1. `User u = AgentContext.requireUser()`
  2. 读 `UserPreference.agentChatModelId`（经 `UserPreferenceService.getOrCreatePreference`）
  3. 校验该 modelId 仍 `enabled`；无效/为空 → 回落 `catalog.defaultModelId()`
  4. `Provider p = catalog.providerOf(modelId)` → `Endpoint e = balancer.pick(p)`
  5. 返回 `ResolvedTarget { modelId, baseUrl, apiKey, providerType }`
- `record ResolvedTarget(...)`

### 4.5 `LlmGateway` 改造（核心）
- **`generateChat(messages)`**：内部改为
  ```
  ResolvedTarget t = router.resolveForCurrentUser();
  // 用 t.baseUrl / t.apiKey / t.modelId 组装请求
  ```
  取代原先固定的 `baseUrl/apiKey/chatModelOverride`。
- **`generateText` / `generateEmbedding`**：**保持不变**（仍用旧 `@Value` 字段），因为它们在无 `AgentContext` 的线程也会被调用（如 Scheduler、异步索引），不能强依赖当前用户。
- 抽出私有方法 `postChat(baseUrl, apiKey, model, messages, temperature)` 复用 HTTP 逻辑，`generateChat` 与旧路径都调它。
- 错误信息带上 provider/model，便于定位（如 `"[deepseek/deepseek-chat] HTTP 402 Insufficient Balance"`）。

> **子代理**：`SubAgentRunner.generateChat` 无需改动——它调的还是同一个 `LlmGateway.generateChat`，
> 由于子代理运行在主用户的 worker 线程（`AgentContext` 已 set 为同一 user），会自动沿用该用户选中的模型。

### 4.6 持久化（改造）
- `UserPreference` 新增字段：
  ```java
  /** Agent 对话选用的模型 id（对应 ModelCatalog 中的 Model.id）；null=用系统默认 */
  @Column(name = "agent_chat_model_id", length = 100, nullable = true)
  private String agentChatModelId;
  ```
- 迁移脚本 `db/migration/V7__agent_chat_model.sql`：
  ```sql
  ALTER TABLE user_preference ADD COLUMN agent_chat_model_id VARCHAR(100) NULL;
  ```

### 4.7 REST 接口（扩展现有 `AgentSettingsController`）
沿用 `/agent/settings` 前缀，新增两个端点：
- `GET /agent/settings/models`
  返回：
  ```json
  {
    "models": [
      {"id":"deepseek-chat","displayName":"DeepSeek Chat（推荐）","provider":"DeepSeek"},
      {"id":"deepseek-reasoner","displayName":"DeepSeek Reasoner（深度推理）","provider":"DeepSeek"}
    ],
    "current": "deepseek-chat"
  }
  ```
- `PUT /agent/settings/model`
  请求：`{"modelId":"deepseek-reasoner"}`
  行为：校验 modelId 属于 `availableModels()`，写入 `UserPreference.agentChatModelId`；非法则 400。

### 4.8 前端（改造 `agent-panel.html` + `chat-panel.js` + `chat-panel.css`）
- 在 header 现有「模式下拉」旁加一个**模型下拉** `<select id="lp-agent-model">`。
- JS：
  - 面板初始化时 `GET /agent/settings/models` 填充下拉并选中 `current`。
  - `change` 事件 → `PUT /agent/settings/model`，成功后轻提示（复用现有 status 样式）。
  - 若 `models` 为空（极端情况）→ 下拉禁用并显示"无可用模型"。
- 无需改 WebSocket 协议：**用户发的 chat 帧不带 modelId**，模型由后端按用户偏好解析（避免前端篡改、也让子代理天然一致）。

---

## 5. 请求流程（改造后）

```
用户在面板选模型
   └─ PUT /agent/settings/model → UserPreference.agentChatModelId

用户发消息 (WS chat)
   └─ Handler 派生 worker 线程: AgentContext.set(user, sid)
        └─ AgentOrchestrator.handleUserTurn
             └─ llm.generateChat(msgs)
                  └─ router.resolveForCurrentUser()
                       ├─ 读 user.pref.agentChatModelId（无效→default）
                       ├─ catalog.providerOf(modelId)
                       └─ balancer.pick(provider) → endpoint（加权轮询）
                  └─ postChat(endpoint.baseUrl, endpoint.apiKey, modelId, msgs)
```

多用户并发时：每个 worker 线程各自 `AgentContext.requireUser()`，互不干扰 → **天然按用户路由**；
同模型多 endpoint 时轮询分摊 → **简单负载均衡**。

---

## 6. 影响面与风险

| 项 | 说明 | 处理 |
|----|------|------|
| 老 `generateText`/`generateEmbedding` | 被 Scheduler/异步线程调用，无 `AgentContext` | **不改**，继续用旧 `@Value` 字段 |
| `chatModelOverride`（旧 `agent.chat.model`） | 被 `generateChat` 使用 | 保留为「default-model 未配置时的兜底」 |
| reasoner 干扰 JSON | `deepseek-reasoner` 输出 `<think>` 段 | 现有 `ToolCallParser.cleanForDisplay` 已剥离；上架 reasoner 需回归验证工具调用解析 |
| 无 `AgentContext` 却调 `generateChat` | 理论上不会（chat 只在 worker 线程）| `resolveForCurrentUser` 捕获异常 → 回落 default + 首个 endpoint |
| 配置错误（model 指向不存在 provider）| 启动期 | `ModelCatalog` 启动校验并剔除+告警 |
| 多用户 Key 额度 | 某用户选的模型 Key 402 | 错误信息带 provider/model；负载均衡多 endpoint 可缓解 |
| 前端下拉为空 | openai-compat 未配模型时正常 | 下拉仅显示 enabled 模型；至少 deepseek 两项 |

---

## 7. 执行计划（分阶段）

**阶段 1 · 配置与绑定**
1. `application.properties` 增加 `agent.llm.providers/models/default-model`（DeepSeek 真实 + OpenAI-Compatible 骨架）。
2. 新增 `LlmProperties`（`@ConfigurationProperties`）+ 在启动类或 config 上 `@EnableConfigurationProperties`。

**阶段 2 · 路由内核**
3. 新增 `ModelCatalog`（可选模型目录 + 启动校验）。
4. 新增 `EndpointBalancer`（加权轮询）。
5. 新增 `LlmRouter`（`resolveForCurrentUser` + `ResolvedTarget`）。

**阶段 3 · 网关改造**
6. `LlmGateway`：抽 `postChat` 私有方法；`generateChat` 改为走 `router`；`generateText`/`generateEmbedding` 不动。

**阶段 4 · 持久化 + 接口**
7. `UserPreference` 加 `agentChatModelId` 字段；新增 `V7__agent_chat_model.sql`。
8. `AgentSettingsController` 加 `GET /agent/settings/models`、`PUT /agent/settings/model`。

**阶段 5 · 前端**
9. `agent-panel.html` header 加模型下拉。
10. `chat-panel.js` 拉取/渲染/切换 + 保存。
11. `chat-panel.css` 下拉样式（与现有 mode 下拉一致）。

**阶段 6 · 联调验证**
- 单用户切换 chat/reasoner，确认实际命中的模型（可在日志打印 provider/model）。
- 双用户分别选不同模型并发发消息，验证互不串（路由正确）。
- provider 配 2 个 endpoint，多次调用验证轮询分摊。
- 子代理场景确认沿用主用户模型。
- 老路径（Planner 生成、RAG rerank、晨晚报总结、embedding）回归正常。
- 边界：用户选中的模型被下架（`enabled=false`）→ 自动回落 default。

---

## 8. 后续增强（本期不做，预留）

- **端点熔断/健康检查**：某 endpoint 连续失败自动降权/摘除，恢复后回补。
- **失败自动重试到同 provider 其他 endpoint**（402/5xx 时切 Key）。
- **每用户/每模型限流与配额统计**（审计用量）。
- **模型级参数**（temperature / max_tokens 随模型走，而非全局固定 0.2）。
- **管理员在 UI 动态增删 provider/model**（当前靠改配置 + 重启）。
- **provider.type 差异化报文**：若接入非 OpenAI 风格提供方（如 Anthropic 原生），按 type 分派不同 body 组装器。
