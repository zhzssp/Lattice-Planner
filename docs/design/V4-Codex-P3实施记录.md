# V4 Codex · P3 实施记录（缺口三源合流）

> 承接 `知识资产沉淀-Git仓库形态产品方案V2.md` §8.2。
>
> **状态：代码已落地，本机无 JDK，未执行编译与测试。**

---

## 0. 这一期回答什么问题

> **我不知道自己不知道什么。**

人无法凭回忆列出自己的盲区——盲区之所以是盲区，正因为想不起来。
所以「列一份待学清单」这种做法从根上不成立：能被列出来的，已经不是盲区了。

但盲区在**行为**里留了痕。P3 把三类此前用完即丢的痕迹攒起来：

| 源 | 含义 | 独有价值 | 此前的处置 |
|---|---|---|---|
| `CRAG` | 问了但库里没有 | 免费——信号本来就在产生 | 只用于让 Agent 说「以下基于通用知识」，**用完即丢** |
| `SKIP_RECALL` ★ | 当初判定「先跳过」的概念现在被反复问到 | 让止损线**可召回** | 「遇到了」这件事**完全无人监测** |
| `CP_FAIL` / `CP_MISPREDICT` ★ | 以为懂了但没懂 | **有机器判据** | P1 已采集，但没有下游消费 |

闭环：**缺口 →（可选）Issue → 学习计划 → 学完沉淀 → 关闭（须给证据文档）**

首尾都能被现有指标量化：入口是 CRAG 的 `degradedRate`，出口是缺口关闭数。
**degraded 率单调下降 = 知识体系真的在长**——这个指标无法靠「多写几篇文档」刷上去。

---

## 1. ★一个必须提前做的决定：skip 召回不能等 P4

### 1.1 问题

`SKIP_RECALL` 依赖 `kb_entity` 有数据，而原路线图把概念抽取放在后续的「蒸馏」阶段。
照原顺序执行的话，**skip 召回上线即是死的**：表里一条数据都没有，永远不会触发。
而它恰恰是 P3 最有价值的一环。

### 1.2 处置：做一个窄口径的解析器，而不是提前做全量概念抽取

新增 `ScopeListParser`：**只抽「先跳过」清单，不做全量概念抽取**。
两者难度差一个数量级——前者有明确的章节边界与列表结构，后者需要理解全文语义。

实测语料里有两种写法，都要支持：

| 形式 | 出现位置 | 示例 |
|---|---|---|
| `###` 小节标题 | `learning-guides/` | `### 9.2 可以先跳过` |
| 行内粗体小标题 | `paper-notes/` | `**可以先跳过的内容**：` |

措辞变体（全部实测存在）：`可以先跳过` / `先跳过` / `可以跳过` / `可推迟` / `以后再看`。

### 1.3 精度优先，而且代价是不对称的

| | 代价 |
|---|---|
| 漏一个术语 | 少一次提醒，用户毫无感觉 |
| **误报一次** | 用户立刻发现软件在瞎猜，**几天内就会关掉这个功能** |

所以只认三类高置信位置，置信度依次下降：

1. **行内代码** —— 作者显式标记的标识符（`spirv` / `DICompositeType` / `llvm-mca`）
2. **粗体** —— 作者显式强调的概念
3. **领头短语** —— 第一个分隔符（`（` `：` `。`）之前的片段，再按 `/`、`、` 切开，只取前 3 段

并把**整条原文存进 `reason`**，用户能核对「软件凭什么说我跳过了它」。

术语过滤的每一条规则都对应一类真实误报：

| 规则 | 挡住的误报 |
|---|---|
| 纯 latin 去符号后 < 3 字符 → 拒 | `io` / `os` 在提问里必然误报 |
| 纯中文且 > 12 字符 → 拒 | 那是描述不是术语 |
| 词数 > 5 → 拒 | 那是短句 |
| 以 `的/与/和/或/在/把/被` 收尾 → 拒 | 截断位置不对（「异常处理的」） |
| 无字母也无汉字 → 拒 | 只剩标点数字 |

### 1.4 ★词边界匹配是必须的

清单里有 `omp` / `acc` / `pdl` 这类三字母术语。用 `contains` 匹配的话：

```
"compiler 是怎么工作的"   → 命中 omp   ❌
"量化后 accuracy 掉了"     → 命中 acc   ❌
"pdll 声明式重写怎么写"    → 命中 pdl   ❌
```

一句话能误触发好几条。所以：

- **纯 latin 术语**：`(?<![A-Za-z0-9_])` + `Pattern.quote(term)` + `(?![A-Za-z0-9_])`
  用 lookaround 而非 `\b`——术语可能含 `.` `-` `+` `#`（`llvm-mca` / `c++`），
  `\b` 在这些字符处的行为与直觉不符。
- **含 CJK 的术语**：`contains`（中文没有词边界概念）。

`ScopeRecallTest.WordBoundary` 里每个断言都对应一种上面的真实误报。

### 1.5 阈值不是 1

一次提到某个跳过的概念，很可能只是顺带提及（「这个先不管」）。
反复被问才说明它真的挡路了。阈值把**「偶遇」与「绕不开」**区分开——
这个区分正是「遇到再学」里「遇到」二字的实际含义。默认 3，可配。

---

## 2. ★为什么信号在工具层发布，而不在 CorrectiveRetriever 里

这是本期最重要的一个技术决定。

`CorrectiveRetriever` 是产生 degraded 信号的地方，看起来是最自然的埋点位置。
但那里**缺少一层决定性的信息：这次检索是谁为了什么发起的**。

后台批量检索、索引自检、以及**评测套件跑的 47 个用例**都会经过 `CorrectiveRetriever`。
若在那里埋点，跑一次 `agentEval` 就会往缺口台账灌进几十条来自测试用例的假缺口——
而 `kb_gap` 是本期唯一**不可从仓库重建**的表，污染了没法靠重建索引洗掉。

只有「用户在对话里真的问了，而库里答不上来」才构成知识缺口。
这个语义只有工具调用层知道。所以：

```
KnowledgeTools.semanticSearch  ─┐
                                ├→ RetrievalDegradedEvent →  GapSignalListener
DocTools.searchDocs            ─┘        （定义在 pkm/crag）
```

事件定义在 `pkm/crag` 而非 codex，是为了保持依赖方向为 `codex → pkm`；
若放进 codex 就会出现 `agent/pkm → codex` 的反向耦合。
事件本身只陈述事实（「这次检索命中 0 条」），不含「该拿它做什么」的判断。

同理，检验信号也走事件（`CheckpointJudgedEvent`），与 P1 的 `RepoIndexedEvent` 同一立场：
**「关掉缺口闭环时验证行为完全不变」成为结构性保证**，而不是靠散落的 if。

三处发布点全部 `try { ... } catch { 吞掉 }`：附加信号失败绝不能让核心功能报错。

---

## 3. askCount 是排序键，不是统计量

这个定位决定了归一化的全部取舍。它回答「我该先补哪个盲区」，
不回答「这个问题我精确问过 N 次」。

### 3.1 因此不做 embedding 语义聚类

**过度聚合的危害是不对称的**：

- 把两个不同缺口合成一条 → `askCount` 虚高，且指向一个模糊的问题，**转成学习计划时无法执行**；
- 分成两条 → 只是让看板多一行。

所以拿不准时**宁可分开**。加上语义阈值需要按语料调参而这里没有标注集，
以及重复提问在真实场景里措辞高度接近（人会重复自己的说法），词法归一化足够。

### 3.2 归一化流程

```
原文 → 去代码块与行内代码 → 切 token → 去疑问词/虚词 → 丢弃过短 token
     → 去重 → 排序 → 拼接 → 截断 255
```

两个刻意的选择：

**排序 token**：「A 和 B 的区别」与「B 和 A 的区别」应聚成一条，它们只差语序。

**CJK 串不切成单字**：切成单字后排序等于「字符集合相同即同一问题」，
会把大量无关问句合并——正是上面说的有害过度聚合。
CJK 的处理是：剔除停用词后，剩余片段整体作为 token（长度 ≥2）。

**停用词只收确定无信息量的词**。像「区别」「原理」「实现」刻意**不收**——
它们区分了「X 是什么」和「X 与 Y 的区别」，那是两个不同的缺口。

**无法归一化时返回 null 且不登记**：全是停用词的提问无法去重，
登记它只会在台账里堆出一串永远聚不起来的噪声。

---

## 4. 三个状态语义上的严格区分

### 4.1 `CLOSED` ≠ `DISMISSED`

| | 含义 | 计入补全率分母 |
|---|---|---|
| `CLOSED` | 我补上了——一次**成果** | ✓ |
| `DISMISSED` | 这不是我的缺口——一次**判断** | ✗ |

混在一起会让「我补上了多少缺口」这个数字失真，而那正是衡量知识体系是否在长的关键数字。
所以 `closedRate` 的分母刻意排除 dismissed。

### 4.2 关闭必须给证据文档

`close()` 在没有 `documentPath` 时**直接拒绝**：

> 没有证据的「已关闭」只是自我安慰，而且以后想回溯「这个知识点我是怎么补上的」时无从查找。

而且这份证据有实际用途——缺口记录会直接指向那篇文档。

### 4.3 被 dismiss 的缺口不会被自动重开

只累加 `askCount`。用户判定过「这不是我的缺口」之后又被自动重开，
是最容易让人放弃一个功能的行为——他会觉得自己的判断不被尊重。
但计数仍然累加，因为若它真的被问了 20 次，用户自己会想回头看一眼。

---

## 5. 转学习计划：复用而非另造

`gap.to_learning_plan` 直接复用 `PlannerAgentService` + `AgentPlanApplyService`，
落成既有的目标 + 任务体系。

**刻意不新建一套「学习计划」实体**：用户的目标体系已经存在，学习本身就是一个目标。
另造一套会让「我在推进的事」分裂成两个列表，而**两个待办列表的结局一定是其中一个被遗忘**。

生成的计划强制附加两条约束：

```
"这是一次补知识缺口的学习，产出必须落成知识仓库里的文档或笔记"
"必须包含一条可执行的验收（能改能跑），不接受「读完就算学会」"
```

第二条把 P1 的立场带进了学习计划本身。

### 5.1 目标陈述按来源分别措辞

三类缺口的补法完全不同，用同一句模板会让规划器给出千篇一律的任务树：

| 来源 | 目标陈述要点 |
|---|---|
| `SKIP_RECALL` | 「学完要能说清它解决什么问题、**以及为什么当初可以先跳过而现在不能**」 |
| `CP_FAIL` | 「能独立跑通并解释每一步为什么这么做」 |
| `CP_MISPREDICT` | 「重点不是把结果做对（已经对了），而是**弄清我原来的因果推断错在哪里**」 |

### 5.2 关于「CURATE 模式 deny 了 task/goal，这个工具却会建目标」

看似矛盾，实则不是。那条 deny 防的是**顺手改**——「让它整理笔记，结果动了我的任务」。
而本工具的语义正相反：它唯一的作用就是建目标，用户调用它时明确知道会发生什么，
且带 `requiresConfirm` 需逐次确认。

**刻意不给它加 `goal` tag 来「绕过」deny**——tag 表达的是工具属于哪个能力域，
不是用来调可见性的旋钮。

---

## 6. 顺带修掉 P2 的一个隐患

P2 的 CI 里 `SCOPE_DANGLING` 检查在实体表为空时返回 `SKIPPED`。
**但 P3 会把「先跳过」清单填进实体表**，于是不再命中那个分支，
而语料里没有任何文档声明 front-matter `scope`，结果 `scanned=0`、findings 为空 → 返回 **OK**。

那就又一次把「没检查」显示成了「通过」。已修：`scanned==0` 时同样 `SKIPPED`，
并说明「止损线本身已解析进库（见缺口看板），本项校验的是 front-matter 里的显式声明，是另一回事」。

---

## 7. 文件清单

### 新增（11）

```
db/migration/V10__codex_gap.sql             kb_gap 建表

feature/codex/entity/
  KbGap.java                                缺口实体（含 Source / Status 语义注释）
feature/codex/repository/
  KbGapRepository.java                      按 askCount 倒序的看板主查询

feature/codex/gap/
  QuestionNormalizer.java                   词法归一化（★不做语义聚类的理由）
  ScopeListParser.java                      ★「先跳过」清单解析（两种写法 + 精度过滤）
  ScopeRecallService.java                   ★止损线召回（★词边界匹配）
  GapService.java                           台账 + 转计划 + 关闭 + Issue
  GapSignalListener.java                    三源汇入（全部只监听，异常吞掉）

feature/pkm/crag/
  RetrievalDegradedEvent.java               ★中立事实事件（定义在 pkm 保持依赖方向）
feature/codex/verify/
  CheckpointJudgedEvent.java                检验判定事件

feature/codex/tool/GapTools.java            gap.list/detail/to_learning_plan/close/dismiss
                                            + scope.skipped/set
feature/codex/controller/CodexGapController.java   缺口看板 REST
resources/templates/gap.html                缺口看板页

test/.../unit/
  GapNormalizerTest.java                    ★含「不该合的绝不能合」断言
  ScopeRecallTest.java                      ★解析精度 + 词边界（用真实语料片段）
```

### 改动（9）

| 文件 | 改动 |
|---|---|
| `KnowledgeTools.java` | 发布 `RetrievalDegradedEvent`（工具层，不在 CorrectiveRetriever） |
| `DocTools.java` | 同上（GIT_DOC 通路） |
| `CheckpointService.java` | 两处判定点发布 `CheckpointJudgedEvent` |
| `GitHubPrClient.java` | 新增 `createIssue` / `closeIssue` + 抽出 `baseRequest` |
| `CodexMetrics.java` | 新增 gap 分区（按来源分开计数） |
| `KnowledgeCiService.java` | ★修 `SCOPE_DANGLING` 在实体表非空时误报 OK |
| `CodexViewController.java` | 新增 `/codex/gaps` 路由 |
| `PromptBuilder.java` | 新增「知识缺口原则」段 |
| `application.properties` | 新增 `codex.gap.*` |
| `codex.html` | 加「知识缺口」入口 |
| `CodexModeVisibilityTest.java` | 补 P3 工具 fixture + study 只读断言 |

### 工具增量（7）

| 工具 | tags | confirm | 可见模式 |
|---|---|---|---|
| `gap.list` / `gap.detail` | `codex, read` | — | study / curate / verify |
| `scope.skipped` | `codex, read` | — | study / curate / verify |
| `gap.to_learning_plan` | `codex, write` | ✓ | **仅 curate** |
| `gap.close` / `gap.dismiss` | `codex, write` | ✓ | **仅 curate** |
| `scope.set` | `codex, write` | ✓ | **仅 curate** |
| ~~`gap.create`~~ | **刻意不存在** | — | — |

> `gap.create` 不存在的理由与 P1 的 `checkpoint.predict` 同类：
> 缺口的价值在于它来自**行为证据**而非主观感觉。若给 Agent 一个随手登记的工具，
> 它会在对话里「贴心地」记下一堆「你可能需要了解 X」——那些不是缺口，是猜测，
> 会迅速把台账淹掉，让真正有证据的三类信号沉底。
> 手工登记保留给用户经 HTTP 提交（`source=MANUAL`），那是用户自己的判断。

---

## 8. 验收清单（以 `AI-Infra` 为测试集）

前置：`codex.enabled=true`、跑完 V8/V9/V10 迁移、已同步索引、`codex.gap.enabled=true`。

| # | 项 | 判定标准 |
|---|---|---|
| 1 | **止损线解析** | `POST /api/codex/scope/sync` → `termsFound > 0`；`/codex/gaps` 止损线区能列出 `spirv` / `emitc` / `omp` / `llvm-mca` 等 |
| 2 | **不误抓「必须掌握」** | 抽出的术语里不含 `llvm-learning-guide.md` §9.1 的条目 |
| 3 | **reason 可核对** | 每条止损线都显示原始列表项全文 |
| 4 | **★词边界** | 问「compiler 是怎么工作的」→ `omp` 的 hitCount **不增加**；问「omp dialect 怎么用」→ 增加 1 |
| 5 | **阈值生效** | 同一术语问到第 3 次才出现 `SKIP_RECALL` 缺口；前两次只累加 hitCount |
| 6 | **CRAG 信号** | 问一个库里确实没有的问题 → 出现 `CRAG` 缺口，`askCount=1`；再问一次相同语义（换语序）→ `askCount=2` 而非新增一条 |
| 7 | **★不该合的没合** | 「X 是什么」与「X 和 Y 的区别」产生**两条**缺口 |
| 8 | **检验信号** | 跑一条检验失败 → `CP_FAIL` 缺口；判定预测错 → `CP_MISPREDICT` 缺口（两条独立） |
| 9 | **转学习计划** | 点「转学习计划」→ 生成目标 + 任务，缺口状态变 `PLANNED` 且带 `goalId`；任务里含可执行验收 |
| 10 | **关闭须证据** | 不给文档路径调 close → `EVIDENCE_REQUIRED`；给不存在的路径 → `DOC_NOT_FOUND` |
| 11 | **补全率分母** | dismiss 一条 → `closedRate` 分母不变 |
| 12 | **dismiss 不被重开** | dismiss 后再问同一问题 → 状态仍是 `DISMISSED`，但 `askCount` 增加 |
| 13 | **★评测不污染** | `codex.gap.enabled=false` 下跑 `gradlew agentEval` → `kb_gap` 表**零新增** |
| 14 | **CI 未回归** | `SCOPE_DANGLING` 状态为 `SKIPPED`（不是 OK），skipReason 说明是「无 front-matter 声明」 |
| 15 | **模式隔离** | `plan/reflect/learn/chat` 均看不到 `gap.*` / `scope.*`；`study` 能看到 `gap.list` 但看不到 `gap.to_learning_plan` |
| 16 | **可重建约束** | `DELETE FROM kb_entity, kb_scope_decision` → rescan 后 hitCount 归零属预期（它们是派生的）；而 `kb_gap` **必须保留**——它不可重建 |

第 4 与第 13 条最该先验：前者决定这个功能会不会被用户关掉，后者防止不可逆的数据污染。

---

## 9. 已知边界

| # | 事项 | 说明 |
|---|---|---|
| 1 | **未编译** | 本机无 JDK。仅有 IDE 语言服务 0-error 诊断 + 人工审计 |
| 2 | `hitCount` 是派生数据但存在 `kb_scope_decision` | 重新解析清单时**刻意不重置**（与 P1 检验同步同一立场）；但删表重建会归零。这是自觉的取舍：为它单独建一张不可重建的表不值得 |
| 3 | 语义聚类未做 | 见 §3.1。若将来台账里出现大量「明显是同一问题却分成多条」，再考虑 |
| 4 | Issue 状态不回读 | 在 GitHub 上手动关闭 Issue，本地台账不会跟着变。要做需要 webhook 或轮询 |
| 5 | `Closes #N` 未自动写入 PR | P2 的 PR body 不会自动带上关联缺口的 Issue 号。用户可手填；自动化需要把 gap 与分支关联起来 |
| 6 | 概念抽取仍是窄口径 | 只抽「先跳过」清单。全量概念（用于覆盖率计算）仍待后续阶段 |
| 7 | 缺口上限行为 | 达到 `max-open` 后**停止新增**而非淘汰最老的——最老的往往最该补。代价是新缺口会被静默丢弃（有 INFO 日志） |

---

## 10. 需要你做的

```
1. 有 JDK 的环境跑：gradlew test 与 gradlew agentEval
2. 跑 V10 迁移
3. 保持 codex.gap.enabled=false 先跑一次 agentEval，确认 kb_gap 零新增（验收 13）
4. 再开 codex.gap.enabled=true，访问 /codex/gaps
5. 先点「重新解析清单」看止损线抽得对不对（验收 1~3）——
   这一步不产生任何缺口，纯只读判断解析质量
6. 确认解析质量后再开始日常提问，让三类信号自然积累
```

第 5 步与第 6 步刻意分开，理由与 P2 的「先 CI 后写入」相同：
**先确认软件的判断可信，再让它开始记录。** 若止损线抽得一塌糊涂，
先积累出来的缺口全是噪声，而缺口表不可重建。
