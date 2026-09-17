package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每个仓库一份路径解析快照（派生）。解析失败时保留上一版车站，本行记 error。
 */
@Entity
@Table(name = "kb_path_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_path_snap_repo",
                columnNames = "repo_id"))
@Data
public class KbPathSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false)
    private int version = 1;

    /** 当前车站 id。列名避开 MySQL 8 保留字 CURSOR。 */
    @Column(name = "path_cursor", length = 64)
    private String cursor;

    @Column(name = "parse_ok", nullable = false)
    private boolean parseOk;

    @Column(name = "parse_error", length = 512)
    private String parseError;

    @Column(name = "station_count", nullable = false)
    private int stationCount;

    @Column(name = "must_count", nullable = false)
    private int mustCount;

    @Column(name = "skip_count", nullable = false)
    private int skipCount;

    @Column(name = "indexed_at", nullable = false)
    private LocalDateTime indexedAt;
}
