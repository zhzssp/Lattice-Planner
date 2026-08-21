# V4 Codex · P2 实施记录（沉淀 + PR + 知识 CI）

> 承接 `知识资产沉淀-P0至P2实施设计.md` 第四章。
> 本期把三条原本靠人自觉的约定变成机器门禁。
>
> **状态：代码已落地，本机无 JDK，未执行编译与测试。**

---

## 0. 这一期在回答什么问题

前两期解决了「知识在哪」（P0 索引）与「怎么证明学会了」（P1 验证）。
P2 解决的是**知识怎么进来，以及进来的东西能不能信**。

用户方法论里有三条硬性约束，此前全部靠自觉：

| 约定 | 原状态 | P2 之后 |
|---|---|---|
| 笔记必须挂回 guide（SKILL.md 第 4 步） | 漏了无人知晓，笔记成孤岛 | CI 报 ERROR；`doc.write` 自动插入 |
| 示例必须入库（IR/代码/对照表不得删成摘要） | prompt 提醒，模型倾向压缩 | **执行层拒绝写入**（`MISSING_EXAMPLES`） |
| 引用必须可达（1263 条相对链接 / 377 带 anchor） | IDE 不报错、渲染不报错，点了才 404 | CI 逐条校验到 anchor 级 |

---

## 1. ★先修一个 P0/P1 遗留的真实缺陷

### 1.1 问题

P0 与 P1 的设计文档都写着这句话：

> 四个既有 mode（`chat/plan/reflect/learn`）的 allowTags **一个字不改** →
> 新工具因不带旧 tag 而天然不可见 → 既有 cassette 不失效

**这个推理是错的。** 工具可见性的 allow 判定是 OR 语义（命中任一 tag 即保留），
而 Codex 工具为了参与统一的读写治理，必须携带 `read` / `write` tag：

| 工具 | tags | 泄漏到 |
|---|---|---|
| `doc.search` / `doc.read` / `repo.list` | `codex, read` | **plan、reflect、learn**（三者都放行 `read`） |
| `repo.sync` | `codex, write` | **plan**（放行 `write`） |

也就是说 **P0 上线起，三个旧模式的工具 schema 就已经被改变了**，
方案 A 的 47 个 cassette（按 `messages_hash` 命中）会静默失效。
而 P0 的测试 `legacyModesExcludeCodexNaturally` 断言的正是「看不到」——
它写对了期望，但依赖的机制不存在，所以这条断言本身会红。

### 1.2 修法

与 CHAT 同一手法：**只加 deny，不动 allow**。

```java
private static final class Tags {
    static final Set<String> CODEX_FAMILY =
            Set.of("codex", "doc", "git", "checkpoint", "lab", "exec");
}

CHAT("chat", Set.of(), Tags.CODEX_FAMILY),
PLAN("plan",    <原 allow 不变>, Tags.CODEX_FAMILY),
REFLECT("reflect", <原 allow 不变>, Tags.plus("write")),
LEARN("learn",     <原 allow 不变>, Tags.plus("write")),
```

没有任何 V3 工具携带 `CODEX_FAMILY` 里的 tag（实测：V3 工具的 tag 只有
`task/goal/planner/note/kb/insight/subagent/read/write/local/mcp`），
因此加 deny 对旧工具列表是**逐字节无影响**的。

两个细节：
- deny **整族**而非逐个列举工具名。将来新增 Codex 工具只要沿用族内 tag 就不会再次泄漏——「忘记同步 deny 列表」是这类治理最典型的失效方式。
- 用嵌套类持有常量：Java 禁止枚举常量的构造参数引用本枚举的静态字段（前向引用）。

### 1.3 测试加强

`CodexModeVisibilityTest.legacyModesExcludeCodexByDeny` 现在同时断言两件事：

```java
assertFalse(view.contains("doc.search"));                    // 结果正确
assertTrue(view.reasonOf("doc.search").contains("deny"));     // ★机制正确
```

第二条是关键：它防止将来有人「顺手」给某个 Codex 工具去掉 `write` tag
时重新出现泄漏——那时第一条断言仍会通过，只有第二条会红。

---

## 2. P2a 知识 CI（九项，全部只读）

`feature/codex/ci/`

### 2.1 检查清单与实现判据

| # | 检查 | 判据 | 严重度 | 前提 |
|---|---|---|---|---|
| 1 | `DEAD_LINK` | `kb_link.broken` + `NO_FILE` | ERROR | — |
| 2 | `DEAD_ANCHOR` | `kb_link.broken` + `NO_ANCHOR`，并给出目标文档中最相近的 3 个 anchor | ERROR | — |
| 3 | **`BACKREF_BIDIRECTIONAL`** | 见 §2.2 | ERROR / WARN | — |
| 4 | `NOTE_EXAMPLES` | 笔记正文须含 fenced block 或 Markdown 表格 | 显式声明 `has_examples: true` → ERROR；否则 WARN | — |
| 5 | `FRONT_MATTER` | schema 校验；缺失聚合成一条 INFO | ERROR / WARN / INFO | — |
| 6 | `SCOPE_DANGLING` | `scope.must/skip` 的实体须已定义 | WARN | 需 `kb_entity` 非空 |
| 7 | `CHECKPOINT_EXECUTABLE` | 验收命令引用的脚本存在 + 被 git 跟踪 | 不存在 ERROR；未跟踪 WARN | 需已解析检验册 |
| 8 | `PROTAGONIST_CONSISTENCY` | 声明 `protagonist: <key>` 的文档须含声明文件里的全部数值 | ERROR | 需 `protagonist.yml` |
| 9 | `ORPHAN_DOC` | 无入链文档 | WARN | — |

### 2.2 双向性检查的强弱两档

这是整套 CI 里最有价值的一项，因为它监测的东西此前完全无人监测。
判据按**声明强度**分档，不假装统一精确：

```
笔记有「> 来源：[...](../learning-guides/x.md) §y」行
  → 必须在 x.md 里找到指回本笔记的链接   → 精确判据，ERROR
笔记无来源行
  ├ 且无任何入链  → 它是孤岛              → ERROR
  └ 有入链但无来源行 → 只能弱校验          → WARN（提示补来源行以获得精确校验）
```

### 2.3 三条贯穿的取舍

**① 宁可 SKIPPED，不要假 OK。**
检查 6/7/8 都有前提。前提不满足时状态是 `SKIPPED` 并写明缺什么，
而不是报 OK。把「没检查」显示成「通过」会让用户以为已经验过——
那比不做这项检查更有害。CI 工具的返回里专门有 `_skippedNotice`
提醒模型不得把「9 项有 7 项 OK」总结成知识库健康。

**② WARN / INFO 绝不阻塞。**
`passed` 只由 ERROR 数决定。61 篇文档一篇都没有 front-matter，
若判 ERROR，启用第一天就是满屏红色——**满屏红色的 CI 等于没有 CI**，
用户随后会永久无视它。同理 61 条「缺元数据」聚合成一条 INFO，
不逐篇列出去冲掉真正的 ERROR。

**③ 全部只读，绝不修改文件。**
理由不是保守而是职责边界：「发现问题」与「修改内容」混在一起时，
用户无法信任报告——他会怀疑报告是为了让修改看起来必要。

### 2.4 可定位性

`kb_link` 没有行号字段，所以死链发现的行号是**现场在源文件里搜 `rawTarget` 得到**的。
只给「有问题」而不给位置和改法，这个工具的价值就仅等于一个 grep。
每条发现都带 `locator`（`文件:行`）与 `hint`（怎么改），模型能直接接着修。

---

## 3. P2b 沉淀（SEDIMENTER）

`feature/codex/sediment/`

### 3.1 ★最重要的一条安全边界：写入路径白名单

```properties
codex.write.allowed-paths=docs/notes/**/*.md
```

**Agent 能在 `notes/` 下新建文件，但对既有文档只能做外科式插入。**

| | 最坏后果 |
|---|---|
| 在 `notes/` 新建笔记 | 多一篇没用的笔记，删掉即可 |
| 改写 guide | 6 篇主干 guide 累计 40 万字符、数月写成，**一次幻觉即不可逆损失** |

所以 guide 只能通过 `doc.insert_backref` 修改，而那条路径**只插入一行**。
`SedimentFormatTest.onlyAppendsOneLine` 直接验证这条结构性保证：

```java
assertEquals(GUIDE, r.newContent().replace("\n\n" + line, ""));
assertEquals(GUIDE.length() + line.length() + 2, r.newContent().length());
```

删掉插入的内容后必须与原文**逐字符相同**。这让「Agent 弄坏我的语料」
在结构上不可能发生，而不是依赖 prompt 里写「请不要改动无关章节」。

### 3.2 ★示例入库门禁：按类别对齐 + 来源取并集

**为什么是执行层强制**：模型天然倾向于把内容压缩得更"整洁"，
而被压掉的恰恰是让人半年后还能重新看懂的那部分。prompt 提醒挡不住这个倾向。

**为什么按类别对齐而非「有就行」**：

```
源有代码块 → 成品必须有代码块
源有对照表 → 成品至少要有表格或代码块之一
```

若只要求「成品含任一示例」，模型可以用一个无关的短代码块蒙过检查，
却把真正的对照表丢掉。按类别对齐让偷懒的成本高于照做的成本。

**为什么原文取两个来源的并集**（这是实现时发现并修正的一个设计缺陷）：

| 只取模型自述的 `sourceExcerpt` | 只取会话里的 assistant 消息 |
|---|---|
| 等于让它自己出考题：删掉原文的代码块就能绕过 | 「最后一条 assistant 消息」不一定是被认可的那条，也可能是「好的，我来记下来」——门禁会因为拿到一段没代码的文本而**静默放宽** |

并集在两个方向上都安全：模型无法通过*添加*内容放宽门禁（只会让要求更严），
也无法通过*删除*绕过（它删不掉会话那一半）。
静默放宽比误拒严重得多——它无声无息。

### 3.3 格式跟随语料，而非跟随 SKILL.md 模板

实测发现两者不一致：

| 来源 | 速记引用写法 |
|---|---|
| `SKILL.md` 模板 | `[notes/x.md](../notes/x.md)`（文字不带 `../`） |
| 仓库里 23 处真实引用 | `[../notes/x.md](../notes/x.md)`（文字与目标一致）**全部如此** |

**选择跟随语料。** 产出物必须与手写内容无法区分——若软件按文档模板生成、
人按习惯手写，同一篇 guide 里就会出现两种写法，
之后任何基于文本匹配的校验（尤其双向性检查）都要同时兼容两种形态。
**语料是事实，文档是意图，冲突时以事实为准。**

同理刻意**不给新笔记加 front-matter**：现存 19 篇一篇都没有，
新产出的若带上，目录里就会分成「有元数据的机器笔记」与「没元数据的手写笔记」两类。
元数据补齐应当是一次统一迁移，而不是从今天起新旧不一致。

### 3.4 其他几个决定

**现场重新解析章节结构，不用 `kb_section` 的偏移量。**
那些偏移是索引时的快照，用户可能在那之后编辑过文件——
**拿过期偏移去写文件会把内容插进句子中间**，而 Markdown 不会因此报错。
多花几毫秒换「写入位置一定正确」。

**anchor 必须来自工具输出，绝不猜。**
anchor 猜错不报错，只是把速记引用插到一个无关章节里，
这种错误要等半年后点开链接才被发现。所以 `doc.write` 的 anchor 参数必填，
且不存在时返回 `availableAnchors` 让模型重选；UI 里做成下拉而非输入框。

**幂等判定用文件名而非完整相对路径。**
同一篇笔记在不同 guide 里的相对路径不同（`../notes/x.md` vs `./x.md`），
只比全路径会漏判成「未引用」而重复插入。

**先全部校验，最后一次性落盘。**
笔记与 guide 是两次写入，若边校验边写，anchor 校验失败时会留下
「笔记写了但引用没插」的半成品——而那正是双向性检查会报 ERROR 的状态。

**写完自动重建索引。** 否则新笔记检索不到，用户会以为沉淀没生效。

---

## 4. P2c 分支与 PR

### 4.1 四条铁律（全部为执行层校验）

| # | 铁律 | 实现 | 为什么 |
|---|---|---|---|
| 1 | 永不向默认分支提交 | `checkBranch` 比对 `defaultBranch` | 默认分支上的提交不经审阅即进入历史 |
| 2 | 脏工作副本一律拒绝，**绝不 stash** | `checkWorkingTree` 比对 `dirtyPaths` 与本次 owned 集合 | stash 会把用户正在编辑的内容挪到他不知道的地方；等他发现文件"变回去了"，第一反应是软件弄丢了他的修改 |
| 3 | `git add` 精确到文件 + **提交前自校验暂存区** | `add(paths)` 后比对 `git diff --cached --name-only` | 精确 add 只保证「我加了什么」，不保证「暂存区里只有这些」。用户在别处已 add 的文件会搭车进 PR |
| 4 | 提交信息标注 Agent 参与 | `Generated-by` / `Lattice-Session` / `Co-authored-by` trailer | 溯源信息只有留在 git 历史里才能随仓库带走——仓库要能脱离本软件独立存在 |

`GitClient` 刻意**不提供 `addAll`**：知识仓库同时是动手实验目录，
本机常有未 ignore 的编译产物、临时脚本、调试用大文件。

### 4.2 新分支从默认分支拉，而非从当前分支

否则第二次沉淀会叠在第一次之上，第一个 PR 被否时第二个连带作废。
每次独立起点才能各自合并或丢弃。

### 4.3 提交是一次独立的、需确认的调用

沉淀流程结束时**不提交**（SKILL.md 第 5 步的明确要求，也是产品上正确的）：
**提交表达的是「我认可这份产出」**。让软件替用户表达认可，等于把审阅这一环取消掉。

### 4.4 PR 是可选能力

`provider=LOCAL` 是一等公民。没有 token 时流程停在本地分支，
UI 提供「查看 diff / 提交 / 丢弃」——审阅这一环并不缺失，只是少了远端协作。
把 GitHub 做成必需会让「离线也能用自己的知识库」这条承诺失效。

**凭证不落库**：`knowledge_repo.token_ref` 存的是**环境变量名**。
明文 token 进数据库后，任何一次备份、日志转储、截图都可能把它带出去，
而 GitHub token 的权限通常远超本软件所需。

**推送成功但 PR 失败时仍返回 ok=true**：内容已安全到远端，
把 API 失败升级成整体失败会让用户以为工作白做了。

### 4.5 diff 截断必须自报

`clipPatch` 在截断处追加显式标记。
审阅者若不知道自己没看全，「看过 diff 才合并」这道人工闸门就是假的。

---

## 5. P1 → P2 的连接：`divergence` 转笔记草稿

P1 采集的「通过但预测错」是本设计里少有的、别的工具拿不到的原料：
`divergence` 记录的是**心智模型被修正的那一瞬间**（「我原以为…实际…」）。
正确结论到处能查，而「我曾经错在哪里」只有自己这一份。

`GET /api/codex/sediment/draft?code=L2-MLIR-04` 生成草稿：

```markdown
## 我原以为
<divergence 原文>

## 这条检验在检验什么
<checks_what>

## 示例
<!-- 把验收时的实际输出 / 关键 diff 粘在这里 -->
```

**刻意只产草稿不直接写入**，且没有对应的 Agent 工具：
由机器代笔总结「我原来错在哪」是荒谬的。

---

## 6. 文件清单

### 新增（16）

```
db/                                      — 本期无新表（★见 §7）

feature/codex/ci/
  CiCheck.java                           严重度 / 检查项 / 发现 / 报告模型
  KnowledgeCiService.java                九项检查实现

feature/codex/sediment/
  NoteTemplate.java                      笔记与速记引用排版（跟随语料）
  DocWriteGuard.java                     ★路径沙箱 + 示例门禁 + 分支/工作副本
  BackrefInserter.java                   ★外科式单行插入（现场解析结构）
  SedimentService.java                   五步工作流编排

feature/codex/git/
  GitHubPrClient.java                    PR 创建（可选，凭证不落库）

feature/codex/service/
  RepoWriteService.java                  四条铁律 + 分支/提交/推送/审阅

feature/codex/tool/
  SedimentTools.java                     doc.write / doc.insert_backref / doc.anchors
  GitTools.java                          repo.branch / diff / commit / open_pr / branches
  CiTools.java                           ci.run_local

feature/codex/controller/
  CodexCurateController.java             CI / 沉淀 / Git / 文档锚点 REST

resources/templates/
  curate.html                            策展页（CI 报告 + 沉淀表单 + diff 审阅）

test/.../unit/
  DocWriteGuardTest.java                 路径沙箱 + 示例门禁 + 分支保护
  SedimentFormatTest.java                排版格式 + 插入幂等 + 不改写既有内容
  KnowledgeCiTest.java                   判据 + 报告语义 + 提交溯源
```

### 改动（8）

| 文件 | 改动 |
|---|---|
| `AgentMode.java` | ★为 plan/reflect/learn 加 `CODEX_FAMILY` deny（修 §1 缺陷）；新增 Tags 嵌套类 |
| `GitClient.java` | 新增 12 个写操作与审阅方法签名 |
| `ProcessGitClient.java` | 实现之；`commit` 走 `-F -` stdin；`user.email` 未配置时给明确提示 |
| `CodexMetrics.java` | 新增 sediment / ci 两个分区 |
| `CodexViewController.java` | 新增 `/codex/curate` 路由 |
| `PromptBuilder.java` | 新增「知识沉淀原则」段 |
| `application.properties` | 新增 `codex.write.*` / `codex.github.*` / `codex.ci.*` |
| `CodexModeVisibilityTest.java` | ★加强旧模式断言到机制层；补 P2 工具 fixture |
| `codex.html` | 加「知识策展」入口 |

### 工具增量（9）

| 工具 | tags | confirm | 可见模式 |
|---|---|---|---|
| `ci.run_local` | `codex, read` | — | study / curate / verify |
| `doc.anchors` | `codex, read` | — | study / curate / verify |
| `doc.write` | `codex, doc, write` | ✓ | **仅 curate** |
| `doc.insert_backref` | `codex, doc, write` | ✓ | **仅 curate** |
| `repo.branch` | `codex, git, write` | ✓ | **仅 curate** |
| `repo.commit` | `codex, git, write` | ✓ | **仅 curate** |
| `repo.open_pr` | `codex, git, write` | ✓ | **仅 curate** |
| `repo.branches` | `codex, read` | — | study / curate / verify |
| `repo.diff` | `codex, read` | — | study / curate / verify |

---

## 7. 本期不新增任何数据库表

CI 报告只存进程内缓存（`lastReports`），不落库。

这不是省事，而是符合 §V2 的架构硬约束：**Git 是权威源，MySQL 是可丢弃的派生索引**。
CI 结果 100% 可由仓库内容重算，落库只会多一张需要维护一致性的表。
代价是重启后看不到上一轮报告——而重跑一轮的成本是几十毫秒读 61 个文件。

> 若将来要做「死链数量随时间下降」的趋势图，那时再加一张纯统计表，
> 且它必须是可删的。

---

## 8. 验收清单（以 `AI-Infra` 为测试集）

前置：`codex.enabled=true`、`codex.write.enabled=true`、已跑 V8/V9 迁移、已同步索引。

| # | 项 | 判定标准 |
|---|---|---|
| 1 | **CI 跑通** | `/codex/curate` 点「跑一轮检查」→ 9 项均有结果，无 `FAILED` |
| 2 | **双向性有真实产出** | `BACKREF_BIDIRECTIONAL` 对 19 篇 notes 逐一给结论；手动删掉 `llvm-learning-guide.md` 里指向 `llvm-phi.md` 的速记行 → 重新同步后该项报 ERROR |
| 3 | **anchor 校验** | 把某 guide 的一个标题改名 → 同步 → `DEAD_ANCHOR` 报出指向旧 anchor 的链接，且 hint 里给出相近 anchor |
| 4 | **SKIPPED 如实** | `SCOPE_DANGLING` 与 `PROTAGONIST_CONSISTENCY` 状态为 SKIPPED 且 `skipReason` 说明缺什么（当前 `kb_entity` 为空、无 `protagonist.yml`） |
| 5 | **不阻塞** | 61 篇缺 front-matter 只产生 **1 条** INFO，且 `passed` 不因它变 false |
| 6 | **端到端沉淀** | 填表沉淀 → 生成 `docs/notes/<x>.md` + guide 中插入一行速记引用 + 自动建 `lattice/sediment-<date>-<slug>` 分支 |
| 7 | **格式一致** | 产出的 note 与 `docs/notes/llvm-phi.md` 结构一致；速记行与语料**逐字符**同形（`[../notes/x.md](../notes/x.md)`） |
| 8 | **★示例强制** | 原文含代码块、正文写成一句话摘要 → 返回 `MISSING_EXAMPLES`，`docs/notes/` 下**无新文件** |
| 9 | **★不改写既有内容** | `git diff` 中 guide 的改动**只有一行新增**，无任何删除行 |
| 10 | **分支隔离** | 在 `main` 上直接调 `doc.write` → `ON_DEFAULT_BRANCH`；`git log main` 无 Agent 提交 |
| 11 | **脏工作区保护** | 手动改一个无关文件不提交 → 沉淀被拒且提示里明确说「不会自动 stash」 |
| 12 | **精确 add** | `repo.commit` 后 `git show --stat` 只含预期的 2 个文件 |
| 13 | **暂存区自校验** | 手动 `git add` 一个无关文件后调 `repo.commit` → `STAGED_FOREIGN`，未提交 |
| 14 | **幂等** | 对同一 guide 同一章节重复插同一笔记 → `ALREADY_PRESENT`，文件未变 |
| 15 | **★旧模式无泄漏** | `/api/agent/tools?mode=plan`（或 explain 端点）中不含任何 `doc.*` / `repo.*` / `ci.*`；`gradlew agentEval` 全绿 |
| 16 | **可重建** | `DELETE FROM kb_*` → rebuild → CI 结果与之前一致（P2 未破坏「可重建」约束） |

第 15 条最该先验：它同时验证 §1 的缺陷已修、以及 47 个 cassette 未失效。

---

## 9. 已知边界与未做的事

| # | 事项 | 说明 |
|---|---|---|
| 1 | **未编译** | 本机无 JDK。仅有 IDE 语言服务 0-error 诊断 + 人工交叉审计，**不等于编译通过** |
| 2 | GitHub Actions 版 CI | 本期只做本地版。Actions 版是同一套检查换触发入口，等本地版判据稳定后再搬 |
| 3 | `SCOPE_DANGLING` 实际会 SKIPPED | 依赖 `kb_entity`，那是 P4 蒸馏阶段的产物 |
| 4 | `PROTAGONIST_CONSISTENCY` 实际会 SKIPPED | 目标仓库无 `protagonist.yml`。检查已完整实现，放着等声明文件出现 |
| 5 | 会话原文依赖进程内记忆 | `ConversationMemory` 是内存滑动窗口（30 条），重启即失。跨重启沉淀只能靠 `sourceExcerpt` |
| 6 | 未做 PR 状态回读 | 开了 PR 之后是否被合并，软件不知道。要做需要轮询或 webhook |
| 7 | 沉淀失败后的半成品 | 写盘阶段失败（磁盘满等）时不做自动回滚——回滚文件写入可能覆盖用户在别处的改动。改动都在工作分支上，可整分支丢弃 |
| 8 | 合并动作不由软件执行 | 本地仓库的合并留给用户自己做。软件只提供 diff 与丢弃 |

---

## 10. 需要你做的

```
1. 有 JDK 的环境跑：gradlew test 与 gradlew agentEval
   ★ 重点看 CodexModeVisibilityTest 与 47 个 cassette 用例
2. 配置：codex.write.enabled=true（CI 只读，不开写也能用）
3. 访问 /codex/curate → 先只点「跑一轮检查」，验收 1~5 条
4. 再试沉淀，验收 6~14 条
```

第 3 步与第 4 步刻意分开：CI 只读、零风险，可以先单独确认判据是否符合你的判断；
确认之后再开写入。**先建立对报告的信任，再给它修改的权限。**
