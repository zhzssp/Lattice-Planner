-- V13: 路径 churn 历史；PKM 笔记晋升到 Git 的回指；每用户写入偏好

CREATE TABLE IF NOT EXISTS kb_path_revision (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    repo_id             BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    version             INT           NOT NULL,
    head_sha            VARCHAR(64)   NULL,
    n_stations          INT           NOT NULL DEFAULT 0,
    n_must              INT           NOT NULL DEFAULT 0,
    n_skip              INT           NOT NULL DEFAULT 0,
    added_points        INT           NOT NULL DEFAULT 0,
    removed_points      INT           NOT NULL DEFAULT 0,
    reordered_stations  TINYINT(1)    NOT NULL DEFAULT 0,
    station_ids_csv     VARCHAR(1024) NULL,
    point_ids_csv       VARCHAR(2048) NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_path_rev_repo_time (repo_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE note
    ADD COLUMN promoted_path VARCHAR(255) NULL COMMENT '晋升到 Git 仓库后的相对路径；原文不删';

ALTER TABLE user_preference
    ADD COLUMN codex_write_enabled TINYINT(1) NULL COMMENT '允许写入知识仓库工作副本（每用户，默认关）';
