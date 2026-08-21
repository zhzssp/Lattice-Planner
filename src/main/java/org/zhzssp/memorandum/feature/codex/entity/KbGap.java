package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识缺口（P3 缺口三源合流的核心实体）。
 *
 * <h3>它回答的是最难自问的一个问题</h3>
 * <p>「我不知道自己不知道什么。」</p>
 *
 * <p>人无法凭回忆列出自己的盲区——盲区之所以是盲区，正是因为想不起来。
 * 但它在行为里留了痕：问过却没得到答案、当初判定跳过如今反复遇到、
 * 以为掌握了却跑不通。这三类痕迹本来都被丢掉了，本表把它们攒起来。</p>
 *
 * <h3>本表是本期唯一不可从 Git 重建的表</h3>
 * <p>{@link #askCount} / {@link #firstAt} / {@link #lastAt} 记录的是<strong>提问行为</strong>，
 * 仓库里没有这些信息。所以它与 {@code knowledge_repo} 一样需要备份，
 * 而其余 {@code kb_*} 表仍可随时删库重建。</p>
 *
 * <p>语义部分（描述、讨论、优先级）可外化为 GitHub Issue，那部分带得走；
 * {@code askCount} 这类高频埋点留在 DB。</p>
 */
@Entity
@Table(name = "kb_gap",
        uniqueConstraints = @UniqueConstraint(name = "uk_gap_user_source_norm",
                columnNames = {"user_id", "source", "norm_question"}),
        indexes = {
                @Index(name = "idx_gap_user_status", columnList = "user_id,status"),
                @Index(name = "idx_gap_repo_status", columnList = "repo_id,status"),
                @Index(name = "idx_gap_user_count", columnList = "user_id,ask_count"),
                @Index(name = "idx_gap_entity", columnList = "entity_id")
        })
@Data
public class KbGap {

    /**
     * 缺口来源。
     *
     * <p>三个自动源的信号强度<strong>依次递增</strong>，这个顺序决定了看板的默认权重：
     * CRAG 只说明「检索没命中」（可能是措辞问题），
     * SKIP_RECALL 说明「我当初主动跳过的东西现在挡路了」，
     * 而 CP_* 有机器判据——不是感觉没掌握，是检验真的没过。</p>
     */
    public enum Source {
        /** CRAG 判定检索质量差 / 无命中：问了但库里没有。免费信号，已有管道。 */
        CRAG,
        /**
         * 「先跳过」的概念被反复问到。★
         *
         * <p>这是市面上没有任何软件在采集的信号。用户每篇 guide 都写了
         * 「先跳过（遇到再学）」清单，但「遇到了」这件事无人监测，
         * 于是止损线只能单向生效。</p>
         */
        SKIP_RECALL,
        /** 检验执行失败：动手做不出来。有机器判据。 */
        CP_FAIL,
        /**
         * 检验通过但预测错。★★
         *
         * <p>最高价值的一类：结果对但因果理解错，是所有假通过里最危险的。
         * 「预测错不扣分，预测错但没发现自己错了才是问题」。</p>
         */
        CP_MISPREDICT,
        /** 用户手工登记。 */
        MANUAL;

        public static Source of(String s) {
            if (s == null) return MANUAL;
            try {
                return valueOf(s.strip().toUpperCase().replace('-', '_'));
            } catch (Exception e) {
                return MANUAL;
            }
        }

        /** 是否属于「有机器判据」的强信号。 */
        public boolean machineJudged() {
            return this == CP_FAIL || this == CP_MISPREDICT;
        }
    }

    public enum Status {
        OPEN,
        /** 已转成学习计划（目标 + 任务）。 */
        PLANNED,
        /** 已补上——须有 closedByDocumentId 作为证据。 */
        CLOSED,
        /**
         * 判定为「不是我的缺口」。
         *
         * <p>与 {@link #CLOSED} 严格区分：DISMISSED 是一次<em>判断</em>
         * （超出目标范围、措辞问题、误报），CLOSED 是一次<em>成果</em>。
         * 混在一起会让「我补上了多少缺口」这个数字失真，
         * 而那是衡量知识体系是否在长的关键数字。</p>
         */
        DISMISSED;

        public static Status of(String s) {
            if (s == null) return OPEN;
            try {
                return valueOf(s.strip().toUpperCase());
            } catch (Exception e) {
                return OPEN;
            }
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 可空：CRAG 信号来自笔记检索时不属于任何仓库。缺口不该因未接入仓库而无法记录。 */
    @Column(name = "repo_id")
    private Long repoId;

    /** 原始问题原文——归一化结果不可读，看板要让用户一眼认出「这是我问过的」。 */
    @Column(nullable = false, length = 1024)
    private String question;

    /** 归一化结果，仅用于聚合去重。 */
    @Column(name = "norm_question", nullable = false, length = 255)
    private String normQuestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Source source = Source.MANUAL;

    @Column(name = "entity_id")
    private Long entityId;

    /** 被问到的次数——看板的排序键。「问得最多的盲区」优先补。 */
    @Column(name = "ask_count", nullable = false)
    private Integer askCount = 1;

    @Column(name = "first_at", nullable = false)
    private LocalDateTime firstAt = LocalDateTime.now();

    @Column(name = "last_at", nullable = false)
    private LocalDateTime lastAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    /** P0 | P1 | P2 */
    @Column(length = 8)
    private String priority;

    @Column(name = "github_issue_number")
    private Integer githubIssueNumber;

    /** 转成学习计划后关联的目标 id（复用既有 goal 体系）。 */
    @Column(name = "goal_id")
    private Long goalId;

    /** 由哪篇文档关闭——闭环闭合的证据。 */
    @Column(name = "closed_by_document_id")
    private Long closedByDocumentId;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(length = 1024)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 是否仍需处理。 */
    @Transient
    public boolean actionable() {
        return status == Status.OPEN || status == Status.PLANNED;
    }
}
