package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 路径要点 → Dashboard 任务的投影（派生，可按路径文件重建关联）。
 */
@Entity
@Table(name = "kb_task_projection",
        uniqueConstraints = @UniqueConstraint(name = "uk_proj_repo_point",
                columnNames = {"repo_id", "point_id"}),
        indexes = {
                @Index(name = "idx_proj_task", columnList = "task_id"),
                @Index(name = "idx_proj_user_state", columnList = "user_id, state")
        })
@Data
public class KbTaskProjection {

    public enum State { ACTIVE, STALE, SATISFIED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "goal_id")
    private Long goalId;

    @Column(name = "path_version", nullable = false)
    private int pathVersion;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    @Column(name = "point_id", nullable = false, length = 64)
    private String pointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.ACTIVE;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
