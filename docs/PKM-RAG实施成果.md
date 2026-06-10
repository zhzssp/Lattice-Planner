# Lattice-Planner PKM-RAG 实施成果

> 版本：v1（基于实际代码落地）
> 配套文档：`docs/PKM-RAG实现方案.md`（设计方案）、`docs/Lattice-Agent功能总览.md`（Agent 全景）
> 适用读者：维护者 / 二次开发者 / Demo 答辩人

---

## 0. TL;DR

PKM-RAG 三阶段全部交付，把 `Note` 从"标题 + 纯文本"升级为「**Markdown + 双向链接 + 全文/向量混合检索 + Agent 工具化**」的个人知识中枢。Agent 不再"凭记忆编"，而是「先检索个人笔记 → 再回答 → 写回笔记」。复盘模块也接上同一通路，给出「结合你最近写过的内容」的针对性建议。

零外部向量组件、零部署形态变化、单体 Spring Boot + MySQL + 本地 Caffeine LRU。

---

## 1. 阶段交付总表

| 阶段 | 主题 | 关键产出 |
|---|---|---|
| **Stage 1** | PKM 基础升级 | Markdown 渲染 / `[[双链]]` / 反链面板 / 标签 / FULLTEXT / `noteEdit/noteView` 页面 / `V4__pkm_rag.sql` |
| **Stage 2** | RAG 通路打通 | `EmbeddingClient`（DashScope text-embedding-v3）/ `NoteIndexService` 异步切块入库 / `RagSearchService` hybrid 融合 / 4 个 Agent KB 工具 |
| **Stage 3** | 管理面 + 体验闭环 + 性能 | 反摄取 + 已摄取清单工具 / 列表页搜索框 / 标签云 + 标签筛选页 / 复盘自动接 RAG / Caffeine LRU 向量缓存 |

---

## 2. 模块全景

```
┌─────────────────── Web 端 ───────────────────┐
│  noteList.html ── 搜索框（GET /note/search） │
│              └── 标签云 / 标签筛选页          │
│  noteEdit / noteView ── Markdown 渲染 + 反链 │
└──────────────┬───────────────────────────────┘
               │ HTTP（Spring Security）
┌──────────────▼───────────────────────────────┐
│ NoteViewController                           │
│ ├─ list / byTag → buildTagCloud()            │
│ ├─ edit / view  → MarkdownRenderer           │
│ └─ search       → RagSearchService.search    │
└──────────────┬───────────────────────────────┘
               │
   ┌───────────┴────────────┬────────────────────┐
   ▼                        ▼                    ▼
NoteService         NoteIndexService      RagSearchService
(@Transactional)    (@Async, write 后失效缓存)  ├─ FULLTEXT(ngram) 关键字
   │                  ├─ NoteLinkParser   │     └─ EmbeddingVectorCache
   │ NotePersistedEvent ├─ chunk          │        └─ float[] LRU(Caffeine)
   ▼ (Async)            └─ EmbeddingClient
NotePersistedListener        ↓                  ↑
                       note_embedding ──────────┘
                       (MySQL JSON / TEXT)

Agent 抽屉 ──ToolRegistry──▶ KnowledgeTools(6) / InsightTools / ...
                                        │
                       ToolConfirmCoordinator（write+local 强制确认）
```

---

## 3. Stage 3 详细实施清单

### 3.1 反摄取 + 已摄取清单（KB 管理面）

| 文件 | 改动 |
|---|---|
| `repository/NoteEmbeddingRepository.java` | `+countByLocalPath(userId, path)`、`+listLocalDocs(userId)`（带 `LocalDocSummary` 投影：`path / chunks / latest`） |
| `feature/pkm/service/NoteIndexService.java` | `+deleteLocalDoc(userId, path) → int`：先 `count → delete → invalidate cache`，事务原子 |
| `feature/agent/tool/impl/KnowledgeTools.java` | `+kb.list_ingested_docs`（read）`+kb.delete_local_doc`（write+local，requiresConfirm） |

工具最终清单：

| 工具名 | tags | requiresConfirm | 阶段 |
|---|---|---|---|
| `kb.semantic_search` | kb,read | ❌ | Stage 2 |
| `kb.lookup_by_title` | kb,read | ❌ | Stage 2 |
| `kb.list_backlinks` | kb,read | ❌ | Stage 2 |
| `kb.ingest_local_doc` | kb,write,local | ✅ | Stage 2 |
| **`kb.list_ingested_docs`** | kb,read | ❌ | **Stage 3** |
| **`kb.delete_local_doc`** | kb,write,local | ✅ | **Stage 3** |

### 3.2 笔记列表搜索框 + 标签云

| 文件 | 改动 |
|---|---|
| `feature/pkm/controller/NoteViewController.java` | `+GET /note/search?q=&topK=` 直连 `RagSearchService.search`，返回 JSON 命中列表（含 `score / reason / source / title|sourcePath / content`）<br>`+GET /note/tag/{tag}` 标签筛选页（复用 `noteList.html`，注入过滤后的 `notes` 与 `activeTag`）<br>`+buildTagCloud / containsTag`：基于 `Note.tags` 字段（逗号分隔）内存聚合，零新表 |
| `templates/noteList.html` | 顶部新增 `.note-search` 搜索框 + `.tag-cloud` 标签云；JS 异步 fetch 搜索接口，`escapeHtml` 防 XSS |
| `static/css/note.css` | `+.note-search / .hit-list / .tag-cloud / .tag-chip` 样式块 |

### 3.3 复盘自动接 RAG

| 文件 | 改动 |
|---|---|
| `feature/insight/service/AiSummaryService.java` | `+RagSearchService` 注入<br>`+summarizeScores(start, end, scores, user)` 新签名（旧三参签名内部转发，零回归）<br>`+buildRagContext(user, start, end)`：以「复盘 学习 习惯 自律 卡点 反思 + 时间窗」为 query，取 top5 chunk（≤300 字）注入 prompt<br>Prompt 增加第 6 条：让 LLM 引用真实笔记，并以 `[[标题]]` 写法 |
| `feature/insight/controller/InsightController.java` | 调用点切到带 `user` 的新签名 |
| `feature/agent/tool/impl/InsightTools.java` | 同上，`insight.summarize_period` 自动获得 RAG 增强 |

### 3.4 V2 向量 LRU 缓存

| 文件 | 改动 |
|---|---|
| `build.gradle` | `+com.github.ben-manes.caffeine:caffeine:3.1.8` |
| `feature/pkm/service/EmbeddingVectorCache.java`（新增） | Caffeine `Cache<Long, List<Entry>>`；`Entry` 持有反序列化后的 `float[]`，根除每次 search 的 JSON→`float[]` 瓶颈<br>`expireAfterAccess=30min` + `maximumSize=32`，均可配置<br>`recordStats() / stats()` 暴露命中率 |
| `feature/pkm/service/RagSearchService.java` | 向量通路改走 `vectorCache.load(userId)`；关键字通路不变；任一异常自动降级仅关键字 |
| `feature/pkm/service/NoteIndexService.java` | `rebuildForNote / rebuildForLocalDoc / deleteLocalDoc` 末尾 `vectorCache.invalidate(userId)`，写穿一致性 |
| `application.properties` | `+pkm.rag.cache.max-users=32`、`+pkm.rag.cache.expire-minutes=30` |

---

## 4. 关键设计决策与回执

| 决策 | 落点与理由 |
|---|---|
| **不引外部向量库** | 仅 MySQL JSON + 本地 Caffeine LRU。万级 chunk × 1024dim 全表 cosine ~10ms，单体部署形态不变 |
| **Hybrid 优于纯向量** | `RagSearchService` 同时跑 FULLTEXT(ngram) + cosine，加权融合（`alpha=0.4`）；任一通路异常自动降级，未跑 V4 SQL 或未配 `EMBED_API_KEY` 都不致命 |
| **多用户隔离贯穿到底** | 所有工具入口走 `AgentContext.requireUser()`；FULLTEXT `where user_id=?`；向量端只取本用户 `note_embedding`；越权读他人笔记天然不可达 |
| **写后即时可见** | 所有写口径（笔记保存 / 摄取 / 反摄取）都在事务结束位置 `vectorCache.invalidate(userId)`，下一次 `load()` miss 重建 |
| **复盘 RAG 完全降级安全** | `buildRagContext` 外层 `try/catch (Exception)` 吞异常返回空串；空串时 prompt 不注入 RAG 段；旧调用面零回归 |
| **本地文档摄取需用户确认** | `kb.ingest_local_doc / kb.delete_local_doc` 标 `requiresConfirm=true`；执行链路通过 `LocalBridgeProxy → Electron preload 白名单网关`，不直接接触磁盘 |
| **标签云零持久化** | 直接基于 `Note.tags` 字段在内存聚合，无新表无新查询；切换标签筛选只过滤已加载列表 |
| **搜索 UI 安全** | 后端走 Spring Security 已认证通道；前端 `escapeHtml`；JSON 端点 `produces application/json;charset=UTF-8` 兼容中文 |

---

## 5. 配置参数手册

```properties
# RAG 检索调权（Stage 2）
pkm.rag.alpha=0.4            # 向量分量权重；1-alpha 为关键字 RRF 占比
pkm.rag.candidates=50        # 关键字 / 向量各自候选取多少条入融合池
pkm.rag.topK=6               # 默认返回 topK（kb.semantic_search 未传 topK 时使用）

# RAG 向量缓存（Stage 3）
pkm.rag.cache.max-users=32       # 同时驻留缓存的最大用户数
pkm.rag.cache.expire-minutes=30  # 访问后无活动则释放，兜底防止离线用户占内存

# Embedding（Stage 2）
agent.embedding.api-base=https://dashscope.aliyuncs.com/compatible-mode/v1
agent.embedding.api-key=${EMBED_API_KEY:}
agent.embedding.model=text-embedding-v3
agent.embedding.dim=1024

# 复盘 LLM 超时（Stage 3 起带 RAG 上下文，仍受同一超时）
agent.llm.summary-timeout-seconds=30
```

> **降级矩阵**：
> - 没跑 `V4__pkm_rag.sql` → FULLTEXT 抛 SQLException → 仅向量通路
> - 没配 `EMBED_API_KEY` → `EmbeddingClient.embed` 抛 IllegalStateException → 仅关键字通路
> - 两者皆失败 → `RagSearchService.search` 返回空 list，但不抛异常上抛业务面

---

## 6. 数据流时序

### 6.1 笔记保存 → 索引重建

```
NoteController.save()
  └─ NoteService.update(...)         @Transactional
        └─ ApplicationEventPublisher.publishEvent(NotePersistedEvent)

NotePersistedListener.onEvent()       @Async
  └─ NoteIndexService.rebuildForNote(note)
        ├─ NoteLinkParser.parse() → 重写 Link(NOTE→NOTE)
        ├─ chunk(content)         → 按段切块
        ├─ EmbeddingClient.embed(chunks) → DashScope
        ├─ embeddingRepository.deleteByNoteId(noteId) + saveAll(...)
        └─ vectorCache.invalidate(userId)   ← Stage 3
```

### 6.2 Agent 检索 → 回答

```
LLM 决策调用 kb.semantic_search(query, topK)
  └─ KnowledgeTools.semanticSearch()
        └─ RagSearchService.search(user, query, topK)
              ├─ noteRepository.fulltextSearch(userId, query, candidates)   ── kw 通路
              ├─ embeddingClient.embed([query])
              ├─ vectorCache.load(userId)                                   ── vec 通路（缓存）
              │     └─ miss 时：embeddingRepository.findByUserId(userId) → deserialize 全量
              ├─ cosine + 加权融合（alpha）
              └─ topK Hit[]
  → JSON 回灌 LLM → 最终回答
```

### 6.3 复盘 → RAG 注入

```
InsightController.summary(...)
  └─ aiSummaryService.summarizeScores(start, end, scores, user)
        ├─ buildRagContext(user, start, end)
        │     └─ ragSearchService.search(user, "复盘 学习 习惯 ...", 5)
        │           → top5 chunk（≤300字）→ 文本块
        ├─ buildPrompt(...)（合并规则总结 + RAG 上下文 + 原始得分）
        └─ llmGateway.generateText(prompt) → 自然语言总结
```

---

## 7. 验证步骤（Demo 路径）

1. **环境准备**
   ```bash
   ./gradlew bootRun
   # 可选：set EMBED_API_KEY=sk-xxxx 启用向量通路
   ```

2. **Demo A —— Markdown + 双链 + 反链**（Stage 1）
   - 新建笔记 A：`# Hello\n\n- [ ] todo` → `/note/{id}` 看到 GFM 渲染
   - 新建笔记 B：内容写 `参考 [[A]]` → 保存
   - 进入 A 页面下方"反向链接"列表应出现 B

3. **Demo B —— 列表搜索框**（Stage 3）
   - `/note` 输入 "RAG" 回车 → 命中卡片显示 `score / reason / 内容预览`，点击跳转

4. **Demo C —— 标签云 / 标签筛选页**（Stage 3）
   - 编辑几篇笔记把 `tags` 字段填上 `spring,ai` `rag,paper`
   - `/note` 顶部出现标签云，点击 `#spring` → URL 跳到 `/note/tag/spring`

5. **Demo D —— Agent KB 工具**（Stage 2 + 3）
   - 抽屉问"找一下我笔记里关于 RAG 的内容" → `kb.semantic_search`
   - "把 D:/learning/spring-ai/intro.md 摄取进知识库" → `kb.ingest_local_doc`（弹窗确认）
   - "我摄取过哪些资料？" → `kb.list_ingested_docs`
   - "把 D:/learning/spring-ai/intro.md 移除" → `kb.delete_local_doc`（弹窗确认）

6. **Demo E —— 复盘接 RAG**（Stage 3）
   - 写一篇《本周复盘 - 时间分配卡点》笔记
   - Insight 页或 Agent `insight.summarize_period(from,to)` → 总结里命中应包含 `[[本周复盘 - 时间分配卡点]]` 引用
   - 不配 `EMBED_API_KEY` 时仍能正常返回（仅缺 RAG 段）

7. **Demo F —— 缓存命中观察**
   - 连续两次 `kb.semantic_search`，第二次响应明显更快
   - 30+ 分钟空闲后再请求，触发一次 reload；日志可见 `加载向量缓存：成功 N 条`

---

## 8. 文件索引（速查）

```
db/migration/V4__pkm_rag.sql                        FULLTEXT + tags + note_embedding 建表
build.gradle                                        commonmark-java + caffeine

src/main/java/org/zhzssp/memorandum/
├── entity/
│   ├── Note.java                                   +tags 字段
│   └── NoteEmbedding.java                          向量存储实体
├── repository/
│   ├── NoteRepository.java                         +fulltextSearch +findFirstByUserAndTitle
│   └── NoteEmbeddingRepository.java                +countByLocalPath +listLocalDocs (Stage 3)
├── core/service/
│   └── NoteService.java                            +update +findByIdForUser + 发事件
├── feature/pkm/
│   ├── event/NotePersistedEvent.java
│   ├── listener/NotePersistedListener.java         @Async 入口
│   ├── service/
│   │   ├── MarkdownRenderer.java                   commonmark + 任务列表
│   │   ├── NoteLinkParser.java                     [[标题]] 解析
│   │   ├── NoteIndexService.java                   切块 + 索引 + 反摄取（Stage 3）
│   │   ├── EmbeddingClient.java                    DashScope embed + serialize/cosine
│   │   ├── EmbeddingVectorCache.java               Caffeine LRU（Stage 3 新增）
│   │   └── RagSearchService.java                   hybrid 融合
│   └── controller/NoteViewController.java          +/note/search +/note/tag/{tag}（Stage 3）
├── feature/agent/tool/impl/
│   └── KnowledgeTools.java                         6 个 KB 工具
├── feature/insight/service/
│   └── AiSummaryService.java                       +summarizeScores(... , user)（Stage 3）
└── ...

src/main/resources/
├── templates/
│   ├── noteList.html                               +搜索框 +标签云（Stage 3）
│   ├── noteEdit.html / noteView.html               Markdown 编辑 / 渲染 + 反链
├── static/css/note.css
└── application.properties                          pkm.rag.* + pkm.rag.cache.*（Stage 3）
```

---

## 9. 后续可拓展方向

| 方向 | 触发条件 | 落点建议 |
|---|---|---|
| **批量摄取目录** | 用户希望一次导入整个 `D:/notes/**` | 新增 `kb.ingest_directory(path, glob)`：递归走 `LocalBridgeProxy.listDir`，逐文件复用 `rebuildForLocalDoc`；带断点续传 |
| **编辑器侧边栏反链实时面板** | `noteEdit.html` 输入 `[[` 时实时联想 | 复用 `kb.list_backlinks` + `kb.lookup_by_title`，前端 contenteditable + popover |
| **超 10w chunk 规模** | 单用户笔记 / 文档量爆发 | 把向量列迁到 `sqlite-vec` 或 `pgvector`，`EmbeddingClient.cosine` 替换为 SQL 内积；接口签名不变 |
| **Embedding 多模型** | 用户自带 OpenAI / Ollama | `EmbeddingClient` 抽 `Provider` 接口，按 `agent.embedding.provider` 切换 |
| **检索结果高亮** | 列表搜索给定 `q` 时片段中关键词高亮 | 后端在 `Hit.content` 旁加 `highlights:int[]`，前端 `<mark>` 包裹 |
| **私密笔记排除 RAG** | 部分笔记不希望参与 Agent 检索 | `Note` 加 `excludedFromRag boolean`，`NoteIndexService` 入库前过滤；`RagSearchService` 自然不再命中 |

---

> **总结**：Stage 1 把 Note 升级成 PKM 形态，Stage 2 让 Agent "看见"个人知识，Stage 3 把它做成「日常可用、可管理、可复盘」的闭环。后续无论扩到多模态还是更大规模，都只需要替换两个点 —— `EmbeddingClient` 的 Provider 与向量存储后端，业务面零回归。
