package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 知识点 / 概念（方法论一等公民）。
 *
 * <p>对应目标仓库里 foundations 文档的 26 条横切概念。它是「知识体系完整度」
 * 唯一可计算的锚点：覆盖率 = 有资产覆盖的 entity 数 / entity 总数。
 * 没有 entity 这一层，「体系」就只是形容词。</p>
 */
@Entity
@Table(name = "kb_entity",
        uniqueConstraints = @UniqueConstraint(name = "uk_entity_repo_name",
                columnNames = {"repo_id", "name"}),
        indexes = @Index(name = "idx_entity_user", columnList = "user_id"))
@Data
public class KbEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 128)
    private String name;

    /** 逗号分隔的别名。「同构换名」场景必需（fatbin ≈ ExecutableVariant）。 */
    @Column(length = 512)
    private String aliases;

    /** P0 | P1 | P2 */
    @Column(length = 8)
    private String priority;

    @Column(name = "defined_in_document_id")
    private Long definedInDocumentId;
}
