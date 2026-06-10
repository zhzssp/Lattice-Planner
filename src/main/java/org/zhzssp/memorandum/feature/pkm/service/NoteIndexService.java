package org.zhzssp.memorandum.feature.pkm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteEmbedding;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;
import org.zhzssp.memorandum.repository.NoteRepository;

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

    public NoteIndexService(NoteRepository noteRepository,
                            NoteEmbeddingRepository embeddingRepository,
                            LinkRepository linkRepository,
                            NoteLinkParser linkParser,
                            EmbeddingClient embeddingClient,
                            EmbeddingVectorCache vectorCache) {
        this.noteRepository = noteRepository;
        this.embeddingRepository = embeddingRepository;
        this.linkRepository = linkRepository;
        this.linkParser = linkParser;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
    }

    @Transactional
    public void rebuildForNote(Note note) {
        if (note == null || note.getId() == null || note.getUser() == null) return;
        rebuildNoteLinks(note);
        rebuildEmbeddings(note);
        // Stage 3：写后失效用户向量缓存，确保下一次 RAG 查询读到最新向量
        vectorCache.invalidate(note.getUser().getId());
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
        List<String> chunks = chunk(note.getTitle(), note.getContent());
        if (chunks.isEmpty()) return;

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
        List<String> chunks = chunk(path, content);
        if (chunks.isEmpty()) return 0;
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
        return chunks.size();
    }

    private void saveEmbedding(Long uid, Long nid, String src, String path,
                               int idx, String content, float[] v) {
        NoteEmbedding e = new NoteEmbedding();
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
     * 段落优先 + 滚动切片，带 overlap，单笔记上限 MAX_CHUNKS_PER_NOTE。
     *
     * 切片策略：
     *  - 以连续 2 个换行视为段落分隔；
     *  - 同一段落能拼入当前 buffer（不超 CHUNK_SIZE）则合并，否则 flush；
     *  - 单段落本身超过 CHUNK_SIZE 时按 (CHUNK_SIZE - CHUNK_OVERLAP) 步长滑窗切；
     *  - 标题作为 "[标题] xxx" 注入正文头，避免短笔记切出空 chunk 同时让向量更紧贴主题。
     *
     * 包级访问以便单测覆盖。
     */
    static List<String> chunk(String title, String content) {
        if (content == null) content = "";
        String header = (title == null || title.isBlank()) ? "" : "[标题] " + title + "\n";
        String text = (header + content).trim();
        if (text.isEmpty()) return List.of();

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
                    if (out.size() >= MAX_CHUNKS_PER_NOTE) return out;
                }
                if (para.length() <= CHUNK_SIZE) {
                    buf.append(para);
                } else {
                    int step = CHUNK_SIZE - CHUNK_OVERLAP;
                    for (int i = 0; i < para.length(); i += step) {
                        out.add(para.substring(i, Math.min(para.length(), i + CHUNK_SIZE)));
                        if (out.size() >= MAX_CHUNKS_PER_NOTE) return out;
                    }
                }
            }
            if (out.size() >= MAX_CHUNKS_PER_NOTE) return out;
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out.size() > MAX_CHUNKS_PER_NOTE ? out.subList(0, MAX_CHUNKS_PER_NOTE) : out;
    }
}
