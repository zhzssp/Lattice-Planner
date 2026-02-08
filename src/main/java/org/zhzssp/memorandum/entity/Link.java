package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 弱关联表：Task ↔ Note、Task ↔ Goal 等多对多关系。
 * 核心模型极简，高级能力通过此表扩展。
 */
@Entity
@Table(name = "link")
@Data
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 源类型：TASK, NOTE */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private LinkSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** 目标类型：TASK, NOTE, GOAL */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private LinkTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum LinkSourceType { TASK, NOTE }
    public enum LinkTargetType { TASK, NOTE, GOAL }
}
