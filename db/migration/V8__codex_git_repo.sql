-- =========================================================
-- V8: Codex 知识仓库接入（Git-native 知识资产沉淀）
-- =========================================================
-- 架构原则：Git 仓库是唯一权威源，本文件建的表中
--   · knowledge_repo  = 权威（含用户凭证与本地路径，不可重建）
--   · 其余 kb_*       = 派生索引（删空后可由 rebuild-index 全量重建）
-- 验收标准：DELETE FROM kb_* 后重建，检索/结构/死链结果完全一致。
--
-- 不建外键：沿用项目既有风格（note_embedding / link 均为逻辑关联 + 应用层用户过滤），
--          删除走显式级联，避免 ddl-auto=update 与外键约束互相干扰。
-- =========================================================

-- ---------------------------------------------------------
-- ① 仓库注册（★权威，不可重建）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_repo (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'LEARNING',  -- LEARNING|WORK
    provider        VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',     -- LOCAL|GITHUB
    remote_url      VARCHAR(512) NULL,
    local_path      VARCHAR(512) NOT NULL,
    default_branch  VARCHAR(64)  NOT NULL DEFAULT 'main',
    token_ref       VARCHAR(128) NULL,        -- 凭证存储的键名，不直接存 token 明文
    template_pack   VARCHAR(64)  NULL,
    last_synced_sha VARCHAR(64)  NULL,
    last_synced_at  DATETIME     NULL,
    sync_status     VARCHAR(16)  NOT NULL DEFAULT 'IDLE',      -- IDLE|SYNCING|DIRTY|ERROR
    sync_error      VARCHAR(512) NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repo_user_name (user_id, name),
    INDEX idx_repo_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- ② 文档（派生）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_document (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,   -- 冗余：使用户隔离无需 join
    path              VARCHAR(512) NOT NULL,   -- 仓库内相对路径，统一正斜杠
    kind              VARCHAR(32)  NOT NULL,   -- guide|note|roadmap|checkpoint-set|source|lab|unknown
    subkind           VARCHAR(32)  NULL,       -- paper-note 等
    title             VARCHAR(512) NULL,
    front_matter_json TEXT         NULL,
    blob_hash         VARCHAR(64)  NOT NULL,   -- ★增量索引判据（git hash-object）
    char_count        INT          NOT NULL DEFAULT 0,
    chunk_count       INT          NOT NULL DEFAULT 0,
    truncated         TINYINT(1)   NOT NULL DEFAULT 0,  -- ★切片触顶明示（P0a）
    loss_ratio        DOUBLE       NOT NULL DEFAULT 0,
    git_updated_at    DATETIME     NULL,
    git_last_author   VARCHAR(128) NULL,
    fm_valid          TINYINT(1)   NOT NULL DEFAULT 1,
    fm_error          VARCHAR(512) NULL,
    indexed_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_repo_path (repo_id, path),
    INDEX idx_doc_user_kind (user_id, kind),
    INDEX idx_doc_repo (repo_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- ③ 章节（★精确引用定位的载体：命中可跳转到 path#anchor）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_section (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    document_id  BIGINT        NOT NULL,
    anchor       VARCHAR(255)  NOT NULL,   -- GitHub 风格 slug
    heading      VARCHAR(512)  NOT NULL,
    heading_path VARCHAR(1024) NULL,       -- "第 4 章 HAL > 4.6 timeline semaphore"
    level        TINYINT       NOT NULL,
    ord          INT           NOT NULL,
    char_start   INT           NOT NULL,
    char_end     INT           NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sec_doc_anchor (document_id, anchor),
    INDEX idx_sec_doc_ord (document_id, ord)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- ④ chunk + 向量（独立于 note_embedding）
-- 为什么不复用 note_embedding：
--   它的 note_id NOT NULL（LOCAL_DOC 用 0 占位）无法反查「哪个仓库哪篇文档哪一节」，
--   而这正是引用定位的前提；且 findByUserId 全量加载语义已被现有代码依赖。
--   分表可让既有笔记检索路径逐字节不变，保护评测录制资产。
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    repo_id      BIGINT        NOT NULL,
    document_id  BIGINT        NOT NULL,
    section_id   BIGINT        NULL,
    chunk_idx    INT           NOT NULL,
    heading_path VARCHAR(1024) NULL,   -- 冗余进 chunk：喂 LLM 时自带定位上下文
    anchor       VARCHAR(255)  NULL,
    content      TEXT          NOT NULL,
    embedding    MEDIUMTEXT    NULL,   -- NULL = 未向量化（此时仍可走关键字通路）
    dim          INT           NULL,
    model        VARCHAR(64)   NULL,
    blob_hash    VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_chunk_user_repo (user_id, repo_id),
    INDEX idx_chunk_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ngram parser 适配中英混合检索（与 note 的 ft_note_title_content 同构）
ALTER TABLE kb_chunk
    ADD FULLTEXT INDEX ft_kb_chunk_content (content) WITH PARSER ngram;

-- ---------------------------------------------------------
-- ⑤ 链接图（死链校验 + 反链 + 引用双向性）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_link (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id            BIGINT       NOT NULL,
    src_document_id    BIGINT       NOT NULL,
    src_section_id     BIGINT       NULL,
    raw_target         VARCHAR(768) NOT NULL,
    target_path        VARCHAR(512) NULL,
    target_anchor      VARCHAR(255) NULL,
    target_document_id BIGINT       NULL,   -- 解析成功才有
    kind               VARCHAR(16)  NOT NULL,  -- REF|BACKREF|CITATION|LAB|SOURCE|EXTERNAL|WIKI
    broken             TINYINT(1)   NOT NULL DEFAULT 0,
    broken_reason      VARCHAR(128) NULL,   -- NO_FILE|NO_ANCHOR
    PRIMARY KEY (id),
    INDEX idx_link_src (src_document_id),
    INDEX idx_link_target (target_document_id),
    INDEX idx_link_repo_broken (repo_id, broken)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- ⑥ 知识点（entity）与止损线（★方法论一等公民）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_entity (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id                BIGINT       NOT NULL,
    user_id                BIGINT       NOT NULL,
    name                   VARCHAR(128) NOT NULL,
    aliases                VARCHAR(512) NULL,
    priority               VARCHAR(8)   NULL,   -- P0|P1|P2
    defined_in_document_id BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_repo_name (repo_id, name),
    INDEX idx_entity_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 「必学 / 先跳过（遇到再学）」的结构化记录。
-- SKIP 不是终点：被反复问到时会触发召回提示（P3 缺口三源之一）。
CREATE TABLE IF NOT EXISTS kb_scope_decision (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    entity_id              BIGINT       NOT NULL,
    decision               VARCHAR(16)  NOT NULL,   -- MUST|SKIP|DROPPED
    reason                 VARCHAR(512) NULL,
    decided_in_document_id BIGINT       NULL,
    hit_count              INT          NOT NULL DEFAULT 0,  -- SKIP 被"遇到"的次数
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_entity (entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- ⑦ 索引运行记录（可观测：用 docs_skipped 证明增量索引有效）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_index_run (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id        BIGINT       NOT NULL,
    mode           VARCHAR(16)  NOT NULL,   -- FULL|INCREMENTAL
    started_at     DATETIME     NOT NULL,
    duration_ms    BIGINT       NULL,
    docs_total     INT          NULL,
    docs_reindexed INT          NULL,
    docs_skipped   INT          NULL,       -- ★blobHash 命中数
    docs_removed   INT          NULL,
    chunks_written INT          NULL,
    embed_calls    INT          NULL,
    broken_links   INT          NULL,
    truncated_docs INT          NULL,
    status         VARCHAR(16)  NOT NULL,   -- RUNNING|SUCCESS|FAILED
    error          VARCHAR(512) NULL,
    PRIMARY KEY (id),
    INDEX idx_run_repo_started (repo_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
