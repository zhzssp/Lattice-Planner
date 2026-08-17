# Lattice MCP Client 设计方案

> 让 Lattice-Planner 的 Agent 通过 MCP 协议连接外部 MCP Server（联网搜索、文件系统、数据库等），即插即用地扩展 Agent 能力。

---

## 1. 动机与价值

### 1.1 当前 Agent 的能力边界

当前 Agent 的工具全部来自 `@AgentTool` 注解的 Spring Bean，能力受限于本地代码：

| 局限 | 说明 |
|---|---|
| **无联网能力** | Agent 无法搜索互联网，知识仅限于用户本地数据 + LLM 内部知识 |
| **无外部数据源** | 无法访问数据库、日历、第三方 API 等 |
| **扩展成本高** | 每增加一种能力需要写 `@AgentTool` + 对应 Service + 数据库表 |
| **无法共享生态** | MCP 生态已有大量现成 Server（Brave Search、Filesystem、SQLite 等），重复造轮子 |

### 1.2 MCP Client 带来的价值

```
当前：Agent ←→ [20 个 @AgentTool] ←→ 本地 PKM 数据
未来：Agent ←→ [20 个 @AgentTool + N 个 MCP 远程工具] ←→ 本地 + 互联网 + 第三方服务
```

| 价值 | 说明 |
|---|---|
| **即插即用** | 配置一个 MCP Server URL，Agent 自动获得其全部工具 |
| **生态复用** | 直接使用 Brave Search、Filesystem、SQLite 等现成 MCP Server |
| **零代码扩展** | 新增能力 = 新增一行配置，无需写 Java 代码 |
| **与 MCP Server 共存** | 项目同时是 MCP Server（暴露能力）和 MCP Client（消费能力），形成"记忆中枢" |

### 1.3 典型应用场景

| 场景 | 连接的 MCP Server | 效果 |
|---|---|---|
| 联网搜索 | Brave Search / Tavily / SerpAPI | Agent 回答问题时可引用最新网络信息 |
| 安全文件访问 | `@anthropic/mcp-server-filesystem` | Agent 访问指定目录下的文件（比 S4 的自实现更标准） |
| 结构化数据分析 | SQLite / PostgreSQL MCP Server | Agent 直接查询分析数据，做更灵活的统计报表 |
| 日程/邮件 | Google Calendar / Gmail MCP Server | Agent 读日历/邮件，与 PKM 的规划/复盘联动 |
| 知识问答 | Wikipedia / Arxiv MCP Server | Agent 检索学术资料，补充知识库 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     Lattice-Planner Agent                        │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry (扩展后)                    │  │
│  │                                                           │  │
│  │  ┌─────────────────┐  ┌────────────────────────────────┐ │  │
│  │  │ 本地 @AgentTool  │  │   MCP 远程工具 (McpToolProxy)  │ │  │
│  │  │  (反射调用)      │  │   (McpClientConnection 调用)   │ │  │
│  │  └─────────────────┘  └────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                  McpClientManager                          │  │
│  │  管理 N 个 McpClientConnection（每个对应一个远程 MCP Server）│  │
│  └──────────┬──────────────────┬────────────────────────┘  │
│             │                  │                              │
│             ▼                  ▼                              │
│  ┌─────────────────┐ ┌─────────────────┐                     │
│  │McpClientConn #1 │ │McpClientConn #2 │  ...                │
│  │(Brave Search)  │ │(Filesystem)    │                     │
│  └────────┬────────┘ └────────┬────────┘                     │
└───────────┼───────────────────┼──────────────────────────────┘
            │                   │
            ▼                   ▼
   ┌─────────────────┐ ┌─────────────────┐
   │  Brave Search    │ │  Filesystem     │
   │  MCP Server      │ │  MCP Server     │
   │  (第三方)        │ │  (第三方)       │
   └─────────────────┘ └─────────────────┘
```

### 2.2 核心设计原则

| 原则 | 说明 |
|---|---|
| **注册表统一** | MCP 远程工具与本地 `@AgentTool` 共享同一个 `ToolRegistry`，ReAct 循环零改动 |
| **透明调用** | `AgentOrchestrator` 不区分本地工具与远程工具，统一走 `registry.invoke()` |
| **声明式配置** | 通过 `application.properties` 声明 MCP Server 连接，无需写代码 |
| **容错隔离** | 单个 MCP Server 断连不影响其他工具，降级而非崩溃 |
| **命名空间** | 远程工具加 `mcp.{serverName}.` 前缀，避免与本地工具名冲突 |

---

## 3. 核心模块设计（实际实现）

### 3.1 SSE 客户端：`McpSseClient`

基于 Java 21 `HttpClient` 实现的轻量 SSE 客户端，与 MCP Server 端（`McpSseEndpoint`）对称。

**核心机制：**

| 机制 | 实现 |
|---|---|
| SSE 连接 | `HttpClient` GET 请求 + 独立守护线程解析 SSE 事件流 |
| JSON-RPC 请求/响应匹配 | `PendingResponse`（wait/notify）+ 递增 `requestId` |
| endpoint 事件解析 | 正则提取 `sid` 参数 |
| token 认证 | 附加 `?token=xxx` 到 SSE URL |

```java
public class McpSseClient {
    private final String sseUrl;
    private final String authToken;
    private final int connectTimeout;   // 默认 30s
    private final int callTimeout;      // 默认 30s
    private final ObjectMapper om;

    // SSE 读取线程
    private Thread sseThread;
    // 等待中的 JSON-RPC 响应：id → PendingResponse
    private final Map<Integer, PendingResponse> pendingRequests = new ConcurrentHashMap<>();

    /** 连接：GET {sseUrl} → SSE 事件流 → 收到 endpoint → POST initialize */
    public synchronized void connect() throws Exception;

    /** 发送 JSON-RPC 请求并等待响应（callTimeout 秒超时） */
    public Map<String, Object> sendRequest(String method, Map<String, Object> params) throws Exception;

    /** 调用远程工具 */
    public McpToolResult callTool(String toolName, Map<String, Object> args);

    /** 列出远程工具 */
    public List<McpRemoteTool> listTools(String serverName);

    /** 断开连接 */
    public synchronized void disconnect();
}
```

**SSE 解析流程：**
1. `GET {sseUrl}?token=xxx` → 获取 SSE 文本流
2. 守护线程逐行解析 `event:` + `data:` 行
3. `event:endpoint` → 记录 `messageEndpoint` + 解析 `sid`
4. `event:message` → 解析为 JSON-RPC 响应，匹配 `pendingRequests` 中的等待请求
5. 空行标记事件结束，触发处理

**PendingResponse 实现：** 使用 `wait/notify` 替代 `CompletableFuture`，零外部依赖：

```java
private static class PendingResponse {
    private volatile Map<String, Object> result;
    private volatile boolean completed;

    synchronized void complete(Map<String, Object> result) {
        this.result = result;
        this.completed = true;
        notifyAll();
    }

    synchronized Map<String, Object> get(Duration timeout) throws InterruptedException {
        if (completed) return result;
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!completed && System.currentTimeMillis() < deadline) {
            wait(Math.max(100, deadline - System.currentTimeMillis()));
        }
        return result;
    }
}
```

### 3.2 MCP 客户端连接：`McpClientConnection`

每个配置的远程 MCP Server 对应一个 `McpClientConnection` 实例，封装 `McpSseClient`：

```java
public class McpClientConnection {
    private final String serverName;
    private final String sseUrl;
    private McpSseClient sseClient;
    private List<McpRemoteTool> remoteTools;
    private volatile boolean connected = false;

    /** 连接（SSE + initialize + tools/list） */
    public void connect() throws Exception;

    /** 代理 tools/call 请求 */
    public McpToolResult callTool(String originalName, Map<String, Object> args);

    /** 断开连接 */
    public void disconnect();

    /** 重新连接（先断开再连） */
    public void reconnect() throws Exception;
}
```

### 3.3 远程工具元数据：`McpRemoteTool`

```java
public record McpRemoteTool(
    String fullName,        // mcp.{serverName}.{originalName}
    String originalName,    // 远程原始名
    String description,
    Map<String, Object> inputSchema,  // JSON Schema
    String serverName
) {
    /** 统一 tag，供 PromptBuilder 按模式过滤 */
    public List<String> tags() { return List.of("mcp"); }
}
```

### 3.4 远程调用结果：`McpToolResult`

```java
public record McpToolResult(
    List<Map<String, Object>> content,
    boolean isError
) {
    /** 从结果中提取文本内容（拼接所有 text 类型 content） */
    public String extractText();

    /** 构造一个错误结果 */
    public static McpToolResult error(String message);
}
```

### 3.5 工具注册桥接：`McpToolProxy`

**核心设计：将远程 MCP 工具注册到 `ToolRegistry`，调用时走 `McpClientConnection` 而非反射。**

**循环依赖解决方案：** `McpToolProxy` 构造时调用 `toolRegistry.setMcpProxy(this)`，`ToolRegistry` 持有 `Object` 类型引用（非 `McpToolProxy` 类型），调用时通过反射 `mcpProxy.getClass().getMethod("invoke", ...)` 间接调用，避免 `ToolRegistry` 直接依赖 MCP Client 包。

```java
@Component
public class McpToolProxy {
    private final McpClientManager clientManager;
    private final ToolRegistry toolRegistry;

    /** 构造时注入自身到 ToolRegistry */
    public McpToolProxy(McpClientManager clientManager, ToolRegistry toolRegistry, ObjectMapper om) {
        this.clientManager = clientManager;
        this.toolRegistry = toolRegistry;
        this.om = om;
        toolRegistry.setMcpProxy(this);  // 避免循环依赖
        clientManager.setReconnectCallback(this::onServerReconnect);
    }

    /** 启动后注册所有已连接 Server 的远程工具 */
    @EventListener(ApplicationReadyEvent.class)
    public void registerRemoteTools();

    /** 代理调用远程 MCP 工具（由 ToolRegistry.invokeMcp() 委托） */
    public Object invoke(String fullName, JsonNode args);

    /** Server 重连后重新注册其工具 */
    private void onServerReconnect(McpClientConnection conn);
}
```

**`invoke()` 流程：**
1. 解析 `mcp.{serverName}.{originalName}` → 提取 `serverName` + `originalName`
2. 从 `clientManager.getConnection(serverName)` 获取连接
3. 转换 `args`（`JsonNode` → `Map<String, Object>`）
4. `conn.callTool(originalName, argsMap)` → 返回 `McpToolResult`
5. `isError=true` → 返回错误 JSON；否则返回提取的文本内容

### 3.6 ToolRegistry 扩展

**对现有 `ToolRegistry` 的最小改动：**

```java
@Component
public class ToolRegistry {
    // 现有：本地 @AgentTool 注册表
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    // 新增：MCP 远程工具注册表（fullName → McpRemoteTool）
    private final Map<String, McpRemoteTool> mcpTools = new ConcurrentHashMap<>();

    // 新增：MCP 工具代理（Object 类型，避免循环依赖）
    private volatile Object mcpProxy;

    // ---- 新增方法 ----
    public void registerMcpTool(McpRemoteTool rt);
    public void unregisterMcpTools(String serverName);
    public void setMcpProxy(Object proxy);
    public Set<String> mcpToolNames();
    public Collection<McpRemoteTool> mcpToolsAll();
    public Collection<ToolDefinition> allWithMcp();

    // ---- 扩展方法 ----
    public ToolDefinition get(String name) {
        ToolDefinition local = tools.get(name);
        if (local != null) return local;
        McpRemoteTool mcp = mcpTools.get(name);
        return mcp != null ? rtToToolDef(mcp) : null;
    }

    public Object invoke(String name, JsonNode args) throws Exception {
        ToolDefinition local = tools.get(name);
        if (local != null) return invokeLocal(local, args);  // 原有逻辑
        McpRemoteTool mcp = mcpTools.get(name);
        if (mcp != null) return invokeMcp(name, args);      // MCP 远程调用
        throw new IllegalArgumentException("未知工具：" + name);
    }

    public List<Map<String, Object>> exportSchemas(Set<String> tagFilter) {
        // ... 原有本地工具导出 ...
        // 新增：MCP 远程工具（tagFilter 含 "mcp" 时导出）
        if (tagFilter == null || tagFilter.isEmpty() || tagFilter.contains("mcp")) {
            for (McpRemoteTool rt : mcpTools.values()) {
                entry.put("name", rt.fullName());
                entry.put("description", "[MCP/" + rt.serverName() + "] " + rt.description());
                entry.put("parameters", rt.inputSchema());
            }
        }
    }
}
```

**`invokeMcp()` 实现：** 通过反射调用 `McpToolProxy.invoke()`，避免 `ToolRegistry` 直接依赖 MCP Client 包：

```java
private Object invokeMcp(String name, JsonNode args) throws Exception {
    java.lang.reflect.Method invokeMethod = mcpProxy.getClass()
        .getMethod("invoke", String.class, JsonNode.class);
    return invokeMethod.invoke(mcpProxy, name, args);
}
```

**`rtToToolDef()` 转换：** 将 `McpRemoteTool` 转换为 `ToolDefinition` 视图，`requiresConfirm=false`，`tags=["mcp"]`。

### 3.7 连接管理器：`McpClientManager`

```java
@Component
public class McpClientManager {
    private final McpClientProperties properties;
    private final ObjectMapper om;
    private final List<McpClientConnection> connections = new CopyOnWriteArrayList<>();

    /** 启动时连接所有配置的 MCP Server */
    @EventListener(ApplicationReadyEvent.class)
    public void init();

    /** 定期健康检查 + 自动重连（每 30 秒） */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void healthCheck();

    /** 手动触发重连指定 Server */
    public void reconnectServer(String serverName) throws Exception;

    /** 重连后通知（回调机制，替代直接注入 McpToolProxy） */
    public void setReconnectCallback(ReconnectCallback callback);
}
```

**重连回调机制：** `McpClientManager` 不直接注入 `McpToolProxy`，而是通过 `ReconnectCallback` 函数式接口实现松耦合：

```java
@FunctionalInterface
public interface ReconnectCallback {
    void accept(McpClientConnection conn);
}
```

`McpToolProxy` 构造时注册回调：`clientManager.setReconnectCallback(this::onServerReconnect)`。

### 3.8 配置绑定：`McpClientProperties`

```java
@ConfigurationProperties(prefix = "mcp.client")
@Data
public class McpClientProperties {
    private boolean enabled = false;
    private Map<String, ServerConfig> servers = new LinkedHashMap<>();

    @Data
    public static class ServerConfig {
        private String url;            // SSE 端点 URL
        private String token;          // 可选认证 token
        private boolean enabled = true;
        private int connectTimeout = 30;  // 连接超时（秒）
        private int callTimeout = 30;      // 调用超时（秒）
    }
}
```

> **与设计方案差异：** 新增 `connectTimeout` 和 `callTimeout` 两个配置项，允许按 Server 粒度调整超时时间。

### 3.9 REST API：`McpClientRestController`

```java
@RestController
@RequestMapping("/api/mcp/client")
public class McpClientRestController {
    /** 列出所有 MCP Client 连接及其状态 */
    @GetMapping("/servers")
    public ResponseEntity<?> listServers();

    /** 手动触发重连 */
    @PostMapping("/servers/{name}/reconnect")
    public ResponseEntity<?> reconnect(@PathVariable String name);
}
```

**`GET /api/mcp/client/servers` 响应格式：**

```json
{
  "enabled": true,
  "servers": [
    {
      "name": "brave-search",
      "url": "http://localhost:3001/sse",
      "connected": true,
      "toolCount": 3,
      "tools": [
        { "name": "mcp.brave-search.web_search", "description": "联网搜索" }
      ]
    }
  ]
}
```

---

## 4. 命名空间设计

### 4.1 远程工具命名规则

远程 MCP 工具在 `ToolRegistry` 中注册时，使用 `mcp.{serverName}.{originalName}` 格式：

| MCP Server | 原始工具名 | 注册名 | 说明 |
|---|---|---|---|
| Brave Search | `web_search` | `mcp.brave-search.web_search` | 联网搜索 |
| Brave Search | `get_current_weather` | `mcp.brave-search.get_current_weather` | 天气查询 |
| Filesystem | `read_file` | `mcp.filesystem.read_file` | 读文件 |
| Filesystem | `write_file` | `mcp.filesystem.write_file` | 写文件 |
| SQLite | `query` | `mcp.sqlite.query` | SQL 查询 |

### 4.2 与本地工具的隔离

| 隔离维度 | 本地 @AgentTool | MCP 远程工具 |
|---|---|---|
| 命名 | `task.create`、`goal.list` | `mcp.brave-search.web_search` |
| Tag | `task`、`goal`、`kb`... | `mcp`（统一 tag） |
| 调用方式 | 反射 `method.invoke()` | `McpClientConnection.callTool()` |
| 确认机制 | `requiresConfirm=true` → 弹窗 | MCP 工具默认不弹窗（外部 Server 自行负责安全） |
| 出错处理 | 本地异常 → JSON 错误 | 远程超时/断连 → 降级提示 |
| 参数格式 | `@ToolParam` → JSON Schema | 远程 `inputSchema` 直接透传 |

### 4.3 PromptBuilder 集成

`PromptBuilder` 生成的系统 Prompt 中，MCP 工具自动出现在可用工具列表：

```
【可用工具】（必须使用工具完成读写操作，不要编造数据）
--- 本地工具 ---
task.create: 创建新任务（需用户确认）
goal.list: 列出活跃目标
--- MCP 远程工具（mcp.brave-search）---
mcp.brave-search.web_search: [MCP/brave-search] 联网搜索最新信息
--- MCP 远程工具（mcp.filesystem）---
mcp.filesystem.read_file: [MCP/filesystem] 读取文件内容
```

> MCP 工具描述自动加 `[MCP/{serverName}]` 前缀，便于 LLM 区分来源。

---

## 5. PromptBuilder 改造

### 5.1 工具描述格式适配

MCP 工具的 `inputSchema` 与本地 `@AgentTool` 的参数格式不同，需要统一导出。

**实际实现：** `ToolRegistry.exportSchemas()` 中直接将 MCP 工具的 `inputSchema` 作为 `parameters` 字段导出，无需额外转换。

### 5.2 tag 过滤更新

```java
// PromptBuilder.build() 实际改造
Set<String> tagFilter = switch (mode == null ? "chat" : mode) {
    case "plan"    -> Set.of("task", "goal", "planner", "kb", "read", "write", "subagent", "mcp");
    case "reflect" -> Set.of("task", "goal", "insight", "note", "kb", "read", "subagent", "mcp");
    case "learn"   -> Set.of("kb", "note", "read", "subagent", "mcp");  // 联网搜索对学习模式特别有用
    default        -> null;  // chat 模式 = 全部工具（含 MCP）
};
```

> MCP 工具统一打 `mcp` tag，各模式通过 tag 过截决定是否包含。`chat` 模式 `tagFilter=null` 表示包含全部工具。

---

## 6. 配置设计

### 6.1 配置项

```properties
# ==============================
# MCP Client（连接外部 MCP Server）
# 是否启用 MCP Client
mcp.client.enabled=false

# ---------- MCP Server: brave-search ----------
mcp.client.servers.brave-search.url=http://localhost:3001/sse
mcp.client.servers.brave-search.token=
mcp.client.servers.brave-search.enabled=true
# 可选：超时配置（秒），默认 30
#mcp.client.servers.brave-search.connect-timeout=30
#mcp.client.servers.brave-search.call-timeout=30

# ---------- MCP Server: filesystem ----------
mcp.client.servers.filesystem.url=http://localhost:3002/sse
mcp.client.servers.filesystem.token=
mcp.client.servers.filesystem.enabled=true

# ---------- MCP Server: sqlite ----------
mcp.client.servers.sqlite.url=http://localhost:3003/sse
mcp.client.servers.sqlite.token=
mcp.client.servers.sqlite.enabled=false
```

> 配置格式：`mcp.client.servers.{name}.url` / `.token` / `.enabled` / `.connect-timeout` / `.call-timeout`，其中 `{name}` 即为注册名前缀（如 `mcp.brave-search`）。

### 6.2 配置生效

- `McpClientProperties` 使用 `@ConfigurationProperties(prefix = "mcp.client")` + `@Component`
- 主应用类 `MemorandumApplication` 添加 `@EnableConfigurationProperties(McpClientProperties.class)`

---

## 7. 容错与降级

### 7.1 连接失败处理

| 场景 | 处理 |
|---|---|
| MCP Server 启动时不可达 | 日志 warn，跳过该 Server，不影响其他工具和 Agent 启动 |
| 运行中 MCP Server 断连 | 30s 定时健康检查 + 自动重连，期间调用该 Server 工具返回降级提示 |
| 工具调用超时 | 默认 30s 超时（`callTimeout` 配置），超时返回 `McpToolResult.error("MCP 调用失败：请求超时")` |
| 工具调用返回 `isError=true` | 原样透传错误信息给 Agent，让 LLM 自我纠偏 |
| SSE 流断开 | `McpSseClient` 设置 `connected=false`，下次健康检查触发重连 |
| 重连后重新注册 | `McpClientManager` 通过 `ReconnectCallback` 通知 `McpToolProxy`，先 `unregisterMcpTools()` 再 `registerToolsFromServer()` |

### 7.2 工具冲突处理

| 冲突类型 | 处理 |
|---|---|
| 远程工具名与本地工具名冲突 | 远程工具加 `mcp.` 前缀，天然隔离 |
| 两个 MCP Server 有同名工具 | 前缀不同（`mcp.brave-search.web_search` vs `mcp.tavily.web_search`） |
| MCP Server 不响应 `tools/list` | 视为无工具可用，记录 warn 日志 |

### 7.3 超时控制链

```
AgentOrchestrator.handleUserTurn()
  └─ registry.invoke(name, args)           // 无超时限制
       └─ invokeMcp(name, args)
            └─ mcpProxy.invoke(name, args)
                 └─ McpToolProxy.invoke()
                      └─ conn.callTool(originalName, argsMap)
                           └─ McpSseClient.callTool()
                                └─ sendRequest("tools/call", params)
                                     └─ PendingResponse.get(callTimeout)  // ← 默认 30s
```

> MCP 工具调用内部自带超时，超时后返回 `McpToolResult(isError=true)`，不影响 ReAct 循环。

---

## 8. 管理 UI

### 8.1 在 MCP 设置页增加「🌐 MCP 远程连接（Client）」区域

在现有 `mcp-settings.html` 页面底部增加：

| 功能 | 说明 |
|---|---|
| 已配置的 MCP Server 列表 | 展示名称、URL、连接状态（🟢已连接 / 🔴未连接） |
| 发现的远程工具数量和列表 | 如 `brave-search: 3 个工具`，展开显示工具名+描述 |
| 手动重连按钮 | 对未连接的 Server 触发重连（`POST /api/mcp/client/servers/{name}/reconnect`） |
| 未启用提示 | `mcp.client.enabled=false` 时显示提示信息 |
| 配置入口 | 提示用户在 `application.properties` 中添加新 Server 配置 |

> MCP Client 的 Server 配置属于管理员/开发者操作（需要知道 URL 和 Token），不适合在 UI 上动态增删。初始版本通过配置文件管理即可。

### 8.2 REST API

| 端点 | 方法 | 说明 |
|---|---|---|
| `GET /api/mcp/client/servers` | GET | 列出所有 MCP Client 连接及其状态（含工具列表） |
| `POST /api/mcp/client/servers/{name}/reconnect` | POST | 手动触发重连 |

### 8.3 安全配置

`WebSecurityConfig` 中已做以下调整：
- `/api/mcp/client/**` 加入 CSRF 豁免
- `/api/mcp/client/**` 需要登录认证

---

## 9. AgentOrchestrator 改造

### 9.1 改动点

`AgentOrchestrator` 的 `handleUserTurn()` 方法中，工具调用走 `registry.invoke(name, args)`，**无需修改**——因为 `ToolRegistry.invoke()` 已扩展为本地反射 + MCP 代理两条路径。

> **结论：`AgentOrchestrator` 零改动。**

---

## 10. 文件结构（实际实现）

```
src/main/java/org/zhzssp/memorandum/feature/agent/mcp/
├── client/                                   # 新增：MCP Client 子包
│   ├── McpClientManager.java                 # 连接管理器（生命周期 + 健康检查 + 重连回调）
│   ├── McpClientConnection.java              # 单个远程 Server 的连接实例（封装 McpSseClient）
│   ├── McpSseClient.java                     # SSE 客户端（HttpClient + PendingResponse）
│   ├── McpRemoteTool.java                    # 远程工具元数据 record
│   ├── McpToolProxy.java                     # 远程工具→ToolRegistry 桥接（含重连处理）
│   ├── McpToolResult.java                    # 远程调用结果 record
│   ├── McpClientProperties.java              # 配置绑定（@ConfigurationProperties）
│   └── McpClientRestController.java          # REST API（状态查询 + 手动重连）
│
├── (现有 MCP Server 文件不动)
│   ├── McpSseEndpoint.java
│   ├── McpAuthService.java
│   ├── McpSessionCtx.java
│   ├── McpToolAdapter.java
│   ├── McpResourceAdapter.java
│   ├── McpLocalFileService.java
│   ├── entity/McpToken.java
│   ├── repository/McpTokenRepository.java
│   └── controller/...

改动的现有文件：
├── tool/ToolRegistry.java                    # 增加 mcpTools Map + MCP 注册/调用方法
├── runtime/PromptBuilder.java                # tagFilter 增加 "mcp"
├── MemorandumApplication.java                # 增加 @EnableConfigurationProperties
├── config/WebSecurityConfig.java             # CSRF + 认证路径调整
└── resources/application.properties           # 增加 mcp.client.* 配置
└── resources/templates/mcp-settings.html      # 增加客户端连接区域
```

---

## 11. 落地阶段

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **C1 核心连接** | `McpSseClient` + `McpClientConnection` + `McpClientManager` | 能连接远程 MCP Server 并 `tools/list` | ✅ 已完成 |
| **C2 工具注册** | `McpToolProxy` + `ToolRegistry` 扩展 + `McpRemoteTool` + `McpToolResult` | 远程工具自动注册到 Agent 可用工具集 | ✅ 已完成 |
| **C3 配置+容错** | `McpClientProperties` + 健康检查 + 超时降级 + 重连回调 | 声明式配置 + 自动重连 | ✅ 已完成 |
| **C4 Prompt+UI** | `PromptBuilder` 改造 + MCP 设置页客户端区域 + REST API | 远程工具出现在 Agent Prompt + 管理页面 | ✅ 已完成 |

### 验证方式

```properties
# application.properties
mcp.client.enabled=true
mcp.client.servers.filesystem.url=http://localhost:3002/sse
mcp.client.servers.filesystem.enabled=true
```

启动应用后，Agent 的工具列表应包含：
- 本地 20 个 `@AgentTool`
- 远程 `mcp.filesystem.read_file`、`mcp.filesystem.write_file` 等

在聊天面板中对话：「帮我读取 /tmp/test.txt 的内容」→ Agent 应调用 `mcp.filesystem.read_file`。

---

## 12. 与 MCP Server 的共存

MCP Server（已实现）和 MCP Client（本方案）在同一进程中并行运行，互不干扰：

```
Lattice-Planner
┌──────────────────────────────────────────────────┐
│  MCP Server（暴露能力）                           │
│  McpSseEndpoint ← Claude Desktop / Cursor / ...  │
│  让外部 AI 操作你的 PKM 数据                      │
├──────────────────────────────────────────────────┤
│  MCP Client（消费能力）                           │
│  McpClientManager → Brave Search / Filesystem /..│
│  让你的 Agent 获得外部能力                        │
├──────────────────────────────────────────────────┤
│  共享层：ToolRegistry（统一注册表）               │
│  本地 @AgentTool + MCP 远程工具 → 统一 invoke()   │
└──────────────────────────────────────────────────┘
```

**终极形态：Lattice-Planner 成为 AI 生态的"记忆中枢"**

```
Claude Desktop ──MCP──→ [Lattice Server] ←──读── PKM 数据
Cursor / Cline ──MCP──→ [Lattice Server]
                              │
                              ↓ 调用
                     [MCP Client: Brave Search, Filesystem, ...]
                              │
                              ↓ 获取
                     [互联网信息 / 外部文件 / 第三方数据]
```

---

## 13. 与现有架构的兼容性（实际实现）

| 现有模块 | 是否改动 | 说明 |
|---|---|---|
| `ToolRegistry` | **最小改动** | 增加 `mcpTools` Map + `registerMcpTool()` + `invoke()` 拆分两条路径 + `exportSchemas()` 扩展 |
| `@AgentTool` 工具类 | **零改动** | 本地工具注册与反射调用逻辑完全不变 |
| `AgentOrchestrator` | **零改动** | 统一走 `registry.invoke()`，不区分本地/远程 |
| `ToolCallParser` | **零改动** | 解析 `mcp.xxx.yyy` 格式无特殊处理 |
| `PromptBuilder` | **最小改动** | `tagFilter` 各模式增加 `mcp` tag |
| `AgentContext` | **零改动** | MCP Client 调用时 AgentContext 已由 WS Handler 注入 |
| `LlmGateway` | **零改动** | MCP Client 走 HTTP，与 LLM 调用无关 |
| `MCP Server` 模块 | **零改动** | Server 与 Client 完全独立 |
| `SubAgentRunner` | **零改动** | 子代理调用 MCP 工具通过 `ToolRegistry` 自动支持 |
| `MemorandumApplication` | **最小改动** | 增加 `@EnableConfigurationProperties(McpClientProperties.class)` |
| `WebSecurityConfig` | **最小改动** | CSRF 豁免增加 `/api/mcp/client/**` |

---

## 14. 设计方案 vs 实际实现差异

| # | 设计方案 | 实际实现 | 原因 |
|---|---|---|---|
| 1 | `McpClientManager` 直接注入 `McpToolProxy` | 使用 `ReconnectCallback` 函数式接口 | 避免循环依赖：`McpToolProxy → McpClientManager → McpToolProxy` |
| 2 | `ToolRegistry.registerMcpTool(rt, proxy)` | `ToolRegistry.registerMcpTool(rt)` + `setMcpProxy(proxy)` | 分离注册和代理注入，proxy 只需设一次 |
| 3 | `ToolRegistry.mcpProxy` 类型为 `McpToolProxy` | 类型为 `Object`，通过反射调用 | 避免 `ToolRegistry` 依赖 `mcp.client` 包 |
| 4 | `McpRemoteTool.toToolDefinition()` | `ToolRegistry.rtToToolDef(McpRemoteTool)` | `ToolDefinition` 在 `tool` 包，`McpRemoteTool` 不依赖它 |
| 5 | `ServerConfig` 2 个字段（url/token/enabled） | 5 个字段（+connectTimeout/callTimeout） | 按需配置超时，不同 Server 网络环境不同 |
| 6 | `McpToolResult` 未定义 | 新增 `McpToolResult` record | 标准化 MCP 工具调用结果，含 `isError` + `extractText()` |
| 7 | `McpClientRestController` 未设计 | 新增 REST Controller | 提供 `/api/mcp/client/servers` + `/servers/{name}/reconnect` |
| 8 | `McpClientConnection` 含 `SseEmitter.Listener` | 封装 `McpSseClient` 实例 | `SseEmitter.Listener` 不存在于客户端，SSE 解析由 `McpSseClient` 独立实现 |
| 9 | `McpSseClient` 使用 `Map.of()` 构建请求 | 使用 `LinkedHashMap` 构建 | `Map.of()` 不允许 null/empty values，JSON-RPC params 需要空 Map |

---

## 15. 已知限制与后续优化

| 项 | 当前实现 | 优化方向 |
|---|---|---|
| SSE 客户端 | 基于 `HttpClient` 的简单实现 | 后续可引入 `spring-ai-mcp-client` starter |
| 工具发现时机 | 仅启动时 `tools/list` 一次 | 支持 `tools/list` 变更通知（MCP notifications） |
| 认证 | 仅支持 query param token | 支持 OAuth2 / API Key header 等更多认证方式 |
| UI 管理 | 只读状态展示 + 配置文件管理 | UI 动态增删 MCP Server 连接 |
| 工具选择策略 | 全部注册，LLM 自行选择 | 支持 Agent 侧按场景过滤（如"只在 learn 模式用联网搜索"） |
| 并发调用 | 单线程串行 | MCP 工具调用可并行（类似 `subagent.parallel_research`） |
| 缓存 | 无 | 高频工具结果缓存（如天气查询 5 分钟内复用） |
| SSE 重连 | 完整重连（disconnect + connect） | 支持增量重连（保留已发现的工具列表） |
