# Agent 优化方案候选清单（待选择 / 审核）

> 面向 **Agent 开发岗位** 的优化方案。每项给出：现状痛点（附真实代码位置）→ 方案设计 → 面试价值 → 成本 → 风险。
>
> **请在文末"决策表"里勾选，我按你的选择再出详细实施计划。**

---

## 判断前提：当前项目的三个真实短板

在提方案前先说清楚我的判断依据。项目功能层已经相当完整（ReAct 内核、子代理、MCP 双向、CRAG、RAG Serving、Prefix Cache、多模型路由、可观测层），真正的短板是这三个：

| 短板 | 证据 | 对 Agent 岗位的杀伤力 |
|---|---|---|
| **无法证明 Agent 可靠** | `src/test` 只有 `MemorandumApplicationTests.java`（空壳） | ⚠️ **致命**。"你怎么评估 Agent 效果"是必问题，答不上等于前面全白讲 |
| **上下文管理是最朴素的实现** | `ConversationMemory` 固定 30 条窗口硬截断；`truncate(json, 4000)` | ⚠️ **高**。"上下文工程"是当前 Agent 最核心话题 |
| **交互是阻塞式的** | `LlmGateway.generateChat()` 一次性返回，无流式 | ⚠️ **中高**。所有 Agent 产品都要流式，会被问 TTFT |

下面 10 个方案按这三个短板 + 岗位对口度排序。

---

# 第一梯队 · 强烈推荐（面试价值最高）

## 方案 A · Agent 评测体系（轨迹回放 + 指标）

### 现状痛点

无任何 Agent 层测试。改一行 prompt 或调一次工具描述，**完全不知道是否让效果变差了**。之前踩的两个坑（CRAG grade 信号丢失、指标恒为 0）都是因为没有端到端验证。

### 方案设计

核心难点是 **LLM 非确定性导致无法写传统断言**。解法是 VCR（录制/回放）模式：

```
1) 抽接口：LlmGateway → interface LlmClient（现有实现改名 HttpLlmClient）
2) 录制模式：真实跑一遍，把每次 (messages_hash → response) 存成 JSON 落盘
3) 回放模式：测试时注入 ReplayLlmClient，按 hash 命中返回录制的响应
   → 整条 ReAct 链路、工具调用、CRAG 分支都真实执行，只有 LLM 被固定
4) 断言 DSL：
   assertTrajectory(sid)
       .calledTool("kb.semantic_search")
       .thenCalledTool("task.create")
       .stepsWithin(6)
       .finalAnswerContains("已创建")
       .cragGradeWas(INCORRECT)
       .degradedSignalReachedLlm(true);   ← 专门防"坑 2"那类信息传递断裂
```

**要落的指标（跑一批用例后输出报告）**：

| 指标 | 含义 |
|---|---|
| 工具选择准确率 | 期望工具集 vs 实际调用工具集 |
| 步数分布 | P50/P95 步数，发现工具循环 |
| 任务完成率 | 端到端断言全通过的用例占比 |
| 幻觉率 | 编造不存在的 id / 工具名的次数 |
| 降级正确率 | 该降级时真的降级了的比例 |

**种子用例（8~10 个够用）**：建任务、查笔记（命中）、查笔记（不命中→应降级）、读本地文档、读文档后生成规划、触发子代理、工具失败自纠、越权路径被拒。

### 面试价值 ★★★★★

能回答这些必问题：
- "你怎么评估 Agent 效果？" → 有指标、有回归集
- "改了 prompt 怎么知道没变差？" → 回放测试跑一遍看指标
- "LLM 不确定性怎么测？" → **录制回放 + 分层断言**（这个答案很有含量）
- "你的 Agent 成功率多少？" → 直接给数字

**这是唯一能把"我做了个 Agent"升级成"我做了个可工程化的 Agent"的方案。**

### 成本

中等。新增 ~6 个文件：`LlmClient` 接口、`ReplayLlmClient`、`RecordingLlmClient`、`TrajectoryAssert`、`AgentEvalRunner`、用例资源目录。改动 1 处（`LlmGateway` 抽接口，其余调用方不动）。

### 风险

- `messages_hash` 要稳定 → 正好复用已做的**前缀字节稳定化**（否则录制永远命不中，这是个隐藏依赖，我会在实施时处理）
- 录制文件会有一定体积，需要 gitignore 策略

---

## 方案 B · 上下文工程：Token 预算 + 滚动摘要

### 现状痛点

```java
// ConversationMemory：按"条数"截断
agent.chat.history-window=30

// AgentOrchestrator：工具结果按"字符数"硬切
memory.append(sid, "user", "[tool_result " + tool + "]\n" + truncate(resultJson, 4000));
```

两个问题：
1. **条数 ≠ token 数**。30 条长消息可能远超模型上下文；30 条短消息又浪费了可用窗口
2. **硬截断会切掉关键信息**。一个返回 20 条命中的检索结果被切在第 8 条中间，剩下的信息直接丢失且 LLM 不知道丢了

### 方案设计

```
┌─ Token 预算分配（总预算按模型上下文的 70% 设定）──────┐
│ system prefix   : 固定，实测约 3~5k（工具 schema 占大头）│
│ 长期记忆         : 上限 500 token                      │
│ 近期对话         : 剩余预算的 70%                       │
│ 滚动摘要         : 剩余预算的 30%                       │
└──────────────────────────────────────────────────┘
```

**三个机制**：

1. **Token 估算**：不引入 tokenizer 依赖，用启发式（中文 ≈ 字符数/1.5，英文 ≈ 字符数/4），误差 10% 内够用。**面试时要主动说这是估算而非精确计数**

2. **滚动摘要替代丢弃**：超预算时把最老的 N 条对话用一次 LLM 压成一条摘要，而不是直接删。摘要本身进 history 头部，标注 `[早期对话摘要]`

3. **分级压缩策略**（这是设计亮点）：不同内容压缩优先级不同
   ```
   用户原始意图     → 永不压缩
   最近 3 轮        → 永不压缩
   工具结果         → 优先压缩（保留 meta，正文可摘要）
   早期对话         → 滚动摘要
   ```

### 面试价值 ★★★★★

"上下文工程"是当前 Agent 最热话题，能回答：
- "上下文满了怎么办？" → 预算管理 + 分级压缩 + 滚动摘要
- "怎么决定丢什么？" → 按信息价值分级，不是简单 FIFO
- "怎么算 token？" → 说清估算方案及误差，以及为什么不引 tokenizer

配合已有的**子代理上下文隔离**，可以讲成一套完整的"上下文治理"故事：*隔离（子代理）+ 复用（前缀缓存）+ 压缩（本方案）*。

### 成本

中等。改 `ConversationMemory`（加预算逻辑）、新增 `TokenEstimator` + `ContextCompressor`，`AgentOrchestrator` 接入点 1 处。

### 风险

摘要要花一次 LLM 调用 → 只在超预算时触发，且失败时退回原截断逻辑（保持可降级原则）

---

## 方案 C · 流式输出 + TTFT 优化

### 现状痛点

`LlmGateway.generateChat()` 阻塞式一次性返回。用户发一条消息，要等完整响应（可能 10~30 秒，reasoner 模型更久）才看到任何东西。**这是当前最明显的体验硬伤。**

### 方案设计

关键技术点是**流式与工具调用解析的冲突**——这也是最好的面试谈资：

> 流式的前提是"边收边显示"，但工具调用需要**完整 JSON 才能解析**。如果无脑流式，用户会看到 `{"tool":"task.crea` 这种半截 JSON 闪过。

我的解法是**两阶段判别**：

```
收到首个 delta
  ├─ 若首个非空白字符是 '{' 或 '`'  → 判定为疑似工具调用
  │     → 不推送给前端，静默累积，等完整后走 ToolCallParser
  │     → 前端只显示"思考中…"（已有的 loading 态）
  └─ 否则 → 判定为终态自然语言
        → 逐 delta 推 WebSocket，前端增量渲染
```

**实现要点**：
- 请求体加 `"stream": true`
- Java `HttpClient` 用 `BodyHandlers.ofLines()` 逐行读 `data: {...}`，解析 `choices[0].delta.content`
- WebSocket 新增 `assistantDelta` 消息类型，前端累积拼接
- reasoner 模型的 `<think>` 段要在流式阶段就过滤（不能等结束才 strip）

**顺带能报的指标**：TTFT（首 token 延迟）。这个数字配合前缀缓存的 `promptCacheHitTokens`，能讲出"缓存命中让 TTFT 降低"的完整因果链。

### 面试价值 ★★★★☆

- "怎么优化首 token 延迟？" → TTFT 指标 + 前缀缓存 + 流式
- "流式和工具调用怎么共存？" → **两阶段判别**（这个回答有区分度，大多数人没想过）
- 体验层面：demo 时流式 vs 阻塞的观感差距巨大

### 成本

中等偏高。后端改 `LlmGateway`（加流式方法，保留原方法）+ `AgentOrchestrator`（终态分支走流式）+ `AgentChatWebSocketHandler`（新消息类型）；前端改 `chat-panel.js` 增量渲染。

### 风险

- 前后端联调工作量占一半
- reasoner 的 think 段流式过滤有边界情况（`<think>` 标签可能被切在两个 delta 之间，需要跨 delta 缓冲）

---

# 第二梯队 · 低成本高性价比（建议全做）

## 方案 D · 显式 Reflexion + 失败模式策略

### 现状

只有隐式 Reflexion（错误 JSON 回灌）。设计文档里规划的"连续 2 次同名工具失败强制换工具"**未实现**。

### 方案

在 `AgentOrchestrator` 里加一个轮内失败计数：

```java
Map<String, Integer> failCount = new HashMap<>();   // 单轮内

// 工具失败时
int n = failCount.merge(call.name(), 1, Integer::sum);
if (n >= 2) {
    resultJson = injectStrategyHint(resultJson, call.name(), n);
    // 追加强制指令：
    // "⚠️ 工具 X 已连续失败 2 次。禁止再次调用它。
    //   请改用其他工具，或直接向用户说明遇到的问题。"
}
```

**三种失败模式差异化处理**（这是设计深度）：

| 失败类型 | 处理策略 |
|---|---|
| 参数错误 | 回灌 schema 期望值，引导修参数（可重试） |
| 资源不存在 | 提示先检索获取真实 id（换工具） |
| 权限/白名单拒绝 | 直接引导向用户说明（不重试） |

### 面试价值 ★★★☆☆

- "Agent 卡住了怎么办？" → 失败计数 + 强制换策略
- "Reflexion 怎么落地？" → 隐式回灌 + 显式策略注入两层

### 成本

低。改 `AgentOrchestrator` 一处 + 一个小工具类。

---

## 方案 E · 工具参数前置校验（配合 D）

### 现状

参数错误要等到反射调用时 Jackson `treeToValue` 抛异常才发现，回灌给 LLM 的是笼统的 `{"error":"IllegalArgumentException"}`，**信息量太低导致它很难自修复**。

### 方案

调用前按 `@ToolParam` 声明校验，失败时返回**精确的可操作错误**：

```json
{
  "error": "INVALID_ARGUMENTS",
  "tool": "task.create",
  "issues": [
    {"param":"title", "problem":"缺少必填参数"},
    {"param":"dueDate", "problem":"类型错误", "expected":"string(yyyy-MM-dd)", "got":"number"}
  ],
  "hint": "请修正上述参数后重试"
}
```

实测这类"精确错误"能显著提升 LLM 一次自修复成功率——**这个提升可以用方案 A 的评测体系量化出来**，两个方案互相成就。

### 面试价值 ★★★☆☆

体现"为 LLM 设计错误信息"的意识：错误信息的受众是模型不是人，要可操作。

### 成本

低。`ToolRegistry.invokeLocal` 前加校验方法。

---

## 方案 F · Token 计量与会话预算

### 现状

已经解析了 `usage` 的 prompt cache 字段，但**没有累计 token 与成本**。

### 方案

- 复用现有 usage 解析，累计 `prompt_tokens` / `completion_tokens`
- 按模型单价表估算成本（配置化）
- 单会话预算上限，超限时：先警告 → 再拒绝新一轮，给用户明确提示
- 指标进已有的 `/api/observability/stats`

### 面试价值 ★★★☆☆

- "Agent 成本怎么控？" → 计量 + 预算 + 超限降级
- 能报"单次对话平均成本"这类具体数字

### 成本

低。已有 usage 解析基础，主要是加累计器 + 配置单价表。

---

# 第三梯队 · 有价值但成本高（建议暂缓）

## 方案 G · Plan-and-Execute 显式规划

当前 ReAct 是"边想边做"的隐式规划。可增加：复杂任务先产出显式计划 → 展示给用户确认 → 逐步执行、可中断调整。

**面试价值高**（规划能力是 Agent 核心考察点），**但成本高**（新的状态机 + 前端计划视图 + 中断恢复语义）。

> 折中做法：只做"计划预览"——委派子代理前把计划展示给用户确认，复用已有的确认弹窗机制。成本降到 1/5，也能讲出 Plan-and-Execute 的思路。

## 方案 H · 并行工具调用

当前 system prompt 明确限制"一次只能调一个工具"。放开为工具数组 + 无依赖并行，可降延迟。已有 `SubAgentExecutor` 线程池可复用。

**难点**：依赖判断（`task.create` 依赖 `goal.create` 的返回 id），做不好会引入难查的时序 bug。**建议暂缓。**

## 方案 I · 会话持久化

内存态 `ConversationMemory` 重启即丢，刷新页面也丢。需要 `agent_session` / `agent_message` 两张表。

偏通用工程而非 Agent 特性，**面试价值一般**。若时间紧可跳过，被问到时说清"当前是内存态，生产要持久化 + 用工作流引擎做长任务"即可。

## 方案 J · Prompt 版本管理与 A/B

把 prompt 外置 + 版本号 + 灰度对比。**依赖方案 A 的评测体系才有意义**（否则无法判断哪版更好）。若做了 A 且还有时间可考虑。

---

# 第四梯队 · 前沿实践吸收（2026-08 新增）

> 来源：DeepSeek Harness 架构分析。**已剔除场景错配的部分**（Cordis 可逆副作用 / HMR 热更新 / Code Mode / preset 两层 scope 链）——
> 那些是「平台级 Agent 产品」的需求，本项目是个人单机助手，引入即过度设计。
> 下面两项是经场景筛选后**真正契合本项目**的部分。

## 方案 K · 工具运行时可见性（分层遮蔽）

### 现状痛点

工具可见性靠 `PromptBuilder.resolveTagFilter(mode)` 的 OR 语义 tag 白名单，有三个真实缺陷：

1. **只能放行不能禁止**：`note.create` 的 tags 是 `{note, write}`，`learn` 模式放行了 `note`
   → **learn 模式下 Agent 依然能写笔记**（`reflect` 模式同理能建任务）。这是现存 bug，不是假设
2. **只是提示层约束，不是执行层约束**：`registry.get()` 查全量注册表，模型凭上下文记忆调用被过滤掉的工具
   → **能调通**。同构于方案 D 的教训：*只做提示层，模型完全可能无视*
3. **会话级临时屏蔽完全不存在**；`SubAgentRole.toolTags` 与 mode 白名单是两套独立体系，
   子代理**不受主对话模式约束**

### 方案

四层 scope 链 **GLOBAL → MODE → ROLE → SESSION**，运行时实时计算可见集：
allow（收窄）/ deny（累积剔除）/ pin（破例，可覆盖 deny）/ 结构性保留（不可被 pin 解除，如子代理不得见 `subagent.*`）。
执行层增加拦截：不可见工具短路返回 `TOOL_NOT_VISIBLE` 并接入 `ReflexionAdvisor`（不可重试，一次即封禁）。

**与已完成工作的接缝**：静态可见性（K）+ 动态封禁（D）= **两层权限治理**
——K 决定"你本来能碰什么"，D 决定"你这轮已经把什么碰坏了"。

**顺带修掉 3 个隐患**：
- 度量缺陷：`hallucinationRate` 把"工具不存在"与"越界调用"混算 → **幻觉率被高估**
- 静默遮蔽：本地与 MCP 同名工具 schema 重复导出，但 invoke 永远走本地
- 补上「坑 3」欠账：tag 悬空引用的启动自检（面试手册里承诺过但一直没做）

### 面试价值 ★★★★☆

- "工具权限怎么治理？" → 分层遮蔽 + 可解释决策链 + 执行层强制
- "怎么证明执行层强制有必要？" → `notVisibleBlocked` > 0（与 D 的 `bannedToolCallsBlocked` 同构）
- **能讲清"哪些 DSH 语义被刻意裁剪及为什么"** → 证明不是照搬

### 成本 / 风险

中。新增 6 类，改动 11 处，无迁移。最大风险是**过度收窄导致无工具可用**（坑 3 形态）
→ 启动自检对空可见集报 error；降级路径要求 `exportSchemas` 输出**字节级一致**（否则破坏评测录制）。

> 详细设计：`docs/design/Agent工具运行时可见性-分层遮蔽设计.md`

## 方案 L · 轮次收尾解耦（TurnStopping + 主动衔接）

### 现状痛点

1. **收尾时机硬编码**：`call == null` 就 `sendDone` + `return`，无任何外部干预点。
   加一种"还没完"的理由必须改主循环
2. **主动式能力与对话割裂**：晨报/晚报走独立轮询通道，
   用户刚做完复盘，系统明知"明天 3 个任务到期"却无法在对话里接一句
3. **截断是静默的**：`truncate(resultJson, 4000)` 切掉了 **LLM 不知道、用户更不知道**，
   `convergenceRate` 因此把"丢过数据但蒙对了"算成正常收敛 → **偏乐观**

### 方案

- `TurnEndReason` 枚举 + `TurnOutcome` 统一收尾出口（4 条散落的结束路径归一）
- **粘性降级标记**：本轮发生截断 / CRAG degraded / 子代理截断，置位后**不可清除**
  （借鉴 DSH 的 max-tokens 粘性设计）
- `TurnStoppingAdvisor` 钩子：收尾前询问，可返回 steer 消息让轮次继续
- 两个内置顾问：**降级明示**（软约束→硬闭环，延续 CRAG 思路）、**主动衔接**（对接晨报/晚报）
- `concludesTurn` 工具提前收尾——**默认关闭**（会吞掉多步意图，收益/风险比不同于 coding agent）

### 面试价值 ★★★★☆

- "怎么让 Agent 主动？" → 收尾钩子 + 结构信息判定语境（与"角色感知预取"同一类思路）
- "截断了怎么办？" → 粘性降级标记 + 强制明示；**在没能力不丢信息之前，先做到不隐瞒**
- "steer 会不会失控？" → Bus 强制上限，顾问绕不过（与 D 的"执行层强制"同立场）+ 恶意顾问测试桩

### 成本 / 风险

低。新增 6 类，改动 6 处，不改 WebSocket 协议与 Prompt。
最大风险是 **steer 无限循环**（无限 LLM 调用）→ 硬上限 + 剩余步数保护 + 专项测试。

> **L1+L2 阶段不引入任何行为变化**，纯补齐轮次归因可观测性，
> 却能立刻拿到 `degradedTurns` —— **这是决定方案 B 优先级的硬数据依据**。

> 详细设计：`docs/design/Agent轮次收尾解耦-TurnStopping设计.md`

---

# 推荐组合

按你的时间预算，我给三个档：

### 档位一：最小可信（推荐指数 ★★★★★）

**A（评测体系）+ D + E**

理由：A 直接补掉最致命短板，D/E 成本极低且能被 A 量化验证（"加了参数前置校验后，工具自修复成功率从 x% 提升到 y%" —— 这句话在面试里非常有力）。三者形成闭环。

### 档位二：均衡（推荐指数 ★★★★☆）

**A + B + D + E + F**

在档位一基础上加 B（上下文工程，最对口）和 F（成本控制）。这套打完，Agent 岗位的核心考点基本都能覆盖：可靠性、上下文、成本、鲁棒性。

### 档位三：完整（时间充裕）

**A + B + C + D + E + F**

加上 C（流式），demo 观感质变，且多一个 TTFT 优化的技术话题。

---

# 我的建议

**优先做 A。** 理由不只是面试：

1. 它是**其他所有优化的度量衡**。做 B 的上下文压缩，怎么知道压缩后效果没退化？做 E 的参数校验，怎么证明自修复率提升了？都靠 A
2. 它能**反向发现存量 bug**。我们已经踩过两个"组件都对但集成断了"的坑（CRAG 信号丢失、指标恒为 0），有回归集会提前暴露
3. 面试中它是**唯一能把项目从"做过"提升到"做好"的证明**

**其次 D + E**（半天内能完成，且立刻能被 A 验证）。

**B 和 C 二选一**：如果面试偏底层/Infra 选 B（上下文工程更有技术含量）；如果面试偏应用/产品选 C（流式体验更直观）。

---

# 决策表（请勾选）

| 方案 | 内容 | 梯队 | 成本 | 面试价值 | 选择 |
|---|---|---|---|---|---|
| **A** | Agent 评测体系（录制回放 + 指标） | 一 | 中 | ★★★★★ | ✅ **已完成** |
| **B** | Token 预算 + 滚动摘要 + 分级压缩 | 一 | 中 | ★★★★★ | 📄 **设计待审核** |
| **C** | 流式输出 + TTFT（两阶段判别） | 一 | 中高 | ★★★★☆ | ☐ |
| **D** | 显式 Reflexion + 失败模式策略 | 二 | 低 | ★★★☆☆ | ✅ **已完成** |
| **E** | 工具参数前置校验 | 二 | 低 | ★★★☆☆ | ✅ **已完成** |
| **F** | Token 计量与会话预算 | 二 | 低 | ★★★☆☆ | 📄 **设计待审核** |
| **G** | Plan-and-Execute（或仅计划预览） | 三 | 高/中 | ★★★★☆ | ☐ |
| **H** | 并行工具调用 | 三 | 中高 | ★★★☆☆ | ☐ |
| **I** | 会话持久化 | 三 | 中 | ★★☆☆☆ | ☐ |
| **J** | Prompt 版本管理与 A/B | 三 | 中 | ★★★☆☆ | ☐ |
| **K** | 工具运行时可见性（分层遮蔽） | 四 | 中 | ★★★★☆ | ✅ **K1~K5 已交付** |
| **L** | 轮次收尾解耦（TurnStopping + 主动衔接） | 四 | 低 | ★★★★☆ | ✅ **L1~L6 已交付** |

> **档位一（A + D + E）已交付**，实施记录见文末「附录：D + E 实施记录」。
>
> **B / F 详细设计已产出**（尚未实施），见：
> - `docs/design/Agent上下文工程-Token预算与分级压缩设计.md`
> - `docs/design/Agent-Token计量与会话成本预算设计.md`
>
> 两份文档均含：现状问题（附真实代码位置）→ 设计 → 改动清单 → 配置项 → 指标 → 验收标准 → 风险降级 → 分阶段实施 → 面试要点。
> **审核重点**：B §4.4 的分级策略、B §7.3 的验证局限、F §4.4 的拦截点选择、F §4.2 的三段式成本模型。
>
> **K / L 详细设计已产出**（尚未实施），见：
> - `docs/design/Agent工具运行时可见性-分层遮蔽设计.md`
> - `docs/design/Agent轮次收尾解耦-TurnStopping设计.md`
>
> **审核重点**：K §3.4「刻意裁剪掉的 DSH 语义」（这节决定方案是"吸收"还是"照搬"）、
> K §3.5 执行层强制的必要性、K 验收第 10 条（降级必须字节级一致，否则破坏评测录制）；
> L §3.3 粘性降级的三处接线、L §3.5 为何 `concludesTurn` 默认关闭、L §3.7 steer 失控防护。
>
> **两者的推荐起步**：K1+K2（修掉 learn 模式能写笔记的真实 bug，不改执行路径）、
> L1+L2（零行为变化，纯补轮次归因可观测性，产出 `degradedTurns` 作为方案 B 的立项依据）。

**或直接告诉我档位**：档位一 / 档位二 / 档位三。

选定后我会出详细实施计划（文件清单、改动点、验收标准、面试话术更新），再开始写代码。

---

# 附：两条硬性约束（无论选哪个）

1. **可降级**：所有新能力必须有开关，关闭时行为与现状一致。这是项目既有的设计原则（6 个开关全关仍可用），不能破坏
2. **可观测**：每个新能力必须有对应指标进 `/api/observability/stats`。这是"坑 4"（指标恒为 0）教给我的——**没有消费方的指标等于没有指标**

---

# 附录：D + E 实施记录

## 交付清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `feature/agent/tool/ToolArgumentValidator.java` | E 核心：参数前置校验 |
| 新增 | `feature/agent/runtime/ReflexionAdvisor.java` | D 核心：失败分类 + 策略 + 单轮封禁状态机 |
| 改动 | `feature/agent/tool/ToolRegistry.java` | `invoke` 前置校验；`jsonType` 委托给校验器 |
| 改动 | `feature/agent/runtime/AgentOrchestrator.java` | 接入两层 Reflexion + 执行层阻断 + 埋点 |
| 改动 | `feature/agent/subagent/SubAgentRunner.java` | 同样接入 D（子代理步数预算更紧，收益更高） |
| 改动 | `runtime/trace/AgentTraceListener/Bus/Metrics` | 新增 5 个事件与 `argValidation` / `reflexion` 指标分区 |
| 改动 | `controller/ObservabilityController.java` | `trace/stats` 增加 config 回显 |
| 新增测试 | `agenteval/unit/ToolArgumentValidatorTest.java` | 21 个用例 |
| 新增测试 | `agenteval/unit/ReflexionAdvisorTest.java` | 17 个用例 |

新增配置（均默认开启，关闭即回到改造前行为）：

```properties
agent.chat.reflexion.enabled=true
agent.chat.reflexion.fail-threshold=2
agent.tool.validate-args=true
```

## 相对原计划的三处升级

原计划只写了「注入强制指令」，实施时补了三点：

### 1. 建议层之外加了执行层强制

原计划的 `injectStrategyHint` 只是在回灌内容里追加「禁止再次调用」。**问题是这完全依赖模型遵从**——它可以无视。

实施时增加了硬约束：工具进入 `banned` 后，模型若再次调用，`AgentOrchestrator` 直接短路返回 `TOOL_BANNED_THIS_TURN`，**工具不真正执行**。

配套指标 `reflexion.bannedToolCallsBlocked` 恰好能回答「这层是否必要」：若长期为 0，说明模型听劝，强制层是冗余保险；一旦不为 0，就是「只做提示层不够」的实证。

### 2. 失败模式从 3 类扩到 6 类

原计划列了参数错误 / 资源不存在 / 权限拒绝三类。实施时补了三类，都对应真实故障：

| 补充模式 | 可重试 | 为何必要 |
|---|---|---|
| `USER_REJECTED` | ✗ | 用户拒绝确认后，模型可能反复弹窗骚扰用户，甚至换等效工具绕过。提示里显式禁止绕过 |
| `UNKNOWN_TOOL` | ✗ | 反复调用同一个编造的工具名是真实的循环来源。原实现只统计幻觉率，不干预 |
| `TRANSIENT` | ✓ | 超时/网络类失败若被当成死路会过早放弃，与参数错误需要区别对待 |

**可重试性是这套设计的主轴**：不可重试的模式（`DENIED` / `USER_REJECTED` / `UNKNOWN_TOOL` / `RESOURCE_NOT_FOUND`）**一次即封禁**，不受 `fail-threshold` 约束——花第二步去证明权限拒绝还是会拒绝，是纯粹的浪费。

### 3. 「宁松勿严」成为 E 的首要约束

原计划只描述了错误格式。实施时发现真正的风险在反面：**校验器若拒掉了 Jackson 本来能成功反序列化的调用（假阳性），就凭空制造了一次失败，比完全不校验更糟。**

所以类型判定刻意接受 Jackson 默认的标量强转（`"3"` → int、`2026` → String），只拦截必然失败的形状错误（对象/数组喂给标量参数）。单元测试里「宽容度」用例（`Tolerance` 嵌套类，7 个）比「拦截」用例更受重视。

同理，**未知参数不单独判失败**——模型多传一个无害字段是常见的，历史行为是静默忽略且调用成功。但未知参数会作为诊断线索附在错误里，这刚好覆盖最常见的真实故障：把 `title` 写成 `name` 时必填缺失已触发失败，`unknownParams` 让模型立刻明白是**名字错了**而不是漏传了。

## 一处顺带修掉的一致性隐患

`ToolRegistry.exportSchemas`（给 LLM 看的 schema）与校验器（判定期望类型）各写一份类型映射，是典型的后期漂移来源——两处不一致时，模型会按 schema 填参却被校验拒掉，且极难排查。已让 `ToolRegistry.jsonType` 委托给 `ToolArgumentValidator.jsonTypeOf`，单一事实来源。

顺带修正了原映射漏掉的类型：`Short`/`Byte`（原落到 `object`）与枚举（原落到 `object`，应为 `string`）。

## 新增指标（`GET /api/agent/trace/stats`）

```json
{
  "argValidation": {
    "argumentsRejected": 12,
    "rejectionRate": 0.0857,
    "topRejectedParams": {"task.create.title": 5, "goal.link_task.taskId": 3}
  },
  "reflexion": {
    "repairAttempts": 12,
    "repairSuccesses": 10,
    "selfRepairRate": 0.8333,
    "strategyHintsInjected": 14,
    "bannedToolCallsBlocked": 2,
    "failureModes": {"INVALID_ARGUMENTS": 9, "RESOURCE_NOT_FOUND": 3, "DENIED": 2}
  },
  "config": {
    "reflexionEnabled": true,
    "reflexionFailThreshold": 2,
    "argValidationEnabled": true
  }
}
```

`config` 分区是刻意加的：看到 `selfRepairRate=0.83` 却不知道校验开没开，这个数字就没有解释力，也无法做前后对比。

## 怎么产出「自修复率从 x% 提升到 y%」这个数字

这是 A + E 互相成就的地方，也是面试里最有力的一句话。步骤：

1. `agent.tool.validate-args=false` 重启 → 跑一批会触发参数错误的对话 → 记 `reflexion.selfRepairRate`（这是**基线**）
2. 改回 `true` 重启 → 跑同一批 → 再记 `selfRepairRate`
3. 差值即为 E 的效果

**注意**：`selfRepairRate` 的定义是「某工具在本轮失败过、之后重试并成功」的比例。分母是修复尝试次数而非失败次数——模型压根不重试的情况不计入分母，否则会把「模型放弃了」和「修复失败了」混为一谈。

> 我用的是**同一批对话**而不是随机流量。真实评测应该用方案 A 的录制回放集来跑，但录制盒是按 `(caseId, callIndex)` 回放固定响应的，模型不会真的重新决策，**观测不到自修复行为**。所以这个对比只能用真实调用（录制模式或线上）做——面试被追问时要主动说清这个限制，别把回放测试的结果说成自修复率。

## 验证状态

| 项 | 结果 |
|---|---|
| 主代码编译 | 通过，0 lint 错误 |
| L1 单元测试 | **38 个新用例全部 PASSED**（连同既有 9 个共 47 个） |
| Spring 上下文装配 | 正常（4 处构造器变更均为自动注入） |
| 既有评测 fixture | 2/2 仍 PASSED，无回归 |

## 边界（面试要主动说的）

1. **失败分类是字符串/字段级识别，不是统一异常体系**。工具实现分散在多个模块，统一异常体系更彻底但改动面太大；而分类只要准到「能选对策略」即可，误判的代价仅是给了一条次优提示，不影响正确性。这是有意识的取舍，不是偷懒。
2. **封禁是单轮作用域，不跨轮**。用户下一轮可能已补齐信息，继承封禁会误伤。代价是同一个死路在多轮里可能被重新撞一次。
3. **没做重试退避/次数分级**。`TRANSIENT` 类失败只是「允许再试一次」，没有指数退避——Agent 的每一步都是一次 LLM 调用，成本远高于网络重试，靠模型自己决定比机械退避更合适。
