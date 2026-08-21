package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次检验运行记录。
 *
 * <p>双重职责：① 耗时与结果统计；② <strong>受限执行的安全审计台账</strong>。</p>
 *
 * <p>后者更重要。受限执行是整个方案风险最高的一环——它在用户机器上跑真实命令。
 * {@link #cmdExecuted} / {@link #cwdExecuted} / {@link #approvedByUser}
 * 三个字段合起来回答「到底执行了什么、在哪执行、谁批准的」。
 * 没有这份台账，一旦出问题就无法事后复盘。</p>
 *
 * <p>{@link #rejectReason} 记录被安全闸门拦下的情况。它长期为 0 说明闸门是冗余保险；
 * 一旦不为 0，就是「白名单机制必要」的实证——与方案 D 的
 * {@code bannedToolCallsBlocked} 同一立场。</p>
 */
@Entity
@Table(name = "kb_checkpoint_run",
        indexes = {
                @Index(name = "idx_run_cp", columnList = "checkpoint_id"),
                @Index(name = "idx_run_user", columnList = "user_id,started_at")
        })
@Data
public class KbCheckpointRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checkpoint_id", nullable = false)
    private Long checkpointId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "exit_code")
    private Integer exitCode;

    private Boolean passed;

    /** 逐条断言结果 —— 判定必须可解释，不能只给一个 true/false。 */
    @Column(name = "expect_result_json", columnDefinition = "TEXT")
    private String expectResultJson;

    @Column(name = "stdout_excerpt", columnDefinition = "TEXT")
    private String stdoutExcerpt;

    @Column(name = "stderr_excerpt", columnDefinition = "TEXT")
    private String stderrExcerpt;

    /** 输出超限被截断——必须标记，不能静默丢。 */
    @Column(name = "output_truncated", nullable = false)
    private Boolean outputTruncated = false;

    @Column(name = "timed_out", nullable = false)
    private Boolean timedOut = false;

    @Column(name = "approved_by_user", nullable = false)
    private Boolean approvedByUser = false;

    /** 实际执行的完整命令（审计）。 */
    @Column(name = "cmd_executed", length = 1024)
    private String cmdExecuted;

    @Column(name = "cwd_executed", length = 512)
    private String cwdExecuted;

    /** 被安全闸门拦下时的原因；正常执行为 null。 */
    @Column(name = "reject_reason", length = 256)
    private String rejectReason;
}
