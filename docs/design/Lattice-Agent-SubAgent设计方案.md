# Lattice-Agent SubAgent（子代理）设计方案

> 本文档是在已落地的单 Agent（ReAct）基础上，引入 **SubAgent 多代理协作** 的设计蓝图。
> 配套阅读：`docs/Agent实现方案.md`（为什么这么做）、`docs/Lattice-Agent功能总览.md`（已做完什么）。

---

## 0. 一句话目标

把当前「单 Agent + 单上下文 + 26 工具」升级为 **「编排者主 Agent + 多个专精子代理（Worker）」** 的协作体，
通过 **上下文隔离** 解决 token 爆 / 上下文污染，通过 **并行 Fan-out** 解决多目标串行慢，
让项目具备「真·多代理编排」这一面试硬通货能力。

---

## 1. 现状诊断：为什么单 Agent 不够

当前核心循环 `AgentOrchestrator.handleUserTurn(sid, userInput, mode, longTermMemo)`：

- 所有工具结果都通过 `appendToolTrace()` **回灌进同一个 `ConversationMemory(sid)`**（窗口仅 `WINDOW=30`）。
- 26 个工具按 `PromptBuilder` 的 `mode` 做 tag 过滤，但仍共用一个上下文、一个 `maxSteps=8`。

由此产生三个明确瓶颈：

| 瓶颈 | 触发场景 | 根因 |
|---|---|---|
| **上下文污染 / 爆 token** | "读 `D:/计划.pdf` 把目标拆成任务建好" | `local.read_pdf` 全文 + 每个 `task.create` 的 args/result JSON 全进主窗口，挤掉真实对话 |
| **串行慢 / 易超步** | "体检我所有目标进度并给建议" | 多目标只能串行；`maxSteps=8` 几个目标就耗尽 |
| **浅检索** | "我之前关于 X 的笔记和文档都说了啥" | 单轮 `kb.semantic_search` 召回有限；多跳检索的中间片段又会污染主上下文 |

---

## 2. 可被 SubAgent 优化的 4 个功能点

| # | 子代理 | 解决的瓶颈 | 复用的现有工具子集（实际落地的 tag 集合） | 模式 |
|---|---|---|---|---|
| 1 | **PlannerSubAgent**（规划专家） | 上下文污染 | `local` + `kb` + `planner` + `task` + `goal` | 串行委派 |
| 2 | **ReflectionSubAgent**（复盘专家） | 串行慢 / 超步 | `insight` + `task` + `goal` + `note` + `kb` | 串行委派 |
| 3 | **ResearchSubAgent**（检索专家） | 浅检索 + 污染 | `kb` + `note` + `local` | 串行委派 |
| 4 | **多目标并行体检** | 串行慢 | 多个 Research worker（`subagent.parallel_research`） | **Fan-out 并行** |

> 落地说明：角色工具子集只用领域 tag（如 `task`/`goal`），不再带 `read`/`write` 伪 tag——
> 工具的读写性质由各 `@AgentTool` 自身的 `requiresConfirm()` 决定，无需在角色层重复表达。
> 并行 fan-out 当前仅固定绑定 **RESEARCH**（只读）角色，避免并发写冲突。

每个子代理的价值都源于同一句话：**它在自己的独立上下文里跑完一段多步推理，只把"压缩后的结论"还给主 Agent**，主 Agent 的对话窗口因此保持干净。

---

## 3. 架构模式

采用三层组合：

1. **Orchestrator-Worker（编排者-工作者）**：主 `AgentOrchestrator` 是编排者，SubAgent 是工作者。
2. **Agents-as-Tools（代理即工具）**：每个 SubAgent 包装成一个 `@AgentTool`（`tags={"subagent"}`）。
   > 关键收益：主循环 `AgentOrchestrator` **零改动**——它本来就只通过 `ToolRegistry.invoke()` 调工具，
   > 委派子代理对它而言只是"调了一个慢一点的工具"。
3. **Context Isolation（上下文隔离）**：SubAgent 用**局部临时记忆**跑 ReAct，**不写** `ConversationMemory(sid)`，
   只把最终 `SubAgentResult` 序列化后作为工具结果回灌主上下文。

### 3.1 数据流

```
用户: "读 D:/计划.pdf 帮我建好目标和任务"
  │
  ▼
主 Agent (depth=0, ConversationMemory[sid])
  │  LLM 决策 -> {"tool":"subagent.plan","arguments":{"instruction":"...","source":"D:/计划.pdf"}}
  ▼
ToolRegistry.invoke("subagent.plan", args)
  │
  ▼
SubAgentTools.plan(...) ──► SubAgentRunner.run(PLANNER, instruction, parentSid)
        │                         │  独立 system prompt + 工具子集 + 局部 List<Msg> + maxSteps=6
        │                         │  内部多步: local.read_pdf -> planner.draft_goal_plan
        │                         │           -> goal.create(确认) -> task.create x N
        │                         ▼
        │                    SubAgentResult{ role, finalText:"已建目标《X》及8个任务", steps, toolsUsed }
        ▼
回灌主上下文（仅 finalText，PDF 全文与建库中间 JSON 全部留在子上下文，已丢弃）
  │
  ▼
主 Agent: "已为你建立目标《X》，拆出 8 个任务，其中 3 个标了 P0……"
```

---

## 4. 详细实现方案

### 4.1 新增文件清单

```
feature/agent/subagent/
  ├── SubAgentRole.java          # 角色枚举：systemPrompt 模板 + 工具 tag 子集 + maxSteps
  ├── SubAgentResult.java        # record：role / finalText / steps / toolsUsed / truncated
  ├── SubAgentRunner.java        # 子代理内核：独立 ReAct 循环（复用 LlmGateway/ToolRegistry/Parser）
  └── SubAgentExecutor.java      # 并行 fan-out：线程池 + AgentContext 跨线程传播

feature/agent/tool/impl/
  └── SubAgentTools.java         # 把子代理注册成 @AgentTool（subagent.plan/reflect/research/parallel_research）
```

### 4.2 改动文件清单（均为小改）

| 文件 | 改动（实际落地） | 目的 |
|---|---|---|
| `runtime/AgentContext.java` | 增加 `ThreadLocal<Integer> DEPTH`（`withInitial(()->0)`）+ `depth()/enterSub()/exitSub()`；`clear()` 一并 remove DEPTH | 防递归、识别主/子层级 |
| `runtime/PromptBuilder.java` | `build()` **未加 depth 形参**；改为给 `plan/reflect/learn` 三个 mode 的 tagFilter **加入 `subagent`**，`chat` 模式 `tagFilter=null` 天然含全部工具 | 主 Agent 各模式都能委派子代理 |
| `chat/AgentChatWebSocketHandler.java` | 新增 `sendSubAgentStart/sendSubAgentEnd` 两个 WS 事件 | 前端展示子代理卡片 |
| `static/agent/chat-panel.js` + `chat-panel.css` | 渲染「🤖 子代理」紫色折叠卡片（委派任务 + 内部工具链 chips + Markdown 结论 + 运行/完成/截断状态） | UI 可视化 |
| `application.properties` | 新增 `agent.subagent.*` 配置段（max-steps / result-max-chars / parallel.size / parallel.timeout-seconds） | 步数 / 并发 / 超时 / 截断可调 |

> 注意：`AgentOrchestrator.java` **零改动**（Agents-as-Tools 的核心红利）。
> 防递归未走「`depth>0` 移除 subagent tag」这条路，而是落到 **运行期双护栏**：
> ① `SubAgentTools.guardTopLevel()` 在 `depth>0` 时直接抛错；
> ② `SubAgentRunner.buildSystemPrompt` 用角色 tag 子集导出工具 schema，本就不含 `subagent.*`，
> 且 system prompt 显式声明「你不能委派其它子代理」。两者叠加，子代理无法递归套娃。

### 4.3 核心类骨架

#### (1) `SubAgentRole`

```java
public enum SubAgentRole {
    // 第一个形参是中文 label（供前端子代理卡片标题展示），随后才是 tag 子集 / maxSteps / systemPrompt
    PLANNER(
        "规划专家",
        Set.of("local", "kb", "planner", "task", "goal"),
        6,
        """
        你是规划专家子代理。目标：把用户素材（本地文档/目标描述）拆解为可执行的目标与任务并落库。
        步骤建议：必要时 local.read_file/read_pdf 读素材 -> planner.draft_goal_plan 拆解 ->
        goal.create 建目标 -> task.create 逐条建任务 -> goal.link_task 关联。
        完成后用 3~6 行中文总结"建了什么"，不要复述原文，不要输出 JSON。
        """),
    REFLECTION(
        "复盘专家",
        Set.of("insight", "task", "goal", "note", "kb"),
        6,
        """
        你是复盘专家子代理。聚合指定周期的分数/任务/目标/笔记，产出结构化复盘报告
        （亮点 / 问题 / 下一步建议）。用 Markdown 输出，简洁。
        """),
    RESEARCH(
        "检索专家",
        Set.of("kb", "note", "local"),
        6,
        """
        你是检索专家子代理。围绕给定问题做多跳检索：kb.semantic_search ->（必要时）
        kb.lookup_by_title / kb.list_backlinks / local.read_file。综合命中片段给出有出处的答案，
        引用笔记用 [[标题]]。无强相关命中需明示。
        """);

    private final String label;          // 角色中文名（前端卡片）
    private final Set<String> toolTags;
    private final int maxSteps;
    private final String systemPrompt;
    // 构造 + label()/toolTags()/maxSteps()/systemPrompt() getter 略
}
```

#### (2) `SubAgentResult`

```java
public record SubAgentResult(
        String role,
        String finalText,
        int steps,
        List<String> toolsUsed,
        boolean truncated) {}
```

#### (3) `SubAgentRunner`（内核）

设计要点（实际落地）：
- 复用 `LlmGateway.generateChat(messages)` + `ToolCallParser` + `ToolRegistry`，**不依赖** `AgentOrchestrator`（避免循环）。
- 用**局部 `List<Map<String,String>> msgs`** 当短期记忆，**绝不** 写 `ConversationMemory(sid)`。
- 工具子集 = `registry.exportSchemas(role.toolTags())`，在 `buildSystemPrompt` 内拼进 system prompt。
- 写工具确认走 `ToolConfirmCoordinator.askUser(AgentContext.sessionId(), ...)`——worker 线程的 sid 已被设为 parentSid，弹窗回到**主用户**的 WS。
- `AgentContext.enterSub()/exitSub()` 包裹核心循环，保证 depth 正确、user/sid 复用父线程已设置的值。
- 步数取 `min(role.maxSteps(), maxStepsCap)`；终态/截断文本统一 `truncate(.., resultMaxChars)`。
- **可视化**：注入 `@Lazy AgentChatWebSocketHandler`（破循环依赖），`run()` 拆为「生成 subId → emitStart → `doRun` 核心循环 → emitEnd」，事件失败仅 debug 日志、不影响主流程。

```java
@Component
public class SubAgentRunner {
    private final LlmGateway llm;
    private final ToolRegistry registry;
    private final ToolCallParser parser;
    private final ToolConfirmCoordinator confirm;
    private final ObjectMapper om;
    private final AgentChatWebSocketHandler ws;   // @Lazy 注入，避免循环依赖

    @Value("${agent.subagent.max-steps:6}")        private int maxStepsCap;
    @Value("${agent.subagent.result-max-chars:4000}") private int resultMaxChars;
    // 构造注入（ws 用 @Lazy）

    /** 包裹层：发可视化事件 + enterSub/exitSub，核心循环在 doRun。 */
    public SubAgentResult run(SubAgentRole role, String instruction, String parentSid) {
        String subId = UUID.randomUUID().toString();
        emitStart(parentSid, subId, role, instruction);   // → ws.sendSubAgentStart
        SubAgentResult result;
        AgentContext.enterSub();                           // depth++
        try {
            result = doRun(role, instruction);
        } catch (Exception e) {
            result = new SubAgentResult(role.name(), "子代理异常：" + e.getMessage(), 0, List.of(), true);
        } finally {
            AgentContext.exitSub();                        // depth--
        }
        emitEnd(parentSid, subId, role, result);           // → ws.sendSubAgentEnd
        return result;
    }

    /** 核心 ReAct 循环。 */
    private SubAgentResult doRun(SubAgentRole role, String instruction) {
        List<String> used = new ArrayList<>();
        int maxSteps = Math.min(role.maxSteps(), Math.max(1, maxStepsCap));
        List<Map<String,String>> msgs = new ArrayList<>();
        msgs.add(msg("system", buildSystemPrompt(role)));  // role.systemPrompt + 角色工具 schema + 输出协议
        msgs.add(msg("user", instruction));
        for (int step = 0; step < maxSteps; step++) {
            String raw = llm.generateChat(msgs);           // 失败则降级返回 truncated 结论
            ToolCallParser.ToolCall call = parser.parse(raw);
            if (call == null) {                            // 终态：自然语言结论
                return new SubAgentResult(role.name(),
                        truncate(parser.cleanForDisplay(raw), resultMaxChars), step, used, false);
            }
            used.add(call.name());
            msgs.add(msg("assistant", raw));
            ToolDefinition def = registry.get(call.name());
            if (def == null) { feed(msgs, call.name(), "{\"error\":\"UNKNOWN_TOOL\"}"); continue; }
            if (def.requiresConfirm()) {
                boolean ok = confirm.askUser(AgentContext.sessionId(), UUID.randomUUID().toString(),
                        call.name(), call.arguments()).get();   // 弹窗回主用户 WS
                if (!ok) { feed(msgs, call.name(), "{\"status\":\"USER_REJECTED\"}"); continue; }
            }
            feed(msgs, call.name(), safeInvoke(call));      // try/catch -> 错误 JSON 回灌（Reflexion）
        }
        return new SubAgentResult(role.name(),
                "（子代理已达最大步数 " + maxSteps + "，返回阶段性结论）", maxSteps, used, true);
    }
}
```

#### (4) `SubAgentTools`（代理即工具）

```java
@Component
public class SubAgentTools {
    private final SubAgentRunner runner;
    private final SubAgentExecutor executor;

    @AgentTool(name = "subagent.plan", tags = {"subagent"},
        description = "委派【规划专家】子代理：读取本地文档/目标描述并拆解为目标与任务落库。" +
                      "适合一次性的复杂建库，避免污染主对话上下文。")
    public Map<String,Object> plan(
        @ToolParam(value="instruction", desc="要规划的目标或需求", required=true) String instruction) {
        guardTopLevel();   // 断言 depth==0，禁止子代理再起子代理
        return wrap(runner.run(SubAgentRole.PLANNER, instruction, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.reflect", tags = {"subagent"},
        description = "委派【复盘专家】子代理：聚合一段周期的数据生成复盘报告。")
    public Map<String,Object> reflect(
        @ToolParam(value="instruction", desc="复盘范围与诉求，如'最近7天'", required=true) String instruction) {
        guardTopLevel();
        return wrap(runner.run(SubAgentRole.REFLECTION, instruction, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.research", tags = {"subagent"},
        description = "委派【检索专家】子代理：对一个问题做多跳知识库+文档检索综合。")
    public Map<String,Object> research(
        @ToolParam(value="question", desc="要深入检索的问题", required=true) String question) {
        guardTopLevel();
        return wrap(runner.run(SubAgentRole.RESEARCH, question, AgentContext.sessionId()));
    }

    @AgentTool(name = "subagent.parallel_research", tags = {"subagent"},
        description = "并行委派多个【检索专家】子代理处理多个子问题，汇总结果。适合'体检所有目标'等可拆分任务。")
    public Map<String,Object> parallelResearch(
        @ToolParam(value="questions", desc="子问题数组（建议<=4）", required=true) List<String> questions) {
        guardTopLevel();
        return executor.fanOut(SubAgentRole.RESEARCH, questions, AgentContext.sessionId());
    }
}
```

#### (5) `SubAgentExecutor`（并行 fan-out，亮点）

ThreadLocal 不跨线程，必须**显式捕获** user/sid 并在每个 worker 线程 set/clear：

```java
@Component
public class SubAgentExecutor {
    private final SubAgentRunner runner;
    private final ExecutorService pool;     // 固定大小=maxParallel 的 daemon 线程池，构造期建好
    private final int maxParallel;          // ${agent.subagent.parallel.size:4}
    private final int timeoutSeconds;       // ${agent.subagent.parallel.timeout-seconds:120}

    /** 汇总结构：{ role, count, results:[{question, finalText, steps, toolsUsed, truncated}] } */
    public Map<String,Object> fanOut(SubAgentRole role, List<String> tasks, String parentSid) {
        List<String> capped = tasks.stream()
            .filter(s -> s != null && !s.isBlank()).map(String::trim)
            .limit(maxParallel).toList();           // 去空 + 截断到并发上限
        if (capped.isEmpty()) return /* { role, count:0, results:[], note:"未提供有效子问题" } */;

        final User user = AgentContext.requireUser();   // 父线程取出，worker 重新注入
        List<CompletableFuture<SubAgentResult>> futs = capped.stream()
            .map(t -> CompletableFuture.supplyAsync(() -> {
                AgentContext.set(user, parentSid);      // ThreadLocal 跨线程显式传播
                try { return runner.run(role, t, parentSid); }
                finally { AgentContext.clear(); }
            }, pool)).toList();

        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();
        } catch (Exception e) { /* 超时/异常仅 warn，不抛 */ }

        // 用 getNow(null) 逐个收集：已完成填结果，未完成/异常降级标 truncated
        // ... 组装 results 后返回 { role, count, results }
    }

    @PreDestroy void shutdown() { pool.shutdownNow(); }   // 应用关闭时回收 worker
}
```

### 4.4 防递归与预算护栏（必做）

| 护栏 | 机制 |
|---|---|
| **禁止子代理再起子代理** | ① `SubAgentTools.guardTopLevel()` 在 `AgentContext.depth()>0` 时抛 `IllegalStateException`；② `SubAgentRunner` 按角色 tag 子集导出工具 schema，本就不含 `subagent.*`，且 system prompt 显式声明「不能委派其它子代理」（未采用「PromptBuilder 移除 tag」方案） |
| **步数预算** | 每个 `SubAgentRole.maxSteps`（默认 6），独立于主 `maxSteps=8` |
| **超时预算** | 单 worker `confirm` 60s（已有）；fan-out `allOf().orTimeout(120s)` |
| **并发上限** | 固定线程池 size=4，`questions` 截断到 4，防 DeepSeek 限流 |
| **结果截断** | `SubAgentResult.finalText` 回灌前 `truncate(.., 4000)`（复用主循环阈值），`truncated` 标志透传 |
| **并发安全** | fan-out 仅用于**只读** 角色（RESEARCH/REFLECTION）；写操作（PLANNER）保持串行单 worker |

### 4.5 配置段（`application.properties`）

```properties
# === SubAgent（实际落地的配置项）===
# 单个子代理最大推理步数（独立于主 agent.chat.max-steps）
agent.subagent.max-steps=6
# 子代理结论回灌主上下文前的最大字符数（截断防爆 token）
agent.subagent.result-max-chars=4000
# 并行 fan-out 固定线程池大小 = 子问题并发上限（同时也是截断上限）
agent.subagent.parallel.size=4
# 并行 fan-out 总超时（秒）：超时未完成的 worker 在汇总时降级标注
agent.subagent.parallel.timeout-seconds=120
```

> 当前未引入 `agent.subagent.enabled` 开关：子代理工具随 Bean 扫描默认注册启用，无需额外配置。

### 4.6 UI（已落地，增强版）

> 本节已在 S3 完整落地，非可选。

实现的是增强版可视化（增强叙事张力）：

- **后端事件**：`AgentChatWebSocketHandler` 新增两条 WS 消息
  - `subagentStart`：`{ msgType, subId, role, roleLabel, instruction }`
  - `subagentEnd`：`{ msgType, subId, role, roleLabel, finalText, steps, toolsUsed, truncated }`
- **前端渲染**（`chat-panel.js` + `chat-panel.css`）：一张「🤖 子代理 · {roleLabel}」的**紫色折叠卡片**，含三段
  - 委派任务（instruction）
  - 内部工具链（`toolsUsed` 渲染成 chips，结束时回填）
  - 结论（`finalText` 走 Markdown 渲染）
  - 右上角状态徽标：`推理中`（running）/ `完成 · N 步`（done）/ `已截断 · N 步`（warn）
- **双卡片叙事**：因 `subagent.*` 本身仍是工具，主循环原有的 `toolStart/toolResult` 普通工具卡片照常出现，
  叠加上更丰富的子代理卡片，视觉上强化「多代理编排」效果。

> 同步/并行调用都复用 `parentSid` 把 start/end 事件与确认弹窗准确送回主用户面板。

---

## 5. 分阶段落地（建议）

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **S1 内核** | `AgentContext.depth` + `SubAgentRole/Result/Runner` + `SubAgentTools`（plan/reflect/research）+ `PromptBuilder` 给非 chat 模式注入 `subagent` tag + `guardTopLevel` 运行期防递归 | 串行子代理可用，主循环零改 | ✅ 已完成 |
| **S2 并行** | `SubAgentExecutor`（固定线程池 + ThreadLocal 跨线程显式传播 + `orTimeout` 降级 + `@PreDestroy` 关池）+ `subagent.parallel_research`（固定 RESEARCH 角色） | Fan-out 多目标体检 | ✅ 已完成 |
| **S3 可视化** | WS `subagentStart/End` + `SubAgentRole.label()` 中文名 + 前端紫色子代理折叠卡片 | 演示效果拉满 | ✅ 已完成 |

> 每阶段结束 `read_lints` 零告警 + `gradlew.bat compileJava` 通过；
> `bootRun` 启动日志应出现新增工具：
> `[Agent] Registered N tools: [... subagent.plan, subagent.reflect, subagent.research, subagent.parallel_research]`

---

## 6. 面试讲点（30 秒电梯陈述）

> "我在单 Agent 上加了 **Orchestrator-Worker 多代理**：主 Agent 负责对话与编排，
> 把复杂子任务委派给专精子代理。子代理用 **独立上下文** 跑完多步推理只回压缩结论，
> 解决了长文档/多步建库把主对话上下文撑爆的问题；还支持 **并行 fan-out**，
> 多目标体检从串行变并行。落地巧思是 **Agents-as-Tools**——子代理注册成普通工具，
> 主循环零改动，并用 `depth` 护栏防止子代理递归套娃。"

七个支撑细节：上下文隔离省 token、Agents-as-Tools 零侵入、depth 防递归、
角色化工具子集（最小权限）、确认弹窗跨层回到主用户 WS、ThreadLocal 跨线程显式传播、Reflexion 错误回灌。

---

## 7. 已知边界 / 后续演进

- 子代理写操作的确认弹窗会"打断"看似自动的流程——可加"本会话信任规划专家"开关跳过确认。
- 当前局部记忆是进程内 List；若要子代理结果可追溯，可落 `NoteType.AGENT_MEMO` 或新增 `SUBAGENT_TRACE` 类型。
- 进一步可做 **动态角色**（让主 Agent 自己拟 system prompt 临时造一个一次性 worker）= 通用 `subagent.spawn(role, tools, instruction)`。
