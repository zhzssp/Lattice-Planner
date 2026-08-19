# Agent 工具运行时可见性 · 分层遮蔽设计（方案 K）

> **一句话**：把"当前 Agent 能看到哪些工具"从**编译期硬编码的 tag 白名单**，升级为**运行时按 scope 链实时计算的可解释视图**。
>
> 灵感来源：DeepSeek Harness 的分层工具遮蔽算法。但**不照搬**——本文第 3.4 节说明哪些语义被刻意裁剪，以及为什么。

---

## 0. TL;DR

| 维度 | 内容 |
|---|---|
| **核心能力** | 四层 scope 链（GLOBAL → MODE → ROLE → SESSION）实时计算工具可见集，支持**显式禁用**（deny）与**显式钉住**（pin），并对每个工具给出"为什么可见/不可见"的决策链 |
| **补上的缺口** | ① 现在只能"放行"不能"禁止"；② 会话级临时屏蔽完全不存在；③ 模式语义散落在 `PromptBuilder` 的 switch 里 |
| **顺带修掉两个隐患** | ① **度量缺陷**：工具幻觉与"越界调用"混算进 `hallucinationRate`；② **静默遮蔽**：MCP 与本地工具同名时 schema 重复导出但 invoke 永远走本地 |
| **与已有工作的接缝** | 与 `ReflexionAdvisor`（动态封禁）组成**两层权限治理**：静态可见性 + 动态封禁 |
| **降级** | `agent.tool.visibility.enabled=false` → 完全回退到现有 `resolveTagFilter` 行为，字节级一致 |
| **成本** | 中。新增 5 个类，改动 5 处接入点，无数据库迁移 |

---

## 1. 现状痛点（附真实代码位置）

### 1.1 只有"放行"语义，没有"禁止"

```java
// PromptBuilder.resolveTagFilter —— 唯一的可见性控制点
private Set<String> resolveTagFilter(String mode) {
    return switch (mode == null ? "chat" : mode) {
        case "plan"    -> Set.of("task","goal","planner","kb","read","write","subagent","mcp");
        case "reflect" -> Set.of("task","goal","insight","note","kb","read","subagent","mcp");
        case "learn"   -> Set.of("kb","note","read","subagent","mcp");
        default        -> null;   // chat = 全部
    };
}
```

```java
// ToolRegistry.exportSchemas —— OR 语义，命中任一 tag 即保留
if (tagFilter != null && !tagFilter.isEmpty()
        && t.tags().stream().noneMatch(tagFilter::contains)) {
    continue;
}
```

**问题**：tag 过滤是 **OR 语义的并集放行**。想表达"learn 模式下不要任何写工具"，只能靠**不写 `write` tag**来间接实现。但工具往往同时带多个 tag——`note.create` 的 tags 是 `{note, write}`，`learn` 模式放行了 `note`，于是 **`note.create` 在 learn 模式下依然可见**。

这不是假设，这是当前的真实行为：`learn` 模式的注释写着"纯检索问答"，但它能写笔记。

### 1.2 会话级屏蔽完全不存在

产品场景很自然：用户在某轮对话里想说"这次别动我的任务，只帮我查"。当前**无任何机制**支持——只能切换整个思维模式，而模式是全局偏好，切换成本高且影响其它页面。

### 1.3 角色层是编译期常量，且与 mode 层语义割裂

```java
// SubAgentRole —— enum 常量，编译期固定
PLANNER("规划专家", Set.of("mcp","kb","planner","task","goal"), 6, "...")
```

`SubAgentRole.toolTags()` 与 `PromptBuilder.resolveTagFilter()` 是**两套独立的白名单**，共用 `exportSchemas` 但互不知晓。后果是**子代理的可见集不受主对话模式约束**——在 `learn` 模式下委派 `PLANNER` 子代理，它照样能建任务、写目标。模式的语义边界被子代理直接绕过。

### 1.4 「坑 3」的根因至今未治

`docs/面试讲解手册.md` 坑 3 记录了 `toolTags` 里留着已下线的 `local` tag，导致子代理调用不存在的工具、浪费步数。当时的结论是：

> 工具是用注解+字符串 tag 弱关联的，编译器查不出来。**如果重来，我会加一条启动自检**：校验每个角色的 toolTags 至少能命中一个已注册工具。

这条自检**一直没做**。本方案把它作为交付项之一。

### 1.5 两个隐患（本方案顺带修掉）

**隐患 A · 度量缺陷：幻觉与越界被混为一谈**

```java
// AgentOrchestrator：def == null 就记 UNKNOWN_TOOL
if (def == null) {
    trace.unknownTool(sid, step, call.name());   // → hallucinationRate
```

但 `registry.get(name)` 查的是**全量注册表**，而 LLM 看到的是**过滤后的 schema**。所以：

- 工具**根本不存在**（模型编的）→ `def == null` → 记幻觉 ✔ 正确
- 工具**存在但当前不可见**（模型从历史上下文里记得它）→ `def != null` → **被正常执行！**

**第二种情况是真正的越界**：`learn` 模式下模型凭记忆调 `task.create`，`registry.get` 能查到，于是**绕过了 tag 过滤直接执行**。当前的 tag 过滤只是"不告诉模型"，不是"不许调用"——它是**提示层约束，不是执行层约束**。

这与方案 D 的教训完全同构：*只做提示层，模型完全可能无视*。

**隐患 B · 静默遮蔽：同名工具 schema 重复导出**

```java
// ToolRegistry.get —— 本地优先
ToolDefinition local = tools.get(name);
if (local != null) return local;
McpRemoteTool mcp = mcpTools.get(name);
```

`get()` 是本地优先，但 `exportSchemas()` 把本地和 MCP **各导出一遍**。若某个 MCP server 提供了与本地同名的工具，LLM 会在工具列表里看到**两个同名条目**（描述不同），而 invoke 时永远走本地。这是一个"隐式优先级 + 无声遮蔽"，schema 重复还会污染 `toolsetHash`。

---

## 2. 设计目标

| # | 目标 | 验收信号 |
|---|---|---|
| 1 | 可见性可**禁止**，不只可放行 | `learn` 模式下 `note.create` 不出现在 schema 中 |
| 2 | 可见性成为**执行层约束** | 模型凭记忆调不可见工具 → 被短路拒绝，不真正执行 |
| 3 | 支持**会话级临时屏蔽/钉住** | 一次 REST 调用即生效，本轮结束不残留 |
| 4 | 每个决策**可解释** | 能回答"为什么 `task.create` 现在不可见" |
| 5 | 角色层受模式层约束 | `learn` 模式委派 `PLANNER`，写工具仍不可见 |
| 6 | 结构性保留不可被解除 | 任何层都无法让子代理看到 `subagent.*` |
| 7 | 完全可降级 | 开关关闭后行为与现状**字节级一致** |

---

## 3. 方案设计

### 3.1 四层 Scope 链

```
┌─ GLOBAL ──────────────────────────────────────────────┐
│ 全量注册表（本地 @AgentTool + MCP 远程工具）             │
│ 唯一职责：打底 + 解决同名冲突（本地遮蔽 MCP，隐患 B）      │
└───────────────────────┬───────────────────────────────┘
┌───────────────────────▼───────────────────────────────┐
│ MODE 层  chat / plan / reflect / learn                 │
│ 思维模式的语义边界。learn 显式 deny tag=write            │
└───────────────────────┬───────────────────────────────┘
┌───────────────────────▼───────────────────────────────┐
│ ROLE 层  PLANNER / REFLECTION / RESEARCH（子代理时才有） │
│ 角色最小职责集 + 结构性保留（无条件 deny subagent.*）      │
└───────────────────────┬───────────────────────────────┘
┌───────────────────────▼───────────────────────────────┐
│ SESSION 层  用户对本会话的临时意愿                        │
│ deny：这轮别动我的任务； pin：破例允许某个工具              │
└───────────────────────────────────────────────────────┘
```

**为什么是这个顺序**：越靠下越贴近"当前这一刻的真实意图"，因此优先级越高。这与 DSH 的"越靠近当前 scope 的祖先优先级越高"一致。

### 3.2 一层的规则模型

```java
/**
 * 一层可见性规则。四个字段的语义**刻意不对称**：
 *  - allowTags/allowTools 是「收窄」：非空时，只有命中的才能继续可见
 *  - denyTags/denyTools   是「剔除」：命中即不可见，跨层累积
 *  - pinnedTools          是「破例」：优先级高于一切 deny（除结构性保留）
 */
public record ToolLayer(
        ScopeKind kind,
        String label,            // "learn" / "PLANNER" / sid，用于决策链解释
        Set<String> allowTags,
        Set<String> allowTools,
        Set<String> denyTags,
        Set<String> denyTools,
        Set<String> pinnedTools
) {
    public enum ScopeKind { GLOBAL, MODE, ROLE, SESSION }
}
```

**为什么 allow 与 deny 不对称**：allow 表达"这一层的职责范围"（收窄，符合最小权限），deny 表达"这一层的禁令"（累积，符合安全默认）。若两者都做成累积或都做成收窄，就无法同时表达"规划专家只管任务和目标"和"learn 模式一律不许写"。

### 3.3 遮蔽算法

```java
public ToolView resolve(ScopeChain chain) {
    // ① 全局层打底：全量注册表，本地遮蔽同名 MCP（修隐患 B）
    Map<String, ToolDefinition> base = new LinkedHashMap<>();
    for (ToolDefinition t : registry.all())        base.put(t.name(), t);
    for (McpRemoteTool rt : registry.mcpToolsAll()) {
        if (base.containsKey(rt.fullName())) {
            reason.put(rt.fullName(), "GLOBAL:shadowed(local-wins)");
            continue;                              // 不重复导出
        }
        base.put(rt.fullName(), asToolDef(rt));
    }
    Set<String> visible = new LinkedHashSet<>(base.keySet());

    // ② 祖先层逐层「收窄」（从远到近，不含 SESSION 的 pin）
    for (ToolLayer layer : chain.layers()) {
        if (layer.allowTags().isEmpty() && layer.allowTools().isEmpty()) continue;
        visible.removeIf(name -> {
            boolean hit = layer.allowTools().contains(name)
                    || base.get(name).tags().stream().anyMatch(layer.allowTags()::contains);
            if (!hit) reason.put(name, layer.label() + ":not-in-allow");
            return !hit;
        });
    }

    // ③ 限制规则筛一遍（deny 跨层累积，任一层 deny 即剔除）
    for (ToolLayer layer : chain.layers()) {
        visible.removeIf(name -> {
            boolean denied = layer.denyTools().contains(name)
                    || base.get(name).tags().stream().anyMatch(layer.denyTags()::contains);
            if (denied) reason.put(name, layer.label() + ":deny");
            return denied;
        });
    }

    // ④ 当前 scope 的 pin 覆盖一切 —— 除结构性保留
    for (String name : chain.current().pinnedTools()) {
        if (!base.containsKey(name)) continue;                  // 不存在的名字不 pin
        if (isStructurallyReserved(name, chain)) {
            reason.put(name, "RESERVED:unpinnable");
            continue;
        }
        visible.add(name);
        reason.put(name, chain.current().label() + ":pin");
    }
    return new ToolView(visible, reason, chain.signature());
}
```

**关键：为什么 pin 能覆盖 deny，但覆盖不了结构性保留**

pin 表达的是**用户的显式破例意愿**（"我知道 learn 模式一般不写，但这次我就要记一笔"）——用户意愿应当高于系统默认策略。但**结构性保留**表达的是**架构不变量**，不是策略：

```java
/** 子代理不得看到 subagent.* —— 与 AgentContext.guardTopLevel() 的运行时护栏同源。 */
private boolean isStructurallyReserved(String name, ScopeChain chain) {
    return chain.hasRole() && name.startsWith("subagent.");
}
```

这是 DSH 里 `run_code` 被硬编码保留的等价物。它的必要性在你项目里更强：`SubAgentRunner` 的 prompt 现在靠一句"你不能委派其它子代理（subagent.* 工具对你不可见）"来约束——**软约束**。而 `guardTopLevel()` 虽有运行时护栏，却是**抛异常**而非"不可见"，模型要浪费一步才知道。结构性保留把它前移到可见性层。

### 3.4 刻意裁剪掉的 DSH 语义（重要）

照搬会引入你场景里不存在的复杂度。明确裁掉两条：

| DSH 语义 | 是否引入 | 理由 |
|---|---|---|
| 每层维护独立 `ToolLayer` 的**工具实现表**，同名条目"就近替换实现" | ❌ **不引入** | DSH 是插件化运行时，同一工具名可以有多个插件实现（如两个 llm provider 插件）。你的工具由 `@AgentTool` 注解扫描，**每个名字只有唯一实现**，"替换实现"无落点 |
| 工具注册走 `effect()`，随插件卸载自动摘除 | ❌ **不引入** | 依赖 Cordis 的可逆副作用机制。你的工具生命周期与 Spring 容器一致，无运行时装卸需求 |
| 同名条目的**优先级**概念 | ✅ **保留并显式化** | 你确实存在同名冲突：本地工具 vs MCP 远程工具（隐患 B）。把隐式的"本地优先"提升为算法第 ① 步的显式规则 |

> 这一节是本方案与"生搬硬套"的分界。DSH 的分层遮蔽解决的是**插件化实现替换 + 可见性收窄**两件事，你只需要后者，外加一个它顺带能解决的同名冲突。

### 3.5 执行层强制（目标 2，最关键的一环）

可见性若只影响 schema 导出，就仍是提示层约束。必须在执行前拦一道：

```java
// AgentOrchestrator：紧跟在 def == null 判断之后、reflexion.isBanned 之前
ToolDefinition def = registry.get(call.name());

if (def == null) { /* 真幻觉，记 UNKNOWN_TOOL（不变） */ }

// K · 执行层强制：工具存在，但当前 scope 不可见 → 越界，短路拒绝
if (!view.visible(call.name())) {
    String blocked = writeJson(visibility.notVisibleResult(view, call.name()), ...);
    trace.toolNotVisible(sid, step, call.name(), view.reasonOf(call.name()));
    String hint = advise(reflexion, sid, step, call.name(),
            ReflexionAdvisor.FailureMode.TOOL_NOT_VISIBLE);
    ws.sendToolResult(sid, callId, blocked);
    appendToolTrace(sid, call.name(), call.arguments(), blocked, hint);
    continue;
}
```

回灌给 LLM 的结果（延续方案 E 的"为模型设计错误信息"原则——必须**可操作**）：

```json
{
  "error": "TOOL_NOT_VISIBLE",
  "tool": "task.create",
  "reason": "当前处于 learn（学习）模式，写入类工具已被禁用。",
  "decisionChain": ["GLOBAL:allow", "MODE(learn):deny(tag=write)"],
  "hint": "禁止再次调用 task.create。当前模式下你只能读取与检索。若用户确实想创建任务，请用自然语言告知他切换到「执行」或「规划」模式，不要尝试其它写工具绕过。本次调用未执行，不会产生任何副作用。",
  "visibleAlternatives": ["kb.semantic_search", "task.search", "note.list"]
}
```

`visibleAlternatives` 是刻意加的：方案 D 的经验是"别空喊换个工具，要给具体方向"。

### 3.6 与 ReflexionAdvisor 的接缝：两层权限治理

新增一个失败模式，**不可重试，一次即封禁**：

```java
// ReflexionAdvisor.FailureMode
/** 工具存在但在当前 scope 不可见——策略性拒绝，重试毫无意义 */
TOOL_NOT_VISIBLE(false),
```

```java
// classify()：置于 DENIED 判定之前（TOOL_NOT_VISIBLE 更具体，避免被 DENIED 抢先命中）
if (hay.contains("TOOL_NOT_VISIBLE")) return FailureMode.TOOL_NOT_VISIBLE;
```

```java
// hintForNonRetryable()
case TOOL_NOT_VISIBLE -> "⛔ 工具 " + tool + " 在当前模式/角色下不可见，这是策略边界，"
        + "换参数或重试都不会通过。禁止再次调用该工具，也不要尝试用其它写工具绕过。"
        + "请在【可用工具】列表范围内重新规划，或用自然语言说明当前模式不支持该操作。";
```

于是形成完整的两层叙事：

| 层 | 机制 | 判断依据 | 作用域 | 已有/新增 |
|---|---|---|---|---|
| **静态可见性** | 分层遮蔽（本方案） | 模式 / 角色 / 会话的**策略** | 整个会话或轮次 | 🆕 K |
| **动态封禁** | `ReflexionAdvisor.banned` | 本轮**实际失败**记录 | 单轮 | ✅ D |

一句话：**K 决定"你本来能碰什么"，D 决定"你这轮已经把什么碰坏了"。**

### 3.7 可解释性：`explain()`

```
GET /agent/settings/visibility/explain?tool=note.create&mode=learn
{
  "tool": "note.create",
  "visible": false,
  "decisionChain": [
    {"scope":"GLOBAL",       "verdict":"allow", "detail":"已注册（本地工具）"},
    {"scope":"MODE(learn)",  "verdict":"allow", "detail":"命中 allowTags: note"},
    {"scope":"MODE(learn)",  "verdict":"deny",  "detail":"命中 denyTags: write"},
    {"scope":"SESSION(abc)", "verdict":"-",     "detail":"无相关规则"}
  ],
  "finalReason": "MODE(learn):deny(tag=write)"
}
```

这个端点存在的理由很实在：**1.1 节那个 bug（learn 模式能写笔记）之所以潜伏至今，就是因为没有任何方式能看到"当前到底能看到什么、为什么"。** 可解释性不是锦上添花，它是这类"弱类型 tag 关联"必需的可观测手段。

### 3.8 默认规则配置

模式层默认规则收敛到一处（替代散落的 switch）：

```java
/** 模式层默认规则。刻意保留与现状一致的 allow 集合，只**新增** deny —— 便于对照验证。 */
public enum AgentMode {
    CHAT   ("chat",    Set.of(),                                          Set.of()),
    PLAN   ("plan",    Set.of("task","goal","planner","kb","read","write","subagent","mcp"), Set.of()),
    REFLECT("reflect", Set.of("task","goal","insight","note","kb","read","subagent","mcp"),  Set.of("write")),
    LEARN  ("learn",   Set.of("kb","note","read","subagent","mcp"),       Set.of("write"));
    // (label, allowTags, denyTags)
}
```

> **注意 `REFLECT` 也加了 `deny=write`**：它原本的 allow 集不含 `write` tag，但含 `note`/`task`/`goal`——与 1.1 节同样的漏洞。复盘场景本应只读。这是本方案顺带修正的第三个语义 bug。

### 3.9 会话层存储

内存态，对齐 `ConversationMemory` 的既有风格（项目已明确"状态仅存内存、零新表"是可接受取舍）：

```java
@Component
public class SessionToolMask {
    // sid → 规则；Caffeine，expireAfterAccess 与会话归档阈值（30min）对齐
    private final Cache<String, ToolLayer> masks;

    public void deny(String sid, Set<String> tools, Set<String> tags) { ... }
    public void pin(String sid, Set<String> tools) { ... }
    public void clear(String sid) { ... }
    public ToolLayer layerOf(String sid) { ... }   // 无规则时返回空层
}
```

**不落库的理由**：会话级屏蔽本质是"这次对话的临时意愿"，跨重启保留反而违背直觉（与 D 的"封禁不跨轮"同一思路：**临时状态不应比它所服务的场景活得更久**）。若日后需要"每次都别动我的任务"这种持久偏好，那属于 MODE 层或 `UserPreference`，不是 SESSION 层。

### 3.10 视图缓存与前缀缓存的关系

`ToolView` 的计算涉及全量工具遍历，而 ReAct 每步都要用。做一层轻缓存：

```java
// ScopeChain.signature() = sha256(mode + role + sessionMaskVersion + registryVersion)
private final Cache<String, ToolView> viewCache;   // maxSize 256, expireAfterWrite 10min
```

**`PrefixKey` 无需改动**——这是个好消息，值得说明：

```java
PrefixKey(mode, toolsetHash, memoHash, dateBucket)
                ↑
        sha256(exportSchemas(view) 的 JSON)
```

`toolsetHash` 是对**导出结果**求的 hash。视图变化必然改变导出结果，进而改变 `toolsetHash`，前缀缓存自动失效。**已有设计天然容纳了这个扩展**，无需新增 key 维度。

同时隐患 B 的修复还带来一个副作用收益：去掉重复的同名 MCP 条目后，`toolsJson` 变短且更稳定，对上游 prompt cache 命中略有正向帮助。

### 3.11 启动自检（补上「坑 3」的欠账）

```java
@EventListener(ApplicationReadyEvent.class)
@Order(Ordered.LOWEST_PRECEDENCE)   // 必须晚于 ToolRegistry.scan()
public void selfCheck() {
    Set<String> allTags = registry.all().stream()
            .flatMap(t -> t.tags().stream()).collect(toSet());
    Set<String> allNames = /* 本地 + MCP */;

    for (每一层默认规则) {
        for (String tag : layer.allTags()) {
            if (!allTags.contains(tag))
                log.warn("[ToolVisibility] {} 引用了不存在的 tag «{}» —— 该规则永不生效（悬空引用）",
                         layer.label(), tag);
        }
        for (String name : layer.allToolNames()) {
            if (!allNames.contains(name))
                log.warn("[ToolVisibility] {} 引用了未注册的工具 «{}»", layer.label(), name);
        }
        if (resolve(chainOf(layer)).visible().isEmpty())
            log.error("[ToolVisibility] {} 的可见工具集为空 —— 该 scope 下 Agent 无任何工具可用！",
                      layer.label());
    }
}
```

**为什么是 warn 而不是启动失败**：MCP 远程工具是异步连上后才注册的，启动瞬间 `mcp` 相关 tag 可能确实还不存在。这里若抛异常会造成"MCP 服务器慢一点就起不来"的脆弱性。但**可见集为空**是 error——那是必然的功能失效（正是坑 3 的形态）。

> 对比 `ToolRegistry` 对缺失 `@ToolParam` 的处理是**直接启动失败**。差别在于：那是编译期可完全确定的信息，宁可失败；这里存在合法的异步不确定性，只能告警。这个区分本身就是要讲清楚的取舍。

---

## 4. 改动清单

### 4.1 新增

| 文件 | 职责 |
|---|---|
| `feature/agent/tool/visibility/ToolLayer.java` | 一层规则的 record（含 `ScopeKind`） |
| `feature/agent/tool/visibility/ScopeChain.java` | 四层链构造 + `signature()` |
| `feature/agent/tool/visibility/ToolView.java` | 可见集 + 决策链 + `visible(name)` / `reasonOf(name)` |
| `feature/agent/tool/visibility/ToolVisibilityResolver.java` | **核心**：遮蔽算法 + 视图缓存 + 启动自检 + `notVisibleResult()` |
| `feature/agent/tool/visibility/SessionToolMask.java` | 会话层规则的内存存储 |
| `feature/agent/runtime/AgentMode.java` | 模式层默认规则（替代 `resolveTagFilter` 的 switch） |

### 4.2 改动

| 文件 | 改动点 |
|---|---|
| `tool/ToolRegistry.java` | `exportSchemas(ToolView)` 新重载；**保留** `exportSchemas(Set)` 不动（降级路径 + 兼容既有测试） |
| `runtime/PromptBuilder.java` | `buildPrefix` 改为经 resolver 取 view；`resolveTagFilter` 保留为降级分支 |
| `runtime/AgentOrchestrator.java` | turn 开始解析一次 view；新增 3.5 的执行层拦截 |
| `subagent/SubAgentRunner.java` | `buildSystemPrompt` 用 ROLE 层 view；**继承主对话 mode**（修 1.3） |
| `subagent/SubAgentRole.java` | `toolTags` 语义不变，新增 `denyTags()`（默认空） |
| `runtime/ReflexionAdvisor.java` | 新增 `TOOL_NOT_VISIBLE` 失败模式 + classify + hint |
| `runtime/trace/AgentTraceListener.java` | 新增 `onToolNotVisible` / `onVisibilityResolved`（default 空实现，保持兼容） |
| `runtime/trace/AgentTraceBus.java` | 对应 emit 方法 |
| `runtime/trace/AgentTraceMetrics.java` | 新增 `visibility` 指标分区 |
| `controller/AgentSettingsController.java` | 3 个新端点（见 4.3） |
| `controller/ObservabilityController.java` | `trace/stats` 的 config 分区回显可见性开关 |

### 4.3 新增端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/agent/settings/visibility?mode=&role=` | 当前视图：可见工具 + 每个工具的来源标注 |
| GET | `/agent/settings/visibility/explain?tool=&mode=` | 单个工具的完整决策链（3.7） |
| PUT | `/agent/settings/visibility/session` | 设置会话层 `{sid, denyTools[], denyTags[], pinnedTools[]}` |
| DELETE | `/agent/settings/visibility/session/{sid}` | 清空会话层规则 |

> 写端点必须走 `requireUser(principal)`——`/agent/**` 在 `WebSecurityConfig` 里是 `permitAll`（静态资源同前缀），这是该控制器已有的坑，新端点不能重犯。

---

## 5. 配置项

```properties
# 总开关：false 时完全回退到 PromptBuilder.resolveTagFilter 原行为
agent.tool.visibility.enabled=true

# 执行层强制：false 时仅过滤 schema（提示层），不拦截越界调用
# 单独开关的意义：可用于量化「只做提示层够不够」（见 §6 的 notVisibleBlocked）
agent.tool.visibility.enforce=true

# 视图缓存
agent.tool.visibility.cache-size=256
agent.tool.visibility.cache-expire-minutes=10

# 子代理是否继承主对话 mode 层规则（修 §1.3）
agent.tool.visibility.subagent-inherits-mode=true

# 会话层规则空闲过期（与会话归档阈值 30min 对齐）
agent.tool.visibility.session-mask-expire-minutes=30
```

---

## 6. 指标（`GET /api/agent/trace/stats`）

```json
{
  "visibility": {
    "viewsResolved": 42,
    "viewCacheHitRate": 0.9048,
    "avgVisibleTools": 11.3,
    "notVisibleBlocked": 3,
    "notVisibleByTool": {"task.create": 2, "note.create": 1},
    "deniedByScope": {"MODE": 7, "ROLE": 2, "SESSION": 1, "RESERVED": 1},
    "shadowedDuplicates": 0,
    "sessionMasksActive": 1
  },
  "config": {
    "visibilityEnabled": true,
    "visibilityEnforce": true,
    "subagentInheritsMode": true
  }
}
```

**每个指标都有明确的消费理由**（项目原则：*没有消费方的指标等于没有指标*）：

| 指标 | 回答什么问题 |
|---|---|
| `notVisibleBlocked` | **本方案最关键的数字**。>0 即证明"模型真的会凭记忆调不可见工具"，也就证明了执行层强制的必要性——与 D 的 `bannedToolCallsBlocked` 同构 |
| `deniedByScope` | 哪一层规则最常生效。若 `SESSION` 长期为 0，说明会话层屏蔽是伪需求 |
| `avgVisibleTools` | 工具集大小直接影响 prompt token 与选择准确率。可用于验证"收窄是否过度" |
| `shadowedDuplicates` | 隐患 B 的实际发生次数。为 0 说明当前无同名冲突，但机制已就位 |
| `viewCacheHitRate` | 视图计算是每步都要做的，命中率低则需调整缓存 key |

**同时必须修正既有指标**：`hallucinationRate` 的分子应只含"真不存在的工具"。越界调用改记 `notVisibleBlocked`。这是 1.5 节隐患 A 的修复——**现在的幻觉率是被高估的**（把越界算进去了）。这个修正要在文档和面试话术里主动说明。

---

## 7. 验收标准

| # | 场景 | 预期 |
|---|---|---|
| 1 | `learn` 模式取 view | `note.create` **不在**可见集，`reasonOf` = `MODE(learn):deny(tag=write)` |
| 2 | `reflect` 模式取 view | `task.create` / `goal.create` 不可见（3.8 新增的 deny 生效） |
| 3 | `plan` 模式取 view | 写工具可见（不能过度收紧，保持现状能力） |
| 4 | 模型在 `learn` 模式调 `task.create` | 返回 `TOOL_NOT_VISIBLE`，`registry.invoke` **未被调用**（用 spy 断言无副作用） |
| 5 | 同一工具连续两次越界 | 第 2 次被 `ReflexionAdvisor` 封禁（`TOOL_NOT_VISIBLE` 不可重试 → 实际第 1 次即封禁） |
| 6 | 会话层 `deny=[task.*]` | 该 sid 下 `task.create` 不可见；`clear` 后恢复 |
| 7 | 会话层 `pin=[note.create]` + `learn` 模式 | `note.create` **可见**（pin 覆盖 deny） |
| 8 | 会话层 `pin=[subagent.delegate]` + ROLE 层 | **仍不可见**，reason = `RESERVED:unpinnable` |
| 9 | `learn` 模式委派 `PLANNER` 子代理 | 子代理可见集不含写工具（`subagent-inherits-mode=true`） |
| 10 | `visibility.enabled=false` | `exportSchemas` 输出与改造前**逐字节相同**（关键回归） |
| 11 | 启动时某层引用不存在的 tag | 日志出现 `悬空引用` warn |
| 12 | 某层可见集为空 | 日志出现 error |

第 10 条是最重要的回归断言：**降级路径必须字节级一致**，否则会破坏前缀缓存与既有评测 fixture（`toolsetHash` 一变，录制回放全部失效）。

---

## 8. 风险与降级

| 风险 | 影响 | 应对 |
|---|---|---|
| **过度收窄导致 Agent 无工具可用** | 功能失效，形如坑 3 | 启动自检对空可见集报 error；`avgVisibleTools` 指标持续观测 |
| 越界拦截误伤合法调用 | 凭空制造失败（方案 E 的"宁松勿严"教训） | `enforce` 独立开关，可只开过滤不开拦截；拦截前 `def != null` 已确保工具真实存在 |
| 视图计算拖慢每步 ReAct | 延迟增加 | 视图缓存 + `signature()` 命中；实测目标 <1ms（远小于 LLM 调用） |
| 破坏既有评测录制 | 回放全部 miss | 验收第 10 条；默认规则的 allow 集**保持与现状一致**，只增 deny |
| 会话层规则内存泄漏 | 长期运行内存增长 | Caffeine `expireAfterAccess` + `maximumSize` |
| MCP 异步注册导致启动瞬间自检误报 | 噪声日志 | 自检用 warn 而非 fail；`@Order(LOWEST_PRECEDENCE)` 尽量晚跑 |

**降级矩阵**：

| `enabled` | `enforce` | 行为 |
|---|---|---|
| false | — | 完全等同改造前（走 `resolveTagFilter`） |
| true | false | 分层过滤 schema，但越界调用**照旧执行**（可用于测量 `notVisibleBlocked` 的基线） |
| true | true | 完整能力 |

中间态存在的价值：它能产出"只做提示层时模型越界了 N 次"这个数字，从而**实证**执行层的必要性。这与 D 的做法一致。

---

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| **K1** | `ToolLayer` / `ScopeChain` / `ToolView` / `ToolVisibilityResolver` + `AgentMode` + 单元测试 | 验收 1/2/3/7/8 |
| **K2** | 接入 `PromptBuilder` + `ToolRegistry.exportSchemas(ToolView)` + 降级开关 | 验收 10（字节一致回归） |
| **K3** | 执行层强制 + `TOOL_NOT_VISIBLE` 接入 `ReflexionAdvisor` | 验收 4/5 |
| **K4** | `SessionToolMask` + 4 个端点 + 前端设置项 | 验收 6/7 |
| **K5** | 子代理继承 mode + 结构性保留 | 验收 8/9 |
| **K6** | 指标 + `explain` 端点 + 启动自检 | 验收 11/12 |

**建议先做 K1+K2**：这两步就能修掉 1.1 的真实 bug（learn 模式能写笔记），且风险最低（不改执行路径）。

---

## 10. 面试话术

**开场（把它讲成场景驱动，不是炫技）**：

> 个人规划工具有个特点：同一个用户在不同思维模式下，Agent 的权限**应该**不同。我在「学习」模式只想让它帮我查笔记，不希望它顺手改我的任务；「执行」模式又必须能写。
>
> 我原来的做法是给工具打 tag，按模式过滤。后来发现两个问题。**第一，tag 过滤是 OR 语义的并集放行，只能表达"允许什么"，没法表达"禁止什么"**——`note.create` 同时带 `note` 和 `write` 两个 tag，learn 模式放行了 `note`，于是它照样能写笔记。这个 bug 潜伏了很久。
>
> **第二，更严重的是，tag 过滤只影响给 LLM 的 schema 列表，不影响执行**。模型如果从上下文里记得 `task.create` 这个名字，直接调用是能调通的——因为 `registry.get()` 查的是全量注册表。**所以它是提示层约束，不是执行层约束。**

**方案**：

> 我参考了 DeepSeek Harness 的分层工具遮蔽，做了四层 scope 链：全局、模式、角色、会话。可见性在运行时实时算出来，支持显式 deny 和显式 pin，而且每个工具都能给出"为什么可见/不可见"的决策链。
>
> 关键是**执行层也拦一道**：工具存在但当前不可见，就短路返回 `TOOL_NOT_VISIBLE`，不真正执行。这和我之前做的 Reflexion 封禁刚好组成两层权限治理——**可见性决定"你本来能碰什么"，Reflexion 封禁决定"你这轮已经把什么碰坏了"**。

**取舍（必讲，体现不是照搬）**：

> DSH 的分层遮蔽其实解决两件事：插件化的实现替换、可见性收窄。**我只需要后者**——我的工具是注解扫描的，一个名字只有唯一实现，"替换实现"在我这没有落点，硬做就是过度设计。
>
> 但它顺带帮我修了一个隐患：本地工具和 MCP 远程工具可能同名，我原来的 `get()` 是本地优先，但 `exportSchemas()` 把两个都导出了——LLM 会看到两个同名条目，而调用永远走本地。这个隐式遮蔽现在被显式化成算法第一步。

**效果证明**：

> 埋了 `visibility.notVisibleBlocked`。这个数字大于 0 就直接证明"模型真的会凭记忆调不可见的工具"，也就证明了执行层强制不是多余的。我特意把过滤和强制做成两个独立开关，只开过滤时能测出基线。
>
> 另外这个改造**修正了我原来的一个度量缺陷**：我的 `hallucinationRate` 之前把"工具不存在"和"工具存在但越界"混在一起算，所以幻觉率是被高估的。现在分开了。

**主动划边界**：

> 三点限制。**一，可见性规则是字符串 tag 的弱关联，编译器查不出悬空引用**——我踩过这个坑（子代理 tag 指向已下线工具），所以补了启动自检，但因为 MCP 工具是异步注册的，只能告警不能启动失败。
>
> **二，会话层规则只在内存里，不跨重启**。这是刻意的：临时意愿不该比它服务的场景活得更久。要持久化的偏好属于模式层。
>
> **三，我没做工具实现的运行时热替换**。那需要 Cordis 那种可逆副作用机制，是 TS 原型系统的特权，Java 静态类型做不到，而我的场景也不需要。

---

## 11. 边界（不做什么）

| 不做 | 理由 |
|---|---|
| 工具实现的运行时装卸/热替换 | 依赖可逆副作用运行时；个人单机应用无"服务不中断改自己"的需求 |
| 可见性规则持久化到数据库 | 模式层用配置、会话层用内存已足够；引入表会带来迁移与一致性成本 |
| 按工具**参数**判定可见性 | 那是并发安全判定（方案 H）的范畴，与可见性正交 |
| 图形化规则编辑器 | 先用端点验证需求真伪（看 `deniedByScope.SESSION` 是否真被用） |

---

## 12. 与现有文档的定位关系

- `Agent实现方案.md` —— Agent 运行时与工具机制全貌
- `Lattice-Agent-SubAgent设计方案.md` —— 角色层可见性的上游来源
- `Agent工具授权-AutoApprove设计与执行计划.md` —— **确认策略**（能不能执行），与本文的**可见性**（能不能看到）互补
- **本文档** —— 工具可见性的分层治理
- `Agent轮次收尾解耦-TurnStopping设计.md`（方案 L）—— 同批次的另一个创新点
