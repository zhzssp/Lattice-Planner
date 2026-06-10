package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 笔记 / 本地文档 切片向量索引。
 *
 * V4 PKM-RAG 新增。embedding 以 JSON 数组（MEDIUMTEXT）存储，
 * 用户级数据量内（万级 chunk × 1024 dim）应用层全表 cosine 即可，
 * 不依赖 pgvector / Elasticsearch。
 */
@Entity
@Table(name = "note_embedding",
        indexes = {
                @Index(name = "idx_user", columnList = "user_id"),
                @Index(name = "idx_note", columnList = "note_id"),
                @Index(name = "idx_user_source", columnList = "user_id,source")
        })
@Data
public class NoteEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 来源是 NOTE 时为对应 note.id；LOCAL_DOC 时为 0 */
    @Column(name = "note_id", nullable = false)
    private Long noteId;

    /** NOTE | LOCAL_DOC */
    @Column(name = "source", length = 16, nullable = false)
    private String source;

    /** LOCAL_DOC 的源路径，NOTE 为 NULL */
    @Column(name = "source_path", length = 1024)
    private String sourcePath;

    @Column(name = "chunk_idx", nullable = false)
    private Integer chunkIdx;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "embedding", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String embedding;

    @Column(name = "dim", nullable = false)
    private Integer dim;

    @Column(name = "model", length = 64, nullable = false)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
