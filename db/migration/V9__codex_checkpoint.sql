-- =========================================================
-- V9: Codex 验证闭环（Checkpoint · 知识落地检验）
-- =========================================================
-- 这一期实现的是「怎么证明你不是看懂了，而是能改能跑」。
--
-- 两条与众不同的设计，DDL 里各有一个字段承载：
--   1) prediction / predicted_at —— 「先预测再动手」的纪律锁。
--      不填预测不允许运行验收：改完再解释，人会自动为既成结果编理由。
--   2) prediction_correct —— 「通过但预测错」这个信号。
--      它精确定位「结果对但因果理解错」，是所有假通过里最危险的一类，
--      市面上没有任何工具在采集它。
--
-- 派生性：本文件两张表都可由仓库 Markdown 重建（verify_source=PARSED），
--        但 prediction / status / 运行记录是用户行为数据，不可重建 ——
--        因此 P1 阶段的 rebuild 只覆盖定义，刻意保留用户填写的预测与状态。
-- =========================================================

CREATE TABLE IF NOT EXISTS kb_checkpoint (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    document_id     BIGINT       NULL,          -- 所属检验册
    section_anchor  VARCHAR(255) NULL,          -- 可跳回原文条目
    code            VARCHAR(32)  NOT NULL,      -- L2-MLIR-04
    level           VARCHAR(4)   NOT NULL,      -- L0|L1|L2|L3
    title           VARCHAR(512) NOT NULL,
    checks_what     VARCHAR(1024) NULL,         -- 「检验什么」
    prerequisite    VARCHAR(512) NULL,
    entity_id       BIGINT       NULL,
    lab             VARCHAR(128) NULL,
    resource_tag    VARCHAR(32)  NULL,          -- local|local+toolchain|gpu1|gpuN|multinode
    est_hours       DECIMAL(5,1) NULL,

    -- ★ 判据来源：PARSED = 从既有 Markdown 解析推断（判据较弱，UI 必须明示）
    --              DECLARED = front-matter 显式声明（判据精确）
    -- 不假装精确是这里的关键：解析出的 expect 只能是 exit_code + 关键词。
    verify_source   VARCHAR(16)  NOT NULL DEFAULT 'PARSED',
    verify_json     TEXT         NULL,          -- {cmd, cwd, timeout, expect:[...]}
    fallback_json   TEXT         NULL,          -- 无 GPU 等资源时的降级判据
    pass_criteria   TEXT         NULL,          -- 原文「通过标准」，供人工核对
    blind_spots     TEXT         NULL,          -- 「常见失败 → 盲点」映射

    -- ★ 预测门禁
    predict_required TINYINT(1)  NOT NULL DEFAULT 1,
    prediction      TEXT         NULL,
    predicted_at    DATETIME     NULL,          -- 一旦写入即冻结，UI 转只读
    prediction_questions TEXT    NULL,          -- 原文「先预测再动手」的提问

    status          VARCHAR(16)  NOT NULL DEFAULT 'TODO',
                    -- TODO|PREDICTED|PASSED|FAILED|DEGRADED|SKIPPED
    passed_at       DATETIME     NULL,
    -- NULL=未判定 | 0=预测错（★最高价值信号） | 1=预测对
    prediction_correct TINYINT(1) NULL,
    prediction_judge VARCHAR(16) NULL,          -- AI|USER，指标解释力依赖此字段
    divergence      TEXT         NULL,          -- 「我原以为X，实际Y」

    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cp_repo_code (repo_id, code),
    INDEX idx_cp_user_status (user_id, status),
    INDEX idx_cp_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------
-- 运行记录：既是耗时统计，也是受限执行的安全审计台账
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_checkpoint_run (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    checkpoint_id   BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    started_at      DATETIME    NOT NULL,
    duration_ms     BIGINT      NULL,
    exit_code       INT         NULL,
    passed          TINYINT(1)  NULL,
    expect_result_json TEXT     NULL,           -- 逐条断言结果，判定必须可解释
    stdout_excerpt  TEXT        NULL,           -- 上限 8KB，截断即标记
    stderr_excerpt  TEXT        NULL,
    output_truncated TINYINT(1) NOT NULL DEFAULT 0,
    timed_out       TINYINT(1)  NOT NULL DEFAULT 0,

    -- ★ 安全审计：谁批准了、实际执行了什么。
    -- 受限执行是本方案风险最高的一环，没有审计台账就无法事后复盘。
    approved_by_user TINYINT(1) NOT NULL DEFAULT 0,
    cmd_executed    VARCHAR(1024) NULL,
    cwd_executed    VARCHAR(512) NULL,
    reject_reason   VARCHAR(256) NULL,          -- 被闸门拦下时的原因
    PRIMARY KEY (id),
    INDEX idx_run_cp (checkpoint_id),
    INDEX idx_run_user (user_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
