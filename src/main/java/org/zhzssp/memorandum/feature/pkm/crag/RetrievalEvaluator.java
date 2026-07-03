g1package org.zhzssp.memorandum.feature.pkm.crag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.util.List;

/**
 * 检索质量评估器（C1）。
 *
 * <p>对 RagSearchService 返回的命中做快速分级，支撑 CRAG 状态机决策：
 * <ul>
 *   <li>CORRECT（score ≥ upper）：直接使用</li>
 *   <li>AMBIGUOUS（lower ≤ score < upper）：保留 + 尝试改写重检索</li>
 *   <li>INCORRECT（score < lower 或无命中）：触发纠错</li>
 * </ul>
 *
 * <p>默认走零 LLM 成本的分级（仅用已有融合分数），AMBIGUOUS 时可开 LLM 精判。
 */
@Component
public class RetrievalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);

    public enum Grade { CORRECT, AMBIGUOUS, INCORRECT }

    @Value("${pkm.crag.upper:0.6}")
    private double upper;

    @Value("${pkm.crag.lower:0.4}")
    private double lower;

    /**
     * 轻量：基于最高融合分数判级（零 LLM 成本，默认路径）。
     */
    public Grade gradeByScore(List<RagSearchService.Hit> hits) {
        if (hits == null || hits.isEmpty()) return Grade.INCORRECT;
        double top = hits.get(0).score();
        if (top >= upper) return Grade.CORRECT;
        if (top >= lower) return Grade.AMBIGUOUS;
        return Grade.INCORRECT;
    }

    public double getUpper() { return upper; }
    public double getLower() { return lower; }
}
