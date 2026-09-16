package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 学习路径上的一站（派生索引，权威在 Git 的 {@code docs/learning-path.md}）。
 */
@Entity
@Table(name = "kb_station",
        uniqueConstraints = @UniqueConstraint(name = "uk_station_repo_id",
                columnNames = {"repo_id", "station_id"}),
        indexes = @Index(name = "idx_station_repo_ord", columnList = "repo_id, ordinal"))
@Data
public class KbStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "station_id", nullable = false, length = 64)
    private String stationId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "sources_csv", length = 1024)
    private String sourcesCsv;

    @Column(length = 255)
    private String lab;

    @Column(name = "next_csv", length = 512)
    private String nextCsv;

    @Column(name = "cursor_flag", nullable = false)
    private boolean cursorFlag;
}
