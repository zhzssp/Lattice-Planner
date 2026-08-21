# V4.0.0 · Codex 知识仓库 P0 交付记录

> 对应设计：`知识资产沉淀-P0至P2实施设计.md` 的 **P0a + P0b~e**。
> 状态：**已实现，待真机验收**（本机无 JDK，未能执行编译与测试，见 §7）。

---

# 一、交付范围

按设计文档的分期，本次交付 **P0 全部内容**：

| 子阶段 | 内容 | 状态 |
|---|---|---|
| **P0a** | 阻塞修复：分层切片 + 缓存改造 + `kb_chunk` 表 | ✅ |
| **P0b** | Git 客户端 + 仓库注册 + 扫描 | ✅ |
| **P0c** | 解析器（front-matter / 标题树 / 链接 / 布局 glob） | ✅ |
| **P0d** | 索引编排 + 增量 + run 记录 | ✅ |
| **P0e** | 仪表盘 + 只读工具 + 可观测指标 | ✅ |

P1（验证闭环）、P2（沉淀 + PR + CI）**未开始**，留待下一批。

---

# 二、三个阻塞性缺陷的修复

## P0a-1 · 切片静默腰斩（最严重）

**原状**：`NoteIndexService.MAX_CHUNKS_PER_NOTE = 64`，`64 × 600 = 38,400` 字符上限，
且触顶时直接 `return out`，**不记日志、不落库、无任何信号**。

实测目标仓库：单文件最大 `iree-learning-guide.md` **107,153 字符** → 仅索引前 36%。
Top 6 文件全部超 80K，恰好是知识体系的主干。

**危害不是「搜不到」，而是「搜不到却以为搜过了」**：Agent 会声称已检索知识库，
用户据此判断「我没写过这个」，比检索失败更具误导性。

**修法（三层）**：

| 层 | 做法 |
|---|---|
| 结构 | 新增 `SectionAwareChunker`：按 `##`/`###` 先分段，段内再切片 |
| 上限 | 文档级上限 `codex.index.max-chunks-per-document=400`；**笔记侧 64 保持不变** |
| 可观测 | `ChunkResult.truncated` + `kb_document.truncated` / `loss_ratio` + WARN 日志 + 仪表盘红标 + 工具返回 `_indexWarning` |

`NoteIndexService.chunk(title, content)` 两参签名保留并委托到三参版本，
**现有笔记路径逐字节等价**。

顺带收益：chunk 自带 `heading_path` 与 `anchor`，引用可精确到
`iree-learning-guide.md#46-timeline-semaphore`。

## P0a-2 · 向量缓存容量口径错误

**原状**：`maximumSize(maxUsers=32)` 按**用户数**计容。但单用户体积可差三个数量级——
2900 chunk × 1024 维 ≈ 12 MB，32 个这样的用户 ≈ 380 MB+，JVM 默认堆直接 OOM。
且 `Entry` 无 `repoId`，查一个仓库要扫该用户全部向量。

**修法**：
- `maximumWeight` + `weigher`（按向量条数计权），配置改为 `pkm.rag.cache.max-vectors=20000`
- 缓存键 `(userId, scopeKey)` 分桶：`note` 桶与 `repo:{id}` 桶互不干扰
- `Entry` 增加 `repoId / documentId / headingPath / anchor`；笔记走 `Entry.ofNote(...)` 保持原语义
- 外部 `ScopeLoader` 注册回调 —— 让 `pkm` 不反向依赖 `codex`，保证 Codex 全关时 pkm 行为不变

**刻意没做**：倒排 + 两级召回、向量量化。2900 chunk 全表 cosine 约 3~5ms，
**瓶颈是内存不是算力**，先解决"别把整个库装进内存"才是对症。

## P0a-3 · `note_embedding` 无法承载引用定位

**原状**：`note_id NOT NULL`，`LOCAL_DOC` 用 `0` 占位，无法反查「哪个仓库/哪篇/哪一节」。

**修法**：新建独立 `kb_chunk` 表，而非给 `note_embedding` 加字段。
决定性理由是**保护评测资产**：分表可让既有笔记检索路径逐字节不变，
不触碰方案 A 的 cassette。

---

# 三、审计中额外发现并修掉的两个 bug

这两个都不在原设计里，是实现过程中通过交叉检查发现的。

## 章节区间互相覆盖 → 索引体积成倍膨胀

最初 `charEnd` 取「下一个**同级或更高级**标题的起点」，看似合理，实际后果严重：
H1 区间会覆盖其下所有 H2/H3 的正文，同一段文字被父节与子节**各切一次**。

- 10 万字符文档可膨胀到 ~3 倍
- 检索结果出现大量近重复命中，把真正相关的其他章节挤出 topK
- embedding 成本同比例翻倍

**修法**：`charEnd` = 下一个标题的 `lineStart`，与层级无关。
层级关系由 `heading_path` 承载，不需要靠区间嵌套表达。
新增 `Section.lineStart` 区分「标题行起点」与「正文起点」。

回归测试：`nestedHeadingsDoNotDuplicate`（同一标识文本只能出现在一个 chunk）
+ `sectionRangesDoNotOverlap`。

## BOM 导致 `bodyStart` 偏移 1 字符

`FrontMatterParser` 原先剥离 BOM 后用**剥离后的串**计算偏移，
但返回的 `bodyStart` 被调用方用于对**原始 content** 做 `substring` →
带 BOM 的文件所有章节区间整体错位。

这类 off-by-one 只在带 BOM 文件上出现，且表现为「章节内容莫名偏移一个字符」，
极难定位。**修法**：记录 `bomOffset`，返回时加回。
回归测试：`bodyStartIsRelativeToOriginalContent`。

---

# 四、交付清单

## 新增（35 个文件）

| 类型 | 路径 |
|---|---|
| 迁移 | `db/migration/V8__codex_git_repo.sql` |
| 实体 | `feature/codex/entity/` × 7（`KnowledgeRepo` / `KbDocument` / `KbSection` / `KbChunk` / `KbLink` / `KbEntity` / `KbScopeDecision` / `KbIndexRun`） |
| 仓储 | `feature/codex/repository/` × 7 |
| Git | `feature/codex/git/` × 3（`GitClient` / `ProcessGitClient` / `GitCommandException`） |
| 索引 | `feature/codex/index/` × 6（`RepoLayout` / `RepoScanner` / `FrontMatterParser` / `MarkdownStructureParser` / `SectionAwareChunker` / `LinkExtractor` / `RepoIndexer`） |
| 服务 | `feature/codex/service/` × 5（`RepoRegistryService` / `RepoSyncService` / `CodexSearchService` / `RepoHealthService` / `CodexMetrics`） |
| 控制器 | `feature/codex/controller/` × 2 |
| 工具 | `feature/codex/tool/` × 2（`RepoTools` / `DocTools`） |
| 页面 | `resources/templates/codex.html` |
| 测试 | `agenteval/unit/` × 3（`CodexModeVisibilityTest` / `SectionAwareChunkerTest` / `CodexIndexParsersTest`），约 60 个用例 |

## 改动（8 处，均保持向后兼容）

| 文件 | 改动 | 兼容性保证 |
|---|---|---|
| `NoteIndexService` | `chunk` 加 `maxChunks` 参数 + `ChunkResult` + 触顶告警 | 保留 2 参重载委托，现有调用逐字节等价 |
| `EmbeddingVectorCache` | scope 分桶 + `maximumWeight` + `Entry` 扩字段 + `ScopeLoader` | 笔记走 `SCOPE_NOTE` 桶与 `Entry.ofNote`，行为等价 |
| `AgentMode` | 新增 `STUDY` / `CURATE` / `VERIFY`；`CHAT` 加 deny | 既有四模式 `allowTags` **一字未改** |
| `PromptBuilder` | 降级路径补三个模式 + 系统提示加「知识仓库原则」 | 既有模式 tagFilter 不变 |
| `ObservabilityController` | 新增 `codex` 分区 + `/api/codex/stats` | 既有分区不变 |
| `WebSecurityConfig` | `/api/codex/**` CSRF 豁免 + 需登录 | 与 `/api/agent/**` 同策略 |
| `application.properties` | 新增 `codex.*` 段；`max-users` → `max-vectors` | 全部有默认值，总开关默认 `false` |
| `dashboard.html` | 增加「知识仓库」入口 | — |

---

# 五、保护评测资产的具体做法（本次最需要说明的技术决策）

项目已有 47 个评测用例 + cassette，按 `messages_hash` 回放。
**任何改变 `exportSchemas` 输出字节的改动都会让它们全部失效。**

新增 9 个工具必然改变工具列表，因此采取三条措施：

1. **既有四模式的 `allowTags` 一字不改** → 新工具因不带旧 tag 而天然不可见。
2. **`CHAT` 显式加 `denyTags={codex, exec, checkpoint}}`** ——
   它的 `allowTags` 是空集（语义为"不收窄"），是唯一会自动看到新工具的模式。
   这是**唯一必须动既有枚举的地方，且只加 deny 不加 allow**。
3. **不改 `kb.semantic_search`**：Git 检索走新工具 `doc.search`。
   零回归，且语义更清晰（kb = 随手笔记，doc = 知识仓库）。

`CodexModeVisibilityTest.ChatByteStability` 专门守这条约束——
将来若有人给 CHAT 放开这些 tag，测试会先红。

---

# 六、验收清单（需真机执行）

前置：`codex.enabled=true`、`pkm.rag.git.enabled=true`、跑 `V8__codex_git_repo.sql`。

| # | 验收项 | 判定标准 |
|---|---|---|
| 1 | 零改动接入 | 注册 `C:\Users\hanishzheng\Desktop\AI-Infra` 后，`git status` 仍干净 |
| 2 | 文档识别 | `kb_document` ≈ 61 行；kind 分布含 guide/note/roadmap/checkpoint-set/lab/source |
| 3 | **截断修复** | `iree-learning-guide.md` 的 `chunk_count ≥ 150` 且 `truncated=0` |
| 4 | 章节定位 | `kb_section` 有该文档的 4.6 级 anchor；`doc.search` 返回 `locator` |
| 5 | **死链检测** | `kb_link` ≈ 1263 行，377 带 anchor；抽查 5 条 broken 确认真断 |
| 6 | **增量索引** | 连续两次 sync：第二次 `docsSkipped=61`、`embedCalls=0`、耗时降至 1/10 |
| 7 | **可重建** | `DELETE FROM kb_*` 后调 `/rebuild`，第 2~5 项结果一致 |
| 8 | 外部修改感知 | IDE 改一篇 md → sync → `docsReindexed=1` |
| 9 | 检索可用 | 问 timeline semaphore → 命中该 guide 的 4.6 节 |
| 10 | **无回归** | `./gradlew test` + `./gradlew agentEval` 全绿 |

第 3、6 条是本期核心价值；第 7 条是架构正确性；第 10 条是不破坏存量。

---

# 七、未验证事项（如实声明）

**本机未安装 JDK**（`JAVA_HOME` 未设，`C:\Program Files\Java` 等常见位置均无，
Gradle wrapper 无法启动），因此以下**均未执行**：

- `./gradlew compileJava`
- `./gradlew test`（含新增约 60 个用例）
- `./gradlew agentEval`（cassette 回放回归）
- 针对 `AI-Infra` 的端到端验收（§6 全部 10 条）

已做的替代验证：
- IDE 语言服务诊断：`src/` 全树 **0 error**
- 人工交叉审计：签名一致性、跨模块调用点、测试所需可见性（已把
  `RepoLayout.globToPattern` / `RepoIndexer.normalizeTarget` / `normalizeSlashes` 提为 public）
- 逐一核对既有调用方（`vectorCache.load` / `NoteIndexService.chunk` 等）未被破坏

**请在有 JDK 的环境执行 §6 后再合并。** 若编译有遗漏，大概率集中在
`RepoIndexer` 的泛型与 `Optional` 用法上。

---

# 八、下一步

设计文档中的 P1（验证闭环）是护城河所在，且价值最高：

```
Guide → 出题 → 【先预测（不填则 run 锁定）】→ 受限执行 → 机器判定
  ├ 通过且预测对 → Mastery +1
  ├ 通过但预测错 → ★最高价值信号 → 生成 Gap + 建议写 note
  └ 失败 → 按「常见失败→盲点」回指 guide 章节
```

本期已为它铺好三处地基：
- `AgentMode.VERIFY` 与 `exec` tag 隔离（测试已守住"仅 VERIFY 可见"）
- `kb_document` / `kb_section` 提供 checkpoint 的挂靠点
- `GitClient.isTracked` 提供受限执行的信任判据（未被跟踪的脚本不可信）
