package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 一站上的学习要点（MUST / SKIP）。权威在路径文件表格行。
 */
@Entity
@Table(name = "kb_point",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_repo_id",
                columnNames = {"repo_id", "point_id"}),
        indexes = @Index(name = "idx_point_station", columnList = "repo_id, station_id"))
@Data
public class KbPoint {

    public enum Level { MUST, SKIP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "station_row_id", nullable = false)
    private Long stationRowId;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    @Column(name = "point_id", nullable = false, length = 64)
    private String pointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Level level = Level.MUST;

    @Column(nullable = false, length = 1024)
    private String statement;
}
