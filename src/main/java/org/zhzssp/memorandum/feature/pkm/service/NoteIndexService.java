package org.zhzssp.memorandum.feature.pkm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteEmbedding;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;
import org.zhzssp.memorandum.repository.NoteRepository;
import org.zhzssp.memorandum.feature.pkm.serving.QueryResultCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 笔记保存后的索引联动核心。
 *
 * Stage 1：仅重建 NOTE→NOTE 双向链接（落 link 表）。
 * Stage 2：重建 chunk → embedding → note_embedding；新增 rebuildForLocalDoc 供 kb.ingest_local_doc 使用。
 *
 * 设计要点：
 * 1. 复用 Link 表表达 NOTE→NOTE，无新表；
 * 2. 重建时先清空当前笔记的 NOTE→NOTE 出链，再按当前 [[Title]] 全量插入；
 * 3. 对端笔记不存在则跳过——用户后续创建该标题笔记并保存其本身或其他指向该
 *    标题的笔记时，会自然补全双链；
 * 4. embedding 调用失败（API key 缺失 / 限频 / 网络）不抛，记日志后跳过——
 *    业务保存仍成功，RagSearchService 仅会少一路向量结果，关键字通路仍可用。
 */
@Service
public class NoteIndexService {

    private static final Logger log = LoggerFactory.getLogger(NoteIndexService.class);

    /** 单 chunk 字符数上限（中文按字符数；600 字 ≈ 1 段长文档/2-3 段笔记） */
    static final int CHUNK_SIZE = 600;
    /** 长段落滚动切片时的字符 overlap，避免边界处语义被截断 */
    static final int CHUNK_OVERLAP = 100;
    /** 单笔记最多产生的 chunk 数，防御性上限避免恶意巨长正文打满 embedding 配额 */
    static final int MAX_CHUNKS_PER_NOTE = 64;

    private final NoteRepository noteRepository;
    private final NoteEmbeddingRepository embeddingRepository;
    private final LinkRepository linkRepository;
    private final NoteLinkParser linkParser;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingVectorCache vectorCache;
    private final QueryResultCache queryResultCache;

    public NoteIndexService(NoteRepository noteRepository,
                            NoteEmbeddingRepository embeddingRepository,
                            LinkRepository linkRepository,
                            NoteLinkParser linkParser,
                            EmbeddingClient embeddingClient,
                            EmbeddingVectorCache vectorCache,
                            QueryResultCache queryResultCache) {
        this.noteRepository = noteRepository;
        this.embeddingRepository = embeddingRepository;
        this.linkRepository = linkRepository;
        this.linkParser = linkParser;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
        this.queryResultCache = queryResultCache;
    }

    @Transactional
    public void rebuildForNote(Note note) {
        if (note == null || note.getId() == null || note.getUser() == null) return;
        // AGENT_MEMO 是 Agent 长期记忆条目，仅供注入对话 Prompt，绝不能进入个人知识库向量检索，
        // 否则 kb.semantic_search 会混入记忆碎片污染「我的笔记」召回。此处从源头跳过向量化，
        // 同时省去一次 embedding 调用成本。仍允许其参与 NOTE→NOTE 双链（正文一般无 [[]]，等价 no-op）。
        if (note.getType() == NoteType.AGENT_MEMO) {
            rebuildNoteLinks(note);
            return;
        }
        rebuildNoteLinks(note);
        rebuildEmbeddings(note);
        // Stage 3：写后失效用户向量缓存，确保下一次 RAG 查询读到最新向量
        vectorCache.invalidate(note.getUser().getId());
        // RAG Serving：笔记变更同时失效查询结果缓存
        queryResultCache.invalidate(note.getUser().getId());
    }

    /** NOTE→NOTE 双向链接重建。 */
    private void rebuildNoteLinks(Note note) {
        Long noteId = note.getId();
        User owner = note.getUser();

        // 1) 清空当前笔记的 NOTE→NOTE 出链（保留 NOTE→TASK / NOTE→GOAL 不动）
        for (Link l : linkRepository.findBySourceTypeAndSourceId(Link.LinkSourceType.NOTE, noteId)) {
            if (l.getTargetType() == Link.LinkTargetType.NOTE) {
                linkRepository.delete(l);
            }
        }

        // 2) 解析当前正文中的 [[Title]]，对命中的对端笔记落 NOTE→NOTE
        for (String title : linkParser.extractLinkedTitles(note.getContent())) {
            noteRepository.findFirstByUserAndTitle(owner, title).ifPresent(target -> {
                if (Objects.equals(target.getId(), noteId)) return;     // 不允许自链
                Link l = new Link();
                l.setSourceType(Link.LinkSourceType.NOTE);
                l.setSourceId(noteId);
                l.setTargetType(Link.LinkTargetType.NOTE);
                l.setTargetId(target.getId());
                linkRepository.save(l);
            });
        }
    }

    /** 笔记向量重建：先全量删旧 chunk，再切片 → 调用 embedding → 落库。 */
    private void rebuildEmbeddings(Note note) {
        Long noteId = note.getId();
        Long userId = note.getUser().getId();

        // 1) 清空旧 chunk（删除后 commit 前的查询不会受影响——同事务内一致性）
        embeddingRepository.deleteByNoteId(noteId);

        // 2) 切片
        ChunkResult cr = chunk(note.getTitle(), note.getContent(), MAX_CHUNKS_PER_NOTE);
        List<String> chunks = cr.chunks();
        if (chunks.isEmpty()) return;
        warnIfTruncated("笔记 #" + noteId, cr);

        // 3) 调用 embedding（失败降级：仅记录，不抛——业务保存已成功）
        List<float[]> vecs;
        try {
            vecs = embeddingClient.embed(chunks);
        } catch (Exception ex) {
            log.warn("[PKM] 笔记 #{} embedding 失败，跳过向量索引（关键字检索仍可用）：{}",
                    noteId, ex.getMessage());
            return;
        }
        if (vecs.size() != chunks.size()) {
            log.warn("[PKM] embedding 返回向量数 {} 与 chunk 数 {} 不一致，丢弃本次向量索引",
                    vecs.size(), chunks.size());
            return;
        }

        // 4) 落库
        for (int i = 0; i < chunks.size(); i++) {
            saveEmbedding(userId, noteId, "NOTE", null, i, chunks.get(i), vecs.get(i));
        }
    }

    /**
     * 反摄取本地文档（kb.delete_local_doc 使用）。
     * 删除当前用户名下所有 source=LOCAL_DOC 且 sourcePath=path 的 chunk，并返回删除条数。
     *
     * Stage 3 新增。失效 V2 向量缓存（如启用）由调用链上的 listener / orchestrator 保证。
     */
    @Transactional
    public int deleteLocalDoc(Long userId, String path) {
        if (userId == null || path == null || path.isBlank()) return 0;
        int before = embeddingRepository.countByLocalPath(userId, path);
        if (before == 0) return 0;
        embeddingRepository.deleteByLocalPath(userId, path);
        vectorCache.invalidate(userId);
        queryResultCache.invalidate(userId);
        return before;
    }

    /**
     * 摄取一份本地文档（kb.ingest_local_doc 使用）。
     * 同 path 已存在则先按 (userId, sourcePath) 全量删除，再重新索引。
     *
     * @return 实际入库 chunk 数；0 表示未入库（空文档 / embedding 失败）。
     */
    @Transactional
    public int rebuildForLocalDoc(Long userId, String path, String content) {
        embeddingRepository.deleteByLocalPath(userId, path);
        ChunkResult cr = chunk(path, content, MAX_CHUNKS_PER_NOTE);
        List<String> chunks = cr.chunks();
        if (chunks.isEmpty()) return 0;
        warnIfTruncated("本地文档 " + path, cr);
        List<float[]> vecs;
        try {
            vecs = embeddingClient.embed(chunks);
        } catch (Exception ex) {
            log.warn("[PKM] 本地文档 {} embedding 失败：{}", path, ex.getMessage());
            return 0;
        }
        if (vecs.size() != chunks.size()) return 0;
        for (int i = 0; i < chunks.size(); i++) {
            saveEmbedding(userId, 0L, "LOCAL_DOC", path, i, chunks.get(i), vecs.get(i));
        }
        // Stage 3：摄取完成失效缓存，使新向量立即可被检索
        vectorCache.invalidate(userId);
        queryResultCache.invalidate(userId);
        return chunks.size();
    }

    /**
     * 切片触顶告警（P0a）。
     *
     * <p>为什么必须记日志：截断意味着<strong>后半部分内容永远不会被检索到</strong>，
     * 而 Agent 仍会声称「已检索知识库」。静默截断会让用户误判「库里没有」，
     * 比检索失败更具误导性。</p>
     */
    private void warnIfTruncated(String label, ChunkResult cr) {
        if (!cr.truncated()) return;
        log.warn("[PKM] {} 切片触顶：共 {} 字符，仅索引 {} 字符（约丢失 {}%），chunk={} 已达上限。"
                        + "该内容的后半部分将无法被检索到。",
                label, cr.charsTotal(), cr.charsUsed(),
                Math.round(cr.lossRatio() * 100), cr.chunks().size());
    }

    private void saveEmbedding(Long uid, Long nid, String src, String path,
                               int idx, String content, float[] v) {        NoteEmbedding e = new NoteEmbedding();
        e.setUserId(uid);
        e.setNoteId(nid);
        e.setSource(src);
        e.setSourcePath(path);
        e.setChunkIdx(idx);
        e.setContent(content);
        e.setEmbedding(embeddingClient.serialize(v));
        e.setDim(v.length);
        e.setModel(embeddingClient.modelName());
        embeddingRepository.save(e);
    }

    /**
     * 段落优先 + 滚动切片，带 overlap，单笔记上限 {@link #MAX_CHUNKS_PER_NOTE}。
     *
     * <p>保留 2 参签名，行为与改造前<strong>逐字节一致</strong>（笔记路径不受 P0a 影响）。</p>
     *
     * 包级访问以便单测覆盖。
     */
    static List<String> chunk(String title, String content) {
        return chunk(title, content, MAX_CHUNKS_PER_NOTE).chunks();
    }

    /**
     * 切片结果：内容 + <strong>是否触顶被截断</strong>。
     *
     * <p>P0a 修复的核心：原实现触顶时直接 {@code return out}，
     * 调用方与 LLM 都<strong>无从得知内容已丢失</strong>。
     * 一篇 107K 字符的文档在 64 chunk 上限下只有前 36% 被索引，
     * 检索时表现为「库里没有」——比完全没有检索更具误导性。</p>
     *
     * <p>延续项目既有的「截断不可静默」原则（同方案 L 的粘性降级标记）：
     * 截断事实必须能被上层观察到并明示。</p>
     *
     * @param chunks     切片内容
     * @param truncated  是否因触达 maxChunks 上限而丢弃了后续内容
     * @param charsTotal 输入总字符数（用于估算丢失比例）
     * @param charsUsed  实际被切片覆盖的字符数
     */
    record ChunkResult(List<String> chunks, boolean truncated, int charsTotal, int charsUsed) {
        /** 被丢弃内容的字符占比（0.0 = 完整）。 */
        double lossRatio() {
            if (charsTotal <= 0) return 0.0;
            return Math.max(0.0, 1.0 - (double) charsUsed / charsTotal);
        }
    }

    /**
     * 段落优先 + 滚动切片，带 overlap，上限由调用方指定。
     *
     * 切片策略：
     *  - 以连续 2 个换行视为段落分隔；
     *  - 同一段落能拼入当前 buffer（不超 CHUNK_SIZE）则合并，否则 flush；
     *  - 单段落本身超过 CHUNK_SIZE 时按 (CHUNK_SIZE - CHUNK_OVERLAP) 步长滑窗切；
     *  - 标题作为 "[标题] xxx" 注入正文头，避免短笔记切出空 chunk 同时让向量更紧贴主题。
     *
     * @param maxChunks chunk 数上限；笔记传 {@link #MAX_CHUNKS_PER_NOTE}，
     *                  Git 文档传 {@code codex.index.max-chunks-per-document}（默认 400）
     */
    static ChunkResult chunk(String title, String content, int maxChunks) {
        if (content == null) content = "";
        String header = (title == null || title.isBlank()) ? "" : "[标题] " + title + "\n";
        String text = (header + content).trim();
        if (text.isEmpty()) return new ChunkResult(List.of(), false, 0, 0);

        int limit = Math.max(1, maxChunks);
        int charsTotal = text.length();
        String[] paras = text.split("\\n{2,}");
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String p : paras) {
            if (p == null) continue;
            String para = p.trim();
            if (para.isEmpty()) continue;

            if (buf.length() + para.length() + 2 <= CHUNK_SIZE) {
                if (buf.length() > 0) buf.append("\n\n");
                buf.append(para);
            } else {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                    if (out.size() >= limit) return truncatedResult(out, charsTotal);
                }
                if (para.length() <= CHUNK_SIZE) {
                    buf.append(para);
                } else {
                    int step = CHUNK_SIZE - CHUNK_OVERLAP;
                    for (int i = 0; i < para.length(); i += step) {
                        out.add(para.substring(i, Math.min(para.length(), i + CHUNK_SIZE)));
                        if (out.size() >= limit) return truncatedResult(out, charsTotal);
                    }
                }
            }
            if (out.size() >= limit) return truncatedResult(out, charsTotal);
        }
        if (buf.length() > 0) out.add(buf.toString());
        if (out.size() > limit) {
            return truncatedResult(new ArrayList<>(out.subList(0, limit)), charsTotal);
        }
        return new ChunkResult(out, false, charsTotal, charsTotal);
    }

    /** 触顶收尾：标记 truncated 并统计已覆盖字符数。 */
    private static ChunkResult truncatedResult(List<String> out, int charsTotal) {
        int used = 0;
        for (String c : out) used += c.length();
        return new ChunkResult(out, true, charsTotal, Math.min(used, charsTotal));
    }
}
