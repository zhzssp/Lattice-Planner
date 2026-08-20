# Lattice Codex · Git-Native 知识仓库产品方案（V2）

> 本文取代 `知识资产沉淀-产品方案.md` 的**存储层与采纳流程**设计，保留其闭环与度量框架。
> 差异见 §6「对 V1 的修订」。
>
> 核心转向两条：
> 1. **Git 仓库是唯一权威源，MySQL 降级为可丢弃的派生索引**
> 2. **产品不再是"通用知识库"，而是「你自创的学习/工作方法论」的专用执行环境**

---

# 一、先把你的方法论形式化

我读完 `AI-Infra` 仓库（README 总规划 / docs/README 阶段枢纽 / learning-guides / paper-notes / notes / checkpoints / 六个 lab / AGENTS.md / SKILL.md）后的判断：**你已经跑通了一条完整的知识生产流水线，只是它现在靠人工纪律 + Cursor 约定维持，没有工具强制。** 产品要做的事就是把这条流水线固化。

## 1.1 七个环节（我按你仓库的实际结构提炼）

```
①源 Source          paper/*.pdf、官方文档、源码
   ↓ AI 蒸馏
②蒸 Distill         learning-guides/（机制向教材）+ paper-notes/（论文精读）
                     产物特征：核心运行框架图 + 必学特性表 + 掌握标准 + 【先跳过】
   ↓ 编排
③线 Route           README §0 优先级表（P0/P1/P2 + 投入比例）
                     docs/README 阶段表（读什么→做什么→验什么）+ W0~W8 周次表
   ↓ 落地
④做 Practice        六个 lab，与 guide 一一配对；共用三级主角（axpy/tiny_mlp/FFNBlock）
   ↓ 判定
⑤验 Verify          checkpoints/ L0复现→L1改一处→L2加组件→L3打通
                     硬约束：机器可判定的通过标准 + 先预测再动手
   ↓ 对话补齐
⑥沉 Sediment        提问 → 认可的回答 → notes/ 短笔记 → 回挂「速记」引用到 guide
   ↓ 收束
⑦收 Converge        §6 六个研究问题：不求实现，求「形成自己的观点」
```

## 1.2 六条硬约束（这才是方法论的精髓，也是产品必须强制的东西）

| # | 约束 | 你仓库里的原话 | 解决什么问题 |
|---|---|---|---|
| 1 | **止损线** | 每节都有「必须掌握」与「**先跳过（遇到再学）**」 | 无限深潜——自学第一大死因 |
| 2 | **主干可扫读** | 「深挖细节进 notes/，再用引用挂回去」「主文档保持可扫读」 | 文档膨胀 vs 细节丢失的两难 |
| 3 | **示例不可砍**（硬性） | 「砍的是空话与重复，**不是砍示例**」 | 摘要化导致知识失去可操作性 |
| 4 | **机器可判定** | 「不接受『我觉得懂了』」 | **假通过**——口述能过、动手过不了 |
| 5 | **先预测再动手** | 「改完再解释，人会自动为既成结果编理由」 | 事后合理化，掩盖错误心智模型 |
| 6 | **同构换名** | fatbin ≈ ExecutableVariant；「能举至少三组同构换名例子」 | 知识碎片化，学了 N 个工具却串不起来 |

**约束 4 和 5 是市面上任何知识管理软件都没有的。** Notion/Obsidian 做存储与链接，Anki 做记忆，都不做「证明你能改能跑」。这是产品真正的护城河所在——见 §8.5。

## 1.3 三个独特技巧（值得升为软件的一等公民）

- **贯穿主角**：`axpy`（算子级）→ `tiny_mlp`（图级）→ `FFNBlock`（规模级），六个 lab 共用，且四副面孔必须给出同一个数值 `[2.5,3.5,4.5,7.5]`。**这是知识串联的物理载体**——不是靠"我觉得它们有关"，是靠同一份权重同一个答案。
- **断链表**：「某一环断了下游有什么症状、能不能补救」。这是把知识组织成**因果链**而非目录树。
- **两套顺序共存**：学习顺序（由易到难）与链路顺序（按流水线站点）刻意不同，"各自解决『怎么学得进去』和『怎么串成一条线』"。

---

# 二、为什么它和大厂知识沉淀是同构的

你说「工作的过程和学习的过程本来就有很多共通之处」——这不只是感觉，是**结构同构**。所以同一套软件能服务两个场景，不需要做两个产品：

| 你的学习法 | 大厂知识资产沉淀 | 软件里的统一抽象 |
|---|---|---|
| `paper/*.pdf`、官方文档 | 需求文档、设计评审、源码、故障工单、监控数据 | **Source（原始来源）** |
| `learning-guides/`（蒸馏教材） | 系统机制文档、架构说明、模块 wiki | **Guide（蒸馏资产）** |
| `paper-notes/`（论文精读） | 技术选型调研、竞品分析 | **Guide** 的一种 |
| README 优先级表 + 阶段表 | 新人 onboarding 路径、能力地图 | **Roadmap（路线）** |
| 「必学 / 先跳过」 | onboarding 范围界定「这个先不用管」 | **ScopeDecision（止损线）** |
| 六个 lab | 沙箱环境、复现 case、演练环境 | **Lab（动手载体）** |
| `checkpoints/` L0~L3 | 上手任务、转正考核、值班准入 | **Checkpoint（可执行检验）** |
| `notes/` + 回挂引用 | 群里问答沉淀、FAQ、踩坑短记 | **Note（对话沉淀）** |
| 三级贯穿主角 | 一条典型请求链路贯穿全系统 | **Protagonist（贯穿主角）** |
| 同构换名对照 | 「我们的 X 就是他们的 Y」 | **Isomorphism（同构映射）** |
| 断链表 | 故障传播链、上下游影响面 | **ChainLink（因果链）** |
| §6 六个研究问题 | 待攻克技术难题、技术债台账 | **OpenQuestion（开放问题）** |

**结论：不做两个模式，做一套抽象 + 两套模板包（`LEARNING` / `WORK`）。** 差别只在 Source 类型、Checkpoint 的 verify 手段、Roadmap 的时间粒度。

---

# 三、架构转向：Git 是权威源，MySQL 是派生索引

## 3.1 这个决策为什么正确

你的设想（内容进 Git，用户管理信息进 MySQL）我完全同意，而且要把它变成一条**强约束**：

> **约束：删掉整个 MySQL 的 kb_* 数据，从 Git 仓库全量重建后，系统功能完全恢复。**

这条约束的价值不在容灾，在于它**强迫仓库保持自解释**：

| 收益 | 说明 |
|---|---|
| **可退出性（最重要）** | 用户随时能扔掉 Lattice，仓库照样用 Cursor/Obsidian/VSCode 打开。**你现在就是这么用的**，产品不能剥夺这一点 |
| 天然版本控制 | V1 设计的 `asset_revision` 表**直接废弃**，git log 就是版本史 |
| 天然协作 | PR / Review / Issue / CODEOWNERS 全都白拿 |
| 天然 CI | Actions 做知识质量门禁（§5.2），这是大厂文档门禁的现成实现 |
| 腐化检测有真实数据 | `git log -1 --format=%cd <file>` 就是"多久没动"；`git blame` 就是"这句话谁写的" |
| 用户可用熟悉的工具编辑 | 不必做富文本编辑器（V1 风险 #3 自动解除） |

## 3.2 权威源 / 派生索引的划分标准

这是本方案最需要说清的一条线，含糊会导致"两边都存、两边不一致"：

```
进 Git 仓库（权威）              进 MySQL（派生 / 运营）
─────────────────────           ─────────────────────
判据：低频写 · 有语义 ·           判据：高频写 · 机器埋点 ·
     用户想看见 · 换机器要带走        可从仓库+行为日志重算

· 所有 Markdown 正文             · 全文/向量索引（kb_chunk）
· front-matter 元数据            · 章节/链接图（可重建）
· 路线表、阶段表                  · Gap 的 askCount、语义聚类
· checkpoint 定义 + 通过状态 ★    · checkpoint 每次运行的日志与耗时
· 掌握度等级（低频，语义强）       · 复习调度中间态（ease factor 迭代）
· ScopeDecision（必学/跳过）      · degraded 率、复用次数等指标时序
· 同构映射、断链表                · Agent 会话轨迹与 trace
                                 · GitHub token、同步游标、仓库注册信息
```

★ **checkpoint 通过状态刻意进仓库**：学习进度是知识资产的一部分，换机器必须带走，且你自己会想 review「哪几条我预测错了」。但"第 3 次运行耗时 47 秒"这类留 DB。

## 3.3 三条数据通路

```
┌──────────────┐   git pull    ┌───────────────┐  parse+embed  ┌──────────┐
│ GitHub Remote │ ────────────▶ │ 本地工作副本    │ ───────────▶ │  MySQL   │
│              │ ◀──────────── │ (working copy) │              │  索引    │
└──────────────┘  branch+PR    └───────────────┘              └──────────┘
                                   ▲       ▲                        │
                    用户用 IDE 直接改 │       │ Agent 写入（走分支）      │ 检索
                    （必须支持！）    │       │                        ▼
                                   └───────┴──────────────────  Agent / UI
```

**三个必须处理的现实问题**：

1. **外部修改**：用户会绕过软件直接改文件（你现在就是）。→ 索引器必须能感知：`git status` + blob hash 比对，检测到变更即增量重索引。**不能假设软件是唯一写入方。**
2. **增量索引**：按 **blob hash** 判定是否重算 embedding。文件没变则复用，这是省 embedding 成本的关键（你仓库 49 个 md，全量重算一次不便宜）。
3. **冲突**：Agent 只在独立分支写，永不直接写 `main`。用户本地未提交的改动 → 阻止 Agent 提交并提示，不做自动 stash（数据安全优先）。

---

# 四、仓库规范（产品的契约）

## 4.1 目录布局（兼容你现有仓库，不要求迁移）

```
<repo>/
├── .lattice/
│   ├── repo.yml            # 仓库声明：kind、域树、模板包、CI 规则
│   ├── protagonist.yml     # 贯穿主角定义 + 数值锚点
│   ├── isomorphism.yml     # 同构映射表
│   └── templates/          # 各 kind 的模板（可覆盖内置）
├── AGENTS.md               # ★ 已有，直接沿用（见 4.4）
├── README.md               # kind: roadmap（总规划）
├── docs/
│   ├── README.md           # kind: roadmap（阶段枢纽）
│   ├── learning-guides/    # kind: guide
│   ├── paper-notes/        # kind: guide, subkind: paper-note
│   ├── notes/              # kind: note
│   └── checkpoints/        # kind: checkpoint-set
├── paper/                  # kind: source
└── <lab>/                  # kind: lab（含 README + scripts/）
```

**关键设计：路径 → kind 的映射写在 `repo.yml`，而非硬编码。** 你的仓库当前布局零改动即可被识别；工作仓库可以完全不同的布局。

## 4.2 front-matter Schema

Guide：

```yaml
---
kind: guide
id: mlir-learning-guide
title: MLIR 学习文档：Conversion / Interface / Linalg
domain: ai-infra/middle-end/mlir
priority: P0
maturity: stable                    # seed|draft|reviewed|stable|deprecated
source:                             # 溯源（抗幻觉的根）
  - {kind: paper, ref: "paper/MLIR-....pdf", locator: "§3"}
  - {kind: url,   ref: "https://mlir.llvm.org/docs/"}
entities: [dialect-conversion, op-interface, linalg-bufferize]
scope:                              # ★ 止损线，一等公民
  must: [dialect-conversion, op-interface, linalg-bufferize]
  skip: [pdl-pdll, transform-dialect, python-bindings]
protagonist: tiny_mlp
labs: [mlir-toy-dialect]
checkpoints: [L2-MLIR-04, L2-MLIR-07]
review_due: 2026-11-01
---
```

Note（对话沉淀，**直接对应你的 SKILL.md**）：

```yaml
---
kind: note
id: llvm-phi
title: LLVM IR 中的 Phi
backref:                            # ★ CI 会校验双向性
  - {doc: docs/learning-guides/llvm-learning-guide.md, anchor: "2.3"}
entities: [ssa, phi-node]
session: agent-sess-8f3a            # 由哪次对话沉淀而来（可追溯）
approved_at: 2026-08-20
has_examples: true                  # ★ CI 校验：为 true 则正文必须含代码块
---
```

Checkpoint（**本方案最重要的创新，见 §8.5**）：

```yaml
---
kind: checkpoint
id: L2-MLIR-04
level: L2                           # L0复现|L1改一处|L2加组件|L3打通
title: 新增 toy.sub：ODS → fold → 两种 pattern → lit 测试
entity: dialect-conversion
lab: mlir-toy-dialect
resource: local+toolchain           # local|local+toolchain|gpu1|gpuN|multinode
est_hours: 2
predict_required: true              # ★ 未填预测则拒绝运行验收
verify:
  cmd: "bash scripts/all.sh"
  cwd: "mlir-toy-dialect"
  timeout: 600
  expect:
    - {kind: exit_code, value: 0}
    - {kind: stdout_contains, value: "low.sub"}
    - {kind: stdout_not_contains, value: "toy.sub"}
fallback:                           # 无资源时的降级判据
  when: no_gpu
  cmd: "iree-compile --compile-to=hal ... | grep hal.executable.variant"
status: todo                        # todo|predicted|passed|failed|degraded
prediction: ""                      # 用户填，填完才解锁 verify
---
```

## 4.3 为什么 front-matter 而不是数据库表

三个理由：① 用户在 IDE 里能直接看见并修改；② diff 友好，PR review 时元数据变更一目了然；③ 仓库脱离软件仍自解释。代价是解析成本与 schema 演进要做版本字段（`schema: 1`）。

## 4.4 承接而非夺取：复用 `AGENTS.md` 与 `.cursor/skills/`

你仓库已有 `AGENTS.md` + `.cursor/skills/ai-infra-notes/SKILL.md`，这是**仓库自带的 Agent 行为约定**。

**Lattice 应当读取并注入 prompt，而不是要求你改成 Lattice 格式。** 理由：你的约定是给 Cursor 写的，但内容是通用的（"写笔记必须两步：写 notes + 加引用"、"示例不得删成摘要"）。软件承接它，意味着：
- 你在 Cursor 和 Lattice 里得到一致行为
- 不产生"两份约定要同步维护"的负担
- 用户没有迁移成本

进一步：**把 SKILL.md 里的人工约定升级为 CI 强制**（§5.2）。你现在的第 4 步"加引用"全靠 Agent 自觉，漏了没人知道；变成 CI 检查后，漏了 PR 就红。

---

# 五、GitHub 深度集成

## 5.1 原语映射（这张表是本方案的地基）

| GitHub 原语 | 知识运营用途 | 替代了 V1 的什么 |
|---|---|---|
| Repo | 一个知识仓库（可多个：学习仓 + 工作仓） | — |
| Branch | Agent 草稿隔离（`lattice/harvest-<id>`） | `harvest_candidate` 表 |
| **PR** | **资产采纳评审**——Agent 起草开 PR，用户 merge 即采纳 | **收割收集箱**（大幅简化） |
| PR Review Comment | 逐段批注、"这段没溯源，补上" | — |
| **Issue** | **Gap 知识缺口台账**，label 标域/优先级 | `knowledge_gap` 表（部分） |
| Milestone | 学习阶段（W1~W8）/ 迭代周期 | — |
| **Actions** | **知识 CI 门禁**（见 5.2） | 全新，无对应 |
| Pages | 一键把知识体系发布为静态站 | — |
| commit history | 腐化检测数据源 | `staleness_score` 的计算依据 |
| `git blame` | 溯源"这句话哪次会话产生的" | `asset_provenance` 的补充 |
| CODEOWNERS | 域 owner | `asset.owner` 字段 |
| Releases | 知识体系版本快照（"AI-Infra 入门版 v1.0"） | — |
| Discussions | 开放问题（§6 研究问题）的观点演进 | `OpenQuestion` |

**PR 替代收集箱是本次最大的简化**：白拿 diff review、评论、历史、可回滚、可在手机 GitHub App 上审。V1 那套"卡片流 UI + 采纳/丢弃按钮"降级为「PR 列表的定制视图」，工作量减少一半以上。

## 5.2 知识 CI（Actions）——大厂文档门禁的个人版

这是我认为**最能立刻改善你现有仓库质量**的一环。校验项：

| 检查 | 规则 | 为什么必要（针对你仓库的真实风险） |
|---|---|---|
| **死链** | 所有相对链接与 anchor 必须存在 | 你仓库有**数百条**相对链接与 `#anchor`，改个标题就断，且断了没人知道 |
| **引用双向性** ★ | `note.backref` 声明的位置，必须真存在指向该 note 的「速记」链接 | SKILL.md 第 4 步的强制化。漏挂 = notes 变孤岛 |
| **示例入库** ★ | `has_examples: true` 的 note 正文必须含 fenced code block | SKILL.md「示例必须入库（硬性）」的机器化 |
| front-matter | 必填字段、枚举合法性、schema 版本 | 防手写元数据漂移 |
| **scope 悬空** | `scope.must/skip` 引用的 entity 必须在 entity 表中已定义 | 与你项目里踩过的「tag 悬空引用」同一类问题 |
| **checkpoint 可执行** | `verify.cmd` 引用的脚本文件必须存在 | 定义了检验但脚本被删/改名 = 假检验 |
| **主角一致性** | 声明 `protagonist` 的文档，示例数值须与 `protagonist.yml` 锚点一致 | 防止 `tiny_mlp` 在某篇里写错成别的数 |
| 孤岛 | 无入链的文档（notes 尤其） | 沉淀了但检索不到 = 没沉淀 |
| 覆盖率报告 | 域 × entity 的资产覆盖，输出到 PR comment | 让"体系完整度"每次 PR 都可见 |

CI 全部只读 + 只报告，**不自动改文件**。

## 5.3 认证与同步

- GitHub OAuth App（用户授权）或 PAT（自托管更简单）。token 加密存 MySQL，**这正是你说的"需要管理的用户相关信息"**。
- 同步策略：手动触发 + 定时 pull（复用现有 `SessionArchiveScheduler` 那套调度）。
- 支持 `provider = LOCAL`：**纯本地 git 仓库，不连 GitHub 也能全功能运行**（你的 `AI-Infra` 现在就可以直接接入）。这是可降级原则的体现。

---

# 六、对 V1 方案的修订

| V1 设计 | V2 处置 | 原因 |
|---|---|---|
| `knowledge_asset.content` 存正文 | **废弃**。正文在 Git，DB 只存 `path + blob_hash` | 权威源转移 |
| `asset_revision` 表 | **完全废弃** | git log 即版本史 |
| `harvest_candidate` 表 + 收集箱 UI | **替换为分支 + PR**，UI 降级为 PR 定制视图 | GitHub 原语更强且免费 |
| 「不做富文本编辑器」是妥协 | **升级为正确决策** | 用户本来就用 IDE 编辑 |
| `maturity` 五态 | 保留，写进 front-matter | 仍需要，且 diff 可见 |
| `Provenance` 表 | 移入 front-matter `source` 字段 | 与正文同生共死，不会漂移 |
| Gap 存 DB | **分裂**：语义定义 → GitHub Issue；`askCount`/聚类 → DB | Issue 可讨论可 label，askCount 是高频埋点 |
| 四个闭环 | **加第五个：验证闭环**（§8.5） | 这是你方法论的独有部分，V1 完全没覆盖 |
| 通用知识库定位 | **收窄为方法论专用环境** | 通用打不赢 Notion；专用没有对手 |

---

# 七、方法论一等公民（实体模型）

```
Repo ──┬── Roadmap        路线：优先级表 + 阶段表（读→做→验）+ 周次
       ├── Domain         域树（可来自 repo.yml 或目录结构）
       ├── Entity         知识点/概念（26 条 foundations 概念就是这个）
       │     ├── ScopeDecision   MUST / SKIP_FOR_NOW / DROPPED + 理由 ★
       │     ├── Isomorphism     同构映射（A ≈ B，附说明）★
       │     └── Mastery         掌握度 + 复习调度
       ├── Source         论文 PDF / 官方文档 / 源码 / 工单
       ├── Guide          蒸馏教材（含 paper-note）
       ├── Note           对话沉淀短记 + backref ★
       ├── Lab            动手项目（scripts + 产物约定）
       ├── Checkpoint     可执行检验（L0~L3 + verify + prediction）★
       ├── Protagonist    贯穿主角 + 数值锚点 ★
       ├── ChainLink      因果链 / 断链表（某环断了下游什么症状）★
       ├── OpenQuestion   开放问题 + 已有工作参照 + 我的观点 ★
       └── Gap            知识缺口（CRAG degraded 信号 + skip 召回）
```

★ = 你方法论独有、市面软件没有的。**这七个是产品差异化的全部来源**，其余（Domain/Guide/Note/Source）任何知识库都有。

---

# 八、五个闭环

前四个沿用 V1（Harvest / Gap / Curate / Reuse），此处只写**在 Git 语境下的变化**，重点写新增的第五个。

## 8.1 Harvest 收割：执行 → PR

```
触发：TaskCompleted / 会话归档 / 用户点「沉淀这段对话」/ Lab 跑出新产物
  ↓ 规则预筛（跨天任务 / 曾搁置 / 工具失败后成功 / 关联笔记≥2）
HARVESTER 子代理：判 kind + 套模板 + 填 front-matter source + 写正文
  ↓
git checkout -b lattice/harvest-<id> → 写文件 → commit → 开 PR
  ↓
Actions 跑知识 CI → 结果贴在 PR
  ↓
用户 review：merge（采纳）/ 评论要求改 / close（丢弃，记负反馈）
```

**红线不变且更硬**：Agent 永不向 `main` 直接 push；`maturity: reviewed` 及以上只能由人在 PR 中设定（CI 可校验：PR 作者是 agent 且 maturity ≥ reviewed → 拒绝）。这次是**执行层强制**，不是提示层约定。

## 8.2 Gap 缺口：三个信号源合流

V1 只有 CRAG 信号，V2 增加两个更精准的：

| 信号 | 含义 | 独有价值 |
|---|---|---|
| CRAG `degraded/INCORRECT` | 问了但库里没有 | 已有，免费 |
| **`scope.skip` 被反复问到** ★ | 当初判定"先跳过"的东西，现在**遇到了** | **直接实现你「遇到再学」的召回机制**——止损线不再是单向的 |
| Checkpoint 失败 / 预测错 ★ | 以为懂了但没懂 | **最高质量的缺口信号**，因为有机器判据 |

`scope.skip` 召回是我最想强调的一环：你每篇 guide 都写了「先跳过（遇到再学）」，但**"遇到"这件事目前无人监测**。软件监测到某个 skipped entity 被问到 3 次 → 提示「`transform-dialect` 你标了先跳过，本周已被问到 3 次，建议补上」→ 一键转学习任务。

缺口 → GitHub Issue（可讨论、可 label 域/优先级）→ `gap.to_learning_plan` 复用现有 `PlannerAgentService` 生成目标+任务 → 学完产出 Guide/Note → PR merge 时自动 close Issue（`Closes #123`，Git 原生联动）。

## 8.3 Curate 策展：Git 让腐化检测变准

| 检测 | Git 前（V1 猜） | Git 后（V2 算） |
|---|---|---|
| 腐化 | `review_due` 到期（纯时间） | + `git log` 末次修改 + **溯源文件已变更**（source 的 blob hash 变了 → 依赖它的 guide 可能过期）★ |
| 重复 | 向量相似度 | 同上 + 跨文件片段重复检测 |
| 孤岛 | 无入链 | 同上 + CI 直接报 |
| 死链 | 无法检测 | CI 精确检测 |

★ **溯源级腐化**是 Git 带来的新能力：官方文档更新了、论文出了 v2、lab 脚本改了 → 依赖它的 guide 自动标「可能过期」。这在 DB 方案里做不到。

## 8.4 Reuse 复用：优先自有资产

沿用 V1。补一条 Git 特有的：**Lab 产物即资产**。`iree-lab/out/PHASES.md`、`tvm-fatbin-lab/out/ANALYSIS.md` 这些是跑出来的真实证据，索引进检索后，Agent 回答"IREE 的 flow 层做了什么"可以直接引用**你自己机器上跑出来的产物**，而非通用知识。这个体验差异极大。

## 8.5 ★ Verify 验证闭环（全新，产品的护城河）

这是 V1 完全缺失、也是市面软件全都没有的一环。

```
Guide 学完
   ↓
EXAMINER 子代理：从 guide 的 entities + scope.must 生成 checkpoint 条目
                （L0 复现 / L1 改一处 / L2 加组件 / L3 打通）
                每条必须给出：机器可判定的 expect + 常见失败→盲点映射
   ↓
【先预测】用户在 UI 填 prediction；未填则 verify 按钮锁定 ★
   ↓
【跑验收】Agent 通过受限执行通道跑 verify.cmd（白名单 + 确认 + 超时）
   ↓
【自动判定】按 expect 断言 → passed / failed
   ↓
分流：
  passed  且 预测正确 → Mastery +1
  passed  但 预测错误 → ★★ 最有价值的信号：结果对但因果理解错
                        → 自动生成 Gap + 建议沉淀一篇 note 记录"我原以为…实际…"
  failed              → 按「常见失败 → 盲点」表定位到 entity → 回指 guide 章节
   ↓
状态写回 checkpoint front-matter → commit（进度进仓库，换机器带走）
```

**三个设计要点**：

1. **prediction 门禁是硬的**：不填预测不给跑。你自己写的"预测错不扣分，预测错但没发现自己错了才是问题"——软件必须把这条纪律**变成锁**，靠自觉是守不住的。

2. **"通过但预测错"是最高价值信号**。没有任何软件在采集这个。它精确定位了「结果正确但心智模型错误」，这是所有假通过里最危险的一类。

3. **执行安全（必须严格）**：
   - 只允许执行**仓库内**脚本，路径白名单，禁止任意 shell
   - `requiresConfirm=true`，首次执行必须人工确认
   - 超时 + 输出截断 + 不给网络（可选）
   - 复用现有 MCP 通道扩展，而非新开一条路

工作场景的映射：checkpoint 的 verify 从"跑 lab 脚本"换成"跑单测 / 起服务打一个请求 / 在预发环境复现一次故障"。**判据形式完全一致**。

---

# 九、Agent 侧扩展

## 9.1 子代理（`SubAgentRole` 加枚举）

| 角色 | 职责 | 产出 | 关键约束 |
|---|---|---|---|
| `DISTILLER` 蒸馏 | Source → Guide 草稿 | 框架图 + 必学表 + **必须产出 scope.skip** | 不给出止损线的草稿直接判不合格 |
| `ROUTER` 定线 | 多 Guide → Roadmap 阶段表 | 优先级 + 投入比例 + 读做验三列 | 必须给"应急最短路径" |
| `SEDIMENTER` 沉淀 | 认可的问答 → Note + backref | **直接实现你的 SKILL.md** | 示例不得删成摘要（CI 兜底） |
| `EXAMINER` 检验 | Guide → Checkpoint 条目 | L0~L3 + verify + 失败→盲点映射 | expect 必须机器可判定 |
| `SYNTHESIZER` 综述 | 多 Guide → foundations / 同构表 / 断链表 | 横切概念文档 | **"仓库→体系"的关键动作** |
| `CURATOR` 策展 | 巡检腐化/重复/孤岛/死链 | PR 提议 | 只提议，不自动改 |
| `HARVESTER` 收割 | 工作轨迹 → 资产草稿 | PR | 见 8.1 |

`SYNTHESIZER` 值得多说一句：你的 `ai-compiler-foundations-learning-guide.md`（26 条横切概念 + §2.4 与周次同步的路线表）是整个仓库最难自动化、也最能体现"体系"的产物。它做的事是**跨 guide 抽取公共词汇 + 排出补课顺序**。这是 P4 的核心，做成了就真正回答了"什么是知识体系"。

## 9.2 新增 AgentMode

| Mode | allow | deny | 用途 |
|---|---|---|---|
| `STUDY` | `kb,guide,note,checkpoint,read,subagent,mcp` | `write,task,goal` | 纯学习问答，禁一切写 |
| `CURATE` | `asset,domain,repo,kb,read,write,subagent` | `task,goal` | 整理知识时不动任务 |
| `VERIFY` | `checkpoint,lab,exec,read` | `write` | 跑验收，唯一开放受限执行的模式 ★ |

★ 把"能执行命令"收窄到单一 mode，是权限治理的正确做法——正好用上你方案 K 的 deny 语义与执行层强制。

## 9.3 工具族

```
repo.*        list / sync / status / branch / commit / open_pr / pr_status / diff
doc.*         read / write / move / front_matter_get / front_matter_set
roadmap.*     get / stage_current / next_action        ← "我现在该干什么"
entity.*      list / define / scope_set / isomorphism_add / coverage
note.*        sediment(问答→note+backref)              ← SKILL.md 的工具化
checkpoint.*  list / next / predict / run ★ / grade / stats
lab.*         list / run_script ★ / read_artifact
gap.*         list / upsert / to_learning_plan / close
ci.*          run_local / report                       ← 本地跑一遍 CI，不必等 Actions
```

★ = 受限执行，仅 `VERIFY` mode 可见 + `requiresConfirm`。

## 9.4 复用现有能力清单

| 现有 | 新用途 |
|---|---|
| `note_embedding.source` | 加 `GIT_DOC`，检索管道零改动 |
| Hybrid 检索 + CRAG | 仓库文档检索；degraded → Gap Issue |
| MarkdownRenderer + `[[双链]]` | 渲染仓库 md；相对链接需增加解析（小改） |
| MCP 文档直读（Pdf/Word/Excel） | **直接吃 `paper/*.pdf`** 做蒸馏原料 |
| 方案 K 可见性分层 | `VERIFY` mode 隔离执行权限 |
| 方案 L TurnStopping | 轮次收尾问"要不要沉淀"/"下一个 checkpoint 是 X" |
| 方案 A 评测体系 | 新增蒸馏/沉淀/出题轨迹用例，防 prompt 退化 |
| 事件总线 | Harvest 触发，core 零改动 |
| `PlannerAgentService` | Gap → 学习目标/任务 |
| insight 得分 | 扩展为"知识体系健康分" |

---

# 十、数据模型（MySQL 部分，全部可重建除标注外）

```
── 权威（不可重建，用户管理信息）──────────────────
user / user_preference                            已有
knowledge_repo      id, user_id, name, kind(LEARNING|WORK), provider(GITHUB|GITEE|LOCAL),
                    remote_url, local_path, default_branch, token_ref(加密),
                    last_synced_sha, sync_status, template_pack
repo_member         （前瞻：多人共享，个人版单行）
agent_exec_grant    受限执行授权记录（谁在何时批准了哪条 cmd）★安全审计

── 派生索引（可从仓库全量重建）──────────────────
kb_document         repo_id, path, kind, subkind, title, front_matter_json,
                    blob_hash, git_updated_at, git_last_author, indexed_at
kb_section          document_id, anchor, heading, level, ord, char_range
kb_link             src_document_id, src_section_id, target_path, target_anchor,
                    kind(REF|BACKREF|CITATION|LAB|SOURCE), broken(bool)
kb_chunk            document_id, section_id, chunk_idx, content, embedding, blob_hash
kb_entity           repo_id, name, aliases, priority, defined_in_document_id
kb_entity_ref       entity_id, document_id, section_id
kb_scope_decision   entity_id, decision(MUST|SKIP|DROPPED), reason, decided_in_document_id
kb_isomorphism      entity_a_id, entity_b_id, note
kb_protagonist      repo_id, name, level, anchor_value, defined_in
kb_chain_link       from_entity_id, to_entity_id, break_symptom, recoverable
kb_checkpoint       repo_id, document_id, code, level, entity_id, lab, resource_tag,
                    est_hours, verify_json, status, prediction, predicted_at, passed_at
kb_open_question    repo_id, title, document_id, my_stance, referenced_works_json

── 运营（行为数据，非派生）──────────────────
kb_gap              repo_id, question, norm_question, source(CRAG|SKIP_RECALL|CP_FAIL|CP_MISPREDICT),
                    entity_id, ask_count, first_at, last_at, status,
                    github_issue_number, closed_by_document_id
kb_mastery          entity_id, level, ease_factor, last_examined_at, next_review_at
kb_checkpoint_run   checkpoint_id, started_at, duration_ms, exit_code, stdout_excerpt, passed
kb_metric_daily     repo_id, date, coverage, staleness, orphan_rate, degraded_rate, reuse_count
```

**表数量看着多，但绝大多数是"解析结果落地"，无业务逻辑。** 真正需要写业务代码的是 `knowledge_repo`（同步）、`kb_gap`（聚类）、`kb_checkpoint`（验证闭环）三块。

---

# 十一、界面

| 页面 | 内容 | 优先级 |
|---|---|---|
| **仓库首页 · 体系仪表盘** | 域树 + 覆盖率 + 腐化 + 孤岛 + 死链 + 当前阶段 + 开放缺口 | P0 |
| **"我现在该干什么"** ★ | 读 Roadmap 当前阶段 → 今天：读 X §2 / 跑 lab Y / 验 L2-Z-04 | P0 |
| 文档阅读器 | Markdown + front-matter 面板 + 溯源 + 反链 + 覆盖的 entity | P0 |
| **Checkpoint 面板** ★ | 条目列表 + 预测输入框（未填则 run 锁定）+ 运行输出 + 判定 + 盲点回指 | P1 |
| PR 视图（采纳） | Agent 草稿 PR 列表 + diff + CI 结果 + 一键 merge | P1 |
| 缺口看板 | 三类信号合流，按 askCount 排序，一键转学习计划 | P2 |
| Entity 视图 | 概念 + 定义处 + 引用处 + scope 决策 + 同构 + 掌握度 | P3 |
| 健康周报 | CURATOR 巡检结果 + 待处理 PR 提议 | P3 |
| 知识地图 | 域树 + entity 关系图 + 断链表 | P4 |

★ 两个是最能体现"专用软件"的页面。「我现在该干什么」尤其重要——你的 Roadmap 已经有周次表了，但每天要人工去对照；软件应该直接答出来。

**编辑一律跳转 IDE / GitHub Web**，不自研编辑器。

---

# 十二、度量

**北极星：知识复用率**（沿用 V1）+ **新增第二指标：验证通过率与预测准确率** ★

后者是这个产品独有的、且无法造假的指标：

| 指标 | 定义 | 为什么无法造假 |
|---|---|---|
| **Checkpoint 通过率** | passed / 已尝试 | 机器判定，不能自评 |
| **预测准确率** ★ | 预测与实际一致 / 已验证 | 预测先于结果提交，不可事后修改 |
| L2 达成率 | 每工具至少 2 条 L2 通过的工具占比 | 对应你的"入门线" |
| `degraded` 率趋势 | 应单调下降 | 体系真在长 |
| skip 召回命中数 | 被"遇到"的 skipped 概念数 | 止损线是否设对了 |

后三个能回答一个别的软件回答不了的问题：**"你说你学会了 MLIR，证据是什么？"** → 12 条检验里通过 9 条，其中 3 条 L2；预测准确率 67%（错的 4 条都沉淀了 note）。

---

# 十三、分期

| 期 | 主题 | 交付 | 验收（用你的 AI-Infra 仓库直接验） |
|---|---|---|---|
| **P0** | **Git 接入 + 索引** | `knowledge_repo` + clone/pull + front-matter 解析器 + 增量索引（blob hash）+ 接 `note_embedding` + 仪表盘 + 文档阅读器 | **零改动接入 `AI-Infra`**，49 篇 md 全部可检索，域树正确，死链列出来 |
| **P1** | **验证闭环** ★ | Checkpoint 解析 + 预测门禁 + 受限执行 + 自动判定 + 状态写回 commit | 跑通 `L0-MLIR-01`：填预测 → 执行 `all.sh` → 机器判定 → 状态进仓库 |
| **P2** | **沉淀 + PR** | `SEDIMENTER`（实现 SKILL.md）+ 分支/PR + 知识 CI（死链/双向/示例/schema） | 一次对话 → 自动生成 note + 回挂引用 → 开 PR → CI 绿 → merge |
| **P3** | **缺口三源合流** | CRAG + skip 召回 + checkpoint 失败/误判 → Gap → Issue → 学习计划 | `transform-dialect` 被问 3 次后自动提示补课 |
| **P4** | **蒸馏 + 定线** | `DISTILLER`（PDF→Guide，复用 MCP 直读）+ `ROUTER`（→阶段表）+ `EXAMINER`（→checkpoint） | 投一篇新论文 PDF，产出 guide 草稿 + 3 条 checkpoint |
| **P5** | **体系化** | `SYNTHESIZER`（foundations/同构/断链）+ entity 覆盖率 + 知识地图 | 对 `ai-infra` 域输出覆盖率与横切概念文档 |
| **P6** | **策展 + 掌握度** | `CURATOR` 巡检 + 溯源级腐化 + 周报 + SM-2 复习 | 官方文档变更 → 依赖它的 guide 标"可能过期" |
| **P7** | 工作模板包 | `WORK` 模板包 + 工单/设计文档 Source 类型 + 演练型 checkpoint | 一个工作仓库跑通同样闭环 |

**建议起步：P0 → P1 → P2。**

理由：
- **P0 有现成的真实验收物**（你的仓库），不需要造数据，一天内就能看出方案对不对
- **P1 是护城河**，且它让软件立刻产生"别处得不到"的价值——你现在的 checkpoints 全靠自己手动跑手动记
- **P2 让 Agent 真正接管你已经在做的事**（写 note + 挂引用），是感知最强的自动化

P4（蒸馏）看起来最诱人，但**故意放后面**：起草质量需要大量真实样本调 prompt，而 P0~P2 会先把你已有的 49 篇高质量文档变成可用的 few-shot 素材。先有标准，再自动化。

---

# 十四、风险

| # | 风险 | 后果 | 对策 |
|---|---|---|---|
| 1 | **受限执行被滥用/写坏仓库** | 数据损坏，最严重 | 仓库内脚本白名单 + `requiresConfirm` + 单一 `VERIFY` mode 可见 + 超时 + 只读挂载可选 + 执行审计表 |
| 2 | Agent 直推 `main` | 污染权威源 | 执行层禁止；分支命名前缀强制；CI 校验 PR 作者与 maturity 的组合 |
| 3 | **索引与仓库不一致** | 检索出旧内容，信任崩塌 | blob hash 全量比对 + "重建索引"一键按钮 + 仪表盘显示 `last_synced_sha` 与漂移告警 |
| 4 | 用户本地未提交改动 | Agent 提交时冲突/覆盖 | 提交前 `git status` 检查，脏则拒绝并提示，**不自动 stash** |
| 5 | front-matter 侵入性 | 用户觉得被"污染" | 全部字段可选；缺失只降级不报错；提供 `--no-frontmatter` 模式（元数据存 `.lattice/index/`） |
| 6 | **过度自动化毁掉方法论** | 你的方法论精髓是"人的判断"（认可才沉淀、先预测、自己定止损线），全自动会掏空它 | **明确划线**：Agent 做体力活（检索/起草/跑验收/查死链），人做判断活（认可/预测/裁剪范围/形成观点）。产品文案与交互都要守住 |
| 7 | 大 PDF 蒸馏成本 | token 消耗 | 分章蒸馏 + 复用方案 F 的成本预算 + blob hash 去重 |
| 8 | GitHub 依赖 | 断网/被墙不可用 | `provider=LOCAL` 全功能；GitHub 只增强不必需 |

**风险 6 是最需要警惕的**，也是我对整个方案唯一的担忧。你这套方法论之所以有效，恰恰因为它强迫人做判断（"形成自己的观点"、"预测错但没发现才是问题"、"必学 vs 先跳过"）。如果软件把这些都自动化了，就退化成又一个 AI 摘要工具。**产品的定位应该是「纪律的执行器」而不是「思考的替代者」**——它的价值是让你**没法偷懒**（不填预测跑不了验收、漏挂引用 PR 就红），而不是替你想。

---

# 十五、决策表

| 期 | 内容 | 成本 | 价值 | 选择 |
|---|---|---|---|---|
| **P0** | Git 接入 + front-matter 解析 + 增量索引 + 仪表盘 | 中高 | ★★★★★（一切前提） | ☐ |
| **P1** | 验证闭环（预测门禁 + 受限执行 + 自动判定） | 中 | ★★★★★（**护城河**） | ☐ |
| **P2** | 沉淀 Agent + PR 采纳 + 知识 CI | 中 | ★★★★★（感知最强） | ☐ |
| **P3** | 缺口三源合流 → 学习计划 | 中低 | ★★★★☆ | ☐ |
| **P4** | 蒸馏 + 定线 + 出题 | 高 | ★★★★☆ | ☐ |
| **P5** | 综述 + 同构 + 覆盖率 + 知识地图 | 中高 | ★★★★☆（"体系"落点） | ☐ |
| **P6** | 策展保鲜 + 掌握度 | 中 | ★★★☆☆ | ☐ |
| **P7** | 工作模板包 | 中低 | ★★★★☆（打开工作场景） | ☐ |

选定后我出实施计划：DDL、front-matter JSON Schema、`repo.yml` 规范、工具签名、受限执行安全设计、改动文件清单、验收标准（**以 `AI-Infra` 仓库为真实测试集**）。

---

# 附：三条硬性约束

1. **可降级**：全关时行为与现状一致；`provider=LOCAL` 不连 GitHub 全功能可用。
2. **可观测**：新指标进 `/api/observability/stats`，且必须有消费方。
3. **可重建**（新增）：删掉全部 `kb_*` 表后，从仓库全量重建即恢复。**这条是 Git-native 架构的验收标准，必须有一个 `rebuild-index` 命令来证明它。**
