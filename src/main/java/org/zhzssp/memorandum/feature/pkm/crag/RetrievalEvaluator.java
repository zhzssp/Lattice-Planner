package org.zhzssp.memorandum.feature.pkm.crag;

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
 *   <li>CORRECT（relevance ≥ upper）：直接使用</li>
 *   <li>AMBIGUOUS（lower ≤ relevance &lt; upper）：保留 + 尝试改写重检索</li>
 *   <li>INCORRECT（relevance &lt; lower 或无命中）：触发纠错</li>
 * </ul>
 *
 * <h3>为什么判级看 relevance 而不是 score</h3>
 * {@code score} 是排序用的融合分数，量纲随 alpha 与命中通路浮动：向量通路的上限是
 * {@code alpha × 1.0}，关键字通路 rank 0 的分数是 {@code 1 - alpha}。
 * 拿它去比一个固定阈值属于量纲错配——生产默认 {@code alpha=0.4} 时向量上限恰好
 * 等于 {@code lower=0.4}，于是关键字通路一旦缺席（FULLTEXT 索引未建、
 * 查询被 ngram 切没、或评测环境用 H2），<b>再完美的语义匹配也会被判成 INCORRECT</b>，
 * 多跳问题因此 100% 被误判为「没检索到」。
 *
 * <p>改判 {@code relevance}（余弦，与 alpha 和通路都无关）之后，
 * 阈值 0.6 / 0.4 才真正表达「强相关 / 弱相关 / 不相关」这个本意。
 *
 * <p>默认走零 LLM 成本的分级，AMBIGUOUS 时可开 LLM 精判。
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
     * 基于最高语义相关度判级（零 LLM 成本，默认路径）。
     *
     * <p>取全部命中里的<b>最高</b>相关度而非首条：{@code score} 与 {@code relevance}
     * 排序未必一致，问的是「这批结果里有没有够好的」，不是「第一条够不够好」。
     *
     * <p>全部命中都没有相关度（只有关键字通路存活，例如没配 EMBED_API_KEY）时判
     * AMBIGUOUS：此时手上没有任何可比的语义信号，既不该宣称 CORRECT，
     * 也不该反过来说检索失败。让它落在中间档，由改写重检索去争取更好的结果。
     */
    public Grade grade(List<RagSearchService.Hit> hits) {
        if (hits == null || hits.isEmpty()) return Grade.INCORRECT;

        double best = -1;
        for (RagSearchService.Hit h : hits) {
            if (h.relevance() != null) best = Math.max(best, h.relevance());
        }
        if (best < 0) {
            log.debug("[CRAG] 命中里没有任何语义相关度（向量通路缺席），判 AMBIGUOUS");
            return Grade.AMBIGUOUS;
        }

        if (best >= upper) return Grade.CORRECT;
        if (best >= lower) return Grade.AMBIGUOUS;
        return Grade.INCORRECT;
    }

    public double getUpper() { return upper; }
    public double getLower() { return lower; }
}
