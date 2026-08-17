# 本地文档作为 Agent 信息来源 —— MCP 打通实现规划（v3：真·MCP loopback 方案）

> 目标：让 Lattice-Planner 的 Agent **真正通过 MCP 协议**（而非同进程直接方法调用）只读访问本地磁盘上的多种格式文档（txt/md、PDF、Word、Excel 等），提取 + 摘要后注入模型上下文，作为对话回答的信息来源之一。

---

## 0. 版本演进说明

| 版本 | 核心思路 | 是否字面符合"通过 MCP" |
|---|---|---|
| v1 | 打通 `McpLocalFileService` 给 Agent 用，PDF 占位提取直接改真实解析 | 否，只是复用代码 |
| v2 | 新增独立 `@AgentTool`（`local.read_document`）同进程直接调用，绕开 MCP 协议层 | 否，走 tool-calling 捷径，MCP 骨架被架空 |
| **v3（本版）** | **不新增捷径工具**，而是让 Agent 作为**真正的 MCP Client**，通过项目已有的 `McpClientManager` 连接**本机自己的 `McpSseEndpoint`**（loopback，SSE + JSON-RPC 2.0），像连接任意外部 MCP Server 一样调用本地文件工具 | **是**，走完整 SSE + JSON-RPC 协议栈 |

v3 完全取代 v1/v2，是本次最终推荐方案（对应此前分析中的"方案 2a"）。多格式解析器与摘要层的设计在 v2 基础上保留，仅执行位置从"Agent 侧新组件"改为"Server 侧 `McpLocalFileService` 内部增强"。

---

## 1. 现状盘点（v3 视角下的复用基础）

| 能力 | 位置 | 复用方式 |
|---|---|---|
| MCP Server：SSE 端点 + JSON-RPC 分发 | `McpSseEndpoint` | **零改动直接复用**，`tools/list`/`tools/call` 已能路由到 `McpLocalFileService` |
| MCP Server：Token 鉴权 | `McpAuthService`（`lattice_` 前缀 token，绑定 `User`） | 复用现有生成/校验逻辑，仅需**新生成一个 token** 供 loopback 连接使用 |
| MCP Client：连接管理 + 自动重连 | `McpClientManager` + `McpClientConnection` + `McpSseClient` | **零改动直接复用**，只需在配置里新增一个 `server` 条目指向本机 |
| MCP Client：远程工具注册进 Agent | `McpToolProxy` → `ToolRegistry.registerMcpTool` | **零改动直接复用**，工具会以 `mcp.<serverName>.<toolName>` 形式自动出现在 Agent 可调用工具列表 |
| 本地文件白名单 + 三个基础工具 | `McpLocalFileService`（`local.list_dir`/`local.read_file`/`local.read_pdf`） | 需**增强**：PDF 解析从占位替换为真实解析，新增 Word/Excel 支持 |
| LLM 网关（做摘要） | `LlmGateway.generateText` | Server 侧（`McpLocalFileService`）可直接注入调用，同进程无额外开销 |

### 1.1 v1 中提到的"断点 A"在 v3 下已不存在

需澄清一个此前分析的误区：`McpToolAdapter` 里 `EXCLUDED_TAGS = {"local", "subagent"}` 排除的是**`ToolRegistry` 扫描到的 `@AgentTool`**（若有工具打了 `local` tag），**与 `McpLocalFileService` 的三个硬编码工具无关**——后者在 `McpSseEndpoint.handleToolsList` 中是**单独追加**的（只要 `mcp.server.local-files-enabled=true`），本来就会出现在 `tools/list` 响应里,可以被任何 MCP Client（外部的或本机 loopback 的）发现并调用。也就是说：**MCP Server 侧暴露本地文件工具的能力，一直是打通的**，真正缺的只是"让 Agent 自己也当一次 MCP Client 去连它"。

### 1.2 v3 唯一要打通的缺口

**Agent 目前是 MCP Server + 潜在 Client 两套骨架并存，但从未配置 Client 连接自己**。`application.properties` 里 `mcp.client.enabled=false`，且没有任何 `mcp.client.servers.*` 指向本机地址。这是 v3 唯一要打通的一件事——**配置 + 少量代码增强**，无需新协议、新传输层。

---

## 2. 架构设计

### 2.1 loopback 总览

```
┌───────────────────────── 同一个 Spring Boot 进程 ──────────────────────────┐
│                                                                            │
│  AgentOrchestrator ──决策调用──> mcp.loopback.local.read_document          │
│         │                                                                 │
│         ▼                                                                 │
│  ToolRegistry.invoke() ──(是 mcp.* 前缀)──> McpToolProxy.invoke()          │
│         │                                                                 │
│         ▼                                                                 │
│  McpClientManager.getConnection("loopback")                              │
│         │                                                                 │
│         ▼                                                                 │
│  McpClientConnection ──callTool──> McpSseClient                          │
│         │  POST http://localhost:8080/mcp/message?sid=xxx                │
│         │  { method: "tools/call", params: {name:"local.read_document"}} │
│         ▼                                                                 │
│  McpSseEndpoint.handleMessage() ──路由──> McpLocalFileService.callTool()  │
│         │  1.白名单+扩展名校验  2.按格式分发 Extractor  3.过长走 Summarizer │
│         ▼                                                                 │
│  结果通过 SseEmitter 沿 SSE 推回 McpSseClient（同进程内一次本机 HTTP 往返） │
│         │                                                                 │
│         ▼                                                                 │
│  McpToolProxy 提取文本 → 回灌 ConversationMemory → 下一轮 PromptBuilder 拼进 messages │
│         │                                                                 │
│         ▼                                                                 │
│              LLM 基于文档摘要内容辅助判断/回答                             │
└────────────────────────────────────────────────────────────────────────────┘
```

关键点：**Server 和 Client 是同一个 Java 进程，只是通过一次本机 HTTP(SSE) 回环真正走了一遍标准 MCP 协议**（SSE 建连 → `initialize` 握手 → `tools/list` 发现 → `tools/call` 调用），不是伪装或简化协议。

### 2.2 身份与 Token 问题（v3 特有，必须澄清）

`McpSessionCtx` 把每个 SSE 连接绑定到 token 所属的**固定 `User`**。loopback 连接在应用启动时（`ApplicationReadyEvent`）建立一次，之后长期复用，因此：

- 需要**预先生成一个 MCP Token**（走现有 Settings 页面的 Token 管理功能，调用 `McpAuthService.generateToken(user, label)`），绑定到某个"服务账号"用户（单机部署场景下，直接用管理员/唯一使用者自己的账号即可）。
- 这意味着 loopback 通道读取本地文件时，`AgentContext` 里的"当前用户"是**固定为 Token 持有者**，与"当前正在对话的用户"是**两个独立身份**。对于"读本机磁盘白名单目录"这个场景，这是可接受的——白名单目录（`mcp.server.local-allowed-dirs`）本身是全局配置，不区分用户，功能上等价于一个"系统级本地文件读取服务账号"。
- 文档中需明确标注此限制：**若未来做多用户隔离部署，loopback 方案需要改为按对话用户动态签发临时 token（属于后续可选增强，本次不做）**。

### 2.3 只读收紧（复用现有暴露面，但要"减法"）

`McpLocalFileService.exportLocalTools()` 当前还导出了 `kb.ingest_local_doc` / `kb.delete_local_doc` 两个"写"工具（且在 `callTool` 里实际未实现，落到 `default` 分支报错）。为满足"暂时只读"约束，且避免 Agent 误调用两个必然报错的工具：

- **从 `exportLocalTools()` 移除这两项**（只在 loopback/Agent 场景不需要；若外部 MCP 生态确实需要摄取能力，后续可重新单独设计，不在本次范围）。
- 保留 `local.list_dir`、`local.read_file`，并新增统一的 `local.read_document`（见 2.4），三者均为纯读操作。

### 2.4 多格式解析 + 摘要（执行位置：Server 侧 `McpLocalFileService`）

与 v2 相同的设计，只是宿主从"新 `@AgentTool` 类"改为直接增强到 `McpLocalFileService` 内部（因为现在调用方变成了 MCP `tools/call`，天然就该在 Server 侧实现）：

```java
public interface DocumentExtractor {
    boolean supports(String extension);
    ExtractedDocument extract(Path path) throws IOException;
}
public record ExtractedDocument(String plainText, int pageOrSheetCount, long sizeBytes) {}
```

- `TxtExtractor`（含代码文本，直接读，UTF-8 失败回退 GBK）
- `PdfExtractor`（`PDFBox` `PDFTextStripper` 逐页抽取，替换现有 `readPdf` 里的字节粗过滤占位）
- `WordExtractor`（POI `XWPFDocument`，遍历段落 + 表格）
- `ExcelExtractor`（POI `XSSFWorkbook`，按 sheet→row→cell 转 Markdown 表格文本，行数超阈值截断）

`DocumentSummarizer`：短文本（≤4000 字符）直传原文；长文本走 map-reduce 摘要，调用 `LlmGateway.generateText` 分块摘要 + 合并；异常时降级截断原文，不抛异常阻断工具调用。

新增统一工具方法：

```java
// McpLocalFileService 内新增
private Map<String, Object> readDocument(String pathStr) {
    // 1. isAllowed + 扩展名白名单校验（含新增 docx/xlsx）
    // 2. 按扩展名从 DocumentExtractorRegistry 选择 Extractor
    // 3. DocumentSummarizer 处理（短文直传 / 长文摘要）
    // 4. 返回 {fileName, extension, sizeBytes, pageOrSheetCount, isSummarized, content}
}
```

并在 `exportLocalTools()` 新增对应工具描述，在 `callTool()` 的 `switch` 里新增 `case "local.read_document" -> readDocument(...)`。

### 2.5 与 v1/v2 的关键差异总结

| | v2（`@AgentTool` 捷径） | v3（本版，loopback MCP） |
|---|---|---|
| Agent 调用方式 | 同进程直接反射调用 Java 方法 | 走 `mcp.loopback.local.read_document`，经 SSE+JSON-RPC |
| 是否字面符合"通过 MCP" | 否 | **是** |
| 解析/摘要逻辑执行位置 | Agent 侧新组件 | Server 侧 `McpLocalFileService` 内部 |
| 是否复用现有 MCP Client/Server 骨架 | 否（绕开） | **是（完整复用）** |
| 新增组件 | `LocalDocumentTool`、`LocalFileAccessGuard` | 无新增顶层组件，仅增强 `McpLocalFileService` + 配置 |
| 需要额外配置 | 无 | 需生成 Token + 配置 `mcp.client.servers.loopback.*` |

---

## 3. 实施阶段

### 阶段一：依赖引入 + 提取器实现
1. `build.gradle` 新增：
   ```groovy
   implementation 'org.apache.pdfbox:pdfbox:2.0.31'
   implementation 'org.apache.poi:poi-ooxml:5.2.5'
   ```
2. 新建 `feature/agent/mcp/doc/` 包：`DocumentExtractor` 接口 + `ExtractedDocument` record + 4 个实现类 + `DocumentExtractorRegistry`（按扩展名分发）。
3. 单测：4 类提取器各配一份样例文件，断言文本非空、关键字符串命中。

### 阶段二：摘要层
1. 新建 `DocumentSummarizer`，注入 `LlmGateway`。
2. 分块策略：按段落/表格行边界切分，超过 `CHUNK_SIZE`（约 3000 字符）才强制截断。
3. 异常兜底：摘要失败→截断原文+标记，不抛异常。

### 阶段三：`McpLocalFileService` 增强（只读收紧 + 新工具）
1. 扩展 `ALLOWED_EXTENSIONS` 加入 `docx`、`xlsx`。
2. 新增 `readDocument()` 方法（调用阶段一/二产出的 Extractor + Summarizer）。
3. 在 `exportLocalTools()`：
   - 新增 `local.read_document` 工具描述；
   - **移除** `kb.ingest_local_doc`、`kb.delete_local_doc`（未实现的写工具，收紧为纯只读暴露面）。
4. 在 `callTool()` 的 `switch` 新增 `local.read_document` 分支。
5. 用真实 `PdfExtractor` 替换 `readPdf()` 里的字节粗提取占位（`local.read_pdf` 保留向后兼容，内部改为委托 `PdfExtractor`）。

### 阶段四：打通 loopback MCP Client 连接
1. 通过现有 Settings 页面的 MCP Token 管理功能，为"服务账号"用户生成一个 token（label 如 `loopback-agent`）。
2. `application.properties` 新增：
   ```properties
   mcp.client.enabled=true
   mcp.client.servers.loopback.url=http://localhost:8080/sse
   mcp.client.servers.loopback.token=lattice_xxxxxxxx
   mcp.client.servers.loopback.enabled=true
   mcp.server.local-files-enabled=true
   mcp.server.local-allowed-dirs=D:/learning,D:/reports
   ```
3. 应用重启后，`McpClientManager` 会在 `ApplicationReadyEvent` 时连接本机 SSE 端点，`McpToolProxy` 自动把 `local.list_dir`/`local.read_file`/`local.read_pdf`/`local.read_document` 注册为 `mcp.loopback.*` 供 Agent 调用。
4. 验证：调用现有 MCP Client 状态查看接口（若有）或直接看日志 `[MCP Client] loopback 连接成功，发现 N 个工具`。

### 阶段五：`PromptBuilder` 系统提示补充 + 验证
在 `PromptBuilder.buildSystemPrompt` 追加【本地文档读取原则】：
```
【本地文档读取原则】
- 当用户提到具体本地文件路径，或要求"读取/总结/分析某份文档（含 PDF/Word/Excel 等）"时，
  调用 mcp.loopback.local.read_document 获取内容后再回答；不要凭空猜测文件内容。
- 可先调用 mcp.loopback.local.list_dir 列目录确认文件是否存在。
- 工具返回的 content 若标注 isSummarized=true，说明原文过长已被摘要，回答时可提示"以下基于文档摘要"。
- 路径不在白名单内会返回错误，此时应直接告知用户"该路径未被授权访问"。
- 仅使用以上只读工具，不要尝试调用摄取/写入类工具。
```

---

## 4. 配置汇总（本版新增/变更）

```properties
# —— MCP Server：本地文件只读能力（已有配置项，需启用并填白名单）——
mcp.server.enabled=true
mcp.server.local-files-enabled=true
mcp.server.local-allowed-dirs=D:/learning,D:/reports

# —— MCP Client：新增 loopback 连接（本次新增）——
mcp.client.enabled=true
mcp.client.servers.loopback.url=http://localhost:8080/sse
mcp.client.servers.loopback.token=lattice_xxxxxxxx
mcp.client.servers.loopback.enabled=true
mcp.client.servers.loopback.connect-timeout=10
mcp.client.servers.loopback.call-timeout=30
```

> `mcp.client.servers.loopback.token` 需替换为通过 Settings 页面实际生成的 token 明文（仅展示一次，需提前保存）。

---

## 5. 验证步骤

1. **提取器/摘要单测**：同 v2，覆盖 4 类格式 + map-reduce 摘要 + 异常降级。
2. **loopback 连接验证**：启动应用后查看日志确认 `[MCP Client] loopback 连接成功，发现 N 个工具`，且工具名包含 `mcp.loopback.local.read_document`。
3. **Agent 工具可见性验证**：检查 Agent 系统提示词/工具 Schema 导出结果里确实出现 `mcp.loopback.local.*` 系列工具（区别于 v2 方案的关键验证点——证明走的是 MCP 协议而非直接方法调用）。
4. **端到端**：聊天面板输入「帮我看看 D:/reports/Q3财报.xlsx 里营收趋势」→ Agent 调用 `mcp.loopback.local.read_document` → 经 SSE 往返拿到摘要内容 → 最终答复引用文档数据。
5. **安全回归**：越权路径、非白名单扩展名应被拒绝，且不应有堆栈异常泄漏；确认 `kb.ingest_local_doc`/`kb.delete_local_doc` 已从 loopback 可见工具列表移除。
6. **断连恢复**：手动重启一次应用，确认 `McpClientManager` 健康检查（30s 周期）能自动重连 loopback（正常情况下随应用启动即连上，此项主要验证异常场景下的自愈能力）。

---

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Token 身份固定，与当前对话用户身份不一致 | 单机/单用户部署场景可接受；文档已在 2.2 节标注限制，多用户隔离部署留作后续扩展 |
| loopback 依赖应用自身端口可达（如被防火墙拦截本机回环） | 本机 `localhost` 回环一般不受防火墙影响；异常时 `McpClientManager` 健康检查每 30s 自动重试，日志可观测 |
| Token 明文存储在 `application.properties` | 与项目现有其它 API Key 配置方式一致（`agent.llm.api-key` 等），可用环境变量覆盖，不额外引入新风险面 |
| PDFBox/POI 增加 jar 体积与启动时间 | 纯 Java 库，实测增量约 10-15MB，可接受 |
| 复杂 Excel（合并单元格/公式/图表）提取失真 | 首期仅支持取值文本（公式取缓存结果），复杂样式不处理，文档中注明局限 |
| 长文档摘要丢失细节 | map-reduce 摘要 + 必要时保留首尾原文片段 |
| 同进程内多一次本机 HTTP 往返的延迟 | 本机回环延迟通常 <5ms，相较解析+摘要耗时可忽略 |
| 移除 `kb.ingest_local_doc`/`kb.delete_local_doc` 影响其它外部 MCP 客户端 | 两者当前未实现（`callTool` 落 `default` 分支报错），移除前无任何真实调用方受影响 |

---

## 7. 小结

v3 方案不新增任何"绕开 MCP"的捷径组件，而是：

1. 增强 `McpLocalFileService`：真实 PDF 解析（PDFBox）、新增 Word/Excel 解析（POI）、新增摘要层、新增统一 `local.read_document` 工具、收紧只读暴露面；
2. 配置打通：生成一个 loopback token，在 `mcp.client.servers.loopback.*` 配置本机地址，让 Agent 作为**真正的 MCP Client**连接**本机 MCP Server（自己）**；
3. **零新增顶层架构组件**——完整复用项目已有的 `McpSseEndpoint`（Server）与 `McpClientManager`/`McpSseClient`/`McpToolProxy`（Client）两套骨架，只是第一次真正让它们"自己连自己"。

最终效果：Agent 通过 `mcp.loopback.local.read_document` 等工具，**严格经过标准 MCP 协议（SSE + JSON-RPC 2.0）**读取本地 txt/md/PDF/Word/Excel 文档，提取并摘要后注入对话上下文，作为回答时的信息来源之一。
