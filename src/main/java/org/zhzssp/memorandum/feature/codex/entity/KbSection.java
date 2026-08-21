package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档章节（派生索引）。
 *
 * <p>存在的唯一理由是<strong>精确引用定位</strong>：检索命中一个 chunk 后，
 * 要能告诉用户「这在 {@code iree-learning-guide.md} 的 §4.6 timeline semaphore」
 * 并支持点击跳转。没有本表，命中只能定位到「某篇 10 万字的文档」，等于没定位。</p>
 *
 * <p>anchor 采用 GitHub 的 slug 规则生成，从而与仓库内已有的 377 条
 * {@code ](path#anchor)} 链接可直接比对——这也是死链校验能做到 anchor 级的前提。</p>
 */
@Entity
@Table(name = "kb_section",
        uniqueConstraints = @UniqueConstraint(name = "uk_sec_doc_anchor",
                columnNames = {"document_id", "anchor"}),
        indexes = @Index(name = "idx_sec_doc_ord", columnList = "document_id,ord"))
@Data
public class KbSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** GitHub 风格 slug，如 {@code 46-timeline-semaphore-语义}。 */
    @Column(nullable = false, length = 255)
    private String anchor;

    @Column(nullable = false, length = 512)
    private String heading;

    /** 祖先链，如 {@code 第 4 章 HAL > 4.6 timeline semaphore}。喂给 LLM 时带上，让引用可读。 */
    @Column(name = "heading_path", length = 1024)
    private String headingPath;

    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false)
    private Integer ord;

    @Column(name = "char_start", nullable = false)
    private Integer charStart;

    @Column(name = "char_end", nullable = false)
    private Integer charEnd;
}
