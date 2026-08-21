package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次索引运行的记录（可观测）。
 *
 * <p>存在的理由是<strong>让「增量索引真的生效」成为可证明的事实</strong>：
 * {@code docsSkipped / docsTotal} 就是增量命中率。若这个数字长期为 0，
 * 说明 blobHash 判定有问题（例如行尾转换导致 hash 每次都变），
 * 而这种故障在没有本表时几乎不可能被发现——只会表现为「索引有点慢」。</p>
 *
 * <p>同理 {@code truncatedDocs} 让 P0a 修复的效果可验证。</p>
 */
@Entity
@Table(name = "kb_index_run",
        indexes = @Index(name = "idx_run_repo_started", columnList = "repo_id,started_at"))
@Data
public class KbIndexRun {

    public enum Mode {
        /** 全量：忽略 blobHash，重算所有文档（rebuild-index 用）。 */
        FULL,
        /** 增量：blobHash 未变则跳过。 */
        INCREMENTAL
    }

    public enum Status { RUNNING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode = Mode.INCREMENTAL;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "docs_total")
    private Integer docsTotal;

    @Column(name = "docs_reindexed")
    private Integer docsReindexed;

    /** ★blobHash 命中而跳过的文档数——增量索引有效性的直接证据。 */
    @Column(name = "docs_skipped")
    private Integer docsSkipped;

    /** 仓库中已删除、索引中被清理的文档数。 */
    @Column(name = "docs_removed")
    private Integer docsRemoved;

    @Column(name = "chunks_written")
    private Integer chunksWritten;

    /** embedding API 实际调用批次——成本可见。 */
    @Column(name = "embed_calls")
    private Integer embedCalls;

    @Column(name = "broken_links")
    private Integer brokenLinks;

    @Column(name = "truncated_docs")
    private Integer truncatedDocs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    @Column(length = 512)
    private String error;
}
