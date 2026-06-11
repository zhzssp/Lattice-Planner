package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zhzssp.memorandum.entity.NoteEmbedding;

import java.util.List;

/**
 * NoteEmbedding 仓储。
 * Stage 2：RAG 通路接通；
 * Stage 3：新增已摄取本地文档的反向查询（按 path 分组统计 chunks），用于
 *         kb.list_ingested_docs / kb.delete_local_doc 工具与日后 UI 管理面。
 */
@Repository
public interface NoteEmbeddingRepository extends JpaRepository<NoteEmbedding, Long> {

    List<NoteEmbedding> findByUserId(Long userId);

    @Modifying
    @Query("delete from NoteEmbedding e where e.noteId = :noteId")
    void deleteByNoteId(@Param("noteId") Long noteId);

    @Modifying
    @Query("delete from NoteEmbedding e where e.userId = :userId and e.source = 'LOCAL_DOC' and e.sourcePath = :path")
    void deleteByLocalPath(@Param("userId") Long userId, @Param("path") String path);

    /**
     * 统计某条本地文档已切出的 chunk 数（用于反摄取前/后对比，给 LLM 反馈条数）。
     */
    @Query("select count(e) from NoteEmbedding e " +
            "where e.userId = :userId and e.source = 'LOCAL_DOC' and e.sourcePath = :path")
    int countByLocalPath(@Param("userId") Long userId, @Param("path") String path);

    /**
     * 列出当前用户所有已摄取本地文档（按 path 分组，附带 chunk 总数与最近一次摄取时间）。
     * 列名顺序：[sourcePath, chunks, latestCreatedAt]。
     */
    @Query("select e.sourcePath as path, count(e) as chunks, max(e.createdAt) as latest " +
            "from NoteEmbedding e " +
            "where e.userId = :userId and e.source = 'LOCAL_DOC' " +
            "group by e.sourcePath " +
            "order by max(e.createdAt) desc")
    List<LocalDocSummary> listLocalDocs(@Param("userId") Long userId);

    /** Spring Data JPA Projection：只接收聚合查询的三个字段。 */
    interface LocalDocSummary {
        String getPath();
        long getChunks();
        java.time.LocalDateTime getLatest();
    }
}
