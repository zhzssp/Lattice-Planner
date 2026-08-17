# Lattice-Agent 功能总览

> 本文档面向「快速理解 / 面试讲解 / 二次开发」，聚焦**当前已落地的能力**与**关键设计点**。
> 详细的逐文件实现方案请参考同目录下的 `Agent实现方案.md`。

---

## 1. 一句话概括

**Lattice-Agent** 是内嵌在 Lattice-Planner 任务规划软件里的对话式智能体：用户在任意业务页面右下角点开抽屉式聊天面板，就能用自然语言驱动 Agent **读写自己的目标 / 任务 / 笔记 / 复盘数据**，调用项目原有的 **LLM 规划器** 自动拆目标，甚至**读取本地 PC 上的 Markdown / PDF**。整套循环是自实现的精简 ReAct + Reflexion，未引入 LangChain / Spring AI。

---

## 2. 整体架构

```
┌────────────────────────────────────────────────────────────────────┐
│  浏览器 / Electron 渲染端                                            │
│  ┌────────────┐  ┌──────────────────────────────┐                 │
│  │  Fab 按钮   │→ │  抽屉面板 chat-panel.js       │                 │
│  └────────────┘  │  (sessionId / WS / 渲染)       │                 │
│                  └────────────┬─────────────────┘                  │
│                               │ ws://host/ws/agent/{sid}            │
│                  Electron preload.js   ←─ localCall (反向)          │
└────────────────────────────────┼────────────────────────────────────┘
                                 │
┌────────────────────────────────┼────────────────────────────────────┐
│  Spring Boot 后端                                                    │
│  AgentChatWebSocketHandler  ─►  AgentOrchestrator (ReAct 主循环)     │
│         │                              │                            │
│         │           ┌──── ToolRegistry (反射调用 @AgentTool)  ◄────┐ │
│         │           │       │                                     │ │
│         │           │   TaskTools / GoalTools / NoteTools /       │ │
│         │           │   PlannerTools / InsightTools / LocalDocTools│ │
│         │           │                                             │ │
│         │           └─►  LlmGateway (DeepSeek)                    │ │
│         │                                                         │ │
│         └──── ToolConfirmCoordinator (高危确认弹窗)                │ │
│                LocalBridgeProxy ── 反向 IPC ──────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 主循环（精简 ReAct）

```
user 输入
  ▼
PromptBuilder：拼系统提示（含工具 schema + 长期记忆）
  ▼  ┌─ 最大 N 步（默认 8）─┐
LlmGateway → LLM 输出
  ├─ 解析为 ToolCall ──► 高危？走确认 ──► 反射执行 ──► 结果回灌 history
  └─ 否则视为终态自然语言 ──► 推送给 UI ──► sendDone
```

工具执行抛错时，错误 JSON 直接喂回 LLM——等价于一次 **Reflexion**，让模型自我纠偏。

---

## 3. 已落地的工具集（20 个）

| 域 | 工具 | 读/写 | 需确认 | 作用 |
|---|---|---|---|---|
| **task** | `task.create` | 写 |  | 新建任务（含 deadline / energy / preferredSlot） |
|  | `task.search` | 读 |  | 关键字 + 截止日期范围查询 |
|  | `task.today` | 读 |  | 今日可行动任务 |
|  | `task.fuzzy_pending` | 读 |  | 列出粒度过粗、长期未拆的任务 |
|  | `task.complete` | 写 | ✓ | 标记完成（触发事件回环） |
|  | `task.archive` | 写 | ✓ | 归档任务 |
| **goal** | `goal.list` / `goal.list_all` | 读 |  | 活跃目标 / 全部目标 |
|  | `goal.create` | 写 | ✓ | 新建目标（长期/短期/临时） |
|  | `goal.archive` | 写 | ✓ | 归档目标 + 联动归档其下任务 |
|  | `goal.link_task` | 写 | ✓ | 把任务挂到一个或多个目标下 |
| **note** | `note.list` | 读 |  | 列出非系统笔记（自动屏蔽 AGENT_MEMO） |
|  | `note.create` | 写 |  | 新建用户笔记 |
| **planner** | `planner.draft_goal_plan` | 读 |  | 调用项目原有 PlannerAgentService 拆目标（不落库） |
|  | `planner.apply_goal_plan` | 写 | ✓ | 把上一步草案落库为目标 + 任务树 |
| **insight** | `insight.daily_scores` | 读 |  | 区间每日规划完成得分曲线 |
|  | `insight.summarize_period` | 读 |  | LLM 自然语言总结 + 建议 |
| **local** | `local.list_dir` | 读 | ✓ | 列本地目录（必须在 Electron 白名单内） |
|  | `local.read_file` | 读 | ✓ | 读本地文本文件（md/txt/json/yml…） |
|  | `local.read_pdf` | 读 | ✓ | 读本地 PDF 纯文本（pdf-parse） |

> **20 个工具全部由 `@AgentTool` 注解 + 反射注册**，新增工具只需在任意 `@Service` / `@Component` 上加一个方法、贴上注解，无需改 Registry。

---

## 4. 三种对话模式

面板顶部下拉切换，本质是**给 LLM 喂的工具子集不同**（在 `PromptBuilder` 里按 tag 过滤）：

| 模式 | 暴露的工具 tag | 适用场景 |
|---|---|---|
| **Chat** | 全部 | 通用问答、灵活调度任意工具 |
| **自动规划** | `task` `goal` `planner` `read` `write` | "把这个目标拆成可执行任务"，Agent 调 `planner.draft_goal_plan` → 用户确认 → `planner.apply_goal_plan` |
| **复盘** | `task` `goal` `insight` `note` `read` | "这周完成度怎样？" Agent 自动跑 `insight.summarize_period` + 抽笔记/未完成任务给出反思 |

---

## 5. 核心创新点（面试讲解口径）

### 5.1 业务事件回环（Event Loop）
Agent 写库 → 触发 Spring `TaskCreatedEvent` / `TaskCompletedEvent` → `GoalEventListener` 自动重算挂载目标的进度。
这意味着 Agent 永远在用**业务真服务**而不是写一份"Agent 专用 DAO"，**业务代码 0 修改**。

### 5.2 反射 + 注解的本地工具体系（MCP 等价）
不依赖 Spring AI / LangChain：

```java
@AgentTool(name = "task.complete", tags = {"task","write"}, requiresConfirm = true,
           description = "把指定 id 的任务标记为完成。需用户确认。")
public TaskView complete(@ToolParam(value="id", desc="任务 id") Long id) { ... }
```

启动时一次扫描，运行时 `registry.invoke(name, JsonNode args)` 反射调用，并把方法签名导出成 OpenAI function-calling 风格的 JSON Schema 喂给 LLM。换言之：**这就是一个最小 MCP**。

### 5.3 反向通道（Backend ↔ Electron）
本地文件类工具走"反向 RPC"：

```
后端 LocalDocTools.readPdf
   → LocalBridgeProxy 通过用户当前 WS 推 localCall
   → 浏览器 chat-panel.js 收到后调 window.lattice.localBridge.readPdf
   → Electron preload → ipcMain → fs/pdf-parse
   → 原路返回（CompletableFuture，30s 超时）
```

**敏感操作不出用户机器**，且后端不知道用户磁盘结构——这是该架构相对"服务器代读"的核心安全优势。

### 5.4 高危操作显式确认
所有 `requiresConfirm = true` 的工具：在执行前推一条 `confirmReq` 给前端，前端弹气泡按钮，用户点"允许"才会真正落库；超时 60s 默认拒绝。

### 5.5 Reflexion 式自纠偏
工具异常时，错误 `{error, message}` 直接作为 user 角色追加到 history，下一步 LLM 自动改写参数。无需手写 retry 策略。

### 5.6 长期记忆（基础版已就绪）
会话结束时 `LongTermMemoryService.archive` 把对话浓缩为一段记忆，写入 `Note(type=AGENT_MEMO)`；下次会话开始时自动摘最近 5 条注入 system prompt。`AGENT_MEMO` 已在 `NoteController` / `NoteService` 层对用户列表完全屏蔽。

### 5.7 解耦 + 抗循环依赖
- Handler ↔ Orchestrator / ConfirmCoordinator / LocalBridgeProxy 之间用 `@Lazy` 切环
- ToolRegistry 扫描时机用 `ApplicationReadyEvent` 而非 `@PostConstruct`，杜绝"扫描期触发别的 bean 初始化"造成的循环

---

## 6. 关键文件索引

### 后端（`src/main/java/.../feature/agent`）

```
chat/    AgentChatWebSocketHandler.java   ← WS 入口、消息分发、daemon 线程 + AgentContext
runtime/ AgentOrchestrator.java           ← ReAct 主循环
         PromptBuilder.java               ← 系统提示拼装 + 模式过滤
         ConversationMemory.java          ← 内存版历史（窗口=30）
         ToolCallParser.java              ← 抓 ```json``` / 外层 {} / 去 <think>
         AgentContext.java                ← ThreadLocal user / sid
         LongTermMemoryService.java       ← 会话归档为 AGENT_MEMO
policy/  ToolConfirmCoordinator.java      ← 60s 超时的确认 future
tool/    AgentTool.java / ToolParam.java  ← 注解
         ToolRegistry.java                ← 扫描 + 反射 + schema 导出
         ToolDefinition.java
         LocalBridgeProxy.java            ← 反向 IPC future
   impl/ TaskTools / GoalTools / NoteTools /
         PlannerTools / InsightTools / LocalDocTools
service/ LlmGateway.java                  ← DeepSeek 封装（chat / reasoner 兼容）
```

### 配置

| 文件 | 作用 |
|---|---|
| `config/AgentWebSocketConfig.java` | 注册 `/ws/agent/**` |
| `config/WebSecurityConfig.java` | CSRF 放行 + permitAll `/agent/**` |
| `application.properties` | `agent.chat.model` `agent.chat.max-steps` 等 |

### 前端

| 文件 | 作用 |
|---|---|
| `templates/fragments/agent-panel.html` | Fab + 抽屉面板 fragment |
| `templates/{dashboard,addMemo,preferenceSettings,selectFeatures}.html` | 4 处 fragment 注入点 |
| `static/agent/chat-panel.css` | 抽屉 + 气泡样式 |
| `static/agent/chat-panel.js` | sessionId / WS / 7 种消息渲染 / 反向 localCall 处理 |

### Electron

| 文件 | 作用 |
|---|---|
| `electron-app/preload.js` | 暴露 `window.lattice.localBridge.{listDir, readFile, readPdf}` |
| `electron-app/main.js` | `ipcMain.handle('local:*')` + 路径/扩展名/大小校验 |
| `electron-app/permission-config.json` | `allowDirs / denyDirs / allowExt / maxFileBytes` |

---

## 7. 怎么用（用户视角）

1. `gradlew.bat bootRun` 启动后端，启动末尾日志会出现 `[Agent] Registered 20 tools: [...]`
2. 浏览器或 Electron 登录后，进入 `/dashboard` 等页面
3. 点击右下角蓝紫色 **AI** 圆形悬浮按钮 → 右侧抽屉滑出
4. 顶部下拉选模式（Chat / 自动规划 / 复盘）
5. 输入框打字，**Ctrl+Enter** 发送
6. 高危操作会弹出黄色确认气泡，点"允许"才会真的落库
7. 想用本地文档工具：跑 Electron 壳，并把目录加进 `permission-config.json` 的 `allowDirs`

---

## 8. 三个 Demo 验收路径

| Demo | 自然语言指令 | Agent 应该跑出来的工具链 |
|---|---|---|
| **A. 业务闭环** | "帮我把今天没做完的事归档掉" | `task.today` → 多次 `task.archive`（每次走确认） → 终态自然语言总结 + GoalListener 自动更新进度 |
| **B. 复盘** | "最近一周状态怎么样？给点建议" | `insight.daily_scores` → `insight.summarize_period` → `note.list`（可选） → 终态建议 |
| **C. 本地文档驱动规划** | "读一下 D:/plan.pdf 帮我拆任务" | `local.read_pdf`（确认） → `planner.draft_goal_plan` → 用户审阅 → `planner.apply_goal_plan`（确认）落库 |

---

## 9. 未来演进（Roadmap）

| 版本 | 目标 |
|---|---|
| **V2** | 本地文档向量化（pgvector）+ `memory.search` 工具，让 Agent 跨会话检索历史输入 |
| **V3** | 工具流式日志 + Token 级流式输出（提升交互观感） |
| **V4** | 接入标准 **MCP 协议** 适配层，把现有 ToolRegistry 适配为 MCP server，向第三方客户端开放 |
| **V5** | 主动型触发：定时复盘 / 任务到期主动提醒（cron + Agent 主动 push 入会话） |

---

## 10. 已知限制

- 长期记忆为「全文摘要」，没有向量检索，规模上去后会膨胀（V2 解决）
- 浏览器壳里 `local.*` 工具不可用（必须 Electron 启动）
- 工具调用并行未启用，一次只调一个工具（设计为线性 ReAct，便于审计）
- ConversationMemory 是进程内内存，重启即清空（生产部署需替换为 Redis / DB）

---

> 维护者：将本文档与 `Agent实现方案.md` 配合阅读——前者是"做完的事"，后者是"为什么这么做"。
