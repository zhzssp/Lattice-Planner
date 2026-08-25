-- =====================================================================
-- V11 · Agent 会话事实层（上下文工程 P1 第二步）
-- =====================================================================
--
-- 把「会话后画像」升级为「会话内事实」。
--
-- 背景：长期记忆 AGENT_MEMO 是「画像」（这人是谁），它回答不了
-- 「这个项目 deadline 是下周五」这类**具体事实**。而滑动窗口硬截断
-- 会把这些事实随窗口滑出而静默丢失——Agent 和用户都不知道信息没了，
-- 于是照着错的时间排期。
--
-- 本表做两件事：
--   ① 每轮异步从用户原话里抽取「可复用事实」（约束 / 偏好 / 决定）；
--   ② 按变更频率分流：STABLE 进 system（天级生效），VOLATILE 进 history 首条。
--
-- =====================================================================
-- 关键设计：source_quote + source_turn 是整张表最重要的两列。
--
-- 一条 LLM 抽出来的 fact，若无法追溯它来自哪句话，那么当它抽错时
-- （把「我在想是不是下周五」抽成「deadline 是下周五」），
-- 用户发现不了、也没法纠正。而一条错误的 fact 会被注入每一轮，
-- 污染面比一次错误回答大得多。
-- =====================================================================

CREATE TABLE IF NOT EXISTS agent_fact
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,

    -- VOLATILE 事实绑定会话（会话结束即失效）；STABLE 为 NULL（跨会话）
    session_id   VARCHAR(64)  NULL,

    -- STABLE | VOLATILE
    kind         VARCHAR(16)  NOT NULL,

    -- 归一化键，如 "deadline.project-x"，用于覆盖而非累积
    fact_key     VARCHAR(64)  NOT NULL,

    -- 事实内容（一句话）
    fact_value   VARCHAR(512) NOT NULL,

    -- ★原文片段：用户凭什么能核对「软件凭什么说我有这条约束」
    source_quote VARCHAR(512) NULL,

    -- ★来自第几轮
    source_turn  INT          NULL,

    -- HIGH | MEDIUM（LOW 不入库）
    confidence   VARCHAR(16)  NOT NULL,

    -- ACTIVE | SUPERSEDED | REJECTED
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- 同一用户、同一键、同一会话只留一条 ACTIVE，重复抽取覆盖而非累积
    UNIQUE KEY uk_fact_user_key_session (user_id, fact_key, session_id),

    KEY idx_fact_user_kind_status (user_id, kind, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='Agent 会话事实（可重建，但 SUPERSEDED 历史随行）';
