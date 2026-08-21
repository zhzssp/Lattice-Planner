package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Git 文档的 chunk + 向量（派生索引）。
 *
 * <p><strong>为什么不复用 {@code note_embedding}</strong>（这是本次架构的关键取舍）：
 * <ol>
 *   <li>它的 {@code note_id NOT NULL}（{@code LOCAL_DOC} 用 0 占位）无法反查
 *       「哪个仓库 / 哪篇文档 / 哪一节」，而这是引用定位的前提；</li>
 *   <li>它的 {@code findByUserId} 全量加载语义已被 {@code EmbeddingVectorCache} 依赖，
 *       加字段会牵动全部既有查询；</li>
 *   <li>最重要：分表能让<strong>既有笔记检索路径逐字节不变</strong>，
 *       从而不破坏方案 A 的评测录制资产（cassette 依赖 prompt 与工具 schema 的字节稳定）。</li>
 * </ol>
 *
 * <p>{@code embedding} 允许为 NULL：embedding 服务不可用时仍然落 chunk，
 * 此时关键字（FULLTEXT ngram）通路依然能检索到内容——延续项目「任一通路故障不影响另一路」的设计。</p>
 */
@Entity
@Table(name = "kb_chunk",
        indexes = {
                @Index(name = "idx_chunk_user_repo", columnList = "user_id,repo_id"),
                @Index(name = "idx_chunk_doc", columnList = "document_id")
        })
@Data
public class KbChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "chunk_idx", nullable = false)
    private Integer chunkIdx;

    /** 冗余章节路径：检索结果直接可读，省一次 join。 */
    @Column(name = "heading_path", length = 1024)
    private String headingPath;

    @Column(length = 255)
    private String anchor;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** NULL = 未向量化（embedding 不可用），此时仍可走关键字通路。 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String embedding;

    private Integer dim;

    @Column(length = 64)
    private String model;

    /** 所属文档的 blobHash：文档未变则整批 chunk 可直接复用，无需重算 embedding。 */
    @Column(name = "blob_hash", nullable = false, length = 64)
    private String blobHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
