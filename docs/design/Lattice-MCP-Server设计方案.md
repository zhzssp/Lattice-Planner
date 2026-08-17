# Lattice MCP Server 设计方案

> 让外部 AI 工具（Claude Desktop / Cursor / Cline 等）通过 MCP 协议直接操作 Lattice-Planner 的 PKM 数据与能力。

---

## 1. 现状分析

### 1.1 当前工具清单（30 个 @AgentTool）

| 领域 | 工具 | tag | 写? | 依赖 |
|---|---|---|---|---|
| **目标** | `goal.list` | goal | ✗ | DB |
| | `goal.list_all` | goal | ✗ | DB |
| | `goal.create` | goal | ✓ | DB |
| | `goal.archive` | goal | ✓ | DB |
| | `goal.link_task` | goal | ✓ | DB |
| **任务** | `task.create` | task | ✓ | DB |
| | `task.search` | task | ✗ | DB |
| | `task.today` | task | ✗ | DB |
| | `task.fuzzy_pending` | task | ✗ | DB |
| | `task.complete` | task | ✓ | DB |
| | `task.archive` | task | ✓ | DB |
| **笔记** | `note.list` | note | ✗ | DB |
| | `note.create` | note | ✓ | DB |
| **知识库** | `kb.semantic_search` | kb | ✗ | DB+Embedding |
| | `kb.lookup_by_title` | kb | ✗ | DB |
| | `kb.list_backlinks` | kb | ✗ | DB |
| | `kb.ingest_local_doc` | kb+local | ✓ | **Electron Bridge** |
| | `kb.list_ingested_docs` | kb | ✗ | DB |
| | `kb.delete_local_doc` | kb+local | ✓ | **Electron Bridge** |
| **洞察** | `insight.daily_scores` | insight | ✗ | DB+LLM |
| | `insight.summarize_period` | insight | ✗ | DB+LLM |
| **规划** | `planner.draft_goal_plan` | planner | ✗ | LLM |
| | `planner.apply_goal_plan` | planner | ✓ | DB |
| **本地文件** | `local.list_dir` | local | ✗ | **Electron Bridge** |
| | `local.read_file` | local | ✗ | **Electron Bridge** |
| | `local.read_pdf` | local | ✗ | **Electron Bridge** |
| **子代理** | `subagent.plan` | subagent | ✗ | AgentContext.depth |
| | `subagent.reflect` | subagent | ✗ | AgentContext.depth |
| | `subagent.research` | subagent | ✗ | AgentContext.depth |
| | `subagent.parallel_research` | subagent | ✗ | AgentContext.depth |

### 1.2 MCP 暴露策略：三级筛选

| 级别 | 范围 | 数量 | 理由 |
|---|---|---|---|
| **Tier 1 暴露** | 纯 DB / LLM 工具（无本地依赖） | 20 | 核心价值，安全可暴露 |
| **Tier 2 可选暴露** | 依赖 Electron Bridge 的本地工具 | 5 | 需后端独立实现磁盘 IO 替代 Bridge |
| **Tier 3 不暴露** | 子代理工具 | 4 | MCP Client 本身就是"智能体"，无需委派子代理 |

**Tier 1 工具清单（首批实现）：**

```
goal.list, goal.list_all, goal.create, goal.archive, goal.link_task
task.create, task.search, task.today, task.fuzzy_pending, task.complete, task.archive
note.list, note.create
kb.semantic_search, kb.lookup_by_title, kb.list_backlinks, kb.list_ingested_docs
insight.daily_scores, insight.summarize_period
planner.draft_goal_plan, planner.apply_goal_plan
```

> 共 20 个工具（含 planner 的 2 个）。

**Tier 2 工具清单（S4 实现，需开启 `mcp.server.local-files-enabled=true`）：**

```
local.list_dir, local.read_file, local.read_pdf
kb.ingest_local_doc, kb.delete_local_doc
```

> 共 5 个工具。由 `McpLocalFileService` 独立实现，不依赖 Electron Bridge，通过白名单目录 + 扩展名校验保障安全。

### 1.3 关键约束

| 约束 | 说明 | 解决方案 |
|---|---|---|
| **多用户隔离** | 现有工具靠 `AgentContext.requireUser()` 获取当前用户 | MCP 调用时通过 API Token → User 映射注入 AgentContext |
| **写操作确认** | `requiresConfirm=true` 的工具在内部 Agent 走 WS 弹窗 | MCP 侧默认信任写操作（Token 已认证），工具描述中标注「需用户确认」提示 |
| **Electron Bridge** | `local.*` + `kb.ingest/delete_local_doc` 依赖 Electron preload | S4 阶段用后端直接文件 IO 替代 Bridge 调用 |
| **子代理** | 依赖 `AgentContext.depth()` 防递归 | 不暴露；MCP Client 本身具备推理能力 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  外部 MCP Client（Claude Desktop / Cursor / Cline / 自定义客户端） │
└──────────────────────┬──────────────────────────────────────────┘
                       │ MCP Protocol (SSE + HTTP POST / JSON-RPC 2.0)
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Lattice-Planner (Spring Boot)                │
│                                                                  │
│  ┌──────────────────┐  ┌─────────────────┐  ┌───────────────┐  │
│  │  McpRestController│  │ McpAuthService  │  │ McpToolAdapter│  │
│  │  /sse + /mcp/msg │  │ Token→User 映射  │  │ @AgentTool→MCP│  │
│  └────────┬─────────┘  └────────┬────────┘  └───────┬───────┘  │
│           │                     │                    │           │
│           └──────────┬──────────┘                    │           │
│                      ▼                               │           │
│           ┌──────────────────┐                       │           │
│           │  McpSseEndpoint  │                       │           │
│           │  (JSON-RPC 2.0  │                       │           │
│           │   消息分发)      │                       │           │
│           └────────┬─────────┘                       │           │
│                    │                                  │           │
│                    ▼                                  │           │
│           ┌──────────────────┐                       │           │
│           │   McpSessionCtx  │                       │           │
│           │  (per-connection │                       │           │
│           │   user context)  │                       │           │
│           └────────┬─────────┘                       │           │
│                    │                                  │           │
│                    ▼                                  ▼           │
│           ┌──────────────────────────────────────────────┐      │
│           │           ToolRegistry (现有，零改动)          │      │
│           │  invoke(name, args) → 反射调用 @AgentTool     │      │
│           └──────────────────────────────────────────────┘      │
│                              │                                  │
│                              ▼                                  │
│           ┌──────────────────────────────────────────────┐      │
│           │    现有 Service 层（GoalService / TaskService  │      │
│           │    NoteService / RagSearchService / ...）      │      │
│           └──────────────────────────────────────────────┘      │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  McpLocalFileService (S4, 仅当 mcp.server.local-files-   │  │
│  │  enabled=true 时激活，替代 Electron Bridge 的磁盘 IO)      │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 技术选型：自实现 JSON-RPC 2.0（不引入 MCP SDK）

最终选择 **不引入第三方 MCP SDK**，而是直接基于 Spring `SseEmitter` + 手写 JSON-RPC 2.0 消息分发：

| 对比项 | 使用 MCP SDK | 自实现（当前方案） |
|---|---|---|
| 依赖 | `io.modelcontextprotocol:sdk-java` + `mcp-spring-webmvc` | 零新增依赖 |
| 协议支持 | 完整 MCP spec（含 notifications、sampling 等） | 核心 6 个 method（initialize / ping / tools/list / tools/call / resources/list / resources/read） |
| 传输层 | SDK 封装 SSE transport | Spring `SseEmitter` + `@RestController` |
| 消息格式 | SDK 的 Request/Result 类型 | `Map<String, Object>` + 手写 JSON-RPC |
| 代码量 | 较少（SDK 做了封装） | 略多（但更透明可控） |
| 兼容性 | 受 SDK 版本约束 | 与 Claude Desktop / Cursor / Cline 等标准客户端实测兼容 |

**选择理由：**
- 项目不用 Spring AI 的 ChatClient 体系，引入 SDK 只为 SSE+JSON-RPC 太重
- 实际需要的方法只有 6 个，手写 switch 分发更直观
- 零依赖意味着零版本冲突风险
- `McpSseEndpoint` 总代码 ~200 行，可维护性足够

### 2.3 传输层：SSE（Server-Sent Events）

| 端点 | 方法 | 用途 |
|---|---|---|
| `GET /sse?token=lattice_xxx` | SSE 长连接 | MCP Client 建立连接，接收服务端推送事件 |
| `POST /mcp/message?sid=xxx` | HTTP POST (JSON-RPC 2.0) | MCP Client 发送请求（tools/list, tools/call 等） |

```
Claude Desktop ──GET /sse?token=──→  ┌─────────────┐
               ←──SSE endpoint事件── │  Lattice     │
               ──POST /mcp/message──→│  MCP Server  │
               ←──SSE response事件── │              │
                                     └─────────────┘
```

**连接生命周期：**

1. Client 发起 `GET /sse?token=lattice_xxx`
2. `McpAuthService` 验证 token → 创建 `McpSessionCtx`（含 User + UUID sessionId）
3. 建立 `SseEmitter`（无超时），发送 `endpoint` 事件（告知 POST 地址）
4. 后续 Client 通过 `POST /mcp/message?sid=xxx` 发送 JSON-RPC 请求
5. Server 处理请求，通过同一 `SseEmitter` 推送 JSON-RPC 响应
6. 连接断开时自动清理 `emitters` + `sessions` 缓存

**为什么选 SSE 而非 stdio：**
- 项目是 Spring Boot Web 应用，天然支持 SSE
- 多 MCP Client 可同时连接（stdio 只能单进程 1:1）
- SSE 走 HTTP，便于后续加 TLS / 反代 / 网关鉴权

---

## 3. 核心模块设计

### 3.1 认证：`McpAuthService`

MCP 协议本身不定义认证，我们用 **API Token** 方案：

```
MCP Client 连接时：
  GET /sse?token=lattice_xxx
```

```java
@Component
public class McpAuthService {

    private final McpTokenRepository tokenRepo;
    private static final String TOKEN_PREFIX = "lattice_";

    /**
     * 验证 token → 返回对应 User。
     * token 格式：lattice_{random64hex}
     * 存储在 mcp_token 表：id | user_id | token_hash | label | created_at | last_used_at
     */
    public User authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new McpAuthException("无效的 MCP Token 格式（需 lattice_ 前缀）");
        }
        String hash = sha256(token);
        McpToken t = tokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new McpAuthException("MCP Token 不存在或已吊销"));
        t.setLastUsedAt(LocalDateTime.now());
        tokenRepo.save(t);
        return t.getUser();    // 通过 @ManyToOne 关联直接获取 User，无需额外查询
    }

    /** 生成新 token（明文仅展示一次）。 */
    public String generateToken(User user, String label) {
        String raw = TOKEN_PREFIX + generateRandomHex(32);
        String hash = sha256(raw);
        McpToken t = new McpToken();
        t.setUser(user);
        t.setTokenHash(hash);
        t.setLabel(label);
        t.setCreatedAt(LocalDateTime.now());
        tokenRepo.save(t);
        return raw;
    }

    /** 吊销 token。 */
    public void revokeToken(Long tokenId, Long userId) {
        tokenRepo.deleteByIdAndUserId(tokenId, userId);
    }
}
```

> **实现细节：** `McpToken` 实体通过 `@ManyToOne(fetch = LAZY) User user` 关联，`authenticate()` 直接 `t.getUser()` 获取用户，无需额外注入 `UserRepository`。

### 3.2 会话上下文：`McpSessionCtx`

核心问题：现有 `@AgentTool` 依赖 `AgentContext.requireUser()`（ThreadLocal），MCP 调用时没有 WS session。

```java
/**
 * MCP 连接级会话：持有已认证的 User，在工具调用前注入 AgentContext。
 * 每个 SSE 连接对应一个 McpSessionCtx 实例。
 */
public class McpSessionCtx {
    private final User user;
    private final String sessionId;  // UUID，用于 AgentContext.set(user, sessionId)

    public McpSessionCtx(User user) {
        this.user = user;
        this.sessionId = UUID.randomUUID().toString();
    }

    /** 在 MCP 工具调用前包裹，注入 AgentContext，调用后清理。 */
    public <T> T withContext(Supplier<T> action) {
        AgentContext.set(user, sessionId);
        try {
            return action.get();
        } finally {
            AgentContext.clear();
        }
    }

    /** 无返回值版本。 */
    public void withContext(Runnable action) {
        AgentContext.set(user, sessionId);
        try {
            action.run();
        } finally {
            AgentContext.clear();
        }
    }
}
```

### 3.3 消息分发：`McpSseEndpoint`

**核心设计：手写 JSON-RPC 2.0 分发，不依赖 MCP SDK。**

```java
@Component
public class McpSseEndpoint {

    private final McpAuthService authService;
    private final McpToolAdapter toolAdapter;
    private final McpResourceAdapter resourceAdapter;
    private final McpLocalFileService localFileService;

    /** sessionId → SseEmitter + McpSessionCtx */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, McpSessionCtx> sessions = new ConcurrentHashMap<>();

    /** 建立 SSE 连接（GET /sse?token=xxx）。 */
    public SseEmitter connect(String token) {
        User user = authService.authenticate(token);
        McpSessionCtx ctx = new McpSessionCtx(user);
        String sid = ctx.getSessionId();

        SseEmitter emitter = new SseEmitter(0L); // 无超时
        emitters.put(sid, emitter);
        sessions.put(sid, ctx);

        // 发送 endpoint 事件，告知 Client POST 地址
        emitter.send(SseEmitter.event()
                .name("endpoint")
                .data(messagePath + "?sid=" + sid));
        return emitter;
    }

    /** 处理 JSON-RPC 请求（POST /mcp/message?sid=xxx）。 */
    public Map<String, Object> handleMessage(String sid, Map<String, Object> request) {
        McpSessionCtx ctx = sessions.get(sid);
        String method = (String) request.get("method");
        Object id = request.get("id");
        Map<String, Object> params = (Map) request.getOrDefault("params", Map.of());

        return switch (method) {
            case "initialize"      -> handleInitialize(ctx, id);
            case "ping"            -> Map.of("jsonrpc", "2.0", "id", id, "result", Map.of());
            case "tools/list"      -> handleToolsList(ctx, id);
            case "tools/call"      -> handleToolsCall(ctx, id, params);
            case "resources/list"  -> handleResourcesList(ctx, id);
            case "resources/read"  -> handleResourcesRead(ctx, id, params);
            default                -> errorResponse(id, -32601, "未知方法：" + method);
        };
    }
}
```

**`initialize` 响应示例：**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": {}, "resources": {} },
    "serverInfo": { "name": "lattice-planner", "version": "1.0.0" }
  }
}
```

### 3.4 工具适配：`McpToolAdapter`

**核心设计：不写任何新工具方法，复用现有 `ToolRegistry`。**

```java
@Component
public class McpToolAdapter {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper om;

    /** MCP 不暴露的 tag 集合。 */
    private static final Set<String> EXCLUDED_TAGS = Set.of("local", "subagent");

    /**
     * 导出 MCP tools/list 格式的工具描述。
     * 排除 local / subagent tag 的工具。
     * 返回 List<Map>（而非 SDK 的 Tool 类型），直接序列化为 JSON-RPC 响应。
     */
    public List<Map<String, Object>> exportMcpTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition td : toolRegistry.all()) {
            if (td.tags().stream().anyMatch(EXCLUDED_TAGS::contains)) continue;
            tools.add(buildToolEntry(td));
        }
        return tools;
    }

    /**
     * 执行 MCP tools/call：委托给 ToolRegistry.invoke()。
     * 调用方需确保已在 McpSessionCtx.withContext() 内。
     */
    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        try {
            JsonNode argsNode = om.valueToTree(args != null ? args : Collections.emptyMap());
            Object result = toolRegistry.invoke(toolName, argsNode);
            return Map.of("content", List.of(Map.of("type", "text",
                            "text", om.writeValueAsString(result))),
                    "isError", false);
        } catch (IllegalArgumentException e) {
            return errorResult("未知工具：" + toolName);
        } catch (Exception e) {
            return errorResult("工具调用失败：" + e.getMessage());
        }
    }
}
```

**`tools/list` 响应示例（截取）：**

```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "tools": [
      {
        "name": "goal.list",
        "description": "列出当前用户的活跃目标",
        "inputSchema": { "type": "object", "properties": {}, "required": [] }
      },
      {
        "name": "task.create",
        "description": "创建新任务（需用户确认）",
        "inputSchema": {
          "type": "object",
          "properties": { "title": { "type": "string", "description": "任务标题" } },
          "required": ["title"]
        }
      }
    ]
  }
}
```

### 3.5 本地文件服务：`McpLocalFileService`（S4）

**S4 阶段实现：后端独立磁盘 IO，替代 Electron Bridge。**

```java
@Component
public class McpLocalFileService {

    @Value("${mcp.server.local-files-enabled:false}")
    private boolean enabled;

    @Value("${mcp.server.local-allowed-dirs:}")
    private String allowedDirsConfig;   // 逗号分隔的白名单目录

    /** 导出 5 个本地文件工具描述。 */
    public List<Map<String, Object>> exportLocalTools() { ... }

    /** 执行本地文件工具调用。 */
    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "local.list_dir"  -> listDir((String) args.get("path"));
            case "local.read_file" -> readFile((String) args.get("path"));
            case "local.read_pdf"  -> readPdf((String) args.get("path"));
            default -> errorResult("未实现的本地工具：" + toolName);
        };
    }

    /** 安全校验：路径必须在白名单目录下。 */
    boolean isAllowed(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (Path allowed : getAllowedDirs()) {
            if (abs.startsWith(allowed.toAbsolutePath().normalize())) return true;
        }
        return getAllowedDirs().isEmpty(); // 空白名单 = 不允许任何路径
    }

    /** 安全校验：文件扩展名白名单。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "md", "txt", "json", "yml", "yaml", "xml", "csv", "html", "css", "js",
        "java", "py", "go", "rs", "c", "cpp", "h", "sh", "bat", "sql", "properties"
    );
}
```

> **PDF 解析说明：** 当前 `local.read_pdf` 使用字节级粗提取（过滤非文本字符），文本丰富时可工作。生产环境建议集成 Apache PDFBox 以获得精确解析。

---

## 4. MCP Resources 设计（只读数据端点）

MCP Resources 让 Client 可以**拉取**结构化数据，无需"调用工具"——适合大块只读数据的浏览式访问。

### 4.1 资源端点清单

| URI | 描述 | 对标工具 | 数据源 |
|---|---|---|---|
| `lattice://goals` | 当前用户活跃目标列表 | `goal.list` | `GoalService.findActiveGoalsByUser()` |
| `lattice://goals/all` | 全部目标（含归档） | `goal.list_all` | `GoalService.findGoalsByUser()` |
| `lattice://tasks/today` | 今日可行动任务 | `task.today` | `TaskService.getTodayActionableTasks()` |
| `lattice://notes` | 笔记列表 | `note.list` | `NoteService.listVisibleByUser()` |
| `lattice://insight/recent` | 最近 7 天得分 | `insight.daily_scores` | `InsightScoreService.calculateScores()` |

> **设计取舍：** 初版资源端点不含参数化 URI（如 `lattice://tasks/search?q=xxx`），因为 MCP Resource 的 URI 模板支持（`uriTemplate`）在不同客户端间兼容性不一。搜索类操作统一走 `tools/call`（如 `task.search`、`kb.semantic_search`），更可靠。

### 4.2 `McpResourceAdapter` 实现

```java
@Component
public class McpResourceAdapter {

    private final GoalService goalService;
    private final TaskService taskService;
    private final NoteService noteService;
    private final RagSearchService ragService;
    private final InsightScoreService insightService;
    private final ObjectMapper om;

    /** 列出可用的 MCP Resources。 */
    public List<Map<String, Object>> listResources() {
        return List.of(
            resource("lattice://goals", "活跃目标列表", "application/json"),
            resource("lattice://goals/all", "全部目标（含归档）", "application/json"),
            resource("lattice://tasks/today", "今日可行动任务", "application/json"),
            resource("lattice://notes", "笔记列表", "application/json"),
            resource("lattice://insight/recent", "最近 7 天得分", "application/json")
        );
    }

    /** 读取指定 URI 的资源（调用方需在 McpSessionCtx.withContext 内）。 */
    public Map<String, Object> readResource(String uri) throws Exception {
        User u = AgentContext.requireUser();
        Object data;
        switch (uri) {
            case "lattice://goals"        -> data = goalService.findActiveGoalsByUser(u);
            case "lattice://goals/all"    -> data = goalService.findGoalsByUser(u);
            case "lattice://tasks/today"  -> data = taskService.getTodayActionableTasks(u);
            case "lattice://notes"        -> data = noteService.listVisibleByUser(u);
            case "lattice://insight/recent" -> {
                LocalDate to = LocalDate.now();
                data = insightService.calculateScores(u, to.minusDays(7), to);
            }
            default -> { /* 返回未知资源提示 */ }
        }
        String json = om.writeValueAsString(data);
        return Map.of("contents", List.of(Map.of(
                "uri", uri, "mimeType", "application/json", "text", json)));
    }
}
```

> **注意：** `readResource()` 不接收 `McpSessionCtx` 参数，因为调用方已在 `withContext()` 内，`AgentContext.requireUser()` 可直接使用。

---

## 5. 配置段

```properties
# ==============================
# MCP Server（Model Context Protocol）
# 是否启用 MCP Server（SSE 端点 + 工具注册）
mcp.server.enabled=true
# SSE 端点路径（客户端连接地址：http://host:port/sse?token=lattice_xxx）
mcp.server.sse-path=/sse
# 消息端点路径
mcp.server.message-path=/mcp/message
# S4：本地文件访问（MCP 专用，替代 Electron Bridge）
# 开启后 MCP 客户端可调用 local.list_dir / local.read_file / local.read_pdf 等工具
mcp.server.local-files-enabled=false
# 本地文件白名单目录（逗号分隔），例如 D:/learning,C:/Users/docs
mcp.server.local-allowed-dirs=
```

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `mcp.server.enabled` | `true` | 全局开关，false 则 SSE/消息端点返回 404 |
| `mcp.server.sse-path` | `/sse` | SSE 连接端点路径 |
| `mcp.server.message-path` | `/mcp/message` | JSON-RPC 消息端点路径 |
| `mcp.server.local-files-enabled` | `false` | S4 开关，是否暴露本地文件工具 |
| `mcp.server.local-allowed-dirs` | 空（不允许任何路径） | S4 白名单，逗号分隔目录路径 |

---

## 6. 安全设计

### 6.1 认证流程

```
1. 用户在 Lattice-Planner Web UI → MCP 连接 → 生成 Token（lattice_开头的随机串）
2. 在 Claude Desktop 的 claude_desktop_config.json 中配置：
   {
     "mcpServers": {
       "lattice": {
         "url": "http://localhost:8080/sse?token=lattice_a1b2c3d4..."
       }
     }
   }
3. MCP Client 连接时，McpRestController 提取 token 参数
4. McpSseEndpoint.connect(token) → McpAuthService.authenticate(token) → McpSessionCtx
5. 后续所有 tools/call + resources/read 都在 McpSessionCtx.withContext() 内执行
```

### 6.2 权限矩阵

| 能力 | MCP Client 权限 | 说明 |
|---|---|---|
| 读取目标/任务/笔记 | ✅ 按用户隔离 | Token 绑定 User，只能看自己的数据 |
| 创建/修改/归档 | ✅ 默认信任 | Token 已认证 = 已授权，写操作直接执行 |
| 语义搜索 | ✅ | 只搜当前用户的索引 |
| 本地文件访问 | ❌ 默认禁止 | 需配置 `mcp.server.local-files-enabled=true` + 白名单目录 |
| 子代理委派 | ❌ 不暴露 | MCP Client 自己是智能体 |

### 6.3 Spring Security 配置

```java
// WebSecurityConfig.java 新增的放行规则
.csrf(csrf -> csrf
    .ignoringRequestMatchers(
        // ... 原有规则 ...
        "/sse",        // MCP SSE 端点（Token 认证，不走 CSRF）
        "/mcp/**",     // MCP JSON-RPC 端点
        "/api/mcp/**"  // MCP Token 管理 REST API
    )
)
.authorizeHttpRequests(auth -> auth
    // MCP SSE + JSON-RPC 端点：Token 认证，不走 Spring Security session
    .requestMatchers("/sse", "/mcp/message").permitAll()
    // MCP Token 管理 API & 设置页需登录
    .requestMatchers("/api/mcp/**", "/mcp/settings").authenticated()
    // ...
)
```

### 6.4 Token 存储

```sql
-- db/migration/V5__mcp_token.sql
CREATE TABLE mcp_token (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    label       VARCHAR(100),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP NULL,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_mcp_token_hash ON mcp_token(token_hash);
```

**JPA 实体：** `McpToken` 使用 `@ManyToOne(fetch = LAZY) User user` 关联（非 `user_id` 字段），`authenticate()` 时直接 `t.getUser()` 获取用户，减少一次数据库查询。

---

## 7. 管理 UI（S3）

### 7.1 页面入口

Dashboard 顶部导航栏 → **「MCP 连接」** 按钮 → 跳转至 `/mcp/settings`

### 7.2 页面功能

| 功能 | 说明 |
|---|---|
| 生成 Token | 输入标签名（如"Claude Desktop"），点击生成，**明文只展示一次** |
| 吊销 Token | 点击吊销按钮，该 Token 的所有连接立即失效 |
| 查看列表 | 展示标签、创建时间、最后使用时间 |
| 配置示例 | 生成后自动展示 `claude_desktop_config.json` 配置片段 |

### 7.3 REST API

| 端点 | 方法 | 说明 |
|---|---|---|
| `GET /api/mcp/tokens` | GET | 列出当前用户的所有 Token（脱敏） |
| `POST /api/mcp/tokens` | POST | 生成新 Token，body: `{"label": "xxx"}` |
| `DELETE /api/mcp/tokens/{id}` | DELETE | 吊销指定 Token |

---

## 8. 文件结构

```
src/main/java/org/zhzssp/memorandum/feature/agent/mcp/
├── McpSseEndpoint.java             # SSE 连接管理 + JSON-RPC 2.0 消息分发（6 个 method）
├── McpAuthService.java             # Token → User 认证（SHA-256 存储 + lattice_ 前缀）
│   └── McpAuthException            # 认证异常（内部类）
├── McpSessionCtx.java              # 连接级会话（持有 User + 注入 AgentContext）
├── McpToolAdapter.java             # ToolRegistry → MCP Tool 适配层（20 个 Tier 1 工具）
├── McpResourceAdapter.java         # Resources 端点（5 个只读数据资源）
├── McpLocalFileService.java        # S4：后端磁盘 IO（5 个本地文件工具 + 白名单校验）
├── entity/
│   └── McpToken.java               # JPA Entity（@ManyToOne User user）
├── repository/
│   └── McpTokenRepository.java     # Spring Data JPA
└── controller/
    ├── McpRestController.java       # SSE + JSON-RPC 端点 + Token CRUD API
    └── McpSettingsController.java   # 设置页面路由（/mcp/settings）

src/main/resources/templates/
└── mcp-settings.html               # Token 生成/吊销管理 UI

db/migration/
└── V5__mcp_token.sql               # Token 表 DDL
```

**与设计方案初版的差异：**

| 初版设计 | 实际实现 | 变更原因 |
|---|---|---|
| `LatticeMcpServer.java`（SDK Server 实例） | `McpSseEndpoint.java`（手写 JSON-RPC） | 不引入 MCP SDK，更轻量 |
| `McpSseTransportConfig.java` | 无独立配置类，端点在 `McpRestController` 中 | 更简洁 |
| 7 个 Resource URI | 5 个 Resource URI | 去掉参数化 URI（兼容性问题），搜索走 tools/call |
| `McpAuthService` 注入 `UserRepository` | `McpToken.getUser()` 直接获取 | 减少依赖 |

---

## 9. 落地阶段

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **S1 传输+认证** | SSE Transport + Token 认证 + McpSessionCtx | Claude Desktop 能连上并完成 `initialize` + `tools/list` | ✅ 已完成 |
| **S2 工具暴露** | McpToolAdapter（20 个 Tier 1 工具）+ McpResourceAdapter（5 个 Resources） | 外部 AI 可以读写 PKM 数据 | ✅ 已完成 |
| **S3 管理 UI** | Web 设置页「MCP 连接」面板（生成/吊销 Token） + REST API | 用户自助管理 MCP 接入 | ✅ 已完成 |
| **S4 本地文件** | McpLocalFileService（5 个本地工具 + 白名单目录 + 扩展名校验） | MCP Client 可读本地文件 | ✅ 已完成 |

### 验证方式

```json
// claude_desktop_config.json
{
  "mcpServers": {
    "lattice": {
      "url": "http://localhost:8080/sse?token=lattice_你的token"
    }
  }
}
```

连接成功后，在 Claude Desktop 中应能看到：
- 工具列表：`goal.list`, `task.today`, `kb.semantic_search` 等 20 个
- 资源列表：`lattice://goals`, `lattice://tasks/today` 等 5 个
- 可以直接对话：「帮我看看今天有什么任务」「新建一个学习 Spring AI 的目标」
- 开启 S4 后：还能读取白名单目录下的文件

---

## 10. 与现有架构的兼容性

| 现有模块 | 是否改动 | 说明 |
|---|---|---|
| `ToolRegistry` | **零改动** | `McpToolAdapter` 只读调用 `all()` + `invoke()` |
| `@AgentTool` 工具类 | **零改动** | 所有 20 个 Tier 1 工具方法原封不动 |
| `AgentContext` | **零改动** | `McpSessionCtx.withContext()` 注入/清理 ThreadLocal |
| `AgentOrchestrator` | **零改动** | MCP 不走内部编排链路 |
| `SubAgentRunner` | **零改动** | 不暴露子代理工具 |
| `LlmGateway` | **零改动** | MCP Server 不调 LLM |
| `AgentChatWebSocketHandler` | **零改动** | WS 通道与 MCP SSE 通道并行独立 |

> 核心红利：**MCP Server 是纯"旁路适配层"**，对现有 Agent 内部架构零侵入。
> `ToolRegistry` 天然是"工具注册表"的抽象，MCP 只是把它的内容换了一种协议格式暴露出去。

---

## 11. 已知限制与后续优化

| 项 | 当前状态 | 优化方向 |
|---|---|---|
| PDF 解析 | 字节级粗提取 | 集成 Apache PDFBox 或 iText |
| 配置化 tag 白名单 | 硬编码 `EXCLUDED_TAGS = Set.of("local", "subagent")` | 支持配置项 `mcp.server.excluded-tags` |
| SSE 推送响应 | 当前 JSON-RPC 响应通过 HTTP 同步返回 | 完整 SSE 双向流（notification 支持） |
| Token 粒度权限 | Token = 完整读写权限 | 支持 `read-only` Token、工具白名单 |
| 连接日志 | 仅日志记录 | 前端展示连接历史/在线状态 |
