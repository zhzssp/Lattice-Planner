# 上下文工程 P1 实施记录：滚动摘要 + Facts 层

> 上游：`docs/design/上下文工程-滚动摘要与Facts层设计.md`
> 状态：已实现，**本机无 JDK 未编译**（IDE 语言服务 0 error + 人工审计）

---

# 〇、这一期做了什么

把当前「超窗即丢 + 事后声明」的有损丢弃，换成「折叠成要点 + 按需注入」的近无损管理。
分两步，各自独立开关、独立验收：

```
第一步：滚动摘要（ContextCompactor）    ← 会话内，窗口将满时折叠最老一段为摘要
第二步：Facts 层（FactService）          ← 会话内，抽取具体事实，按变更频率分流注入
```

---

# 一、滚动摘要（ContextCompactor）

## 1.1 触发与折叠

- 触发条件：窗口占用 ≥ 80%（`trigger-ratio`）× 窗口容量（`ConversationMemory.windowSize()`）
- 折叠量：最老 `fold-size`（默认 10）条
- 摘要长度上限 200 字符

## 1.2 三个关键决定（都写进了代码）

**① 纯工具噪声短路。** 待折叠段剔除工具 trace（复用 `ToolNoiseFilter`）后若真实对话 < `min-dialogue`（默认 6），**直接丢弃、不付 LLM 调用**。理由：一次 10 步 ReAct 产生 20 条工具噪声，不短路会为无价值内容反复烧钱。

**② 摘要走 `role=user`，且不进 system。** 与既有 `appendToolTrace` 立场一致（OpenAI 兼容接口对非标准 role 行为不一），且**进 system 会破坏前缀字节稳定、打穿上游 prompt cache**——这是本设计最核心的约束，见 §三。

**③ 失败回退等价旧行为。** LLM 折叠失败 → 退化为直接丢弃 + 置位 `CAUSE_TRUNCATED`，绝不因摘要失败阻断对话。

## 1.3 新增粘性降级原因

`TurnOutcome.CAUSE_SUMMARIZED`——摘要有损但近无损，与 `CAUSE_TRUNCATED`（纯丢弃）**区分统计**。不区分的话，`cleanConvergenceRate` 会重新把「折叠过历史但答对了」算成干净收敛，重蹈 L2 当年要修的偏乐观问题。

## 1.4 实现点

- `ConversationMemory` 新增 `compact(sid, count, role, summary)`：把最老 `count` 条换成一条摘要消息（空内容时退化为纯丢弃），替代默认的 `pollFirst` 无差别丢弃。
- `ToolNoiseFilter`：从 `LongTermMemoryService.isToolNoise` 抽出的独立纯函数，归档与折叠两处复用，避免判定规则漂移。
- 接线点：`AgentOrchestrator.appendToolTrace` 末尾调用 `compactIfNeeded`，异常吞掉。

---

# 二、Facts 层（FactService）

## 2.1 数据模型（V11__agent_fact.sql）

`agent_fact` 表，最核心的两列是 `source_quote`（原文片段）+ `source_turn`（第几轮）——
一条 LLM 抽出来的事实，若无法追溯它来自哪句话，抽错时用户发现不了、也没法纠正。
而错误的 fact 会被注入每一轮，污染面比一次错误回答大得多。

## 2.2 抽取与覆盖

- 抽取：每轮用户输入后 `@Async` fire-and-forget，输入 < 20 字跳过
- 置信度：只收 `MEDIUM` 以上（`LOW` 不入枚举、不入库）
- **覆盖而非追加**：同 key 新值把旧值标 `SUPERSEDED`，不删除历史
- **`REJECTED` 永不再抽**：用户标错后，同 key 不再自动重现

## 2.3 ★实现中发现并修复的一个缺陷

初版 `applyExtracted` 用 `findByUserIdAndFactKeyAndStatus(..., ACTIVE)` 查旧值，然后判断
`if (existing.getStatus() == REJECTED)`——**这段判断是死代码**：查询已经限定 ACTIVE，
永远查不到 REJECTED 的记录，于是「标错永不再抽」这条保证会静默失效，
被标错的 key 会被重新抽成一条新的 ACTIVE。

修复：新增 `findTopByUserIdAndFactKeyOrderByUpdatedAtDesc`（不限状态），先查最近一条
判断是否 REJECTED，再查 ACTIVE 做覆盖。测试 `rejectedKeyNotReextracted` 直接守住这条。

> 教训与 V4 P4 的「放宽白名单漏掉旧写入路径」同构：**安全/正确性保证的失效往往不是
> 逻辑写错，而是「判定条件永远够不到那个状态」。这类缺陷静默、且单测若不专门构造
> REJECTED 场景根本发现不了。**

## 2.4 注入（★不打穿前缀缓存）

| 类别 | 内容 | 位置 | 变更频率 |
|---|---|---|---|
| 稳定 facts | 长期偏好 | system prompt（并入 memo，经 `snippetFor` 拼接） | 天级 |
| 易变 facts | 会话内约束 | history 首条 user 消息（`msgs.add(1, ...)`） | 每轮 |

**易变 facts 刻意不进 system、不进 memory**：进 system 会让 `memoHash` 每轮变 → 前缀缓存 + 上游 prompt cache 双杀；进 memory 会污染持久历史。作为「注入视图」插在 system 之后、真实 history 之前——位置在前缀之后，所以不影响上游缓存的前缀命中。

## 2.5 刻意不做的事

- **不提供 `fact.remember` 工具**——facts 只能服务端从用户原话抽取。给模型「记住这件事」的工具，它会记一堆「用户似乎对 X 感兴趣」的猜测，把硬约束淹掉（与 P1 `checkpoint.predict`、P3 `gap.create` 同一立场）。
- **不做向量检索**——facts 是结构化键值，单用户量级 ≤ 数百条，精确 key + 时间衰减足够。

## 2.6 管理接口

`AgentFactController`：`GET /agent/settings/facts`（列表 + 原文核对）、`POST /agent/settings/facts/{id}/reject`（标错）。

---

# 三、★核心约束：不打穿前缀缓存

这是全期最重要的一个决定，单独成节。

`PrefixCache.PrefixKey(mode, toolsetHash, memoHash, dateBucket)` 里，`memoHash` 是长期记忆内容的 hash。若把「每轮都可能变」的易变 facts 塞进 system prompt，那么：

```
facts 变 → memoHash 变 → PrefixKey 变 → 前缀缓存 miss → system 字节变 → 上游 cache 也 miss
```

**后果是双杀**，会毁掉 P5 实测约 72% 的 prompt cache 命中率。

处置：按变更频率分流。易变 facts 走 history（变化点在前缀**之后**，只影响未命中尾部），稳定 facts 走 system（天级变更，不频繁刷新 memoHash）。

验收清单第 13 条「开启 facts 后 promptCacheHitRate 不显著下降」就是这条决定的直接验证。

---

# 四、配置

```properties
agent.context.compaction.enabled=false   # 滚动摘要总开关
agent.context.facts.enabled=false        # facts 总开关
agent.context.compaction.trigger-ratio=0.8
agent.context.compaction.fold-size=10
agent.context.compaction.summary-max-chars=200
agent.context.compaction.min-dialogue=6
agent.context.facts.max-stable=20
agent.context.facts.max-volatile=15
agent.context.facts.min-confidence=MEDIUM
agent.context.facts.stable-apply-granularity=DAY
```

两个开关默认 false。建议先只开 `compaction`（不碰 system，零缓存风险），确认摘要质量后再开 `facts`。

---

# 五、文件清单

## 新增（7）

```
feature/agent/runtime/ContextCompactor.java      滚动摘要
feature/agent/runtime/ToolNoiseFilter.java       工具噪声判定（从 LongTermMemoryService 抽出）
feature/agent/memory/AgentFact.java              facts 实体
feature/agent/memory/AgentFactRepository.java    facts 仓储（含不限状态的覆盖判定查询）
feature/agent/memory/FactService.java            facts 服务（抽取/覆盖/注入/纠正）
feature/agent/controller/AgentFactController.java facts 管理接口
db/migration/V11__agent_fact.sql                 建表
```

## 改动（6）

```
runtime/ConversationMemory.java        + compact / size / windowSize
runtime/LongTermMemoryService.java     复用 ToolNoiseFilter（删私有 isToolNoise）
runtime/AgentOrchestrator.java         接线滚动摘要 + 易变 facts 注入 + 异步抽取
runtime/turn/TurnOutcome.java          + CAUSE_SUMMARIZED
chat/AgentChatWebSocketHandler.java    稳定 facts 并入 memo
resources/application.properties       配置段
```

## 测试（新增 2 组）

```
ContextCompactorTest   8 用例：触发条件 / 纯噪声短路 / 折叠成功 / 失败回退
FactServiceTest        10 用例：抽取 / 置信度 / 覆盖 / REJECTED 永不再抽 / 注入片段
```

---

# 六、验收清单

## 滚动摘要

```
[1] 开关关闭时行为与当前逐字节一致（超窗仍 pollFirst）
[2] ★40+ 轮对话后，第 3 轮的 deadline 在第 40 轮仍被正确引用
[3] 折叠发生时 turnEnd.degradeCauses 出现 SUMMARIZED
[4] ★待折叠段全是工具噪声时，不触发 LLM 调用（日志确认）
[5] LLM 折叠失败回退为丢弃 + TRUNCATED，对话不中断
[6] cleanConvergenceRate 把「折叠过」排除在干净收敛之外
```

## Facts 层

```
[7] 抽出的 fact 含 source_quote + source_turn，UI 可核对
[8] 同 key 覆盖后旧值 SUPERSEDED 且不再注入
[9] ★REJECTED 的 key 不被重新抽取（对应 2.3 节那个缺陷）
[10] confidence=LOW 不入库
[11] ★fact.remember 工具不存在
[12] 纯工具轮不触发抽取
```

## ★缓存回归（最关键）

```
[13] ★开启 facts 后 promptCacheHitRate 不显著下降
[14] 稳定 facts 更新后同一天内 memoHash 不变（DAY 粒度）
[15] prefixCacheHitRate 与开启前持平
```

**最该先验第 13 条**——它验证 §三 那个核心决定是否成立。若它不成立，facts 层的收益会被 token 成本吃掉。

---

# 七、如实说明的限制

1. **摘要有损，无法保证忠实**。设计只保证「折叠过这件事不会被隐瞒」（`CAUSE_SUMMARIZED` 留痕），不保证「折叠内容完全正确」。
2. **facts 可能抽错，且错误会被注入每一轮**。三层防护（只收 MEDIUM+ / 可核对 / REJECTED 永不再抽）降低概率，但消除不了。这是两个开关默认关闭、且建议「先只开摘要」的原因。
3. **facts 的 key 归一化是启发式的**。同义不同 key（`deadline.project-x` vs `deadline.projectX`）可能导致覆盖失效。缓解：注入时未做 value 去重（当前量级可接受），彻底解决需受控词表。
4. **稳定 facts 的 DAY 粒度是「简化实现」**：当前直接并入 memo 每轮拼接，靠「稳定 facts 本身抽取稀少」控制 memoHash 变化频率，未做显式的「攒到次日」缓存。若将来稳定 facts 频繁变化，需补 `stable-apply-granularity` 的真正实现。
