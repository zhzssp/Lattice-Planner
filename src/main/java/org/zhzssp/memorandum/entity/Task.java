package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 核心任务实体。原 Memo 概念统一为 Task，为扩展预留空间。
 * 表名暂保留 memo 以兼容现有数据。
 */
@Entity
@Table(name = "memo")
@Data
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;
    private LocalDateTime deadline;

    /** 任务状态：支持静默搁置、归档等 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = true)
    private TaskStatus status;

    /** 任务粒度：为模糊任务提示预留 */
    @Enumerated(EnumType.STRING)
    @Column(name = "granularity", nullable = true)
    private TaskGranularity granularity;

    /** 预估分钟数（ATOMIC/STANDARD 时可选） */
    @Column(name = "estimated_minutes", nullable = true)
    private Integer estimatedMinutes;

    /** 创建时间，用于「存在天数」提示 */
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 搁置时间（status=SHELVED 时） */
    @Column(name = "shelved_at", nullable = true)
    private LocalDateTime shelvedAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /** 获取有效状态，null 视为 PENDING（兼容旧数据） */
    public TaskStatus getEffectiveStatus() {
        return status != null ? status : TaskStatus.PENDING;
    }
}
