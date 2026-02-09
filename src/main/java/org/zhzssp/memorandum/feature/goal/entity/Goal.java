package org.zhzssp.memorandum.feature.goal.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.zhzssp.memorandum.entity.GoalType;
import org.zhzssp.memorandum.entity.User;

import java.time.LocalDateTime;

/**
 * 目标实体（插件层）。弱关联设计：不强制树状，支持临时/模糊目标。
 */
@Entity
@Table(name = "goal")
@Data
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", nullable = true)
    private GoalType goalType;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 归档时间（软删除，null 表示有效） */
    @Column(name = "archived_at", nullable = true)
    private LocalDateTime archivedAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}
