-- =========================================================
-- V4: PKM 笔记升级 + RAG 知识库
-- =========================================================
-- 1) Note 增加 tags 字段（逗号分隔），与 ddl-auto=update 兼容
-- 2) Note 全文索引（用于关键字通路检索）
-- 3) note_embedding：向量索引存储表（Stage 2 启用）
-- =========================================================

ALTER TABLE note ADD COLUMN tags VARCHAR(255) NULL;

-- ngram parser 适配中文、英文混合检索
ALTER TABLE note
    ADD FULLTEXT INDEX ft_note_title_content (title, content) WITH PARSER ngram;

CREATE TABLE IF NOT EXISTS note_embedding (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    note_id      BIGINT        NOT NULL,
    source       VARCHAR(16)   NOT NULL DEFAULT 'NOTE',
    source_path  VARCHAR(1024) NULL,
    chunk_idx    INT           NOT NULL,
    content      TEXT          NOT NULL,
    embedding    MEDIUMTEXT    NOT NULL,
    dim          INT           NOT NULL,
    model        VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_user (user_id),
    INDEX idx_note (note_id),
    INDEX idx_user_source (user_id, source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
