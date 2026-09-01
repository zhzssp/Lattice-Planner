package org.zhzssp.memorandum.feature.agent.memory;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话事实（上下文工程 P1 第二步）。
 *
 * <p>长期记忆 {@code AGENT_MEMO} 回答「这人是谁」，本表回答「这段对话里有哪些
 * 还没失效的硬事实」——deadline、技术选型、关键决定。两者不可互相替代。</p>
 *
 * <h3>★为什么 {@code sourceQuote} + {@code sourceTurn} 是最重要的两列</h3>
 * <p>一条 LLM 抽出来的 fact，若无法追溯它来自哪句话，抽错时用户发现不了、
 * 也没法纠正。而错误的 fact 会被注入<strong>每一轮</strong>，污染面比一次
 * 错误回答大得多。所以「可核对」不是锦上添花，是这条链路可信的前提。</p>
 */
@Entity
@Table(name = "agent_fact",
        uniqueConstraints = @UniqueConstraint(name = "uk_fact_user_key_session",
                columnNames = {"user_id", "fact_key", "session_id"}),
        indexes = {
                @Index(name = "idx_fact_user_kind_status",
                        columnList = "user_id,kind,status")
        })
@Data
public class AgentFact {

    /** 事实类别：决定注入到 prompt 的哪个位置（见设计文档第三章）。 */
    public enum Kind {
        /** 长期偏好 / 习惯，变更频率天级，进 system prompt（参与 memoHash）。 */
        STABLE,
        /** 会话内具体约束，变更频率每轮，进 history 首条（不打穿前缀缓存）。 */
        VOLATILE
    }

    /**
     * 抽取置信度。<strong>声明顺序即强度顺序</strong>，{@code ordinal()} 越大越弱——
     * 入库下限过滤依赖这个顺序（见 {@code FactService.parseExtracted}）。
     *
     * <p>LOW 参与枚举只是为了让「低于下限」这件事可被表达并因此被过滤掉；
     * 它永远不会落库，因为过滤发生在 save 之前。</p>
     */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        /**
         * 解析模型输出的置信度。无法识别一律当 LOW —— 宁缺勿错：
         * 模型漏写或乱写 confidence 时，该条候选应当被下限挡掉，而不是蒙混成 MEDIUM 入库。
         */
        public static Confidence ofCandidate(String s) {
            return parse(s, LOW);
        }

        /**
         * 解析配置里的入库下限。无法识别回落 MEDIUM —— 与 ofCandidate 反向：
         * 配置笔误不该把闸门开到最大（若也回落 LOW，则等于什么都收）。
         */
        public static Confidence ofFloor(String s) {
            return parse(s, MEDIUM);
        }

        private static Confidence parse(String s, Confidence fallback) {
            if (s == null) return fallback;
            for (Confidence c : values()) {
                if (c.name().equalsIgnoreCase(s.trim())) return c;
            }
            return fallback;
        }
    }

    /** 生命周期状态。 */
    public enum Status {
        /** 当前生效，会被注入。 */
        ACTIVE,
        /** 被同 key 的新值覆盖（保留历史，不注入）。 */
        SUPERSEDED,
        /** 被用户标记为错误（永不再抽同 key）。 */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(name = "fact_key", nullable = false, length = 64)
    private String factKey;

    @Column(name = "fact_value", nullable = false, length = 512)
    private String factValue;

    @Column(name = "source_quote", length = 512)
    private String sourceQuote;

    @Column(name = "source_turn")
    private Integer sourceTurn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Confidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
