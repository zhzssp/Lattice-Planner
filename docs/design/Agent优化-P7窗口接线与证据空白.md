# P7 · 窗口接线、确认契约、证据空白

> 状态：2026-09-14 真实 API 已补完（控 token：C-8 用 k=3，G1′ / Facts 用 k=1）。

## 做了什么

1. **`ConversationMemory` 读取 `agent.chat.history-window`**。P6 把评测窗口调到 8 时这个键没人读，折叠阈值仍按 30 算。
2. **确认契约**：创建免弹窗，状态变更必须弹窗；批量 = N 次单条。不加 `task.batch_complete`。
3. **C-8 毕业**：`batch_complete_overdue_only` 当前代码（提示词 + `UnconfirmedWriteAdvisor`）重录 `pass^3 = 100%`，三次都是 search → 三次 complete，已进回归集。
4. **G1′ k=1 对照成立**：不透明标记 `ref:7f3a`。开组窗口里有 `[对话摘要]`，任务备注带上标记；关组建了「写技术方案」，备注为空。三种事先写下的结论里，这次是**开过关不过**。
5. **Facts 抽取**：金标 16 条，P=1.0 / R=0.75，过门槛（0.65 / 0.50）。开关仍关。

## 用量（本次补录）

| 项 | trials | 约 LLM 次 | 报告成本 |
|---|---|---|---|
| `batch_complete_overdue_only` | 3 | 15 | ~$0.003 |
| `facts_extraction_accuracy` | 1 | 16 | ~$0.001 |
| `opaque_constraint_retention` | 1 | 18 | ~$0.002 |
| `opaque_constraint_no_compaction` | 1 | 11 | ~$0.001 |

未整套重录能力集，也未把 G1′ 扩到 k=3。前缀缓存命中约 90%。

## 讲解口径

> P6 的 `[WORK]` 对照双重无效：模型自我复述，窗口旋钮也没接到 `ConversationMemory`。
> G1′ 换了模型没有复述动机的标记，k=1 开过关不过——折叠把约束带过了窗口。这不是 pass^3。
> Facts 金标过了门槛，默认仍关：打开会在每轮加抽取调用，且没有「只开 VOLATILE」的独立旋钮。

不要说「上下文工程已验证有效」。机制层桩基准（0/5 → 5/5）和这一次 k=1 活体对照，说的是两件不同的事。

## Facts 为什么过了门槛还不打开

- 抽取走真实 LLM，打开等于每个够长的用户轮次多一次调用。
- STABLE 进 system prompt，错抽会污染前缀；没有只开 VOLATILE 的配置项。
- 评测 profile 锁死 `agent.context.facts.enabled=false`，避免既有录制盒指纹漂移。
