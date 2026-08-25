# 上下文工程 P1：滚动摘要 + Facts 层设计

> 上游背景：`Agent实现方案.md`、`Agent前缀缓存-PrefixCaching实现计划.md`、`长期记忆机制补全实现报告.md`
> 状态：**设计稿，未实施**
> 一句话：把当前「超窗即丢 + 事后声明」的有损丢弃，换成「折叠成要点 + 按需注入」的近无损管理。

---

# 〇、先说清这份设计的边界

**它解决什么**：会话内关键信息因滑动窗口硬截断而**静默丢失**。

**它不解决什么**：
- 不做 token 级压缩（LLMLingua 那类需要额外小模型，当前规模不划算）；
- 不做分布式记忆 / 向量化记忆检索（当前是单机单用户，全表扫 facts 比引 embedding 更快也更准）；
- 不追求"永不丢信息"——只追求"丢的是细节，留下的是约束"。

**为什么现在做这个而不是别的**：因为它填的是当前**最致命**的一个洞。个人规划助手里，用户第 3 轮说「这个项目 deadline 是下周五」，第 35 轮时那条消息已被 `pollFirst` 丢掉，Agent 会照着错的时间排期，**而它和用户都不知道信息丢了**。相比之下"检索质量差"只是答得不好，这个是答得**错**。

---

# 一、现状盘点（先如实承认现在很薄）

## 1.1 当前上下文工程共四招

| # | 机制 | 实现位置 | 本质 |
|---|---|---|---|
| ① | 滑动窗口 30 条 | `ConversationMemory.WINDOW=30`，`while(q.size()>WINDOW) q.pollFirst()` | **硬丢弃**，最老的直接扔 |
| ② | 工具结果截断 4000 | `AgentOrchestrator.truncate(resultJson, 4000, outcome)` | **硬截断** + 置粘性降级标记 |
| ③ | 子代理上下文隔离 | `SubAgentRunner` 局部上下文，只回 ≤4000 字结论 | ✅ 唯一算"工程"的一招 |
| ④ | 长期记忆归档 | 空闲 30min → `LongTermMemoryService.archive` → `AGENT_MEMO` | 跨会话；**会话内不生效** |

## 1.2 ①④ 的具体缺陷

**① 滑动窗口是无差别丢弃。** 它不区分「用户设定的硬约束」与「一次工具结果回灌」——而后者在历史里的占比远高于前者（每步 ReAct 产生 2 条：assistant 的 tool JSON + user 的 tool_result）。**结果是：工具噪声把用户的真话挤出了窗口。**

一次 10 步的 ReAct 就产生 20 条历史。窗口 30 条意味着**不到两轮复杂对话，第一轮用户说的话就被挤没了**。

**④ 归档有三个错位**：

| 错位 | 现状 | 后果 |
|---|---|---|
| 时机 | 空闲 30 分钟后才归档 | 会话内丢的信息，归档救不回来——它是**事后**的 |
| 粒度 | 整段对话凝练成 3~6 行"用户画像" | 「deadline 下周五」这种**具体事实**会被凝练成"用户关注项目进度"，等于丢了 |
| 注入 | `snippetFor` 取最近 5 条 memo **全量**塞进 system prompt | 与当前话题无关的也带上，占 token 且干扰 |

**一句话**：`AGENT_MEMO` 是「画像」，不是「事实」。画像回答"这人是谁"，而排期需要的是"deadline 是哪天"。**两者不能互相替代。**

## 1.3 已经做对的地方（新设计必须保留）

- **粘性降级标记**：截断即置位、不可清除。这个立场是对的，新设计要延续——摘要也是信息有损，**折叠过就要留痕**。
- **`isToolNoise()` 判定**：归档时剔除工具 trace 的逻辑已经存在且正确，滚动摘要可直接复用。
- **前缀缓存 + 字节稳定化**：这是 P5 的成果（上游 prompt cache 命中 token 占比实测约 72%），**新设计绝不能打穿它**——见第三章，这是本设计最关键的约束。

---

# 二、目标与非目标

## 2.1 目标（可验收）

| # | 目标 | 判据 |
|---|---|---|
| G1 | 关键约束不因超窗而丢失 | 构造 40+ 轮对话，第 3 轮说的 deadline 在第 40 轮仍被正确引用 |
| G2 | 信息折叠必须留痕 | 发生摘要时置位粘性标记，可在 `turnEnd.degradeCauses` 看到 `CAUSE_SUMMARIZED` |
| G3 | **不打穿前缀缓存** | 开启本特性后 `prefixCacheHitRate` 与 `promptCacheHitRate` 不显著下降 |
| G4 | 可完全关闭且行为回退 | 开关关闭时逐字节等价于当前实现 |
| G5 | facts 可解释、可纠正 | 每条 fact 记录来源轮次与原文片段；用户可在 UI 删除/修正 |

## 2.2 非目标（明确不做）

- ❌ token 级压缩（LLMLingua / 小模型删词）
- ❌ facts 的向量检索（当前单用户 facts 量级 ≤ 数百条，关键词 + 时间衰减足够）
- ❌ 跨用户记忆共享
- ❌ 保证摘要内容 100% 忠实（**这做不到**，所以要留痕 + 可追溯原文）

---

# 三、★核心冲突：facts 注入 vs 前缀缓存（本设计最重要的一节）

## 3.1 冲突是什么

直觉方案是「facts 实时注入 system prompt」。但 `PrefixCache.PrefixKey` 是：

```java
record PrefixKey(String mode, String toolsetHash, String memoHash, String dateBucket)
```

`memoHash` 是长期记忆内容的 hash。若 facts 进 system prompt 且**每轮都可能变**，那么：

```
facts 变 → memoHash 变 → PrefixKey 变 → 前缀缓存 miss
                                      → system prompt 字节变
                                      → 上游 automatic prefix caching 也 miss
```

**后果是双杀**：本地前缀白建一次（CPU，小损失），**上游 prompt cache 命中率归零（token 成本，大损失）**。P5 辛苦做的「字节稳定化」被自己新功能毁掉——这是最讽刺的一种回退。

## 3.2 处置：facts 分两类，按"变更频率"决定放哪

| 类别 | 内容 | 放哪 | 变更频率 |
|---|---|---|---|
| **稳定 facts** | 长期偏好、习惯（"习惯早上做深度工作"） | **system prompt**（进 memoHash） | 天级，极低 |
| **易变 facts** | 会话内的具体约束（"这个项目 deadline 下周五"） | **history 首条 user 消息**（不进前缀） | 每轮可能变 |

关键在于：**易变 facts 走 history 而不走 system**。

```
messages = [
    system(prefix)          ← 字节稳定，缓存命中
    user("[已知事实]\n- deadline: 下周五\n- 技术栈: MLIR")   ← 易变部分放这里
    ...真实历史...
]
```

**为什么这样就不打穿缓存**：上游 automatic prefix caching 按**前缀**匹配。system 消息在最前面且字节不变，所以「system 这一段」仍然命中；变化发生在它之后，只影响未命中的尾部。而如果把 facts 塞进 system，**变化点在最前面，整条前缀全废**。

> 这是本设计里最值得讲的一个决定：**同一份信息，放在 prompt 的哪个位置，决定了它是否摧毁缓存。**
> 位置不是排版问题，是成本问题。

## 3.3 稳定 facts 的更新节奏也要控

即使是稳定 facts，也不能一有新发现就改 `memoHash`。做法：

- 稳定 facts 的变更**攒着**，按 `dateBucket` 粒度生效（与现有 `dateBucket()` 天级设计一致）；
- 或显式提供"立即生效"入口（用户在 UI 手动确认某条 fact 时才刷新）。

**理由**：一天内多次刷新 memoHash，等于一天内多次让上游缓存冷启动。而稳定 facts 本身就是"长期"的，晚几小时生效没有实际损失。

---

# 四、方案设计

## 4.1 总览

```
                    ┌─────────────────────────────────────┐
用户消息 ──────────▶│ ConversationMemory（滑动窗口 30）      │
                    │  超窗时不再 pollFirst 直接丢          │
                    │       ↓                              │
                    │  ContextCompactor（★新增）            │
                    │   ├ 剔工具噪声（复用 isToolNoise）     │
                    │   ├ LLM 折叠最老 N 轮 → 1 条摘要      │
                    │   └ 置位 CAUSE_SUMMARIZED             │
                    └──────────────┬──────────────────────┘
                                   │
                    ┌──────────────▼──────────────────────┐
                    │ FactStore（★新增）                    │
                    │  每轮异步抽取 → 稳定 / 易变 分流        │
                    └──────────────┬──────────────────────┘
                                   │
       ┌───────────────────────────┴────────────────────┐
       ▼（稳定：进 memoHash，天级）        ▼（易变：进 history 首条，每轮）
   system prompt                      user("[已知事实]...")
```

## 4.2 组件一：`ContextCompactor`（滚动摘要）

**触发条件**（两个都满足才触发，避免过度摘要）：

```java
history.size() >= WINDOW * 0.8    // 接近满
&& dialogueMsgCount(history) >= 6 // 有足够的真实对话可折叠（不是纯工具噪声）
```

**折叠算法**：

```
1. 取最老的 ⌊WINDOW/3⌋ 条（默认 10 条）作为待折叠区
2. 剔除其中的工具噪声（复用 LongTermMemoryService.isToolNoise）
   ★若剔完为空 → 直接丢弃这批（纯工具噪声无需摘要，省一次 LLM 调用）
3. 剩余交给 LLM 折叠成 ≤200 字的一条 summary
4. 用一条 role=user、内容 "[对话摘要 · 第 N~M 轮]\n..." 的消息替换这 10 条
5. 置位 outcome.markDegraded(CAUSE_SUMMARIZED)
```

**为什么摘要用 `role=user` 而不是 `system`**：与现有 `appendToolTrace` 的立场一致——OpenAI 兼容接口对非标准 role 的行为在不同供应商间不一致，user role 最稳。而且它**不能进 system**，否则又打穿前缀（见第三章）。

**为什么第 2 步"剔完为空就直接丢"很重要**：一次 10 步 ReAct 产生 20 条工具噪声。若不做这个短路，每次超窗都会为一堆 `[tool_result ...]` 付一次 LLM 调用，而摘要出来的内容对用户毫无价值。**这是成本上的必要优化，不是可选优化。**

**失败降级**：LLM 折叠失败 → 回退到当前行为（直接 `pollFirst` 丢弃）+ 置位 `CAUSE_TRUNCATED`。**绝不因为摘要失败而阻断对话**——这与项目里"附加信号失败不影响主链路"的既有立场一致。

## 4.3 组件二：`FactStore`（facts 层）

### 数据模型

```sql
CREATE TABLE agent_fact (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT      NOT NULL,
  session_id   VARCHAR(64),            -- 易变 facts 绑定会话；稳定 facts 为 NULL
  kind         VARCHAR(16) NOT NULL,   -- STABLE / VOLATILE
  fact_key     VARCHAR(64) NOT NULL,   -- 归一化键，如 "deadline.project-x"
  fact_value   VARCHAR(512) NOT NULL,
  source_quote VARCHAR(512),           -- ★原文片段，用于核对
  source_turn  INT,                    -- ★来自第几轮
  confidence   VARCHAR(16),            -- HIGH / MEDIUM（LOW 不入库）
  status       VARCHAR(16) NOT NULL,   -- ACTIVE / SUPERSEDED / REJECTED
  created_at   DATETIME    NOT NULL,
  updated_at   DATETIME    NOT NULL,
  UNIQUE KEY uk_user_key_session (user_id, fact_key, session_id),
  KEY idx_user_kind_status (user_id, kind, status)
);
```

### ★为什么必须存 `source_quote` + `source_turn`

这是整张表最重要的两列。理由与 V4 P3 的缺口台账存 `reason` 完全同构：

> **用户必须能核对「软件凭什么说我有这条约束」。**

一条 LLM 抽出来的 fact，如果只有 `deadline: 下周五` 而无法追溯它来自哪句话，那么当它抽错时（把"我在想是不是下周五"抽成"deadline 是下周五"），用户**发现不了、也没法纠正**。而一条错误的 fact 会被反复注入每一轮，**污染面比一次错误回答大得多**。

### 抽取时机与方式

- **时机**：每轮结束后**异步**抽取（fire-and-forget，与 RAG 预取同一手法）
- **成本控制**：只在"本轮有用户新输入且长度 > 20 字"时抽，纯工具轮不抽
- **失败处理**：静默失败，只打 debug 日志

### ★覆盖而非追加（避免脏 facts 累积）

同一 `fact_key` 被新值覆盖时，旧值标 `SUPERSEDED` 而**不是删除**：

- 保留历史让用户能看到"这条约束被改过"
- `SUPERSEDED` 不注入上下文

**为什么不直接 UPDATE**：deadline 从"下周五"改成"下周三"是有意义的历史，而且如果新抽的是错的，用户能看到被它覆盖掉的旧值。

### 注入策略

```
稳定 facts：全量（数量本就少，≤20 条），进 system prompt
易变 facts：当前会话 ACTIVE 的全部（≤15 条），进 history 首条 user 消息
```

**为什么不做相关性筛选**：单用户单会话的易变 facts 本就是十几条量级，"筛"引入的相关性误判风险（漏掉真正相关的那条）远大于"全带"的 token 成本。这与 V4 P3「不做语义聚类」是同一个判断——**规模小的时候，简单穷举比聪明筛选更安全。**

---

# 五、关键取舍（面试会被问的都在这）

## 5.1 为什么摘要走 LLM 而不是抽取式（TextRank 之类）

抽取式无 LLM 成本，但**它保留的是"出现频率高的句子"，而我们要保留的是"约束"**——约束往往只说一次。抽取式恰好会丢掉它。

代价：每次折叠一次 LLM 调用。用 4.2 的"纯工具噪声短路"把频率压下来。

## 5.2 为什么不把 facts 做成向量检索

facts 是**结构化的键值**，不是自然语言段落。`deadline.project-x` 这种 key 用精确匹配就够，上 embedding 反而引入"语义相近但不是同一件事"的误命中。

**门槛判断**：facts 超过 ~500 条 / 单用户时再考虑。当前场景远达不到。

## 5.3 为什么摘要也要置粘性降级标记

摘要是**有损**的。若不置位，`cleanConvergenceRate` 会把"折叠过历史但答对了"算成干净收敛——**这正是 L2 粘性标记当初要修的偏乐观问题**。新增有损环节就必须同步纳入统计，否则那个指标会重新变得偏乐观。

新增 cause：`CAUSE_SUMMARIZED`（与 `CAUSE_TRUNCATED` 区分——前者近无损，后者纯丢弃，两者的严重度不同）。

## 5.4 为什么 facts 抽取不给 Agent 一个工具

**刻意不提供 `fact.remember` 工具**。理由与 P1 的 `checkpoint.predict`、P3 的 `gap.create` 完全一致：

> 给模型一个"记住这件事"的工具，它会热情地记下一堆"用户似乎对性能优化感兴趣"这类猜测，迅速把 facts 表淹掉，让真正的硬约束沉底。

facts 只能由**服务端从用户原话里抽取**，模型无法主动写入。

## 5.5 最大的风险：facts 抽错了怎么办

**这是本设计最大的风险，必须正面承认。** 一条错误的 fact 会被注入每一轮，比一次错误回答的污染面大得多。

三层防护：

| 层 | 措施 |
|---|---|
| 入库前 | 只收 `confidence >= MEDIUM`；`LOW` 直接丢弃 |
| 可核对 | 必存 `source_quote` + `source_turn`，UI 可查看原文 |
| 可纠正 | UI 提供"删除 / 标记错误"，标 `REJECTED` 后**永不再抽同 key**（避免反复重现，与 P3「dismiss 不自动重开」同构） |

**仍然做不到的**：无法保证抽取 100% 正确。所以 G5 把"可解释、可纠正"列为验收目标，而不是"准确率 ≥ X%"——**后者我无法诚实地承诺。**

---

# 六、配置项

```properties
# ==============================
# 上下文工程 P1 · 滚动摘要 + Facts 层
# ------------------------------
# 总开关。默认 false：本特性会引入额外 LLM 调用，且 facts 抽错会污染每一轮。
# 建议先只开 summary（只读语义、风险低），确认摘要质量后再开 facts。
agent.context.compaction.enabled=false
agent.context.facts.enabled=false

# 触发摘要的窗口占用比例（0.8 = 窗口用到 80% 时开始折叠）
agent.context.compaction.trigger-ratio=0.8
# 单次折叠的消息条数
agent.context.compaction.fold-size=10
# 摘要长度上限（字符）
agent.context.compaction.summary-max-chars=200

# facts 注入上限
agent.context.facts.max-stable=20
agent.context.facts.max-volatile=15
# 抽取阈值：低于 MEDIUM 的置信度直接丢弃（宁缺勿错）
agent.context.facts.min-confidence=MEDIUM

# ★稳定 facts 的生效粒度。DAY = 攒到次日生效（不打穿上游 prompt cache）
#   IMMEDIATE 会让 memoHash 每次变化都刷新前缀，谨慎使用
agent.context.facts.stable-apply-granularity=DAY
```

---

# 七、实施顺序（分两步，可独立验收）

## 第一步：滚动摘要（风险低，先做）

```
1. ConversationMemory 增 compactIfNeeded 钩子（不改 WINDOW 语义）
2. 新增 ContextCompactor（折叠 + 纯噪声短路 + 失败回退）
3. TurnOutcome 增 CAUSE_SUMMARIZED
4. 测试：折叠后关键约束仍在、纯工具噪声不触发 LLM、失败回退等价旧行为
```

**这一步不碰 system prompt，所以零前缀缓存风险。**

## 第二步：Facts 层（风险高，后做）

```
1. V11__agent_fact.sql + AgentFact 实体 + Repository
2. FactExtractor（异步抽取）+ FactStore（覆盖/SUPERSEDED 语义）
3. 注入：稳定 → PromptBuilder（memoHash）；易变 → AgentOrchestrator（history 首条）
4. UI：facts 列表 + 原文核对 + 删除/标错
5. ★回归：开启前后对比 prefixCacheHitRate 与 promptCacheHitRate
```

---

# 八、验收清单

## 滚动摘要

```
[1]  开关关闭时，行为与当前逐字节一致（超窗仍 pollFirst）
[2]  ★构造 40+ 轮对话（含多次 10 步 ReAct），第 3 轮说的 deadline 在第 40 轮仍被正确引用
[3]  折叠发生时 turnEnd.degradeCauses 出现 CAUSE_SUMMARIZED
[4]  ★待折叠区全是工具噪声时，不触发 LLM 调用（看日志确认）
[5]  LLM 折叠失败时回退为丢弃 + CAUSE_TRUNCATED，对话不中断
[6]  cleanConvergenceRate 把"折叠过"的轮次排除在干净收敛之外
```

## Facts 层

```
[7]  抽出的 fact 均含 source_quote 与 source_turn，UI 可查看原文
[8]  同 key 新值覆盖后，旧值为 SUPERSEDED 且不再注入
[9]  标记 REJECTED 的 fact 不再被重复抽取
[10] confidence=LOW 的候选不入库
[11] ★fact.remember 工具不存在（facts 只能服务端抽取）
[12] 纯工具轮（用户无新输入）不触发抽取
```

## ★缓存回归（最关键）

```
[13] ★开启 facts 后，promptCacheHitRate 不显著下降
     （易变 facts 走 history 而非 system 的直接验证）
[14] ★稳定 facts 更新后，同一天内 memoHash 不变（DAY 粒度生效）
[15] prefixCacheHitRate 与开启前持平
```

**最该先验第 13 条**——它验证的是第三章那个核心决定是否真的成立。若它不成立，本设计的收益会被 token 成本的上升吃掉，那就该重新考虑要不要做 facts 层。

---

# 九、如实说明的限制

1. **摘要有损，无法保证忠实**。所以要留痕（`CAUSE_SUMMARIZED`）+ 可追溯。设计只保证"折叠过这件事不会被隐瞒"，不保证"折叠内容完全正确"。
2. **facts 可能抽错**，且错误会被注入每一轮。三层防护降低概率，但消除不了。这是本设计最大的风险，也是两个开关默认关闭、且建议"先只开摘要"的原因。
3. **不解决单次超长上下文**。若一次工具返回 10 万字符，本设计无能为力（那是截断 + 子代理隔离的职责）。
4. **facts 的 key 归一化是启发式的**。`deadline.project-x` 这种 key 由 LLM 生成，可能出现同义不同 key（`deadline.projectX`）导致覆盖失效、同一约束存两条。缓解：注入时按 value 去重。**这个缺陷会留着**，因为彻底解决需要 key 的受控词表，那会让抽取变得很脆。

---

# 十、与既有立场的一致性检查

| 既有立场（来自 V3/V4） | 本设计如何延续 |
|---|---|
| 有损即留痕，不可洗白（L2 粘性标记） | 摘要置 `CAUSE_SUMMARIZED`，与截断区分严重度 |
| 不给模型"自我登记"的工具（P1/P3） | 刻意不做 `fact.remember`，facts 只能服务端抽取 |
| 判断必须可核对（P4 定线） | facts 必存 `source_quote` + `source_turn` |
| 用户判定过的不被自动重开（P3 dismiss） | `REJECTED` 的 key 永不再抽 |
| 规模小时穷举优于聪明筛选（P3 不做聚类） | facts 全量注入，不做相关性筛选 |
| 新能力必须可完全关闭且行为回退 | 两个独立开关，关闭时逐字节等价 |
| 附加信号失败不影响主链路 | 摘要失败回退丢弃；抽取失败静默 |

**这张表是有意加的**：它说明这份设计不是孤立的新功能，而是延续同一套方法论——**把"靠模型自觉"换成"结构上保证"，且每次有损都如实留痕。**
