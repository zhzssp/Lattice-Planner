# Agent 前缀缓存（Prefix Caching）实现计划

> 目标：Agent 每一步 ReAct 都会 `PromptBuilder.build()` 重新拼装完整 messages，其中 **system prompt + 工具 schema + 长期记忆** 是跨轮不变的"固定前缀"，却每轮都重新序列化并随请求全量发送。本计划在应用网关层引入前缀缓存，降低每轮的**前缀构造开销**，并为对接"服务端 prompt/KV 缓存"预留 `cache_id` 通道。
>
> 说明：真正的 GPU KV-Cache 复用发生在**推理服务端**（vLLM/SGLang/DeepSeek Context Caching 等）。应用侧能做的是：① 复用前缀构造结果，减少 CPU/GC；② 保证前缀**字节级稳定**从而让服务端 automatic prefix caching 能命中；③ 透传服务端返回的缓存标识。

---

## 1. 现状基线（实测事实）

| 事实 | 位置 |
|---|---|
| 每步都 `promptBuilder.build(mode, memory.history(sid), longTermMemo)` | `AgentOrchestrator.handleUserTurn` for 循环内 |
| system prompt 每次用 `formatted(LocalDate.now(), toolsJson, memoSection)` 重建 | `PromptBuilder.build` |
| `toolsJson` = `registry.exportSchemas(tagFilter)` 每次重新 JSON 序列化 | `PromptBuilder.build` |
| messages 全量发送给 `LlmGateway.generateChat(messages)` | `AgentOrchestrator` → `LlmGateway` |
| LLM 走 DeepSeek OpenAI-Compatible `/v1/chat/completions` | `LlmGateway` |

### 关键观察
- 固定前缀（system）在一次 `handleUserTurn` 的多步循环内**完全不变**（mode / 工具集 / longTermMemo 不变，仅 `LocalDate.now()` 在跨天时变）。
- 变化部分只有 `history`（每步追加 tool trace）。
- 因此：**system 前缀可在一次 turn 内构造一次、复用 N 步**，且其字节表示应保持稳定以利服务端缓存命中。

---

## 2. 目标架构

```
handleUserTurn(sid, ...)
  │  turn 开始：构造一次 SystemPrefix（缓存 key = mode + toolsetHash + memoHash + date）
  ▼
┌────────────── PrefixCache（新增，进程内 Caffeine）──────────────┐
│  key   : PrefixKey(mode, toolsetHash, memoHash, dateBucket)      │
│  value : CachedPrefix(systemContent, prefixHash, remoteCacheId?) │
└─────────────────────────────────────────────────────────────────┘
  │  命中 → 直接取 systemContent（省去 exportSchemas + format）
  ▼
messages = [system(前缀)] + history(变化部分)
  │  透传 remoteCacheId（若服务端支持 context caching）
  ▼
LlmGateway.generateChat(messages, prefixHash)
```

---

## 3. 分阶段实现

### P1：前缀提取与稳定化（重构 `PromptBuilder`）

把 `build()` 拆成"前缀构造"和"消息拼装"两步，前缀可缓存、可复用。

**改动 `PromptBuilder.java`**：

```java
/** 不变前缀（system content）+ 其 hash。 */
public record SystemPrefix(String content, String hash) {}

/** 只构造 system 前缀（可缓存）。 */
public SystemPrefix buildPrefix(String mode, String longTermMemo) throws JsonProcessingException {
    Set<String> tagFilter = resolveTagFilter(mode);
    String toolsJson = om.writerWithDefaultPrettyPrinter()
            .writeValueAsString(registry.exportSchemas(tagFilter));
    String sys = SYS_TEMPLATE.formatted(dateBucket(), toolsJson, memoOr(longTermMemo));
    return new SystemPrefix(sys, sha256(sys));
}

/** 用给定前缀 + history 拼完整 messages。 */
public List<Map<String,String>> assemble(SystemPrefix prefix, List<ConversationMemory.Msg> history) {
    // system(prefix.content) + history
}

/** 兼容旧签名：内部 buildPrefix + assemble，保证现有调用不破。 */
public List<Map<String,String>> build(String mode, List<Msg> history, String memo) throws ... {
    return assemble(buildPrefix(mode, memo), history);
}
```

**稳定化要点**（让服务端 prefix caching 有机会命中）：
- `LocalDate.now()` 改为 `dateBucket()`（当天恒定字符串），避免同一天内因毫秒差异造成前缀漂移。
- `exportSchemas` 输出顺序确定（工具遍历顺序已由 `LinkedHashMap`/固定集合保证，需确认稳定）。

---

### P2：前缀缓存 `PrefixCache`

**新增文件**：`feature/agent/runtime/PrefixCache.java`

```java
@Component
public class PrefixCache {
    public record PrefixKey(String mode, String toolsetHash, String memoHash, String dateBucket) {}
    public record CachedPrefix(String systemContent, String prefixHash, String remoteCacheId) {}

    private final Cache<PrefixKey, CachedPrefix> cache; // Caffeine，size + expireAfterWrite

    public CachedPrefix get(PrefixKey key, Supplier<CachedPrefix> loader) {
        return cache.get(key, k -> loader.get());
    }
    public void attachRemoteId(PrefixKey key, String remoteCacheId) { ... } // 服务端返回后回填
    public String stats() { ... }
}
```

**Key 组成**：`mode`（chat/plan/reflect/learn）+ 工具集 hash（`exportSchemas` 结果的 hash）+ 长期记忆 hash + 当天 bucket。四者相同即可复用。

**配置**：
```properties
agent.prefix-cache.enabled=true
agent.prefix-cache.max-entries=256
agent.prefix-cache.expire-minutes=120
```

---

### P3：接入 `AgentOrchestrator`

**改动 `handleUserTurn`**：把前缀构造移到循环外。

```java
public void handleUserTurn(String sid, String userInput, String mode, String longTermMemo) {
    memory.append(sid, "user", userInput);
    // ① turn 内构造一次前缀（命中缓存则零构造成本）
    PromptBuilder.SystemPrefix prefix = promptBuilder.buildPrefix(mode, longTermMemo);
    try {
        for (int step = 0; step < maxSteps; step++) {
            // ② 每步只拼 history，复用固定前缀
            var msgs = promptBuilder.assemble(prefix, memory.history(sid));
            String llmRaw = llm.generateChat(msgs);   // 可透传 prefix.hash
            ... // 其余逻辑不变
        }
    }
}
```

**收益**：一次 turn 的 N 步里，`exportSchemas` + JSON 序列化 + system format 只做**一次**而非 N 次（原来每步都做）。

---

### P4：服务端 Context Caching 透传（可选，取决于 LLM 供应商）

DeepSeek 等提供 **Context Caching / prompt caching**，命中固定前缀可降 token 成本与首 token 延迟。应用侧要做的是**保证前缀字节稳定**（P1 已做）并透传/记录缓存命中。

**改动 `LlmGateway.generateChat`**（增重载，不破坏原签名）：

```java
public String generateChat(List<Map<String,String>> messages, String prefixHash) {
    // 1. 构造请求体（同现有）
    // 2. 若供应商支持：在响应里读取缓存命中字段（如 usage.prompt_cache_hit_tokens）
    // 3. 记录到 metrics；把 remoteCacheId（若有）回填 PrefixCache
}
```

**注意**：DeepSeek 的 context caching 是**自动**的（按前缀自动命中，无需显式传 cache_id），因此 P4 的核心其实是 **P1 的前缀稳定化** + 读取 `usage` 里的 `prompt_cache_hit_tokens` 做可观测；不需要改协议。若未来换用需显式 cache_id 的供应商，再用 `remoteCacheId` 通道。

---

### P5：可观测

- 复用/新增 metrics：`prefixCacheHit / prefixCacheMiss / promptCacheHitTokens / prefixBuildTimeSavedMs`。
- 暴露到 `GET /api/agent/prefix-cache/stats`（登录可见）或复用现有诊断入口。

---

## 4. 改动清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 改 | `PromptBuilder.java` | 拆 `buildPrefix` / `assemble`；`LocalDate.now()`→`dateBucket()`；保留旧 `build` 兼容（P1） |
| 新增 | `feature/agent/runtime/PrefixCache.java` | Caffeine 前缀缓存（P2） |
| 改 | `AgentOrchestrator.java` | 前缀移到循环外，逐步复用（P3） |
| 改 | `LlmGateway.java` | `generateChat` 增重载读取 `prompt_cache_hit_tokens`（P4，可选） |
| 新增（可选） | `feature/agent/runtime/PrefixCacheMetrics.java` | 指标（P5） |
| 改 | `application.properties` | `agent.prefix-cache.*` 配置段 |

**注意**：`SubAgentRunner` 若也用 `PromptBuilder`，同样可受益——子代理的 system 前缀（角色 prompt + 工具子集）也是固定的，可复用同一 `PrefixCache`（key 里 mode 用角色名区分）。

---

## 5. 验收标准

- `agent.prefix-cache.enabled=false` 时行为与现状一致。
- 同一 turn 内多步：`exportSchemas` 只被调用一次（可用日志/计数验证）。
- 同 mode + 同工具集 + 同 memo 的多次 turn：前缀缓存命中，`prefixCacheHit` 递增。
- 若供应商返回 `prompt_cache_hit_tokens`：metrics 能看到命中 token 数 > 0。
- 前缀字节稳定：同输入两次 `buildPrefix` 的 `hash` 完全相同（含跨请求）。

---

## 6. 风险与取舍

| 风险 | 缓解 |
|---|---|
| 前缀含 `LocalDate.now()` 导致跨天/跨请求漂移 | 改 `dateBucket()`（天级），跨天自然失效重建 |
| 工具集变更后缓存陈旧 | key 含 toolsetHash，工具增减自动 miss 重建 |
| 长期记忆更新未反映 | key 含 memoHash，memo 变即 miss |
| 服务端缓存策略不可控 | 应用侧只保证前缀稳定 + 读 usage 观测，不强依赖 |
| 与 `exportSchemas` 顺序不确定 | 需确认工具遍历顺序稳定（`ToolRegistry` 用 ConcurrentHashMap，导出前应排序固定） |

> **前置修复项**：`ToolRegistry.exportSchemas` 目前遍历 `tools.values()`（ConcurrentHashMap，顺序不保证）。为让前缀字节稳定，导出前应按工具名排序（一行 `sorted`），这是 P1 的隐含前提。

---

## 7. 创新点小结

- **turn 级前缀复用**：把每步重复的 `exportSchemas + format` 降为每 turn 一次，直接削减主循环 CPU/GC。
- **前缀字节稳定化**：让服务端 automatic prefix caching（DeepSeek context caching）可稳定命中，间接降 token 成本与首 token 延迟。
- **主/子代理共享缓存**：同一 `PrefixCache` 服务主 Agent 与三种子代理角色，最大化复用。
- **可观测闭环**：把"缓存命中"从黑盒变为可度量指标，指导后续调优。
