-- =====================================================================
-- V10 · Codex 知识缺口台账（P3 缺口三源合流）
-- =====================================================================
--
-- 这一期回答的问题是：**我不知道自己不知道什么**。
--
-- 三个信号源，全部是「本来就在产生、但此前用完即丢」的数据：
--
--   ① CRAG degraded ------ 问了但库里没有。已有信号，此前只用来让 Agent
--                          降级措辞（「以下基于通用知识」），用完就丢。
--                          它其实是免费的、真实的、按频次排序的个人知识盲区清单。
--
--   ② SKIP_RECALL ★ ------ 当初判定「先跳过（遇到再学）」的概念，现在被反复问到。
--                          用户每篇 guide 都写了先跳过清单，但「遇到了」这件事
--                          在此之前**完全无人监测**——止损线是单向的，只能跳过，
--                          不能召回。本表让它变成可召回的。
--
--   ③ CP_FAIL / CP_MISPREDICT ★ ---- 以为懂了但没懂。这是质量最高的缺口信号，
--                          因为它有机器判据：不是「我感觉没掌握」，
--                          而是「这条 L2 检验跑失败了」或「结果对但预测错了」。
--
-- 闭环：缺口 → （可选）GitHub Issue → 学习计划（复用 PlannerAgentService）
--       → 学完产出 Guide/Note → 缺口关闭
--
-- 可度量的结果：degraded 率单调下降 = 知识体系真的在长。
-- 这是本方案里少数首尾都能被现有指标量化的闭环。
--
-- =====================================================================
-- 归属：kb_gap 属于「运营数据」而非派生索引。
--
-- 它是本期唯一不可从 Git 仓库重建的表——ask_count / first_at / last_at
-- 记录的是**用户的提问行为**，仓库里没有这些信息。
-- 因此它与 knowledge_repo 一样需要备份；其余 kb_* 表仍可随时删库重建。
--
-- 语义部分（缺口的描述、讨论、优先级）可以外化到 GitHub Issue，
-- 那部分就带得走；ask_count 这类高频埋点留在 DB。
-- =====================================================================

CREATE TABLE IF NOT EXISTS kb_gap
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    user_id               BIGINT        NOT NULL,

    -- 可为 NULL：CRAG 信号来自笔记检索时并不属于任何知识仓库。
    -- 缺口不该因为「还没接入仓库」就无法记录。
    repo_id               BIGINT        NULL,

    -- 原始问题（首次出现那次的原文），用于展示。刻意保留原文而非只存归一化结果：
    -- 归一化后的串给人看是不可读的，而缺口看板要让用户一眼认出「这是我问过的」。
    question              VARCHAR(1024) NOT NULL,

    -- 归一化结果，仅用于聚合去重。见 QuestionNormalizer 的注释：
    -- 它的作用是**排序**，不是统计学意义上的精确计数。
    norm_question         VARCHAR(255)  NOT NULL,

    -- CRAG | SKIP_RECALL | CP_FAIL | CP_MISPREDICT | MANUAL
    source                VARCHAR(24)   NOT NULL,

    -- SKIP_RECALL 必有；其余可空
    entity_id             BIGINT        NULL,

    -- 被问到的次数。它是缺口看板的排序键——「问得最多的盲区」优先补。
    ask_count             INT           NOT NULL DEFAULT 1,

    first_at              DATETIME      NOT NULL,
    last_at               DATETIME      NOT NULL,

    -- OPEN | PLANNED | CLOSED | DISMISSED
    -- DISMISSED 与 CLOSED 必须区分：前者是「这不是我的缺口」（判断），
    -- 后者是「我补上了」（成果）。混在一起会让「补了多少」这个数字失真。
    status                VARCHAR(16)   NOT NULL DEFAULT 'OPEN',

    priority              VARCHAR(8)    NULL,

    -- 外化到 GitHub Issue 后的编号（可选能力，LOCAL 仓库为 NULL）
    github_issue_number   INT           NULL,

    -- 转成学习计划后关联的目标 id（复用既有 goal 体系，不另造一套计划）
    goal_id               BIGINT        NULL,

    -- 由哪篇文档关闭——这是「缺口 → 学习 → 沉淀」闭环闭合的证据
    closed_by_document_id BIGINT        NULL,
    closed_at             DATETIME      NULL,

    note                  VARCHAR(1024) NULL,

    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- 同一用户、同一来源、同一归一化问题只留一条，重复提问累加 ask_count。
    -- source 参与唯一键是刻意的：同一个问题从 CRAG 与从 CP_MISPREDICT 来，
    -- 意味着两件不同的事（「库里没有」vs「我理解错了」），补法也不同，不该合并。
    UNIQUE KEY uk_gap_user_source_norm (user_id, source, norm_question),

    KEY idx_gap_user_status (user_id, status),
    KEY idx_gap_repo_status (repo_id, status),
    -- 看板主查询：按 ask_count 倒序
    KEY idx_gap_user_count (user_id, ask_count),
    KEY idx_gap_entity (entity_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识缺口台账（运营数据，不可从仓库重建）';
