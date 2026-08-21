package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库内的一篇文档（派生索引，可重建）。
 *
 * <p>正文<strong>不存在本表</strong>——正文的权威源永远是 Git 工作副本里的文件。
 * 本表只存「为了检索与展示所必需的派生信息」，以及 {@code blobHash} 这个增量判据。</p>
 */
@Entity
@Table(name = "kb_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_repo_path",
                columnNames = {"repo_id", "path"}),
        indexes = {
                @Index(name = "idx_doc_user_kind", columnList = "user_id,kind"),
                @Index(name = "idx_doc_repo", columnList = "repo_id")
        })
@Data
public class KbDocument {

    /**
     * 文档在方法论中承担的角色。
     *
     * <p>这套分类直接对应「源 → 蒸 → 线 → 做 → 验 → 沉」流水线，
     * 而非通用的「文件类型」——因为不同角色的文档在产品里有不同的行为
     * （guide 可出题、checkpoint 可执行、note 需校验 backref）。</p>
     */
    public enum DocKind {
        /** 蒸馏教材：框架图 + 必学特性 + 掌握标准 + 先跳过。 */
        GUIDE,
        /** 对话沉淀短记，须有 backref 挂回 guide。 */
        NOTE,
        /** 路线：优先级表 + 阶段表（读→做→验）。 */
        ROADMAP,
        /** 检验册：L0~L3 条目集合。 */
        CHECKPOINT_SET,
        /** 原始来源：论文 PDF / 官方文档快照。 */
        SOURCE,
        /** 动手项目说明。 */
        LAB,
        UNKNOWN;

        /** front-matter / layout 里的小写连字符写法。 */
        public String label() {
            return name().toLowerCase().replace('_', '-');
        }

        public static DocKind of(String s) {
            if (s == null || s.isBlank()) return UNKNOWN;
            String k = s.trim().toUpperCase().replace('-', '_');
            for (DocKind d : values()) {
                if (d.name().equals(k)) return d;
            }
            return UNKNOWN;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 仓库内相对路径，统一正斜杠（Windows 下也存 {@code docs/notes/x.md}）。 */
    @Column(nullable = false, length = 512)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocKind kind = DocKind.UNKNOWN;

    @Column(length = 32)
    private String subkind;

    @Column(length = 512)
    private String title;

    @Column(name = "front_matter_json", columnDefinition = "TEXT")
    private String frontMatterJson;

    /**
     * 内容哈希（{@code git hash-object} 结果）。
     *
     * <p>用 git 自己算而非应用层 SHA：Windows 下 {@code core.autocrlf} 的行尾转换
     * 会让两者不一致，导致增量索引永远失效或误判。</p>
     */
    @Column(name = "blob_hash", nullable = false, length = 64)
    private String blobHash;

    @Column(name = "char_count", nullable = false)
    private Integer charCount = 0;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    /**
     * 切片是否触顶被截断（P0a）。
     *
     * <p>为什么要落库而不只是打日志：截断意味着文档后半部分<strong>永远检索不到</strong>，
     * 而 Agent 仍会声称「已检索知识库」。这个字段让仪表盘能红标出来，
     * 用户才有机会调大上限——延续项目「截断不可静默」原则。</p>
     */
    @Column(nullable = false)
    private Boolean truncated = false;

    /** 被丢弃内容的字符占比（0.0 = 完整）。 */
    @Column(name = "loss_ratio", nullable = false)
    private Double lossRatio = 0.0;

    /** 末次 git 提交时间——腐化检测的真实数据源（而非猜测）。 */
    @Column(name = "git_updated_at")
    private LocalDateTime gitUpdatedAt;

    @Column(name = "git_last_author", length = 128)
    private String gitLastAuthor;

    @Column(name = "fm_valid", nullable = false)
    private Boolean fmValid = true;

    @Column(name = "fm_error", length = 512)
    private String fmError;

    @Column(name = "indexed_at", nullable = false)
    private LocalDateTime indexedAt = LocalDateTime.now();
}
