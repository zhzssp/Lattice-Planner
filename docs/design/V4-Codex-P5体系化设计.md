# V4 Codex P5 设计：体系化（SYNTHESIZER）

> 上游：`知识资产沉淀-Git仓库形态产品方案V2.md` §十三 P5、§9.1 SYNTHESIZER
> 前序：P0 索引 / P1 验证 / P2 沉淀+CI / P3 缺口 / P4 蒸馏+定线
> 状态：**设计稿，未实施**
> 一句话：把「仓库」变成「体系」——但体系不是 LLM 写出来的，是**从已有结构化数据算出来的**。

---

# 〇、先把这一期最大的陷阱说清楚

V2 路线图对 P5 的描述是：

> `SYNTHESIZER` 综述：多 Guide → foundations / 同构表 / 断链表。
> **"仓库→体系"的关键动作**。

这句话最容易被实现成一个平庸的东西：**「让 LLM 读一堆 guide，吐一篇 foundations 文档」**。

那样做的问题不是"效果不好"，而是**它和 P4 的蒸馏没有本质区别**——只是输入从 PDF 换成了 guide。而且它会踩中三个具体的坑：

| 朴素做法 | 会产出什么 | 为什么是废的 |
|---|---|---|
| LLM 跨 guide 抽「公共词汇」 | `module` / `function` / `pass` | 这些是**高频词**，不是**横切概念**。频率最高的词往往最没有信息量 |
| LLM 排「补课顺序」 | 一份看起来合理的顺序 | **用户无法核对**。这正是 P4 做 ROUTER 时刻意避开的问题 |
| LLM 生成「同构表」 | `A ≈ B`，因为模型觉得像 | 不是证据驱动的等价，而是**模型的联想** |

> **P5 的核心挑战不是"怎么调 prompt"，而是"怎么定义一个不可作弊的判据"。**
> 前四期每一期都有一个硬判据（预测门禁 / 示例门禁 / 路径门禁 / 止损线可解析）。
> 而"什么是知识体系"**没有自然的机器判据**——它必须被设计出来。

---

# 一、★核心立场：判据三分

这是整份设计的骨架。把「体系化」这件事拆成三类，**分别用不同手段**，绝不混为一谈：

| 类别 | 内容 | 谁来做 | 可否作弊 |
|---|---|---|---|
| **A 可机器判定** | 覆盖率、孤岛、断链、entity 引用分布 | **纯计算**（零 LLM） | 不可能——数字就是数字 |
| **B 可机器发现、须人确认** | 同构候选、补课顺序候选 | 机器给候选 + **人拍板** | 候选可能错，但由人把关 |
| **C 只能人判定** | 「这套东西的本质是什么」 | **人写，机器不碰** | — |

**LLM 在这里的角色被严格限定为：把 A 的计算结果和 B 的已确认结论，写成可读的 Markdown。** 它不参与"发现"，只参与"表达"。

> 这个立场与 P4 的 ROUTER 完全一致：ROUTER 一次 LLM 都不调，因为"投入 60% 学 LLVM"是主观分配，机器无依据去改。
> P5 同理——**体系的骨架必须来自证据，LLM 只负责排版。**

---

# 二、现状盘点：地基其实已经打好了

这是我建议现在做 P5 的实际理由——它**不需要新建基础设施**，需要的数据 P0~P4 已经攒齐：

| 已有 | 位置 | P5 怎么用 |
|---|---|---|
| `kb_entity`（知识点/概念） | P0 建表，P3 由「先跳过」清单填充 | **覆盖率的分母** |
| `kb_entity.aliases` | P0 已有字段 | **同构换名的载体**（`fatbin ≈ ExecutableVariant`） |
| `kb_entity.definedInDocumentId` | P0 已有字段 | 「定义处」——覆盖率的分子之一 |
| `kb_link`（含 `BACKREF` / `broken`） | P0 建表 | **孤岛与断链的判据** |
| `kb_scope_decision`（MUST/SKIP） | P3 | 覆盖率该不该算这条 entity |
| `kb_gap.askCount` | P3 | **补课顺序的排序键**（被问最多的优先） |
| `kb_checkpoint`（含 L2 通过状态） | P1 | 「这个概念是否真的掌握」的机器判据 |

## 2.1 ★但有一个硬约束必须正面承认

**当前 `kb_entity` 的唯一填充来源是 P3 的「先跳过」清单解析。**

我核对了 `ScopeRecallService.sync`——它只从 guide 的「可以先跳过」小节抽术语建 entity。这意味着：

> **entity 表里现在装的是「用户主动跳过的概念」，而不是「这个领域的全部横切概念」。**

后果很直接：**覆盖率的分母是错的**。用 `26 条 foundations 概念` 做分母才有意义，而当前分母是"跳过清单里的术语数"。若不处理这一点就算覆盖率，会得到一个**看起来精确、实际无意义**的数字——比 P2 那个"把 SKIPPED 显示成 OK"的问题更隐蔽，因为它是个百分比，天然有说服力。

**处置（本期必做的第一件事）**：增加 entity 的第二个填充来源——**从 foundations 类文档的结构化清单里解析**。

判据仍然是"窄口径、高置信"，与 P3 的 `ScopeListParser` 同一手法：
- 只认 `kind: foundations`（front-matter）或路径含 `foundations` 的文档；
- 只从**编号列表 / 表格行 / H3 标题**这类明确结构里抽，不做全文语义抽取；
- 每条存 `definedInDocumentId` + 原文行，供用户核对。

**若仓库里没有 foundations 文档** → 覆盖率一律返回 `SKIPPED`，并写明"没有 foundations 清单，无法确定分母"。
**绝不用「已抽到的 entity 数」当分母**——那会让覆盖率恒为 100%，是自欺。

---

# 三、A 类：可机器判定的四个指标（零 LLM）

## 3.1 entity 覆盖率

```
分母：kb_entity 中 scope_decision != DROPPED 的条目数
分子：满足下列任一条件的 entity 数
      ① 有 definedInDocumentId（在某篇 guide 里被定义）
      ② 被某篇 note 的正文提及（复用 P3 的词边界匹配，不用 contains）
      ③ 有关联的 checkpoint（说明它被验证过）
```

**三级覆盖，分开报，不合并成一个数**：

| 级别 | 含义 | 为什么要分开 |
|---|---|---|
| `MENTIONED` | 被提到过 | 最弱，"知道有这回事" |
| `DEFINED` | 有定义处 | 中等，"读过了" |
| `VERIFIED` | 有 L2 及以上 checkpoint 通过 | **最强，"能改能跑"** |

> 合并成一个"覆盖率 78%"是最容易做错的一步。因为 `MENTIONED` 和 `VERIFIED` 的含义差着数量级——
> 前者只说明这个词出现过，后者说明你真的掌握了。混在一起算，等于把"听说过"和"会用"当成同一件事，
> 而那正是本产品最反对的「假掌握」。

## 3.2 孤岛检测

```
孤岛 note   ：kind=NOTE 且没有任何 BACKREF 指向 guide（P2 的 CI 已有，这里聚合成体系视图）
孤岛 entity ：既无 definedIn、也无任何文档提及
孤岛 guide  ：没有任何 note 挂回它，也没有 checkpoint（读完就扔的文档）
```

孤岛 guide 是最有价值的一类——它意味着**这篇文档读完之后什么都没沉淀下来**，可能是内容不重要，也可能是该补检验。

## 3.3 断链表（ChainLink）

V2 §1.3 把断链表列为"三个独特技巧"之一：「某一环断了下游有什么症状、能不能补救」。

**本期只做能机器判定的那一半**：`kb_link` 里 `broken=true` 的链接聚合成"哪些文档在引用不存在的东西"。

**刻意不做的那一半**：「某环断了下游什么症状」——这是**因果知识**，只能人写。机器无法从链接图推断"bufferization 挂了会导致什么"。所以设计里给它留一个人工填写的位置（`.lattice/chainlink.yml`），机器只负责校验引用的 entity 是否存在，不生成内容。

## 3.4 域分布

按 `kb_document.domain` / 目录结构统计各域的资产数（guide / note / lab / checkpoint），**找出"读得多但没验"和"验得多但没沉淀"的偏斜**。

---

# 四、B 类：机器给候选 + 人拍板

## 4.1 同构候选（`fatbin ≈ ExecutableVariant`）

**这是 P5 最有意思、也最容易做成玄学的一环。**

朴素做法是让 LLM 判断"哪两个概念像"。不行——那是联想不是证据。

**本设计的判据：两个 entity 的「结构位置」相似，而非「名字」或「描述」相似。**

具体信号（可累加，命中越多置信越高）：

| 信号 | 说明 | 为什么是证据 |
|---|---|---|
| ① 同一「贯穿主角」的不同面孔 | 两个 entity 都出现在同一 protagonist 的产物链上 | V2 §1.3：`axpy` 的四副面孔必须给出同一数值 `[2.5,3.5,4.5,7.5]`。**同一份输入同一个答案，这是物理证据不是感觉** |
| ② 在各自 guide 里占据相同的章节位置 | 都在「核心运行框架」小节被定义 | 结构位置相似 → 承担相同角色 |
| ③ 被同一批 entity 共同引用 | 共同邻居重合度高 | 图结构相似 |
| ④ 用户已在 `aliases` 里写过 | — | 最强：这是人给的 |

**产出是「候选表」而不是「同构表」**，每条附上命中了哪些信号、以及原文位置。用户确认后写入 `kb_entity.aliases` 与 `.lattice/isomorphism.yml`。

**★为什么必须人拍板**：同构判断错了，危害是**结构性的**——它会让用户以为两个不同的东西是一回事，
从此在两处都用错的心智模型。这比"少发现一个同构"严重得多。所以：**宁可漏，不可错。**

## 4.2 补课顺序（不是 LLM 排的）

```
排序键（依次比较）：
① 前置未满足优先 ：该 entity 的前置概念（checkpoint 的 prerequisite）尚未 VERIFIED
② askCount 倒序   ：被问得最多的（来自 P3 缺口台账，行为证据）
③ 覆盖级别升序   ：MENTIONED < DEFINED < VERIFIED，弱的先补
④ scope=MUST 优先 ：用户自己标了"必学"的
```

全部来自已有结构化数据，**每条建议都能点开看依据**——与 P4 的 ROUTER 完全同一手法。

---

# 五、C 类：只能人判定的，机器不碰

以下内容**本期明确不生成**：

| 内容 | 为什么不生成 |
|---|---|
| 「这套东西的本质是什么」 | 这是理解的产物，不是数据的产物 |
| 断链的「下游症状」 | 因果知识，机器无从推断 |
| foundations 里每条概念的**解释** | 若让 LLM 写，会得到一篇正确但无洞见的百科 |
| 领域的「投入比例」 | 与 P4 立场一致：这是用户对自己时间的主观分配 |

**机器提供的是骨架与缺口，人填充的是理解。** 这也是为什么 SYNTHESIZER 的产物是**报告 + 草稿骨架**，而不是一篇完整的 foundations 文档。

---

# 六、产物形态

## 6.1 体系报告（只读，不写文件）

```
GET /api/codex/synthesis?repoId=1
{
  "coverage": {
    "denominator": 26, "denominatorSource": "docs/.../foundations-guide.md",
    "mentioned": 21, "defined": 14, "verified": 6,
    "verifiedRate": 0.231,
    "note": "verified 才代表能改能跑；mentioned 只代表这个词出现过，两者不可合并"
  },
  "islands": { "notes": [...], "entities": [...], "guides": [...] },
  "brokenLinks": [...],
  "isomorphismCandidates": [ {"a":"fatbin","b":"ExecutableVariant","signals":["PROTAGONIST","SECTION_ROLE"],"confidence":"MEDIUM"} ],
  "studyOrder": [ {"entity":"transform dialect","why":"askCount=5 且前置 tiling 未 VERIFIED","refs":[...]} ],
  "caveats": ["...若无 foundations 文档，coverage 为 SKIPPED..."]
}
```

## 6.2 foundations 草稿（可选，create-only）

只在用户明确要求时生成，且：
- 走 P4 的 `create-only` 白名单（**绝不覆盖既有 foundations 文档**）
- 内容 = A 类计算结果 + B 类已确认结论的**排版**，每条概念只有"名字 + 定义处链接 + 覆盖级别"
- **不含 LLM 编写的概念解释**——留空位给人写，并在 front-matter 标 `maturity: skeleton`

> `skeleton` 是新增的第三种成熟度（`draft` 之外）：draft 是"内容有了但没核对"，
> skeleton 是"**只有骨架，内容待人填**"。两者混用会让用户以为骨架文档已经有内容了。

---

# 七、配置

```properties
# ==============================
# V4 P5 · 体系化（SYNTHESIZER）
# ------------------------------
# 体系报告是只读计算，默认开启（零 LLM、零副作用）
codex.synthesis.enabled=true

# ★覆盖率分母来源：foundations 文档的路径 glob。
# 找不到匹配文档时 coverage 返回 SKIPPED——绝不用「已抽到的 entity 数」当分母，
# 那会让覆盖率恒为 100%，是自欺。
codex.synthesis.foundations-glob=docs/**/*foundations*.md

# 同构候选的最低命中信号数（低于此不进候选表，宁可漏不可错）
codex.synthesis.isomorphism-min-signals=2

# foundations 骨架生成（写文件，故默认关闭；且只能新建）
codex.synthesis.skeleton.enabled=false
codex.synthesis.skeleton-dir=docs/learning-guides
```

---

# 八、验收清单

## A 类（纯计算，零 LLM）

```
[1]  ★仓库无 foundations 文档时，coverage 返回 SKIPPED 并写明"无法确定分母"
     （绝不用已抽到的 entity 数当分母）
[2]  ★mentioned / defined / verified 三级分开报，不合并成单一百分比
[3]  entity 提及判定用词边界匹配（复用 P3 规则），omp 不命中 compiler
[4]  孤岛 note 与 P2 的 CI 报告结果一致（同一判据，不该有两套口径）
[5]  孤岛 guide 能识别"读完没沉淀"的文档（无 backref 且无 checkpoint）
[6]  断链表与 kb_link.broken 一致
[7]  整个体系报告不产生任何 LLM 调用（日志确认）
```

## B 类（候选 + 人拍板）

```
[8]  ★同构候选每条都附命中信号与原文位置，可点开核对
[9]  命中信号数低于阈值的不进候选表
[10] ★同构候选不自动写入 aliases——必须用户确认
[11] 补课顺序每条附"依据"（askCount / 前置未满足 / 覆盖级别）
[12] 补课顺序不调用 LLM
```

## C 类（不越界）

```
[13] ★报告里不含任何"这个概念是什么"的机器生成解释
[14] 断链的「下游症状」为空或来自 .lattice/chainlink.yml，机器不生成
```

## 骨架生成（若开启）

```
[15] ★骨架产物 maturity: skeleton（与 draft 区分）
[16] ★既有 foundations 文档不可被覆盖（create-only）
[17] 骨架里每条概念只有名字+定义处+覆盖级别，无 LLM 编写的解释
```

**最该先验第 1 条**——它决定覆盖率这个数字有没有意义。若分母错了，后面所有指标都是装饰。

---

# 九、如实说明的限制

1. **entity 表的完整性依赖 foundations 文档的质量**。若用户的 foundations 清单本身不全，覆盖率的分母就不全。软件无法判断"这个领域还有哪些概念没被列出来"——那需要领域知识，不是数据能给的。
2. **同构候选会有误报**。四个信号都是启发式的。所以产物是候选表 + 人拍板，且阈值默认偏严（宁可漏）。
3. **不解决"体系是否正确"**。软件能算出"26 条概念里 6 条验证过"，但不能判断"这 26 条是不是该学的 26 条"。**后者是方法论问题，不是软件问题**——这也是为什么 C 类内容坚决不生成。
4. **`VERIFIED` 依赖 checkpoint 与 entity 的关联**，而当前 checkpoint 没有显式的 `covers: [entity]` 字段。本期需要补一个轻量关联（front-matter 声明或按名匹配），匹配不上时 `VERIFIED` 计数会偏低——**偏低是可接受的（保守），偏高不可接受**。

---

# 十、与既有立场的一致性

| 既有立场 | P5 如何延续 |
|---|---|
| 判断必须可核对（P4 ROUTER 零 LLM） | A/B 类全部零 LLM，每条附依据 |
| SKIPPED 与 OK 严格区分（P2 CI） | 无 foundations 文档 → coverage SKIPPED，不返回假的 100% |
| 不给模型"自我登记"的工具（P1/P3） | 同构不自动写入 aliases，必须人确认 |
| 只准新建，不覆盖既有语料（P4） | 骨架走 create-only |
| 未经核对必须一眼可辨（P4 draft） | 新增 `maturity: skeleton`，与 draft 区分 |
| 宁缺勿错（上下文工程 facts） | 同构阈值偏严；VERIFIED 宁可偏低 |
