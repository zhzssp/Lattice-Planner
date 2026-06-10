# Lattice-Planner 笔记 PKM 升级 + RAG 知识助手 实现方案

> 版本：v1（基于全量源码核对）
> 设计原则：**复用 > 新建**、**不引入外部向量组件**、**Note 实体不破坏旧数据**、**所有代码骨架可直接复制到工程编译**。

---

## 0. 目标

当前 `Note` 模块极简：标题 + 纯文本 `content` + 类型 + 列表页。Agent 已有 `note.create / note.list`，但**只能列出标题，无法基于笔记真正"理解"用户**。本次升级解决：

1. **笔记 PKM 升级**：Markdown 渲染、双向链接 `[[标题]]`、反向链接面板、标签 / 全文检索。
2. **RAG 知识助手**：Agent 回答时优先检索用户笔记 + 已摄取的本地文档，**严格基于用户已有知识**，不再编造。

> 把 Lattice-Planner 从"任务规划工具"延伸为"AI 时代个人知识库"。所有副作用仍落到现有领域表，事件链不变，Agent 工具集只新增 4 个。

---

## 1. 与现有源码的对接清单

### 1.1 必须复用

| 构件 | 路径 | 角色 |
|---|---|---|
| Note 实体 | `entity/Note.java`（`content` 已是 `TEXT`） | 直接当 Markdown 存，无需改字段 |
| Link 实体 | `entity/Link.java`（`source∈{TASK,NOTE}`，`target∈{TASK,NOTE,GOAL}`） | NOTE→NOTE 双链直接落库，**零新表** |
| LinkRepository | `findBySourceTypeAndSourceId / findByTargetTypeAndTargetId` | 反链查询直接复用 |
| NoteService | `core/service/NoteService` | 增量补 `update / findByIdForUser` |
| LlmGateway | `feature/agent/service/LlmGateway` | **新增 `generateEmbedding`** |
| ToolRegistry / @AgentTool | `feature/agent/tool/*` | 新工具直接注册 |
| LocalDocTools | `feature/agent/tool/impl/LocalDocTools` | 摄取本地文档时复用 `local.read_file/read_pdf` |
| AgentContext | `feature/agent/runtime/AgentContext` | 工具入口仍 `requireUser()` |
| `fragments/agent-panel` | 模板片段 | 新增页面统一注入 |

### 1.2 必须新增 / 修改

```
新增：
  entity/NoteEmbedding.java
  repository/NoteEmbeddingRepository.java
  feature/pkm/service/{MarkdownRenderer, NoteLinkParser, NoteIndexService,
                       EmbeddingClient, RagSearchService}.java
  feature/pkm/event/NotePersistedEvent.java
  feature/pkm/listener/NotePersistedListener.java
  feature/pkm/controller/NoteViewController.java
  feature/agent/tool/impl/KnowledgeTools.java
  resources/templates/{noteList, noteEdit, noteView}.html
  resources/static/css/note.css
  db/migration/V4__pkm_rag.sql

修改：
  entity/Note.java                ← +tags 字段（VARCHAR 255）
  core/service/NoteService.java   ← +update / +findByIdForUser / 在 create 末尾发事件
  repository/NoteRepository.java  ← +fulltextSearch / +findFirstByUserAndTitle
  feature/agent/service/LlmGateway.java  ← +generateEmbedding
  feature/agent/runtime/PromptBuilder.java  ← 系统 Prompt 增"先检索"指令
  MemorandumApplication.java      ← @EnableAsync
  build.gradle                    ← +commonmark-java
  application.properties          ← +agent.embedding.* +pkm.*
```

---

## 2. 总体架构

```
保存笔记 → NotePersistedEvent (Async) → NoteIndexService
                                          ├─ NoteLinkParser → 重写 Link(NOTE→NOTE)
                                          └─ chunk → EmbeddingClient → note_embedding

Agent 提问 → kb.semantic_search → RagSearchService
                                    ├─ 关键字通路：MySQL FULLTEXT(ngram)
                                    └─ 向量通路：load all → cosine → topK
                                    → 加权融合 → 回灌 LLM
```

---

## 3. 模块详细设计

### 3.1 数据层

#### 3.1.1 `Note` 增量

```java
// entity/Note.java —— 在原字段基础上追加
@Column(name = "tags", length = 255)
private String tags;   // 逗号分隔："spring,ai,rag"
```

#### 3.1.2 新表 `NoteEmbedding`

```java
// entity/NoteEmbedding.java
package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "note_embedding",
       indexes = {@Index(name="idx_user", columnList="user_id"),
                  @Index(name="idx_note", columnList="note_id")})
@Data
public class NoteEmbedding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)  private Long userId;
    @Column(name="note_id", nullable=false)  private Long noteId;          // LOCAL_DOC 时为 0
    @Column(name="source",  length=16, nullable=false) private String source;     // NOTE | LOCAL_DOC
    @Column(name="source_path", length=1024) private String sourcePath;            // LOCAL_DOC 时填
    @Column(name="chunk_idx", nullable=false) private Integer chunkIdx;
    @Column(name="content", columnDefinition="TEXT", nullable=false) private String content;
    @Column(name="embedding", columnDefinition="MEDIUMTEXT", nullable=false) private String embedding;
    @Column(name="dim", nullable=false) private Integer dim;
    @Column(name="model", length=64, nullable=false) private String model;
    @CreationTimestamp @Column(name="created_at") private LocalDateTime createdAt;
}
```

#### 3.1.3 Repository

```java
// repository/NoteEmbeddingRepository.java
public interface NoteEmbeddingRepository extends JpaRepository<NoteEmbedding, Long> {
    List<NoteEmbedding> findByUserId(Long userId);

    @Modifying @Query("delete from NoteEmbedding e where e.noteId = :noteId")
    void deleteByNoteId(@Param("noteId") Long noteId);

    @Modifying @Query("delete from NoteEmbedding e where e.userId=:u and e.source='LOCAL_DOC' and e.sourcePath=:p")
    void deleteByLocalPath(@Param("u") Long userId, @Param("p") String path);
}
```

```java
// repository/NoteRepository.java —— 增量
@Query(value = """
    SELECT * FROM note
    WHERE user_id = :userId
      AND MATCH(title, content) AGAINST (:kw IN NATURAL LANGUAGE MODE)
    ORDER BY MATCH(title, content) AGAINST (:kw IN NATURAL LANGUAGE MODE) DESC
    LIMIT :topK
    """, nativeQuery = true)
List<Note> fulltextSearch(@Param("userId") Long userId,
                          @Param("kw") String kw,
                          @Param("topK") int topK);

Optional<Note> findFirstByUserAndTitle(User user, String title);
```

#### 3.1.4 SQL 迁移

```sql
-- db/migration/V4__pkm_rag.sql

ALTER TABLE note ADD COLUMN tags VARCHAR(255) NULL;
ALTER TABLE note
  ADD FULLTEXT INDEX ft_note_title_content (title, content) WITH PARSER ngram;

CREATE TABLE note_embedding (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    note_id      BIGINT       NOT NULL,
    source       VARCHAR(16)  NOT NULL DEFAULT 'NOTE',
    source_path  VARCHAR(1024) NULL,
    chunk_idx    INT          NOT NULL,
    content      TEXT         NOT NULL,
    embedding    MEDIUMTEXT   NOT NULL,
    dim          INT          NOT NULL,
    model        VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_user (user_id),
    INDEX idx_note (note_id),
    INDEX idx_user_source (user_id, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **关键取舍**：MySQL 无 vector 类型，`embedding` 用 `MEDIUMTEXT` 存 JSON 数组。单用户万级 chunk × 1024dim ≈ 40 MB，全表 cosine ~10ms，**完全够用，不引 pgvector / es / sqlite-vec**。多用户 / 数据量大时 V2 再加 LRU 内存缓存即可。

---

### 3.2 Markdown 渲染

```groovy
// build.gradle
implementation 'org.commonmark:commonmark:0.22.0'
implementation 'org.commonmark:commonmark-ext-gfm-tables:0.22.0'
implementation 'org.commonmark:commonmark-ext-task-list-items:0.22.0'
```

```java
// feature/pkm/service/MarkdownRenderer.java
@Component
public class MarkdownRenderer {
    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        var exts = java.util.List.<Extension>of(
                TablesExtension.create(), TaskListItemsExtension.create());
        this.parser   = Parser.builder().extensions(exts).build();
        this.renderer = HtmlRenderer.builder().extensions(exts).escapeHtml(true).build();
    }

    public String render(String md) {
        if (md == null || md.isBlank()) return "";
        // [[Title]] → 内部跳转
        String pre = md.replaceAll("\\[\\[([^\\[\\]\\n]{1,80})]]",
                                   "[$1](/note/by-title/$1)");
        return renderer.render(parser.parse(pre));
    }
}
```

> `escapeHtml=true` 是 PKM 必备的 XSS 屏障。

---

### 3.3 双向链接（复用 Link 表，不新建）

```java
// feature/pkm/service/NoteLinkParser.java
@Component
public class NoteLinkParser {
    private static final Pattern WIKI = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,80})]]");
    private static final Pattern TAG  = Pattern.compile("(?<![\\w/])#([\\p{L}\\p{N}_\\-]{1,30})");

    public List<String> extractLinkedTitles(String md) {
        if (md == null) return List.of();
        var m = WIKI.matcher(md);
        var set = new LinkedHashSet<String>();
        while (m.find()) set.add(m.group(1).trim());
        return new ArrayList<>(set);
    }

    public List<String> extractTags(String md) {
        if (md == null) return List.of();
        var m = TAG.matcher(md);
        var set = new LinkedHashSet<String>();
        while (m.find()) set.add(m.group(1).toLowerCase(Locale.ROOT));
        return new ArrayList<>(set);
    }
}
```

链接落库规则（V1）：保存笔记 → 找到对端笔记 → 落 `Link(NOTE,curId, NOTE,targetId)`；对端不存在则跳过（用户去创建会自动建反链）。

---

### 3.4 Embedding 客户端

#### 3.4.1 `LlmGateway` 增量

```java
// feature/agent/service/LlmGateway.java —— 追加
@Value("${agent.embedding.model:bge-m3}") private String embeddingModel;
@Value("${agent.embedding.base-url:}")    private String embeddingBaseUrl;
@Value("${agent.embedding.api-key:}")     private String embeddingApiKey;

public java.util.List<float[]> generateEmbedding(java.util.List<String> inputs) {
    if (inputs == null || inputs.isEmpty()) return java.util.List.of();
    String key  = (embeddingApiKey != null && !embeddingApiKey.isBlank())
                    ? embeddingApiKey.trim() : resolveApiKey();
    if (key == null || key.isBlank())
        throw new IllegalStateException("agent.embedding.api-key / agent.llm.api-key 未配置");
    String base = (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank())
                    ? normalizeBaseUrl(embeddingBaseUrl) : normalizeBaseUrl(baseUrl);
    try {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "model", embeddingModel, "input", inputs));
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/v1/embeddings"))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new IllegalStateException("Embedding HTTP " + resp.statusCode() + " - " + resp.body());
        var arr = objectMapper.readTree(resp.body()).path("data");
        var out = new java.util.ArrayList<float[]>(inputs.size());
        for (var n : arr) {
            var emb = n.path("embedding");
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) v[i] = (float) emb.get(i).asDouble();
            out.add(v);
        }
        return out;
    } catch (java.io.IOException | InterruptedException ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new IllegalStateException("调用 Embedding API 失败", ex);
    }
}

public String embeddingModelName() { return embeddingModel; }
```

> OpenAI-Compatible `/v1/embeddings` 兼容 **硅基流动 / 智谱 / 通义 / 本地 Ollama nomic-embed-text**。
> DeepSeek 自身**不提供** embedding，所以这里用独立的 `agent.embedding.*` 配置；留空时回落到 `agent.llm.*`。

#### 3.4.2 `EmbeddingClient`（薄封装 + cosine）

```java
// feature/pkm/service/EmbeddingClient.java
@Component
public class EmbeddingClient {
    private final LlmGateway llm;
    private final ObjectMapper om;
    public EmbeddingClient(LlmGateway l, ObjectMapper o) { this.llm = l; this.om = o; }

    public List<float[]> embed(List<String> texts) { return llm.generateEmbedding(texts); }
    public String modelName() { return llm.embeddingModelName(); }

    public String serialize(float[] v) {
        try { return om.writeValueAsString(v); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public float[] deserialize(String json) {
        try {
            List<Double> list = om.readValue(json, new TypeReference<List<Double>>(){});
            float[] v = new float[list.size()];
            for (int i = 0; i < list.size(); i++) v[i] = list.get(i).floatValue();
            return v;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot=0, na=0, nb=0;
        for (int i=0;i<a.length;i++) { dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        return (na==0||nb==0)?0:dot/(Math.sqrt(na)*Math.sqrt(nb));
    }
}
```

---

### 3.5 NoteIndexService（保存后联动核心）

```java
// feature/pkm/service/NoteIndexService.java
@Service
public class NoteIndexService {

    private static final int CHUNK_SIZE   = 600;
    private static final int CHUNK_OVERLAP = 100;
    private static final int MAX_CHUNKS_PER_NOTE = 64;

    private final NoteRepository noteRepository;
    private final NoteEmbeddingRepository embeddingRepository;
    private final LinkRepository linkRepository;
    private final NoteLinkParser linkParser;
    private final EmbeddingClient embeddingClient;

    public NoteIndexService(NoteRepository nr, NoteEmbeddingRepository er,
                            LinkRepository lr, NoteLinkParser p, EmbeddingClient ec) {
        this.noteRepository = nr; this.embeddingRepository = er;
        this.linkRepository = lr; this.linkParser = p; this.embeddingClient = ec;
    }

    @Transactional
    public void rebuildForNote(Note note) {
        Long noteId = note.getId();
        Long userId = note.getUser().getId();

        // 1) 重建出链（仅 NOTE→NOTE）
        for (Link l : linkRepository.findBySourceTypeAndSourceId(Link.LinkSourceType.NOTE, noteId)) {
            if (l.getTargetType() == Link.LinkTargetType.NOTE) linkRepository.delete(l);
        }
        for (String title : linkParser.extractLinkedTitles(note.getContent())) {
            noteRepository.findFirstByUserAndTitle(note.getUser(), title).ifPresent(target -> {
                if (Objects.equals(target.getId(), noteId)) return;
                Link l = new Link();
                l.setSourceType(Link.LinkSourceType.NOTE); l.setSourceId(noteId);
                l.setTargetType(Link.LinkTargetType.NOTE); l.setTargetId(target.getId());
                linkRepository.save(l);
            });
        }

        // 2) 重建向量
        embeddingRepository.deleteByNoteId(noteId);
        List<String> chunks = chunk(note.getTitle(), note.getContent());
        if (chunks.isEmpty()) return;
        List<float[]> vecs = embeddingClient.embed(chunks);
        for (int i = 0; i < chunks.size(); i++)
            saveEmbedding(userId, noteId, "NOTE", null, i, chunks.get(i), vecs.get(i));
    }

    @Transactional
    public int rebuildForLocalDoc(Long userId, String path, String content) {
        embeddingRepository.deleteByLocalPath(userId, path);
        List<String> chunks = chunk(path, content);
        if (chunks.isEmpty()) return 0;
        List<float[]> vecs = embeddingClient.embed(chunks);
        for (int i = 0; i < chunks.size(); i++)
            saveEmbedding(userId, 0L, "LOCAL_DOC", path, i, chunks.get(i), vecs.get(i));
        return chunks.size();
    }

    private void saveEmbedding(Long uid, Long nid, String src, String path,
                               int idx, String content, float[] v) {
        NoteEmbedding e = new NoteEmbedding();
        e.setUserId(uid); e.setNoteId(nid); e.setSource(src); e.setSourcePath(path);
        e.setChunkIdx(idx); e.setContent(content);
        e.setEmbedding(embeddingClient.serialize(v));
        e.setDim(v.length); e.setModel(embeddingClient.modelName());
        embeddingRepository.save(e);
    }

    /** 段落优先 + 滚动切片，带 overlap，单笔记上限 MAX_CHUNKS_PER_NOTE */
    static List<String> chunk(String title, String content) {
        if (content == null) content = "";
        String header = (title == null || title.isBlank()) ? "" : "[标题] " + title + "\n";
        String text = (header + content).trim();
        if (text.isEmpty()) return List.of();
        String[] paras = text.split("\\n{2,}");
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            if (buf.length() + p.length() + 2 <= CHUNK_SIZE) {
                buf.append(p).append("\n\n");
            } else {
                if (buf.length() > 0) { out.add(buf.toString().trim()); buf.setLength(0); }
                if (p.length() <= CHUNK_SIZE) {
                    buf.append(p).append("\n\n");
                } else {
                    for (int i = 0; i < p.length(); i += (CHUNK_SIZE - CHUNK_OVERLAP)) {
                        out.add(p.substring(i, Math.min(p.length(), i + CHUNK_SIZE)));
                        if (out.size() >= MAX_CHUNKS_PER_NOTE) return out;
                    }
                }
            }
            if (out.size() >= MAX_CHUNKS_PER_NOTE) return out;
        }
        if (buf.length() > 0) out.add(buf.toString().trim());
        return out.size() > MAX_CHUNKS_PER_NOTE ? out.subList(0, MAX_CHUNKS_PER_NOTE) : out;
    }
}
```

#### 3.5.1 异步联动

```java
// feature/pkm/event/NotePersistedEvent.java
public class NotePersistedEvent extends ApplicationEvent {
    private final Note note;
    public NotePersistedEvent(Object src, Note n){ super(src); this.note=n; }
    public Note getNote(){ return note; }
}
```

```java
// feature/pkm/listener/NotePersistedListener.java
@Component
public class NotePersistedListener {
    private final NoteIndexService idx;
    public NotePersistedListener(NoteIndexService s){ this.idx = s; }
    @Async @EventListener
    public void onPersisted(NotePersistedEvent ev){
        try { idx.rebuildForNote(ev.getNote()); }
        catch (Exception ex) { System.err.println("[PKM] rebuild failed: " + ex.getMessage()); }
    }
}
```

#### 3.5.2 `NoteService` 增量

```java
// core/service/NoteService.java —— 增字段 + 增方法
private final ApplicationEventPublisher publisher;
public NoteService(NoteRepository r, ApplicationEventPublisher p) {
    this.noteRepository = r; this.publisher = p;
}

public Optional<Note> findByIdForUser(Long id, User user) {
    return noteRepository.findById(id)
            .filter(n -> n.getUser().getId().equals(user.getId()));
}

public Note update(Note n, String title, String content, NoteType type, String tags) {
    if (title != null)   n.setTitle(title);
    if (content != null) n.setContent(content);
    if (type != null)    n.setType(type);
    if (tags != null)    n.setTags(tags);
    Note saved = noteRepository.save(n);
    publisher.publishEvent(new NotePersistedEvent(this, saved));
    return saved;
}

// 在原 create 方法末尾追加一行 publishEvent：
public Note create(User user, String title, String content, NoteType type) {
    Note n = new Note();
    n.setUser(user); n.setTitle(title); n.setContent(content);
    n.setType(type == null ? NoteType.SCRATCH : type);
    Note saved = noteRepository.save(n);
    publisher.publishEvent(new NotePersistedEvent(this, saved));   // ← 新增
    return saved;
}
```

> **完全向后兼容**：`NoteController` 现有 `noteService.create(...)` 调用零改动，自动开始触发索引重建。

---

### 3.6 RAG Hybrid 检索

```java
// feature/pkm/service/RagSearchService.java
@Service
public class RagSearchService {

    public record Hit(String source, Long noteId, String sourcePath,
                      Integer chunkIdx, String content, double score, String reason) {}

    @Value("${pkm.rag.alpha:0.4}")     private double alpha;
    @Value("${pkm.rag.candidates:50}") private int candidates;
    @Value("${pkm.rag.topK:6}")        private int topK;

    private final NoteRepository noteRepository;
    private final NoteEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;

    public RagSearchService(NoteRepository nr, NoteEmbeddingRepository er, EmbeddingClient ec){
        this.noteRepository=nr; this.embeddingRepository=er; this.embeddingClient=ec;
    }

    public List<Hit> search(User user, String query, Integer overrideTopK) {
        int k = overrideTopK == null || overrideTopK <= 0 ? topK : Math.min(overrideTopK, 20);
        Map<String, Hit> merged = new LinkedHashMap<>();

        // ---- 关键字通路（FULLTEXT）----
        try {
            int rank = 0;
            for (Note n : noteRepository.fulltextSearch(user.getId(), query, candidates)) {
                double s = (1.0 - alpha) * (1.0 / (1 + rank++));
                merged.merge("N:" + n.getId() + "#kw",
                        new Hit("NOTE", n.getId(), null, null,
                                preview(n.getContent()), s, "kw"),
                        (a,b) -> b.score > a.score ? b : a);
            }
        } catch (Exception ignore) {}

        // ---- 向量通路 ----
        try {
            float[] qv = embeddingClient.embed(List.of(query)).get(0);
            embeddingRepository.findByUserId(user.getId()).stream().map(e -> {
                float[] v = embeddingClient.deserialize(e.getEmbedding());
                double sim = EmbeddingClient.cosine(qv, v);
                return new Hit(e.getSource(), e.getNoteId(), e.getSourcePath(),
                        e.getChunkIdx(), e.getContent(),
                        alpha * sim, String.format(Locale.ROOT,"vec %.3f", sim));
            }).sorted(Comparator.comparingDouble((Hit h)->-h.score()))
              .limit(candidates)
              .forEach(h -> {
                  String key = ("NOTE".equals(h.source()) ? "N:"+h.noteId() : "L:"+h.sourcePath())
                                + "#" + h.chunkIdx();
                  merged.merge(key, h, (a,b) -> new Hit(
                          a.source(), a.noteId(), a.sourcePath(), a.chunkIdx(),
                          (a.content()==null||a.content().isBlank())?b.content():a.content(),
                          a.score()+b.score(), a.reason()+"+"+b.reason()));
              });
        } catch (Exception ignore) {}

        return merged.values().stream()
                .sorted(Comparator.comparingDouble((Hit h) -> -h.score()))
                .limit(k).toList();
    }

    private static String preview(String s){
        if (s==null) return "";
        return s.length()>200 ? s.substring(0,200)+"..." : s;
    }
}
```

> **设计取舍**：
> - 任一通路异常不影响另一路（向量端 API key 没配也能纯关键字工作）。
> - `alpha` 配置化便于线上调权重。
> - 单用户万级 chunk 全表 cosine ~10ms；超过该量级再换 V2 缓存或 ANN。

---

### 3.7 Web UI

#### 3.7.1 `NoteViewController`

```java
// feature/pkm/controller/NoteViewController.java
@Controller
@RequestMapping("/note")
public class NoteViewController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;
    private final LinkRepository linkRepository;
    private final UserRepository userRepository;
    private final MarkdownRenderer md;

    public NoteViewController(NoteService ns, NoteRepository nr, LinkRepository lr,
                              UserRepository ur, MarkdownRenderer m) {
        this.noteService=ns; this.noteRepository=nr;
        this.linkRepository=lr; this.userRepository=ur; this.md=m;
    }

    @GetMapping
    public String list(Model m, Principal p) {
        User u = userRepository.findByUsername(p.getName()).orElseThrow();
        m.addAttribute("notes", noteService.listVisibleByUser(u));
        return "noteList";
    }

    @GetMapping("/new")
    public String createPage(Model m, @RequestParam(required=false) String title) {
        Note n = new Note();
        if (title != null) n.setTitle(title);
        m.addAttribute("note", n); m.addAttribute("isNew", true);
        return "noteEdit";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model m, Principal p) {
        User u = userRepository.findByUsername(p.getName()).orElseThrow();
        Note n = noteService.findByIdForUser(id, u).orElseThrow();
        m.addAttribute("note", n);
        m.addAttribute("html", md.render(n.getContent()));
        m.addAttribute("backlinks",
            linkRepository.findByTargetTypeAndTargetId(Link.LinkTargetType.NOTE, id).stream()
                .filter(l -> l.getSourceType() == Link.LinkSourceType.NOTE)
                .map(l -> noteRepository.findById(l.getSourceId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(x -> x.getUser().getId().equals(u.getId()))
                .toList());
        return "noteView";
    }

    @GetMapping("/{id}/edit")
    public String editPage(@PathVariable Long id, Model m, Principal p) {
        User u = userRepository.findByUsername(p.getName()).orElseThrow();
        m.addAttribute("note", noteService.findByIdForUser(id, u).orElseThrow());
        m.addAttribute("isNew", false);
        return "noteEdit";
    }

    @PostMapping("/save")
    public RedirectView save(@RequestParam(required=false) Long id,
                             @RequestParam String title,
                             @RequestParam String content,
                             @RequestParam(required=false) String type,
                             @RequestParam(required=false) String tags,
                             Principal p) {
        User u = userRepository.findByUsername(p.getName()).orElseThrow();
        NoteType nt = parseType(type);
        Note saved;
        if (id == null) {
            saved = noteService.create(u, title, content, nt);
            if (tags != null) noteService.update(saved, null, null, null, tags);
        } else {
            Note n = noteService.findByIdForUser(id, u).orElseThrow();
            saved = noteService.update(n, title, content, nt, tags);
        }
        return new RedirectView("/note/" + saved.getId());
    }

    @PostMapping(value="/preview", produces=MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String preview(@RequestBody Map<String,String> body) {
        return md.render(body.getOrDefault("content",""));
    }

    @GetMapping("/by-title/{title}")
    public Object byTitle(@PathVariable String title, Principal p) {
        User u = userRepository.findByUsername(p.getName()).orElseThrow();
        return noteRepository.findFirstByUserAndTitle(u, title)
            .<Object>map(n -> new RedirectView("/note/" + n.getId()))
            .orElseGet(() -> new RedirectView("/note/new?title="
                + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8)));
    }

    private NoteType parseType(String s){
        if (s==null||s.isBlank()) return NoteType.SCRATCH;
        try { NoteType t = NoteType.valueOf(s); return t==NoteType.AGENT_MEMO?NoteType.SCRATCH:t; }
        catch (Exception e) { return NoteType.SCRATCH; }
    }
}
```

#### 3.7.2 模板核心骨架

```html
<!-- noteList.html -->
<div class="note-list">
  <a href="/note/new" class="btn">+ 新建笔记</a>
  <ul>
    <li th:each="n : ${notes}">
      <a th:href="@{|/note/${n.id}|}" th:text="${n.title}"></a>
      <span class="tags" th:if="${n.tags}" th:text="${n.tags}"></span>
    </li>
  </ul>
</div>
<div th:replace="~{fragments/agent-panel :: panel}"></div>
```

```html
<!-- noteEdit.html：左写右预览 -->
<form action="/note/save" method="post" id="f">
  <input type="hidden" name="id" th:value="${note.id}" th:if="${!isNew}"/>
  <input name="title" th:value="${note.title}" placeholder="标题" required/>
  <input name="tags"  th:value="${note.tags}"  placeholder="标签：spring,ai"/>
  <select name="type">
    <option value="SCRATCH">SCRATCH</option>
    <option value="LEARNING">LEARNING</option>
    <option value="PROJECT">PROJECT</option>
    <option value="RETROSPECTIVE">RETROSPECTIVE</option>
  </select>
  <div class="split">
    <textarea name="content" id="md" placeholder="支持 Markdown，[[Title]] 双链"
              th:text="${note.content}"></textarea>
    <div id="preview"></div>
  </div>
  <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
  <button type="submit">保存</button>
</form>
<script>
  const md=document.getElementById('md'), pv=document.getElementById('preview');
  const csrf=document.querySelector('input[name=_csrf]').value;
  async function refresh() {
    const r = await fetch('/note/preview', {
      method:'POST',
      headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},
      body: JSON.stringify({content: md.value})
    });
    pv.innerHTML = await r.text();
  }
  md.addEventListener('input', () => { clearTimeout(window._t); window._t=setTimeout(refresh,200); });
  refresh();
</script>
<div th:replace="~{fragments/agent-panel :: panel}"></div>
```

```html
<!-- noteView.html -->
<article th:utext="${html}"></article>
<aside class="backlinks" th:if="${!backlinks.isEmpty()}">
  <h4>反向链接</h4>
  <ul><li th:each="b : ${backlinks}">
    <a th:href="@{|/note/${b.id}|}" th:text="${b.title}"></a>
  </li></ul>
</aside>
<a th:href="@{|/note/${note.id}/edit|}">编辑</a>
<div th:replace="~{fragments/agent-panel :: panel}"></div>
```

> 在 `dashboard.html` 顶部加一个 `<a href="/note">笔记</a>` 入口。

---

### 3.8 Agent 工具升级 —— `KnowledgeTools`

```java
// feature/agent/tool/impl/KnowledgeTools.java
@Component
public class KnowledgeTools {

    private final RagSearchService rag;
    private final NoteIndexService indexService;
    private final NoteRepository noteRepository;
    private final LinkRepository linkRepository;
    private final LocalBridgeProxy localBridge;

    public KnowledgeTools(RagSearchService r, NoteIndexService i,
                          NoteRepository nr, LinkRepository lr, LocalBridgeProxy lb) {
        this.rag=r; this.indexService=i;
        this.noteRepository=nr; this.linkRepository=lr; this.localBridge=lb;
    }

    @AgentTool(name = "kb.semantic_search", tags = {"kb","read"},
               description = "在用户笔记 + 已摄取本地文档中做语义检索。" +
                             "涉及'我之前/我的笔记/我学过'等问题时必须先调用本工具。")
    public List<RagSearchService.Hit> semanticSearch(
            @ToolParam(value="query", desc="自然语言查询", required=true) String query,
            @ToolParam(value="topK",  desc="返回条数（1~20，默认 6）") Integer topK) {
        return rag.search(AgentContext.requireUser(), query, topK);
    }

    @AgentTool(name = "kb.lookup_by_title", tags = {"kb","read"},
               description = "按精确标题取一篇笔记的全文")
    public Map<String, Object> lookupByTitle(
            @ToolParam(value="title", desc="笔记标题（精确）", required=true) String title) {
        User u = AgentContext.requireUser();
        return noteRepository.findFirstByUserAndTitle(u, title)
                .<Map<String,Object>>map(n -> Map.of(
                        "id", n.getId(), "title", n.getTitle(),
                        "tags", n.getTags()==null?"":n.getTags(),
                        "type", n.getType()==null?"SCRATCH":n.getType().name(),
                        "content", n.getContent()))
                .orElse(Map.of("error","NOT_FOUND"));
    }

    @AgentTool(name = "kb.list_backlinks", tags = {"kb","read"},
               description = "列出指向该标题笔记的反向链接")
    public List<Map<String,Object>> listBacklinks(
            @ToolParam(value="title", desc="目标笔记标题", required=true) String title) {
        User u = AgentContext.requireUser();
        return noteRepository.findFirstByUserAndTitle(u, title)
            .map(n -> linkRepository
                .findByTargetTypeAndTargetId(Link.LinkTargetType.NOTE, n.getId()).stream()
                .filter(l -> l.getSourceType()==Link.LinkSourceType.NOTE)
                .map(l -> noteRepository.findById(l.getSourceId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(x -> x.getUser().getId().equals(u.getId()))
                .map(x -> (Map<String,Object>) Map.of("id", x.getId(), "title", x.getTitle()))
                .toList())
            .orElse(List.of());
    }

    @AgentTool(name = "kb.ingest_local_doc", tags = {"kb","write","local"}, requiresConfirm = true,
               description = "把本地 md/txt/pdf 摄取进个人知识库")
    public Map<String,Object> ingestLocal(
            @ToolParam(value="path", desc="绝对路径（受 Electron 白名单约束）", required=true) String path)
            throws Exception {
        User u = AgentContext.requireUser();
        String content = path.toLowerCase().endsWith(".pdf")
                ? localBridge.call("read_pdf",  Map.of("path", path)).path("content").asText()
                : localBridge.call("read_file", Map.of("path", path)).path("content").asText();
        int chunks = indexService.rebuildForLocalDoc(u.getId(), path, content);
        return Map.of("path", path, "chunks", chunks);
    }
}
```

#### 3.8.1 `PromptBuilder` 系统 Prompt 增量

在原 system prompt 中追加：

```text
【知识检索原则】
- 涉及"我"、"我的笔记/项目/经验"、"我之前学过 X"、"上次我们说过 Y" 等表述时，
  必须先调用 kb.semantic_search 检索；
- 命中的最高 score < 0.4 时视为弱相关，最终回答需明示
  "未找到强相关笔记，以下基于通用知识"；
- 引用笔记请使用 [[标题]] 写法，便于用户点击跳转。
```

`tagFilter` 中 `chat` 模式不限制；新增可选模式 `learn = {kb,note,read}` 用于"问我以前学过什么"场景。

---

### 3.9 Electron 端

后端 `kb.ingest_local_doc` 通过 `LocalBridgeProxy` 复用现有 `local.read_file/read_pdf`，**Electron 主进程零修改**。仅在 `permission-config.json` 加：

```json
{
  "allowDirs":  ["D:/notes", "D:/learning"],
  "denyDirs":   ["C:/Windows", "C:/Program Files"],
  "allowExt":   ["md","txt","json","yml","yaml","csv","log","pdf"],
  "maxFileBytes": 4194304,
  "ingestBatchMaxFiles": 50
}
```

> 用户在抽屉里说"把 D:/learning/spring-ai 下所有 md 摄取进知识库"，Agent 用 `local.list_dir` + `kb.ingest_local_doc`（首次允许后批量放行）即可批量入库。

---

## 4. 配置增量

```properties
# application.properties

# Embedding（默认指向硅基流动免费 BGE-M3，按需替换）
agent.embedding.model=BAAI/bge-m3
agent.embedding.base-url=https://api.siliconflow.cn
agent.embedding.api-key=${EMBED_API_KEY:}

# RAG
pkm.rag.alpha=0.4
pkm.rag.candidates=50
pkm.rag.topK=6

# 异步
spring.task.execution.pool.core-size=2
spring.task.execution.pool.max-size=4
spring.task.execution.thread-name-prefix=pkm-
```

主类启用异步：

```java
@EnableAsync
@SpringBootApplication
public class MemorandumApplication { ... }
```

`WebSecurityConfig` CSRF 豁免列表加 `/note/preview`（或前端用 `X-CSRF-TOKEN`，已写在示例里）。

---

## 5. 安全 / 隐私清单

| 风险 | 措施 | 落点 |
|---|---|---|
| 笔记内容 XSS | `escapeHtml(true)` + Thymeleaf `th:utext` 仅渲染 HTML（不直出原文） | `MarkdownRenderer` |
| 越权读他人笔记 / 反链 | controller / tool 入口 `findByUser` / `requireUser` 双重过滤 | `NoteViewController`、`KnowledgeTools` |
| 嵌入 API 隐私 | embedding 与 chat 独立配置，可换本地 Ollama | `agent.embedding.base-url` |
| Agent 编造 `[[Title]]` 跳转 | `/note/by-title/...` 未命中时跳"以该标题创建" | `NoteViewController.byTitle` |
| 索引爆 | `MAX_CHUNKS_PER_NOTE=64` + `ingestBatchMaxFiles=50` | `NoteIndexService` |
| 异步索引失败影响业务 | 监听器 `try/catch`，不抛 | `NotePersistedListener` |
| API Key 缺失全瘫 | 关键字通路独立可用，向量异常自动降级 | `RagSearchService` |
| 用户删笔记后向量残留 | `deleteByNoteId` 在更新前先清；删除接口 V2 补 hook | `NoteIndexService` |

---

## 6. 工具变更清单

新增 4 个，原 16 个不动：

| 工具名 | tags | requiresConfirm | 说明 |
|---|---|---|---|
| `kb.semantic_search` | kb,read | ❌ | hybrid 检索个人知识库 |
| `kb.lookup_by_title` | kb,read | ❌ | 精确取整篇笔记 |
| `kb.list_backlinks` | kb,read | ❌ | 反向链接 |
| `kb.ingest_local_doc` | kb,write,local | ✅ | 摄取本地 md/txt/pdf |

---

## 7. 分阶段落地（按天）

### Stage 1 — 笔记 PKM（5~7 天）

| Day | 任务 | 验收 |
|---|---|---|
| 1 | `V4__pkm_rag.sql` + `Note.tags` + `NoteEmbedding` + Repository | 表结构生效 |
| 2 | `MarkdownRenderer` + commonmark 依赖 + `/note/preview` | curl 渲染验证 |
| 3 | `NoteService.update / findByIdForUser` + `NoteViewController` | 浏览器能创 / 看 / 改笔记 |
| 4 | `noteList/Edit/View.html` + CSS + dashboard 入口 | 视觉可用，[[Title]] 可跳 |
| 5 | `NoteLinkParser` + `NoteIndexService.rebuildForNote`（仅 link，不含向量）+ `@Async` 监听 | 保存后 `link` 表自动新增；反链面板显示 |
| 6-7 | 单测 + UI 打磨 | PKM 子目标交付 |

### Stage 2 — RAG 通路（4~6 天）

| Day | 任务 | 验收 |
|---|---|---|
| 1 | `LlmGateway.generateEmbedding` + `EmbeddingClient` + 命令行测试 | 控制台打印向量维度 |
| 2 | `NoteIndexService` 接入 embedding；`chunk` 单测 | 保存笔记后 `note_embedding` 行数符合预期 |
| 3 | `RagSearchService` 关键字 + 向量 + Hybrid | `/note/search?q=` 调试接口返回合理排序 |
| 4 | `KnowledgeTools.semantic_search / lookup_by_title / list_backlinks` + Prompt 增量 | Agent 抽屉能命中并以 [[标题]] 引用 |
| 5 | `kb.ingest_local_doc` + permission-config 校验 | 摄取一份本地 md，Agent 检索能命中其 chunk |
| 6 | 端到端联调 + 降级测试（关闭 EMBED_API_KEY 看是否仍能纯关键字工作） | 验收 §8 三条 Demo |

### Stage 3 — 完善与扩展（按需 3~5 天）

- `kb.delete_local_doc`（按 path 删除已摄取）
- 笔记列表搜索框接 `RagSearchService`
- 标签云 / 标签页
- 复盘笔记自动生成时也调用 `RagSearchService` 取近期笔记当上下文
- V2 上 LRU 内存缓存所有用户向量，避免每次 search 全表读

---

## 8. 验收 Demo

### Demo A — 双向链接 PKM
> 创建笔记《Spring AI 入门》，正文写 `相关：[[RAG 基础]]`。
> 创建另一篇《RAG 基础》。回到《Spring AI 入门》，点击 `[[RAG 基础]]` 跳转 →
> 在《RAG 基础》页面看到反向链接列出《Spring AI 入门》。

### Demo B — RAG 命中并引用
> 用户提问："我之前的项目里是怎么做 RAG 的？"
> Agent → `kb.semantic_search(query="RAG 项目实现")` → 命中《RAG 基础》《Spring AI 入门》 →
> 自然语言回答中出现 `参考你之前的笔记 [[RAG 基础]]：…`。
> 验收点：score 与命中片段在抽屉的 toolResult 卡片可见。

### Demo C — 本地资料摄取 → 学习闭环
> 用户："把 D:/learning/spring-ai 下所有 md 摄取进知识库，再帮我列出我没掌握的章节，生成下周计划。"
> Agent → `local.list_dir`（首次允许）→ 多次 `kb.ingest_local_doc`（首次允许后批量放行）→
> `kb.semantic_search` 多轮探查 → `planner.draft_goal_plan` → 用户确认 → `planner.apply_goal_plan`。
> 验收点：`note_embedding` 表中 `source='LOCAL_DOC'` 的记录数等于摄取的文件数 × 平均 chunk 数。

---

## 9. 创新性叙事（简历 / 面试三段）

1. **零外部组件的 RAG**：在 Spring Boot + MySQL 单体上，借 `MEDIUMTEXT(JSON)` + ngram FULLTEXT 实现 hybrid 检索；用户级数据量下全表 cosine ~10ms，**省掉 pgvector / Elasticsearch 整套依赖**，部署难度与原本一致。
2. **复用 Link 表的双向链接**：原 `Link` 表只服务 Task↔Goal，本次零结构变更扩展到 Note↔Note，**用一张表撑起 OKR + PKM 两套域**，体现"核心模型极简、能力插件式扩展"的设计延续性。
3. **事件驱动的索引联动**：`NotePersistedEvent` 与既有 `TaskCreatedEvent / GoalProgressEvent` 同构，让"知识入库"与"目标推进"在事件总线上对齐——**Agent 一次写笔记，既触发索引重建又可级联推进目标**，这是通用 PKM 工具（Obsidian / Notion）做不到的、属于本工程的红利。

---

## 10. 风险与备选

| 风险 | 概率 | 备选 |
|---|---|---|
| Embedding API 不稳定 / 限频 | 中 | 切换为本地 Ollama（`agent.embedding.base-url=http://localhost:11434`）+ `nomic-embed-text` |
| 单用户向量过万拖慢检索 | 低 | V2 加 `Caffeine` LRU 缓存 `userId → List<float[]>`；再大切 sqlite-vec 旁路 |
| Markdown 渲染被 XSS | 低 | `escapeHtml(true)` + 只允许通过表单提交，不接受 raw HTML |
| `[[Title]]` 写错跳到陌生页面 | 中 | `byTitle` 兜底跳"以此标题创建"，不报错 |
| 异步索引滞后导致刚保存的笔记搜不到 | 中 | UI 文案"索引中"；前端 30s 内对刚保存笔记发 `kb.lookup_by_title` 兜底 |

---

## 11. 与原 `Agent实现方案.md` 的关系

- 不修改原方案任何条款；新增的 `kb.*` 工具沿用现有 `@AgentTool / ToolRegistry` 体系，自动出现在 `PromptBuilder.exportSchemas` 输出中。
- `LongTermMemoryService` 现有 `AGENT_MEMO` 笔记会同样进入索引（NOTE 源），后续 Agent 可主动检索"过去会话中提炼的画像"。
- 与 `AgentOrchestrator` 主循环零耦合：本方案不改 ReAct 步进、不改 WebSocket 协议、不改前端抽屉，只增加工具与 Prompt 提示。
