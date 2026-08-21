package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档间链接（派生索引）。
 *
 * <p>目标仓库实测有 <strong>1263 条</strong>相对链接，其中 <strong>377 条带 anchor</strong>。
 * 这类链接的特点是：改一个标题就会静默断掉，而且<strong>断了没有任何人知道</strong>——
 * Markdown 相对链接在 IDE 里不会报错，在 GitHub 上点了才发现 404。</p>
 *
 * <p>{@link LinkKind#BACKREF} 是本表最有价值的一类：它承载「笔记必须挂回知识文档」
 * 这条方法论约束。原本这一步靠 Agent 自觉（SKILL.md 第 4 步），漏了无人察觉；
 * 有了本表就能做双向性校验，漏挂即报错。</p>
 */
@Entity
@Table(name = "kb_link",
        indexes = {
                @Index(name = "idx_link_src", columnList = "src_document_id"),
                @Index(name = "idx_link_target", columnList = "target_document_id"),
                @Index(name = "idx_link_repo_broken", columnList = "repo_id,broken")
        })
@Data
public class KbLink {

    public enum LinkKind {
        /** 普通文档间引用。 */
        REF,
        /** 速记回挂：guide → notes/（方法论硬约束，须双向）。 */
        BACKREF,
        /** 指向 paper/*.pdf 等原始来源。 */
        CITATION,
        /** 指向动手项目目录 / 脚本。 */
        LAB,
        /** front-matter 里声明的 source。 */
        SOURCE,
        /** 外部 URL（不校验可达性，只记录）。 */
        EXTERNAL,
        /** {@code [[标题]]} 双链写法。 */
        WIKI
    }

    public enum BrokenReason {
        /** 目标文件不存在。 */
        NO_FILE,
        /** 文件存在但 anchor 不存在（改标题的典型后果）。 */
        NO_ANCHOR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "src_document_id", nullable = false)
    private Long srcDocumentId;

    @Column(name = "src_section_id")
    private Long srcSectionId;

    /** 原始链接串，保留以便报错时能定位到源文本。 */
    @Column(name = "raw_target", nullable = false, length = 768)
    private String rawTarget;

    @Column(name = "target_path", length = 512)
    private String targetPath;

    @Column(name = "target_anchor", length = 255)
    private String targetAnchor;

    @Column(name = "target_document_id")
    private Long targetDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LinkKind kind = LinkKind.REF;

    @Column(nullable = false)
    private Boolean broken = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "broken_reason", length = 128)
    private BrokenReason brokenReason;
}
