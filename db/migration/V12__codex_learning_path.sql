-- V12: 学习路径 / 要点派生表（Git 文件 docs/learning-path.md 为权威源）
-- 删空后可由索引从路径文件重建。任务投影可重建，memo 行本身不在此删除。

CREATE TABLE IF NOT EXISTS kb_station (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id       BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    document_id   BIGINT        NULL,
    station_id    VARCHAR(64)   NOT NULL,
    title         VARCHAR(512)  NOT NULL,
    ordinal       INT           NOT NULL,
    sources_csv   VARCHAR(1024) NULL,
    lab           VARCHAR(255)  NULL,
    next_csv      VARCHAR(512)  NULL,
    cursor_flag   TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_station_repo_id (repo_id, station_id),
    INDEX idx_station_repo_ord (repo_id, ordinal)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS kb_point (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id       BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    station_row_id BIGINT       NOT NULL,
    station_id    VARCHAR(64)   NOT NULL,
    point_id      VARCHAR(64)   NOT NULL,
    level         VARCHAR(8)    NOT NULL,
    statement     VARCHAR(1024) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_point_repo_id (repo_id, point_id),
    INDEX idx_point_station (repo_id, station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS kb_path_snapshot (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id       BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    document_id   BIGINT        NULL,
    version       INT           NOT NULL DEFAULT 1,
    path_cursor   VARCHAR(64)   NULL,
    parse_ok      TINYINT(1)    NOT NULL DEFAULT 0,
    parse_error   VARCHAR(512)  NULL,
    station_count INT           NOT NULL DEFAULT 0,
    must_count    INT           NOT NULL DEFAULT 0,
    skip_count    INT           NOT NULL DEFAULT 0,
    indexed_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_path_snap_repo (repo_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS kb_task_projection (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    repo_id       BIGINT        NOT NULL,
    task_id       BIGINT        NOT NULL,
    goal_id       BIGINT        NULL,
    path_version  INT           NOT NULL,
    station_id    VARCHAR(64)   NOT NULL,
    point_id      VARCHAR(64)   NOT NULL,
    state         VARCHAR(16)   NOT NULL,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proj_repo_point (repo_id, point_id),
    INDEX idx_proj_task (task_id),
    INDEX idx_proj_user_state (user_id, state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
