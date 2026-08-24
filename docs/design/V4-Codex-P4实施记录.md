# V4 Codex P4 实施记录：蒸馏 + 定线

> 上游：`知识资产沉淀-Git仓库形态产品方案V2.md` §十三 P4
> 前序：P0 索引 / P1 验证闭环 / P2 沉淀+PR+CI / P3 缺口三源合流
> 状态：已实现，**本机无 JDK 未编译**（IDE 语言服务 0 error + 人工审计）

---

# 〇、这一期在回答什么

P0~P3 处理的都是**已有内容**：索引它、验证它、沉淀它、找出它缺什么。
P4 第一次处理**还不存在的内容**：

```
①源 paper/*.pdf ──蒸馏(DISTILLER)──> ②蒸 docs/paper-notes/*.md
                                          │
                                          ├─出题(EXAMINER)──> ⑤验 docs/checkpoints/*.md
                                          │
                                     定线(ROUTER) ──> 「我现在该干什么」
```

这一期最容易做错的地方不是功能，而是**边界**：它是第一个会往用户仓库里
**新建文件**的能力（P2 只往 `docs/notes/` 写，且写的是用户已认可的内容）。
所以本期新增的最重要的东西不是任何一个功能，而是 `create-only` 白名单语义。

---

# 一、★create-only：本期唯一的新安全语义

## 1.1 问题

P4 必须能写 `docs/paper-notes/` 与 `docs/checkpoints/`。
但这两个目录里已有用户手写的高质量内容——**9 册检验、86 条题目**。

若简单把它们加进 `codex.write.allowed-paths`，P2 建立的那条结构性保证
**当场作废**：`doc.write` 的 `REPLACE` 模式立刻就能整体改写它们。

## 1.2 处置：第二类白名单，语义是「路径允许写，但仅当目标文件不存在」

```properties
codex.write.allowed-paths=docs/notes/**/*.md                    # 可 CREATE/APPEND/REPLACE
codex.write.create-only-paths=docs/paper-notes/**/*.md,docs/checkpoints/**/*.md  # 只能 CREATE
```

于是：新建一篇蒸馏草稿可行，改写一册既有检验**在结构上不可能**。

## 1.3 ★为什么把「路径合法性」与「覆盖权」拆成两个方法

```java
guard.checkPath(repo, path)                      // 路径本身合不合法
guard.checkCreatable(repo, path, overwrite)      // 这次写入允不允许落在这个文件上
```

合成一个方法会有一个具体的坏结果：`RepoWriteService.commit` 只需要前者
（提交时文件当然已经存在），若 `checkPath` 把「已存在」也判成不合法，
**提交流程会拒绝自己刚写好的文件**。

`DocWriteGuardTest.pathCheckStaysPermissiveForCommit` 守这一条。

## 1.4 ★放宽白名单时我漏掉的一处，以及它给出的通用教训

`create-only` 让 `checkPath` 开始放行 `docs/paper-notes/**`。
而 **P2 的 `SedimentService` 只调了 `checkPath`**，随后用 `TRUNCATE_EXISTING` 写盘。

也就是说：一次 `doc.write(path=docs/paper-notes/已有文件.md, mode=REPLACE)`
就能覆盖用户手写的论文精读——**这正是 create-only 要防的事，
却会从一条更早存在的路径漏过去。**

已在 `SedimentService` 补上 `checkCreatable`。教训是通用的：

> 放宽任何一处白名单，必须回头把**所有只做过路径校验的写入路径**都补上覆盖权校验。
> 新加的路径会记得校验，旧路径不会自己变严。

## 1.5 刻意不把 `docs/learning-guides/` 放进 create-only

蒸馏官方文档时放那里更自然，但没有加。理由：

- 白名单**能不加就不加**。多一个目录，就多一批需要逐个复核的写入路径；
- 加了它，P2 那句「既有 guide 一律不可写」就不再是**逐字成立**的断言了
  （`DocWriteGuardTest.guidesStillFullyForbidden` 仍在守这句话的原文）；
- 代价极小：产物先落 `paper-notes`，要挪去 `learning-guides` 在 IDE 里移动一下——
  **那本来就该是用户的决定**。

---

# 二、★蒸馏：一个必须绕开的现成通路

## 2.1 `local.read_document` 看起来正好能用，但不能用

MCP 已有 `local.read_document`，支持 pdf/docx/xlsx，白名单也现成。
但它对长文会先经 `DocumentSummarizer` 做 map-reduce **摘要**再返回。

一篇论文提取后常有 4~6 万字符，摘要后只剩两三千字。

**基于摘要蒸馏 = 蒸馏二手信息**：算法伪码、张量维度、消融对照表
会在摘要那一步全部消失，而那恰恰是「核心运行框架」与「必学特性表」的唯一原料。

更糟的是这种损失**无声无息**——产出的 guide 结构完整、读起来专业，
只是里面没有一处能对着原文核对的具体内容。

所以 `SourceReader` 直接用 `DocumentExtractorRegistry.extract()` 拿 `plainText()`，
**绝不让原文在进模型之前被压缩过一次**。

## 2.2 提取质量必须先判定，而且要拒绝

扫描件 PDF 提取出来是空的或几十个乱码字符。判据用**每页字符数**（可机器判定）：

| 判据 | 处置 |
|---|---|
| 提取不到任何文本 | 拒绝，提示需先 OCR（本软件不做 OCR） |
| 总字符 < 2000 | 拒绝 |
| 平均每页 < 250 字符 | 拒绝，判定为图片型 PDF |

拒绝而不是「尽力而为」，理由与上一条相同：残缺文本产出的是一篇**看起来专业的空壳**，
而用户没有办法从产物本身看出它是空壳。

## 2.3 按章节分段而非定长硬切

论文的 Method 与 Experiments 要分别喂进不同的抽取任务
（前者产运行框架，后者产必学特性表的依据）。定长硬切会把一节劈成两半，
让模型在两次调用里**各看到半个算法**，于是两次都只能写出模糊的概括。

清洗只做两件确定安全的事：去页码、接断行连字符。
**刻意不重排双栏、不猜表格边界**——猜错会把两栏文字交错成语义混乱的句子，
比保留原始换行糟糕得多，而且模型无法从结果里看出发生了什么。

## 2.4 ★为什么用分隔符协议而不要模型输出 JSON

草稿正文里必然含代码块、反斜杠、大量换行。让模型把这些塞进 JSON 字符串，
转义出错的概率远高于漏写一个分隔符；而失败代价的量级完全不同：

| 载体 | 一处出错的后果 |
|---|---|
| JSON | **整篇作废** |
| 分隔符 | 只丢一节，其余照常可用 |

`ExamRoundTripTest.partialFailureIsLocal` 直接验证这一点。

---

# 三、★结构门禁：判据是「下游机器真能用」

「这篇 guide 写得好不好」无法机器判定，任何试图判定它的规则最后都会变成
一堆可以凑数满足的字数与关键词要求。所以换一个可判定的问法：
**这份产物能不能被系统里已有的机制吃下去？**

## 3.1 七项判据

| # | 判据 | 严重度 | 为什么是这个级别 |
|---|---|---|---|
| ① | 四个小节都在 | ERROR | 缺「掌握标准」就接不上出题，这篇 guide 后续断链 |
| ② | **止损线能被 `ScopeListParser` 抽出术语** | ERROR | 见 3.2 |
| ③ | 止损线条目有具体名字（反引号/粗体） | ERROR | 见 3.3 |
| ④ | 框架一节有代码块或 ≥4 个具体标识符 | ERROR | 挡「写空」，见 3.4 |
| ⑤ | 必学特性表是真表格 | WARN | 偶尔用列表也说得通 |
| ⑥ | 掌握标准 ≥3 条 | WARN | 太少会退化成「理解本文内容」 |
| ⑦ | `maturity: draft` + 渲染后可见的警示 | ERROR | 见 3.5 |

## 3.2 ★止损线判据不是「有没有那个小节」，而是「解析器真能抽出术语」

一篇有小节但抽不出术语的 guide，在 P3 的 skip 召回机制里是**静默失效**的：
文件看起来合规，而它的止损线永远不会触发提醒，**从文件本身完全看不出这一点**。

用真实解析器做判据，把「看起来合规」与「实际可用」之间那条缝焊死了。

## 3.3 ★同一个解析器，对既有语料与对新产出用两套严格度

这是实现测试时发现的一个更难看见的缝。

`ScopeListParser` 刻意宽容——它要能从用户几年写下的各种写法里抽出东西，
所以「实现细节」这种领头短语它也会收。**对解析既有语料这是对的。**

但模型会非常乐意写「实现细节」「其余部分」。那种条目**能**被抽成术语，
于是 `SKIP_UNPARSEABLE` 不会报，而它永远匹配不上任何一次真实提问——
skip 召回照样是死的，只不过表面上有术语了。

所以增加 `SKIP_TOO_VAGUE`：要求至少一条止损线用反引号或粗体标出具体名字
（与 `ScopeListParser` 的高置信来源规则对齐，而不是另定一套「看起来具体」的标准）。

> 结论可复用：**宽容的解析器不能直接当严格的门禁用。**
> 前者服务于「尽量识别既有内容」，后者服务于「拒绝不合格的新内容」，方向相反。

## 3.4 蒸馏的失败模式是「写空」而不是「写错」

```
「该模块负责处理相关的核心逻辑，并在必要时与其他组件协同工作，
  从而实现整体上的性能优化目标。其内部流程经过精心设计。」
```

这段话读起来完全通顺，却**既无法验证也无法反驳**。
判据用「代码块 or ≥4 个行内标识符」——具体符号是可核对性的最低证据。

## 3.5 ★为什么蒸馏产物写 front-matter，而 P2 的笔记刻意不写

看似不一致，实则是相反的两个诉求：

| | 内容状态 | 要求 | 做法 |
|---|---|---|---|
| P2 沉淀笔记 | 用户**已认可过** | 与手写笔记**无法区分** | 不写 front-matter（现存 19 篇都没有） |
| P4 蒸馏草稿 | **没有任何人看过** | **一眼可辨** | `maturity: draft` + `distilled_by` |

后者的理由是具体的：未核对的草稿一旦看起来与手写 guide 无异，
半年后会被当作可信来源引用，而里面可能有一处模型编的维度——
**这是最坏的失效，因为错误已经通过引用扩散出去了。**

而且 front-matter 在多数 Markdown 渲染器里**不显示**，所以正文顶部还要放一个
渲染后可见的警示块。只靠 front-matter 标注等于没标注（`NO_VISIBLE_BANNER` 守这条）。

## 3.6 待核对清单里固定三条

与具体论文无关，是 LLM 从 PDF 蒸馏时最常出错的三处：

1. **公式与图表**——PDF 提取必然丢公式排版与全部图；
2. **接口名与维度**——这类错误读起来完全通顺，最难发现；
3. **止损线是否合理**——跳错了几周后会变成挡路的盲区。

---

# 四、★出题：守住那个「无法造假」的指标

## 4.1 LLM 出题最典型的失效

不是题目不好，而是**编出一个不存在的脚本**（`bash scripts/all.sh`，而这个 lab
里根本没有 scripts 目录）。这种题从文件上看**完全合规**：有验收命令、
有通过标准、判据是退出码 0。

危害是精确的：跑起来必然失败，而失败原因是「文件不存在」而非「知识没掌握」，
于是它污染 **checkpoint 通过率**——本产品唯一号称无法造假的指标。

> **一个被污染的、无法造假的指标，比没有这个指标更糟，因为用户仍会相信它。**

## 4.2 三道防线

**① 没有 lab 就直接不给出题**

`labDir` 是必填，且必须 `isDirectory` 验过。没有动手项目时，
模型只能凭想象编一个命令。这不是"降级出题"，是**拒绝**。

**② 把 lab 的真实文件清单塞进 prompt**

只写「不要编脚本名」而不给清单，等于要求模型凭空猜对——
它会照写一个最常见的名字，然后被门禁丢掉。给清单才让这条要求可能被满足。

**③ 验收命令引用的路径必须在磁盘上真实存在，否则整条题丢弃**

处置是**丢弃**而不是降级或标注：一道跑不起来的题没有中间形态可留。
丢弃数单独计量（`examDiscardedBadPath`），
**若长期高于产出数，说明出题这件事在当前语料上还不成立，该停用而不是继续调 prompt。**

路径判定刻意只管「看起来像仓库内相对路径」的 token，绝对路径 / URL /
变量展开 / glob / 命令选项一律跳过——在这里从严会造成**用户看不出原因的误杀**。

## 4.3 ★先渲染成 Markdown，再用既有解析器验

最终落库走的是「文件 → `CheckpointParser` → 数据库」这条路。
**只有在这条真实路径上验过，「校验通过」才等于「落库后可运行」。**

若在 LLM 输出层校验，中间还隔着模板渲染与解析两道转换，
任何一处形状不匹配都会让校验结论失真——而那正是最难发现的一类问题：

> 模板写 `**验收命令:**` 而解析器认 `**验收命令**：`，结果**不是报错**，
> 而是那一节被静默忽略——题目落库了、但没有判据，于是永远无法被运行，
> 而从数据库里看它是一条完全正常的 checkpoint。

`ExamRoundTripTest.RoundTrip` 这一组的全部意义就是守住这条缝。

## 4.4 ★新增 `VerifySource.AGENT_DRAFT`

| 枚举值 | 题目来自 | 判据来自 |
|---|---|---|
| `DECLARED` | 人 | front-matter 显式声明（精确） |
| `PARSED` | **人** | 机器从自然语言推断（较弱） |
| `AGENT_DRAFT` ★ | **机器** | 机器（**完全未经人验证**） |

为什么必须与 `PARSED` 分开：`PARSED` 的题是**人写的**，题目本身承载了人的判断；
`AGENT_DRAFT` 从题目到判据全是机器产生的。

不区分的后果很具体：「12 条检验通过 9 条」是本产品的核心证据，
若其中 5 条是机器自己出题自己判过，**这个数字就失去了意义**。

`RouteService.summarize()` 里 `passRateHumanAuthored` 与机器题分开算，永不合并。

**标记跟着内容走**：`authored_by: lattice-agent`（册头）与
「本条由 Lattice Agent 起草」（条目内）两处都认。
用户把机器出的题剪贴进自己手写的册子时，front-matter 就不在了，
只有条目内那行标记跟着走（`markTravelsWithItem` 守这条）。

---

# 五、★定线：一次 LLM 都不调

## 5.1 为什么没有按设计文档做成 `ROUTER` 子代理

用户的 README §0 优先级表里写着「P0 投入 60% / P1 30% / P2 10%」——
那是**他对自己时间的主观分配**，机器没有任何依据去修改它。
让 LLM 生成一份"建议的阶段表"只会产出一份看起来合理、但用户无法核对的东西，
而**无法核对的建议最终只会被忽略**。

软件真正能提供的增量在别处：周次表已经写好了，但每天要人工去对照
「我在哪一周、这周该验哪几条」。这件事是**纯计算**：
checkpoint 状态、缺口 askCount、草稿核对状态全在库里。

> **纯计算的结论可以逐条核对**——每条建议都附上它依据的那条记录，
> 用户点进去就能验证软件有没有算错。**可核对比更聪明重要。**

## 5.2 排序依据不是「价值高低」，而是「不做的后果会不会扩散」

| 权重 | 建议 | 为什么在这个位置 |
|---|---|---|
| 100 | 核对蒸馏草稿 | ★**唯一一项拖着会变坏的**：它会被检索命中、被引用、被出题，一处编的参数会顺引用扩散 |
| 90 | 跑已填预测的检验 | 预测已冻结，此刻信息量最大；拖久了人会忘记当时怎么想的，「预测错」这个信号就废了 |
| 85 | 沉淀「通过但预测错」 | 心智模型被修正的那一瞬间只有当时记得下来 |
| 70 | 补失败的检验 | 附原文的盲点映射 |
| 60 | 补缺口（最多 3 条） | 复用 P3 台账，取最该先补的，避免淹掉列表 |
| 50 | 给无检验的 guide 建检验 | 「读完了但没验」是自我高估最常发生的地方 |
| 40 | 填预测 | 顺带说明门禁理由 |

其余每一项拖延只是拖延，**只有第一项会污染**。

## 5.3 `caveats` 不是免责声明，是真话

```
本页全部结论来自库里已有记录的确定性计算，不调用 LLM，也不做推测。
检验表为空：可能确实还没有检验，也可能只是还没同步。两种情况这里无法区分，所以不做判断。
N 篇文档没有配套 lab。这些主题无法出可执行的题——不是软件不肯出，是没有 lab 时命令只能是编的。
```

若索引没同步，这里会漏掉刚写的文档。不说清楚的话，
用户会把「软件说我没事可做」理解成"我确实没事可做"。

## 5.4 阶段表：lab 匹配不上就显示空，不填猜测值

`guide ↔ lab` 优先取检验册里已解析的 `lab` 字段，其次按命名约定
（`xxx-learning-guide.md ↔ xxx-lab/`）匹配，**且目录必须 `isDirectory` 验过**。

匹配不上就显示空。阶段表里一个错误的 lab 路径会让人照着它 `cd` 进不存在的目录，
然后怀疑是不是自己环境坏了。

---

# 六、工具与可见性

## 6.1 新增 6 个工具

| 工具 | tags | 落盘 | 说明 |
|---|---|---|---|
| `distill.draft` | codex, doc, read | ✗ | 起草，`requiresConfirm`（成本） |
| `distill.write` | codex, doc, write | ✓ | 只新建，不 commit |
| `exam.draft` | codex, doc, read | ✗ | 起草题目 |
| `exam.write` | codex, doc, write | ✓ | 落盘 + 载入检验表 |
| `route.next` | codex, read | ✗ | 「我现在该干什么」 |
| `route.stages` | codex, read | ✗ | 读做验阶段表 |

## 6.2 起草与落盘是两个工具，不提供「一步到位」

合并会省一次往返，但那样**用户在看到产物之前文件就已经写进仓库了**。
蒸馏产物质量取决于 PDF 排版与论文写法，波动很大。
这与 P2 的沉淀刻意不提交是同一条原则：
**凡是质量不稳定的产出，人要在落地之前有一次看的机会。**

`distill.write` 刻意**不接受直接传内容**：否则「必须有止损线」这条约束
只要换个入口就能绕过。且落盘前会**重新过一遍门禁**（草稿可能是上次会话的）。

## 6.3 ★STUDY 与 VERIFY 补 deny `doc`

`distill.draft` / `exam.draft` 确实**不写任何文件**，所以带 `read` tag，
于是天然命中 STUDY 的 allow。但一次起草是 5~9 次 LLM 调用——
在一个「纯研读」的模式里放一个随口一问就会花钱的工具，与这个模式的承诺不符。

处置是给 STUDY / VERIFY deny `doc` tag，而**不是**给这两个工具去掉 `read`：
后者是用 tag 调可见性，而 tag 表达的是工具属于哪个能力域。
**一旦开始用 tag 当旋钮，可见性规则就再也读不懂了**（这条立场与 P3 的
`gap.to_learning_plan` 一致）。

`doc` tag 只挂在会产出文件的那批工具上，检索类的 `doc.search` / `doc.read` /
`doc.outline` / `doc.anchors` 不带它，所以这条 deny 不影响研读本身
（`seesRouteButNotDistill` 同时断言了这两面）。

## 6.4 刻意没做的三件事

| 没做 | 理由 |
|---|---|
| 新增 `DISTILLER` / `EXAMINER` 子代理角色 | 子代理能自由调 `doc.write`，那是一条**能达到同样效果但不过门禁的路径**。多这样一条路径，等于没有门禁 |
| 定线结果一键转任务 | P3 已有 `gap.to_learning_plan`。**两个待办列表里必然有一个被遗忘**，被遗忘的那个会让人不再相信任何一个 |
| 草稿落库 | 草稿是一次会话内的中间物。保存草稿等于鼓励「以后再核对」，而「以后」通常不会到来 |

---

# 七、本期零 DDL、零新表

| 产物 | 存在哪 | 可重建 |
|---|---|---|
| Guide 草稿 | `docs/paper-notes/*.md`（含 front-matter 溯源） | Git 权威源 |
| 检验题 | `docs/checkpoints/*.md` → 既有 parser → `kb_checkpoint` | 可重建 |
| 定线结果 | 不落库（纯计算） | 每次重算 |
| `VerifySource.AGENT_DRAFT` | 既有列（`EnumType.STRING`, length 16） | 无需 DDL |

符合 V2 硬约束：**Git 是权威源，MySQL 是可丢弃的派生索引。**

溯源写进 front-matter 而不是新建一张表，是为了让仓库能脱离本软件独立存在——
这条溯源信息只有留在文件里才带得走。

---

# 八、与 P3 自动衔接的一个闭环

蒸馏产物写入 → `repo.sync` 建索引 → **P3 的 `ScopeListParser` 自动抽出它的
「先跳过」清单 → 进 `kb_entity` → skip 召回自动生效**。

也就是说 P4 的产物**直接喂给 P3 的机制**，不需要任何额外接线。
这也是 P4 门禁选「止损线必须可解析」作为 ERROR 判据的另一个理由：
它是这个闭环的入口，断在这里整条链就是死的。

`distill.write` 的返回里显式回报 `skipTermsRegistered`，
并提醒用户核对——**这些术语将来会替他做「该不该提醒」的判断。**

---

# 九、文件清单

## 新增（11）

```
feature/codex/distill/SourceReader.java          原文读取 + 提取质量门禁 + 章节分段
feature/codex/distill/GuideTemplate.java         草稿骨架（四小节 + 双重草稿标记）
feature/codex/distill/DistillGuard.java          ★结构门禁（7 项）
feature/codex/distill/DistillService.java        蒸馏编排（map-reduce + 分隔符协议）
feature/codex/distill/CheckpointTemplate.java    检验册排版（与既有 parser 逐字对齐）
feature/codex/distill/ExamService.java           出题编排 + ★路径存在性门禁
feature/codex/route/RouteService.java            ★定线（零 LLM）
feature/codex/tool/DistillTools.java             distill.* / exam.*
feature/codex/tool/RouteTools.java               route.*
feature/codex/controller/CodexDistillController.java
resources/templates/distill.html
```

## 改动（10）

```
sediment/DocWriteGuard.java       ★create-only 语义 + checkSize(content, max) + maxGuideChars
sediment/SedimentService.java     ★补 checkCreatable（放宽白名单漏掉的路径）
entity/KbCheckpoint.java          ★VerifySource 增 AGENT_DRAFT
verify/CheckpointParser.java      识别机器出题标记，Parsed 增 verifySource
verify/CheckpointService.java     不再无条件写 PARSED
service/CodexMetrics.java         distill / exam 指标（含分原因拒绝计数）
agent/runtime/AgentMode.java      ★STUDY / VERIFY 补 deny doc；修 javadoc 死链
agent/runtime/PromptBuilder.java  蒸馏与定线原则
controller/CodexViewController.java  /codex/distill 路由
resources/templates/codex.html    导航
application.properties            P4 配置段
```

## 测试（新增 2 组 + 扩充 2 组）

```
DistillGuardTest.java        新增，17 用例：止损线可解析性 / 空话检测 / 写空检测 / 草稿标记
ExamRoundTripTest.java       新增，16 用例：★模板↔解析器往返 / ★路径门禁 / 分隔符解析
DocWriteGuardTest.java       扩充，+8 用例：create-only 七条 + guide 独立体积上限
CodexModeVisibilityTest.java 扩充，+2 用例：P4 工具归属 / ★STUDY 看得到定线但看不到蒸馏
```

---

# 十、验收清单（16 条）

## 只读、零风险（先做这些）

```
[1]  访问 /codex/distill，三个开关状态正确回显，且说明了为什么默认关
[2]  「我现在该干什么」能算出结果，每条都有「依据」，且依据可点开核对
[3]  caveats 里如实写出「只依据库里已有记录」与「N 篇没有 lab」
[4]  阶段表里 labExists=false 的行确实没有对应目录（抽查 2 个）
[5]  ★人写题与机器题的通过率分开显示，没有合并成一个数字
[6]  原料清单能列出 paper/*.pdf；未启用 MCP 时给出 localHint
```

## 起草（花钱但不落盘）

```
[7]  开 codex.distill.enabled，对一篇真实论文 PDF 起草
     ★检查 charsPerPage：正常论文应 >1500；若被判 SOURCE_LIKELY_SCANNED 属正确拒绝
[8]  ★产物里的「核心运行框架」有真实标识符与代码块，不是「负责处理相关逻辑」
[9]  ★「可以先跳过」抽出的术语是具体名字，不是「实现细节」这类空话
[10] 产物顶部有渲染后可见的草稿警示，front-matter 是 maturity: draft
[11] 开 codex.exam.enabled，对一篇有 lab 的 guide 出 3 题
     ★重点看 discarded：被丢弃的原因应当是「引用了不存在的路径」
```

## 落盘（★最该谨慎的一步）

```
[12] ★把 path 改成一个【已存在】的 paper-note，必须被 FILE_EXISTS_PROTECTED 拒绝
[13] ★用 doc.write 试写 docs/paper-notes/【已存在文件】.md（mode=REPLACE），
     必须被拒——这是 1.4 节那个漏洞的回归验证
[14] 落盘后改动停在 lattice/distill-* 分支的【未提交】状态
[15] exam.write 后在检验面板能看到新题，且标注「判据未经人工验证」
[16] ★repo.sync 后 /codex/gaps 的止损线清单里出现了草稿贡献的新术语（P3 闭环）
```

**最该先验的是 [13]**——它验证的是我在实现过程中自己引入又发现的那个漏洞。
其次是 [9]，它决定 P3 的 skip 召回对新产出是活的还是死的。

---

# 十一、如实说明的限制

1. **未编译**。本机无 JDK，只有 IDE 语言服务 0 error + 人工审计。
2. **蒸馏质量取决于 LLM 与 PDF 排版**。门禁只能挡住「结构不合格」与「写空」，
   挡不住「写得通顺但内容错」。这就是为什么产物一律 `draft` 且必须人工核对——
   **门禁的作用是保证产物值得被核对，不是保证产物正确。**
3. **公式与图必然丢失**。PDF 文本提取拿不到公式排版，图一张也拿不到。
   已写进固定核对项，但这是原理性限制，不是可以优化掉的缺陷。
4. **机器出的题判据强度最低**。即使路径都存在，`expect` 也只有退出码。
   `AGENT_DRAFT` 就是为了让这一点在统计口径上永远可见。
5. **一次起草 5~9 次 LLM 调用**。成本可感知，所以两个开关默认关、
   工具带 `requiresConfirm`、`max-chunks` 有上限。

---

# 十二、下一步

按 V2 路线图，P5 是「体系化」（`SYNTHESIZER`：foundations / 同构表 / 断链表 +
entity 覆盖率 + 知识地图）——那是「什么是知识体系」这个问题的落点，
也是整个方案里最难自动化的一环。

但我再说第三次：**P0~P4 五期累计约 110 个新文件，一行未编译。**
P4 改动了 `DocWriteGuard`、`CheckpointParser`、`AgentMode` 三个被多处依赖的地方，
其中 `AgentMode` 的改动会影响工具 schema 字节，也就会影响 47 个 cassette。
先把 `gradlew test` 与 `gradlew agentEval` 跑通，比再加一期更有价值。
