package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 止损线决策：某个知识点是「必须掌握」还是「先跳过（遇到再学）」。
 *
 * <p>这是目标仓库方法论里最被低估的一条纪律——每篇 guide 都写了「先跳过」清单，
 * 它防止了自学中最常见的死法：无限深潜。</p>
 *
 * <p><strong>{@code hitCount} 是本表的产品价值所在</strong>：
 * 「遇到再学」的前提是能感知到「遇到了」，而这件事目前完全无人监测。
 * 当某个 SKIP 概念被反复问到，说明当初的判断需要修正——
 * 止损线由此从单向决策变成<strong>可召回</strong>的机制（P3 缺口三源之一）。</p>
 */
@Entity
@Table(name = "kb_scope_decision",
        uniqueConstraints = @UniqueConstraint(name = "uk_scope_entity", columnNames = "entity_id"))
@Data
public class KbScopeDecision {

    public enum Decision {
        /** 必须掌握。 */
        MUST,
        /** 先跳过，遇到再学——不是放弃，是延后。 */
        SKIP,
        /** 明确不学（超出目标范围）。 */
        DROPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Decision decision = Decision.MUST;

    @Column(length = 512)
    private String reason;

    @Column(name = "decided_in_document_id")
    private Long decidedInDocumentId;

    /** SKIP 概念被「遇到」（提问命中）的次数，达阈值即提示召回。 */
    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;
}
