package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识落地检验条目（P1 验证闭环的核心实体）。
 *
 * <h3>它回答一个别的工具回答不了的问题</h3>
 * <p>「你说你学会了 MLIR，证据是什么？」→ 12 条检验通过 9 条其中 3 条 L2，
 * 预测准确率 67%，错的 4 条都沉淀了笔记。</p>
 *
 * <h3>两个字段承载了全部差异化价值</h3>
 * <ul>
 *   <li>{@link #prediction} + {@link #predictedAt}：「先预测再动手」的纪律锁。
 *       未填预测则拒绝运行——因为「改完再解释，人会自动为既成结果编理由」。
 *       {@code predictedAt} 一旦写入即冻结，防止事后修改预测。</li>
 *   <li>{@link #predictionCorrect}：<strong>「通过但预测错」</strong>信号。
 *       它精确定位「结果对但因果理解错」，是所有假通过里最危险的一类。
 *       预测错不扣分，<em>预测错但没发现自己错了</em>才是问题。</li>
 * </ul>
 *
 * <h3>为什么要有 verifySource</h3>
 * <p>从既有 Markdown 解析出的判据只能是「退出码 + 关键词」，达不到声明式
 * {@code expect} 的精确度。如实标注为 {@code PARSED} 并在 UI 明示「判据较弱」，
 * 比假装精确更诚实——否则用户会以为通过了就一定对。</p>
 */
@Entity
@Table(name = "kb_checkpoint",
        uniqueConstraints = @UniqueConstraint(name = "uk_cp_repo_code",
                columnNames = {"repo_id", "code"}),
        indexes = {
                @Index(name = "idx_cp_user_status", columnList = "user_id,status"),
                @Index(name = "idx_cp_doc", columnList = "document_id")
        })
@Data
public class KbCheckpoint {

    /** 检验分级：L2 是主判据——这一级过了才算掌握。 */
    public enum Level {
        /** 复现：跑通既有脚本，读懂产物。门槛，不算掌握。 */
        L0,
        /** 改一处：改一个参数/规则，先预测再验证。证明知道因果方向。 */
        L1,
        /** 加组件：新增 op / pass / pattern / 策略并补测试。★主判据。 */
        L2,
        /** 打通：跨层或端到端，涉及真实后端、多卡。加深项。 */
        L3;

        public static Level of(String s) {
            if (s == null) return L0;
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (Exception e) {
                return L0;
            }
        }
    }

    /** 判据来源。 */
    public enum VerifySource {
        /** 从既有 Markdown 解析推断，判据较弱（退出码 + 关键词）。 */
        PARSED,
        /** front-matter 显式声明，判据精确。 */
        DECLARED
    }

    public enum Status {
        /** 尚未填写预测。 */
        TODO,
        /** 已填预测，可以运行验收。 */
        PREDICTED,
        PASSED,
        FAILED,
        /** 资源不足走了降级判据（如无 GPU）——通过但结论强度较低。 */
        DEGRADED,
        SKIPPED
    }

    /** 判定「预测是否与实际一致」的裁判。 */
    public enum PredictionJudge {
        /** LLM 判定，会有误判，指标口径必须说明。 */
        AI,
        /** 用户自判，权威。 */
        USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    /** 原文条目的章节 anchor，供跳回核对。 */
    @Column(name = "section_anchor", length = 255)
    private String sectionAnchor;

    /** 形如 {@code L2-MLIR-04}。 */
    @Column(nullable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Level level = Level.L0;

    @Column(nullable = false, length = 512)
    private String title;

    /** 原文「检验什么」——说明这条通过意味着掌握了哪个知识点。 */
    @Column(name = "checks_what", length = 1024)
    private String checksWhat;

    @Column(length = 512)
    private String prerequisite;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(length = 128)
    private String lab;

    /** local | local+toolchain | gpu1 | gpuN | multinode */
    @Column(name = "resource_tag", length = 32)
    private String resourceTag;

    @Column(name = "est_hours", precision = 5, scale = 1)
    private BigDecimal estHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "verify_source", nullable = false, length = 16)
    private VerifySource verifySource = VerifySource.PARSED;

    /** {@code {cmd, cwd, timeout, expect:[...]}} */
    @Column(name = "verify_json", columnDefinition = "TEXT")
    private String verifyJson;

    @Column(name = "fallback_json", columnDefinition = "TEXT")
    private String fallbackJson;

    /** 原文「通过标准」，供人工核对机器判定是否合理。 */
    @Column(name = "pass_criteria", columnDefinition = "TEXT")
    private String passCriteria;

    /** 原文「常见失败 → 盲点」映射：失败时用它回指 guide 章节。 */
    @Column(name = "blind_spots", columnDefinition = "TEXT")
    private String blindSpots;

    @Column(name = "predict_required", nullable = false)
    private Boolean predictRequired = true;

    /** 用户填写的预测。★只能由用户经 HTTP 提交，Agent 无对应工具。 */
    @Column(columnDefinition = "TEXT")
    private String prediction;

    /** 预测冻结时刻——写入后 UI 转只读，判定时对比冻结版本。 */
    @Column(name = "predicted_at")
    private LocalDateTime predictedAt;

    /** 原文「先预测再动手」列出的提问，引导用户预测什么。 */
    @Column(name = "prediction_questions", columnDefinition = "TEXT")
    private String predictionQuestions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.TODO;

    @Column(name = "passed_at")
    private LocalDateTime passedAt;

    /** NULL=未判定，false=预测错（★最有价值），true=预测对。 */
    @Column(name = "prediction_correct")
    private Boolean predictionCorrect;

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_judge", length = 16)
    private PredictionJudge predictionJudge;

    /** 「我原以为…实际…」——建议沉淀为一篇笔记。 */
    @Column(columnDefinition = "TEXT")
    private String divergence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 预测门禁是否已满足。 */
    @Transient
    public boolean predictionSatisfied() {
        if (!Boolean.TRUE.equals(predictRequired)) return true;
        return prediction != null && !prediction.isBlank();
    }
}
