# Agent 轮次收尾解耦 · TurnStopping 与主动衔接设计（方案 L）

> **一句话**：把"这一轮什么时候真正结束"从 ReAct 循环内部的硬编码条件，变成**可被外部拦截的钩子**——让主动式能力（晨报/晚报的对话内延续）、降级明示、未完成意图检测都能挂进来，而循环本身不需要预先知道有多少种"还没完"的理由。
>
> 灵感来源：DeepSeek Harness 的 `agent/turn-stopping` 事件 + `steer()` 机制、结束原因多源、以及 max-tokens 状态的粘性设计。

---

## 0. TL;DR

| 维度 | 内容 |
|---|---|
| **核心能力** | ① 轮次结束原因显式化为枚举；② 收尾前广播 `turnStopping`，顾问可注入 steer 消息让轮次继续；③ **降级标记粘性**——本轮发生过截断/降级就不会被后续正常步骤覆盖；④ 工具可声明 `concludesTurn` 提前收尾（默认关闭） |
| **产品落点** | 直接支撑 `主动式Agent-晨报晚报.md` 的**对话内延续**：复盘收尾前主动追问"明天有 3 个任务到期，要帮你排期吗" |
| **顺带修掉** | `truncate(resultJson, 4000)` 的**静默截断**——现在切掉了没人知道，粘性降级标记让它变成可观测、可消费的信号 |
| **降级** | `agent.chat.turn-stopping.enabled=false` → 行为与现状完全一致 |
| **成本** | 低。新增 3 个类 + 2 个内置顾问，改动 4 处 |

---

## 1. 现状痛点（附真实代码位置）

### 1.1 收尾时机硬编码在循环内部，没有任何外部干预点

```java
// AgentOrchestrator.handleUserTurn
if (call == null) {
    String finalAnswer = parser.cleanForDisplay(llmRaw);
    if (finalAnswer.isBlank()) finalAnswer = "（模型未返回内容，请重试或换一个表述）";
    memory.append(sid, "assistant", finalAnswer);
    trace.finalAnswer(sid, step, finalAnswer);
    ws.sendAssistant(sid, finalAnswer);
    ws.sendDone(sid);
    return;                    // ← 直接 return，无任何可插入点
}
```

结束条件只有三种，全部写死在循环体里：

| 条件 | 位置 | 语义 |
|---|---|---|
| 模型没输出工具调用 | `call == null` | 终态答复 |
| 步数耗尽 | `for` 循环自然结束 | 未收敛 |
| LLM 调用失败 | catch 分支 | 异常中止 |

**想加任何"还没完"的理由，都必须改 `AgentOrchestrator` 主循环。** 这违背了项目已有的解耦思路——工具、确认策略、Reflexion 都做了抽象层，唯独"收尾时机"是硬编码的。

### 1.2 主动式能力与对话是两条完全割裂的链路

`docs/design/主动式Agent-晨报晚报.md` 已实现晨报/晚报，但走的是**独立通道**：

```
DailyReportScheduler → ProactiveReportService → GET /report/pending
    → Electron 60s 轮询 → 系统桌面通知
```

这条链路**完全绕过 Agent 对话**。后果是：用户刚在对话里做完复盘，Agent 说完"以上是本周复盘"就结束了——**即使系统明明知道"明天有 3 个任务到期"**，也没有任何机制让它在这句话后面接一句。

该文档 §9 列的拓展方向第 3 条正是"应用内 Inbox"，但那仍是**推送**思路。真正自然的形态是**在对话收尾的那一刻延续**——而这需要一个收尾钩子。

### 1.3 截断是静默的（真实的信息丢失）

```java
// AgentOrchestrator.appendToolTrace
String body = "[tool_result " + tool + "]\n" + truncate(resultJson, 4000);
```

```java
// SubAgentRunner —— 子代理结论也截断，且 SubAgentResult.truncated 只用于前端卡片
truncate(finalText, resultMaxChars)
```

`docs/Agent优化方案候选.md` 方案 B 已经点出这个问题：

> **硬截断会切掉关键信息**。一个返回 20 条命中的检索结果被切在第 8 条中间，剩下的信息直接丢失**且 LLM 不知道丢了**。

现状是：截断发生了，`truncated` 标记只进了子代理的前端卡片，**主循环层面完全不记录**，轮次结束时也不体现。用户看到一个语气笃定的答复，不知道它是基于被切掉一半的数据。

### 1.4 结束原因粒度太粗，无法归因

`AgentTraceMetrics` 只有三个计数器：

```java
outcome.put("finalAnswers", ...);
outcome.put("stepsExhausted", ...);
outcome.put("llmFailures", ...);
```

**"正常收敛"是个黑盒**——无法区分"干净地完成了"和"中途丢过数据但最后蒙对了"。`convergenceRate` 因此是**偏乐观**的：分子里混着降级完成的轮次。

---

## 2. 设计目标

| # | 目标 | 验收信号 |
|---|---|---|
| 1 | 收尾时机可被外部拦截 | 新增一种"还没完"的理由**不需要改** `AgentOrchestrator` |
| 2 | 结束原因显式且可归因 | 能区分干净完成 / 降级完成 / 步数耗尽 / 工具提前收尾 |
| 3 | 降级标记**粘性** | 第 2 步截断过，第 5 步正常完成，轮次仍标记 `degraded=true` |
| 4 | 主动衔接落到对话内 | 复盘类对话收尾前能自动追问明日待办 |
| 5 | steer 不会失控 | 单轮 steer 次数硬上限，且不可被顾问绕过 |
| 6 | 完全可降级 | 关闭后与现状行为一致 |

---

## 3. 方案设计

### 3.1 结束原因显式化

```java
/** 轮次结束原因。刻意区分「干净完成」与「降级完成」——后者此前被算作前者。 */
public enum TurnEndReason {
    /** 模型给出终态自然语言答复 */
    FINAL_ANSWER,
    /** 工具声明本轮可收尾（§3.5，默认关闭） */
    TOOL_CONCLUDED,
    /** 步数耗尽未收敛 */
    STEPS_EXHAUSTED,
    /** LLM 调用失败中止 */
    LLM_FAILURE,
    /** 前缀构造失败等启动期异常 */
    SETUP_FAILURE
}
```

```java
/**
 * 单轮收尾上下文。传给顾问的**只读视图** + 一个可写的 steer 出口。
 *
 * <p>degraded 是**粘性**的：一旦置位，任何后续步骤都无法清除（§3.3）。
 */
public final class TurnOutcome {
    private final String sessionId;
    private final String mode;
    private final String userInput;
    private TurnEndReason reason;
    private String finalAnswer;
    private int usedSteps;
    private final List<String> toolsUsed;
    private boolean degraded;                 // 粘性
    private final Set<String> degradeCauses;  // TRUNCATED / CRAG_DEGRADED / SUBAGENT_TRUNCATED / TOOL_BANNED
    private int steerCount;

    /* 只读访问器省略 */

    /** 仅允许置位，不允许清除 —— 粘性的实现点。 */
    void markDegraded(String cause) {
        this.degraded = true;
        if (cause != null) degradeCauses.add(cause);
    }
}
```

### 3.2 TurnStoppingAdvisor 钩子

```java
/**
 * 轮次收尾顾问。在轮次**真正关闭前**被调用，可返回一条 steer 消息让轮次继续。
 *
 * <p>实现约定（与 AgentTraceListener 同源）：
 * <ul>
 *   <li>必须非阻塞、不抛异常——异常由 Bus 吞掉降级为日志；</li>
 *   <li>返回 {@code Optional.empty()} 表示"我没意见，可以结束"；</li>
 *   <li>返回非空表示"还没完"，内容会作为 user 消息回灌，循环继续；</li>
 *   <li><strong>必须自己保证幂等/收敛</strong>——同一轮不要反复 steer 同一件事。</li>
 * </ul>
 *
 * <p>Spring 注入所有实现为 List，无实现时为空列表（零配置可选）。
 */
public interface TurnStoppingAdvisor {

    /** 顾问名，用于埋点与日志归因。 */
    String name();

    /** 越小越先执行；首个返回非空的顾问胜出，其余不再询问。 */
    default int order() { return 0; }

    Optional<String> onTurnStopping(TurnOutcome outcome);
}
```

**为什么是"首个非空胜出"而非收集全部**：多条 steer 拼在一起会让模型面对多个互相干扰的指令，反而降低收敛质量。一次只推进一件事，符合 ReAct 的单步语义。

### 3.3 降级标记的粘性（对齐 DSH 的 max-tokens 粘性）

DSH 的原始逻辑：

```javascript
if (turnEnds === null || turnEnds.kind !== 'max-tokens') turnEnds = stepEnd
```

即：**一旦因输出上限被截断，后续正常步骤不得覆盖这个结束原因**。

你的项目没有 max-tokens 截断检测，但有**语义等价物**——三类真实的信息丢失：

| 降级原因 | 触发点 | 现状 |
|---|---|---|
| `TRUNCATED` | `appendToolTrace` 里 `truncate(resultJson, 4000)` 真的切掉了内容 | 完全静默 |
| `CRAG_DEGRADED` | 工具结果 JSON 首项含 `degraded: true` | 只靠 prompt 软约束让模型明示 |
| `SUBAGENT_TRUNCATED` | 子代理结论被截断或 `truncated=true` | 只进前端卡片 |
| `TOOL_BANNED` | 本轮有工具被 Reflexion 封禁 | 有指标但不影响轮次结论 |

实现（`truncate` 改为返回是否发生截断）：

```java
// 原 truncate 无返回信号，改为显式告知调用方
private String truncateAndFlag(String s, int max, TurnOutcome outcome, String cause) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    outcome.markDegraded(cause);          // ← 粘性置位，此后不可清除
    return s.substring(0, max) + "...[truncated]";
}
```

**为什么粘性很重要**：不粘的话，第 2 步截断了、第 5 步模型正常收敛，轮次会被记成"干净完成"，`convergenceRate` 被高估。而这正是 1.4 节指出的现存问题。粘性让"这一轮丢过数据"成为**不可抹除的事实**。

> 这与项目里 CRAG 的思路一脉相承：*把提示词软约束升级为运行时硬闭环*。CRAG 让检索质量差变成结构化信号，这里让信息丢失变成结构化信号。

### 3.4 主循环改造

```java
// AgentOrchestrator.handleUserTurn —— 关键改动
TurnOutcome outcome = new TurnOutcome(sid, mode, userInput);

for (int step = 0; step < maxSteps; step++) {
    ...
    if (call == null) {
        String finalAnswer = parser.cleanForDisplay(llmRaw);
        if (finalAnswer.isBlank()) finalAnswer = "（模型未返回内容，请重试或换一个表述）";
        outcome.propose(TurnEndReason.FINAL_ANSWER, finalAnswer, step);

        // L · 收尾前询问顾问：有人要 steer 就不结束
        Optional<String> steer = turnStopping.consult(outcome);
        if (steer.isPresent()) {
            memory.append(sid, "user", steer.get());
            trace.turnSteered(sid, step, outcome.lastAdvisorName(), outcome.steerCount());
            continue;                       // ← 轮次继续，不 sendDone
        }

        closeTurn(sid, outcome);             // 统一收尾出口
        return;
    }
    ...
}
// 步数耗尽同样走顾问与统一出口
outcome.propose(TurnEndReason.STEPS_EXHAUSTED, exhaustedMessage(maxSteps), maxSteps);
closeTurn(sid, outcome);
```

```java
/** 统一收尾：所有结束路径的唯一出口，保证埋点与 WS 语义一致。 */
private void closeTurn(String sid, TurnOutcome outcome) {
    memory.append(sid, "assistant", outcome.finalAnswer());
    trace.turnEnd(sid, outcome.reason().name(), outcome.usedSteps(),
                  outcome.degraded(), outcome.degradeCauses());
    ws.sendAssistant(sid, outcome.finalAnswer());
    ws.sendDone(sid);
}
```

**注意 `closeTurn` 本身就是一项收益**：当前 4 条结束路径各自散落 `sendAssistant` + `sendDone` + `trace`，容易漏埋点（`stepsExhausted` 分支现在就没写 `memory.append`，导致耗尽提示不进历史——一个既存的小不一致，顺手统一掉）。

### 3.5 `concludesTurn`：工具提前收尾（默认关闭）

**动机（可量化收益）**：`subagent.delegate` 委派的子代理已经产出了完整的自然语言结论，主 Agent 拿到后**往往只是转述一遍**——这是一次纯粹浪费的 LLM 调用。

**约定**：工具返回值可含两个下划线前缀字段：

```json
{
  "role": "REFLECTION",
  "finalText": "【亮点】...【问题】...【下一步建议】...",
  "_concludesTurn": true,
  "_finalText": "【亮点】...【问题】...【下一步建议】..."
}
```

编排层处理：

```java
if (concludeEnabled && looksConcluding(resultJson)) {
    String text = extractFinalText(resultJson);
    if (text != null && !text.isBlank()) {
        outcome.propose(TurnEndReason.TOOL_CONCLUDED, text, step);
        Optional<String> steer = turnStopping.consult(outcome);   // 仍要过顾问
        if (steer.isEmpty()) {
            metrics.recordLlmCallSaved();
            closeTurn(sid, outcome);
            return;
        }
        memory.append(sid, "user", steer.get());
        continue;
    }
}
```

**为什么默认关闭**（`agent.chat.tool-conclude.enabled=false`）：

风险很明确——**用户的意图可能是多步的**。"帮我复盘这周，然后把没做完的任务顺延到下周"，若复盘工具声明 `concludesTurn`，第二个意图会被吞掉。这是真实的正确性风险，不是理论风险。

因此：
1. 默认关闭，属于**性能优化而非正确性前提**（对齐晨报/晚报文档"预生成只是性能优化"的同一原则）
2. 只有**明确适合**的工具才声明——首批仅 `subagent.delegate`（单一委派场景）
3. 即使声明了，**仍要经过顾问**——顾问可以否决（比如检测到用户输入含"然后""再"等多意图信号）

> 这是我对 DSH 该特性的**保守化改造**。DSH 是 coding agent，工具调用密度高、往返成本占比大；个人规划助手的一轮通常只有 2~5 步，省一次 LLM 调用的收益远小于吞掉用户意图的代价。**收益/风险比不同，默认值就该不同。**

### 3.6 两个内置顾问

#### 顾问 1 · `ProactiveFollowUpAdvisor`（产品落点，对接晨报/晚报）

```java
/**
 * 主动衔接顾问：把「晨报/晚报」的信息供给接进对话收尾时刻。
 *
 * <p>解决 §1.2 的割裂：系统明明知道"明天有 3 个任务到期"，
 * 却只能靠 60s 轮询弹桌面通知，无法在用户刚做完复盘的那一刻自然接上。
 *
 * <p><strong>复用而非新建</strong>：数据全部来自既有的
 * {@code TaskService.getTodayActionableTasks} / {@code DailyReportService}，
 * 本顾问只负责"在什么时机、以什么措辞"接入。
 */
@Component
public class ProactiveFollowUpAdvisor implements TurnStoppingAdvisor {

    @Override public String name() { return "proactive-follow-up"; }
    @Override public int order() { return 100; }   // 让降级明示顾问先行

    @Override
    public Optional<String> onTurnStopping(TurnOutcome o) {
        if (!enabled) return Optional.empty();
        // 触发条件必须严格，否则变成骚扰
        if (o.steerCount() > 0) return Optional.empty();                 // 每轮最多接一次
        if (o.reason() != TurnEndReason.FINAL_ANSWER
                && o.reason() != TurnEndReason.TOOL_CONCLUDED) return Optional.empty();
        if (o.degraded()) return Optional.empty();                       // 本轮已降级，别再加负担
        if (!isReflectiveTurn(o)) return Optional.empty();               // 仅复盘类语境
        if (alreadyMentionsFollowUp(o.finalAnswer())) return Optional.empty();  // 模型已经说了

        int dueCount = safeCountTomorrowDue();                           // 全链路容错，异常返回 0
        if (dueCount < threshold) return Optional.empty();               // 默认 3

        return Optional.of("""
            [系统补充信息] 明天有 %d 个任务即将到期。
            请在你上面的答复末尾，用一句自然的中文主动询问用户是否需要你帮忙排期，
            不要重复已经说过的内容，不要调用任何工具，直接给出完整的最终答复。
            """.formatted(dueCount));
    }
}
```

**触发条件为什么这么严**：主动性一旦过度就是骚扰。六道闸门缺一不可——这与晨报/晚报的"时间窗 + 每日一次闸门"是同一种设计克制。

**判定"复盘类语境"的方式**：`mode == "reflect"` 或本轮用过 `insight.*` / `subagent.delegate(REFLECTION)`。这是**结构信息**而非语义猜测——和"角色感知预取"（创新 4）用的是同一类信息：*我知道刚才发生了什么，所以能预判接下来该说什么*。

#### 顾问 2 · `DegradeDisclosureAdvisor`（把软约束变硬闭环）

```java
/**
 * 降级明示顾问：本轮发生过信息丢失，但答复里没有任何提示 → 强制补一句。
 *
 * <p>现状是靠 CRAG 的 message 字段和 system prompt 的软约束，模型可能不遵守。
 * 这里在收尾前做一次**确定性检查**——这是 CRAG「硬闭环」思路在轮次层面的延伸。
 */
@Override
public Optional<String> onTurnStopping(TurnOutcome o) {
    if (!o.degraded()) return Optional.empty();
    if (o.steerCount() > 0) return Optional.empty();
    if (containsDisclosure(o.finalAnswer())) return Optional.empty();   // 已明示，放行

    return Optional.of("""
        [系统提示] 本轮存在信息不完整的情况（原因：%s）。
        你的答复没有向用户说明这一点。请重新给出最终答复，
        在开头简短说明信息可能不完整（例如"部分数据因过长被截断"），
        然后给出你的结论。不要调用工具。
        """.formatted(o.degradeCauses()));
}
```

**这个顾问的价值最容易被低估**：它把 1.3 节的静默截断变成了**用户可见的诚实声明**。方案 B（上下文工程）要真正解决截断，成本是中等；而这个顾问用极低成本先把"至少让用户知道丢了东西"做到位。**在没能力不丢信息之前，先做到不隐瞒**——与项目里"能力不完整时明确禁用并返回 `WRITE_DISABLED`，比留一个半通的功能让用户踩坑更负责"是同一个价值判断。

### 3.7 steer 失控防护

```java
@Component
public class TurnStoppingBus {

    private final List<TurnStoppingAdvisor> advisors;   // 按 order 排序

    @Value("${agent.chat.turn-stopping.max-steer-per-turn:1}")
    private int maxSteer;

    public Optional<String> consult(TurnOutcome outcome) {
        if (!enabled) return Optional.empty();

        // 硬上限：顾问无法绕过（不信任顾问自律，与方案 D「执行层强制」同理）
        if (outcome.steerCount() >= maxSteer) {
            metrics.recordSteerRejected();
            return Optional.empty();
        }
        // 剩余步数不足时不 steer —— steer 会消耗至少一步，不能把轮次推进死路
        if (outcome.usedSteps() >= maxSteps - 1) {
            metrics.recordSteerRejected();
            return Optional.empty();
        }
        for (TurnStoppingAdvisor a : advisors) {
            try {
                Optional<String> r = a.onTurnStopping(outcome);
                if (r.isPresent() && !r.get().isBlank()) {
                    outcome.recordSteer(a.name());
                    return r;
                }
            } catch (Exception e) {
                log.debug("[TurnStopping] 顾问 {} 异常（已忽略）：{}", a.name(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
```

**两道硬上限的必要性**：steer 让循环 `continue`，若顾问实现有 bug（每次都返回非空），就是一个**无限循环 + 无限 LLM 调用 + 无限烧钱**的故障。`maxSteer` 由 Bus 强制、顾问无法绕过——这与方案 D 的"执行层强制而非只做建议层"是完全相同的设计立场：**不能把正确性押在实现者的自觉上**。

第二道（剩余步数检查）防的是另一种形态：在最后一步 steer，导致轮次以"步数耗尽"结束，用户什么都没拿到。

### 3.8 与子代理的关系

`SubAgentRunner` **首批不接入**。理由：

- 子代理步数预算只有 6，steer 会挤占本就紧张的预算
- 子代理的产出是给主 Agent 消费的中间结果，"主动追问用户"在子代理层面没有语义

但 `TurnOutcome` 的**降级标记要向上传播**：子代理结论被截断时，主 Agent 的 `TurnOutcome` 应置位 `SUBAGENT_TRUNCATED`。这是让 `DegradeDisclosureAdvisor` 能覆盖子代理场景的关键接线——否则子代理丢了信息，主 Agent 无从得知（这正是「坑 2」那类"组件间信息传递断了"的典型形态，必须显式接线）。

---

## 4. 改动清单

### 4.1 新增

| 文件 | 职责 |
|---|---|
| `feature/agent/runtime/turn/TurnEndReason.java` | 结束原因枚举 |
| `feature/agent/runtime/turn/TurnOutcome.java` | 轮次上下文（含粘性 degraded） |
| `feature/agent/runtime/turn/TurnStoppingAdvisor.java` | 顾问接口 |
| `feature/agent/runtime/turn/TurnStoppingBus.java` | 分发 + 异常隔离 + steer 硬上限 |
| `feature/agent/runtime/turn/advisor/DegradeDisclosureAdvisor.java` | 内置顾问 1 |
| `feature/agent/runtime/turn/advisor/ProactiveFollowUpAdvisor.java` | 内置顾问 2（依赖 `feature/report` 已有服务） |

### 4.2 改动

| 文件 | 改动点 |
|---|---|
| `runtime/AgentOrchestrator.java` | 引入 `TurnOutcome`；4 条结束路径统一走 `closeTurn`；收尾前 `consult`；`truncate` 改为带降级标记版本 |
| `subagent/SubAgentRunner.java` | 结论截断时向上传播降级原因（新增可选 `TurnOutcome` 参数或回调） |
| `runtime/trace/AgentTraceListener.java` | 新增 `onTurnEnd` / `onTurnSteered`（default 空实现） |
| `runtime/trace/AgentTraceBus.java` | 对应 emit |
| `runtime/trace/AgentTraceMetrics.java` | 新增 `turnEnd` 指标分区；修正 `convergenceRate` 语义 |
| `controller/ObservabilityController.java` | config 回显新开关 |

> **不需要改动**：`PromptBuilder`、`ToolRegistry`、`ReflexionAdvisor`、WebSocket 协议。steer 消息只进 `ConversationMemory`（LLM 上下文），**不推 WebSocket**——与方案 D 的策略提示同一处理原则：*系统内部引导不该让用户在 UI 上看到*。

---

## 5. 配置项

```properties
# 总开关：false 时不询问任何顾问，行为与现状一致
agent.chat.turn-stopping.enabled=true

# 单轮 steer 硬上限（Bus 强制，顾问无法绕过）
agent.chat.turn-stopping.max-steer-per-turn=1

# 工具提前收尾：默认关闭（§3.5 说明风险）
agent.chat.tool-conclude.enabled=false

# 内置顾问独立开关
agent.chat.turn-stopping.degrade-disclosure.enabled=true
agent.chat.turn-stopping.proactive-follow-up.enabled=true
agent.chat.turn-stopping.proactive-follow-up.due-threshold=3
```

> 每个顾问独立开关：出问题时可精确关掉单个顾问，不必关闭整套机制。

---

## 6. 指标（`GET /api/agent/trace/stats`）

```json
{
  "turnEnd": {
    "reasons": {
      "FINAL_ANSWER": 40,
      "TOOL_CONCLUDED": 0,
      "STEPS_EXHAUSTED": 2,
      "LLM_FAILURE": 1
    },
    "degradedTurns": 6,
    "degradeRate": 0.1395,
    "degradeCauses": {"TRUNCATED": 4, "CRAG_DEGRADED": 2, "SUBAGENT_TRUNCATED": 1},
    "cleanConvergenceRate": 0.7907,
    "steerInjected": 5,
    "steerByAdvisor": {"degrade-disclosure": 3, "proactive-follow-up": 2},
    "steerRejectedByLimit": 1,
    "llmCallsSavedByConclude": 0
  }
}
```

| 指标 | 回答什么问题 |
|---|---|
| `degradedTurns` / `degradeCauses` | **本方案最有价值的数字**。它第一次量化了"我的 Agent 有多少轮是在丢过信息的情况下作答的"。`TRUNCATED` 占比高就直接证明方案 B（上下文工程）的必要性——**L 为 B 提供了立项依据** |
| `cleanConvergenceRate` | 修正后的收敛率（排除降级轮次）。与原 `convergenceRate` 的差值即为"被高估的部分" |
| `steerByAdvisor` | 哪个顾问真的在起作用。若 `proactive-follow-up` 长期为 0，说明触发条件过严或场景是伪需求 |
| `steerRejectedByLimit` | >0 说明有顾问想反复 steer，需检查其幂等性——**这是防失控机制真的在工作的证据** |
| `llmCallsSavedByConclude` | `concludesTurn` 的实际收益，用于决定是否值得默认开启 |

---

## 7. 验收标准

| # | 场景 | 预期 |
|---|---|---|
| 1 | 普通对话，无顾问命中 | 行为与改造前一致，`reasons.FINAL_ANSWER` +1 |
| 2 | 第 2 步工具结果超 4000 字符，第 5 步正常收敛 | `degraded=true`、`degradeCauses=[TRUNCATED]`、`reason=FINAL_ANSWER`（**粘性验证**） |
| 3 | 同上，且答复未提及信息不完整 | `DegradeDisclosureAdvisor` 注入 steer，最终答复含降级说明 |
| 4 | 同上，但答复已含"部分数据被截断" | 顾问**不**注入（`containsDisclosure` 命中） |
| 5 | reflect 模式复盘完成，明日 3 个任务到期 | `ProactiveFollowUpAdvisor` 注入，答复末尾含主动询问 |
| 6 | 同上，但明日仅 1 个任务到期 | 不注入（低于阈值） |
| 7 | 顾问实现每次都返回非空 | 单轮只 steer 1 次，`steerRejectedByLimit` +1，**无无限循环** |
| 8 | 在倒数第 1 步触发 steer | 不 steer（剩余步数保护） |
| 9 | 顾问抛异常 | 被 Bus 吞掉，轮次正常结束，主链路无感 |
| 10 | 子代理结论被截断 | 主 `TurnOutcome` 出现 `SUBAGENT_TRUNCATED` |
| 11 | `turn-stopping.enabled=false` | 不询问任何顾问；轨迹与改造前一致 |
| 12 | `tool-conclude.enabled=true` + 委派子代理 | `TOOL_CONCLUDED` +1，`llmCallsSavedByConclude` +1 |

第 7 条是**最关键的安全验收**——必须专门写一个"恶意顾问"测试桩。

---

## 8. 风险与降级

| 风险 | 影响 | 应对 |
|---|---|---|
| **steer 无限循环** | 无限 LLM 调用，成本失控 | Bus 强制 `maxSteer`（顾问无法绕过）+ 剩余步数保护 + 验收第 7 条专项测试 |
| 主动追问变骚扰 | 体验倒退 | 六道触发闸门 + 独立开关 + `steerByAdvisor` 观测；阈值可配 |
| `concludesTurn` 吞掉多步意图 | **正确性问题** | 默认关闭；仅白名单工具声明；仍需过顾问 |
| steer 消息污染上下文 | 挤占历史窗口 | steer 只在收尾时注入，单轮最多 1 条；措辞短 |
| 顾问引入循环依赖 | 启动失败 | `ProactiveFollowUpAdvisor` 依赖 `feature/report` 的服务，注意 `@Lazy`（`AgentOrchestrator` 已有 `@Lazy AgentChatWebSocketHandler` 先例） |
| 降级明示让答复变啰嗦 | 体验打折 | 只在**真降级且未明示**时触发；措辞要求"简短说明" |

---

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| **L1** | `TurnEndReason` / `TurnOutcome` / `closeTurn` 统一出口 + `turnEnd` 埋点 | 验收 1；**此时还没有顾问，纯粹是可观测性提升** |
| **L2** | 粘性降级标记（truncate/CRAG/子代理三处接线） | 验收 2、10 |
| **L3** | `TurnStoppingAdvisor` + Bus + 失控防护 | 验收 7、8、9、11 |
| **L4** | `DegradeDisclosureAdvisor` | 验收 3、4 |
| **L5** | `ProactiveFollowUpAdvisor` | 验收 5、6 |
| **L6** | `concludesTurn`（默认关闭） | 验收 12 |

**建议先做 L1+L2**：不引入任何行为变化，纯粹补齐"轮次归因"这层可观测性，就能立刻拿到 `degradedTurns` 这个数字——**它是决定要不要做方案 B 的关键依据**。若实测截断率极低，方案 B 的优先级就该下调；若很高，方案 B 就有了硬数据支撑。

---

## 10. 面试话术

**开场（从产品缺口切入）**：

> 我的系统有个割裂：一边是 Agent 对话，一边是主动式的晨报/晚报推送，两条链路完全不通。用户刚在对话里做完本周复盘，Agent 说完就结束了——**即使系统明明知道"明天有 3 个任务到期"**，也没有任何机制让它在这句话后面自然接一句。
>
> 根因是我的 ReAct 循环里，"什么时候算这一轮结束"是硬编码的：模型没输出工具调用就 `sendDone` 然后 `return`。**想加任何"还没完"的理由，都得改主循环。**

**方案**：

> 我参考 DSH 的 `turn-stopping` 事件，把收尾时机做成了钩子：轮次真正关闭前广播一次，顾问可以返回一条 steer 消息让循环继续。这样"还没完的理由"变成可插拔的——主循环不需要预先知道有多少种。
>
> 落了两个顾问。一个是**主动衔接**，复盘类对话收尾前检查明日待办，够多就追问一句要不要排期。另一个我觉得更有价值，是**降级明示**。

**降级明示 + 粘性（这是最有含量的部分）**：

> 我原来有个静默问题：工具结果超过 4000 字符就硬截断，**切掉了没人知道**——LLM 不知道，用户更不知道，他看到的是一个语气笃定的答复。
>
> DSH 里有个细节我很受启发：它的 max-tokens 结束状态是**粘性**的，某一步被截断过，后面步骤正常完成也不能把结束原因覆盖回"正常"。我把这个思路搬过来：本轮只要发生过截断、CRAG 降级、或子代理结论被切，就置一个**只能置位不能清除**的 degraded 标记。收尾时如果答复里没提这件事，顾问就强制它补一句。
>
> 这么做的连带收益是**修正了一个被高估的指标**：我原来的收敛率把"中途丢过数据但最后蒙对了"也算成正常收敛。现在多了一个 `cleanConvergenceRate`，两者的差值就是被高估的部分。

**失控防护（体现工程意识）**：

> steer 让循环 `continue`，所以最大的风险是**无限循环 + 无限 LLM 调用**。我把单轮 steer 上限做在 Bus 里强制执行，顾问绕不过去；还加了一道剩余步数保护，避免在最后一步 steer 导致用户什么都拿不到。
>
> 这个立场和我之前做 Reflexion 时一样——**不能把正确性押在实现者的自觉上**。我专门写了一个"每次都返回非空"的恶意顾问测试桩来验证。

**保守化改造（体现不是照搬）**：

> DSH 还有个特性是工具可以声明 `concludesTurn` 提前收尾，能省一次 LLM 调用。我实现了但**默认关闭**。
>
> 原因是收益/风险比不同。DSH 是 coding agent，工具调用密度高、往返成本占比大；我这是个人规划助手，一轮通常 2~5 步，省一次调用的收益，远小于"用户说'先复盘，然后把没做完的顺延到下周'时把第二个意图吞掉"的代价。**同一个特性，场景不同默认值就该不同。**

**为下一步铺路（很能体现规划感）**：

> 这个方案还有个副作用我挺满意：它产出的 `degradedTurns` 和 `degradeCauses` 是我**决定要不要做上下文工程改造的依据**。如果实测截断率很低，那套 token 预算 + 分级压缩就不急；如果很高，它就有了硬数据支撑。**先量化问题，再决定投入。**

---

## 11. 边界（不做什么）

| 不做 | 理由 |
|---|---|
| 收集全部顾问的 steer 并合并 | 多条指令互相干扰，降低收敛质量；一次推进一件事符合 ReAct 语义 |
| 把 steer 推给前端展示 | 系统内部引导，用户看到"[系统补充信息]"只会困惑（与 D 的策略提示同处理） |
| 子代理接入 steer | 步数预算仅 6，且"主动追问用户"在子代理层无语义。仅传播降级标记 |
| 基于 LLM 判断"用户意图是否多步" | 引入额外 LLM 调用与不确定性。`concludesTurn` 默认关闭已足够安全 |
| 事件总线做成异步 | 顾问要影响控制流，必须同步；异步的可观测事件已有 `AgentTraceBus` |

---

## 12. 与现有文档的定位关系

- `主动式Agent-晨报晚报.md` —— **本方案的产品上游**：提供信息供给（明日待办、Insight 得分），本方案提供"在对话里说出来"的时机
- `Agent上下文工程-Token预算与分级压缩设计.md`（方案 B）—— **本方案为其提供立项数据**（`degradeCauses.TRUNCATED`）
- `Agent工具运行时可见性-分层遮蔽设计.md`（方案 K）—— 同批次创新点，一个管"能碰什么"，一个管"什么时候算完"
- `AI-Infra-CRAG-SelfRAG实现计划.md` —— `CRAG_DEGRADED` 降级原因的来源；本方案把 CRAG 的硬闭环思路提升到轮次层面
