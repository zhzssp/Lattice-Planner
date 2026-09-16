package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 路径文件一次解析/apply 的快照，用来看「还在不在大改」——不是分数。
 */
@Entity
@Table(name = "kb_path_revision",
        indexes = @Index(name = "idx_path_rev_repo_time", columnList = "repo_id, created_at"))
@Data
public class KbPathRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int version;

    @Column(name = "head_sha", length = 64)
    private String headSha;

    @Column(name = "n_stations", nullable = false)
    private int nStations;

    @Column(name = "n_must", nullable = false)
    private int nMust;

    @Column(name = "n_skip", nullable = false)
    private int nSkip;

    @Column(name = "added_points", nullable = false)
    private int addedPoints;

    @Column(name = "removed_points", nullable = false)
    private int removedPoints;

    @Column(name = "reordered_stations", nullable = false)
    private boolean reorderedStations;

    @Column(name = "station_ids_csv", length = 1024)
    private String stationIdsCsv;

    @Column(name = "point_ids_csv", length = 2048)
    private String pointIdsCsv;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
