package org.zhzssp.memorandum.agenteval.rag;

import java.util.List;
import java.util.Set;

/**
 * 检索质量指标：recall@k / precision@k / MRR。
 *
 * <h3>为什么必须和生成质量分开报</h3>
 * 一个混合的"RAG 好不好"数字是陷阱——<b>分数掉了你不知道该调检索还是调 prompt</b>。
 * 检索侧的失败（该召的没召回来）和生成侧的失败（召回来了但编造）
 * 修法完全不同，混在一起等于放弃归因。
 *
 * <h3>三个指标各自抓什么</h3>
 * <ul>
 *   <li><b>recall@k</b>：该召的召回来了几个。<b>RAG 里最关键的一个</b>——
 *       没召回来的内容，生成端再强也变不出来</li>
 *   <li><b>precision@k</b>：召回来的里面有几个是对的。低了会挤占上下文预算，
 *       且给模型提供了编造的素材</li>
 *   <li><b>MRR</b>：第一条正确结果排在多靠前。截断到 topK 时，
 *       排第 8 和排第 1 的实际效果天差地别，而 recall 对此完全不敏感</li>
 * </ul>
 *
 * <h3>不可回答类问题不走这里</h3>
 * 相关集为空时 recall 是 0/0，硬塞进来只会得到 NaN 或一个恒为满分的假象。
 * 那类问题该被度量的是<b>"有没有正确降级"</b>，是个二值判断，
 * 由 {@link RagGoldenReport} 单独统计。<b>把它算进平均召回率，
 * 会让一个从不召回任何东西的系统在不可答题上拿满分</b>。
 */
public record RetrievalMetrics(
        int k,
        int relevantCount,
        int retrievedCount,
        int hitCount,
        double recallAtK,
        double precisionAtK,
        /** 第一条命中的倒数排名；一条都没命中为 0。 */
        double reciprocalRank,
        /** 排名从 1 开始；未命中为 -1。用于诊断"召回了但排太后"。 */
        int firstHitRank
) {

    /**
     * @param retrieved 检索返回的文档 id，<b>按相关度降序</b>
     * @param relevant  金标声明的相关文档 id
     * @param k         截断位置
     */
    public static RetrievalMetrics of(List<Long> retrieved, Set<Long> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            throw new IllegalArgumentException(
                    "相关集为空的问题（不可回答类）不该走 recall/precision，"
                            + "应当度量它是否正确降级。见 RetrievalMetrics 类注释");
        }
        List<Long> topK = retrieved == null ? List.of()
                : retrieved.subList(0, Math.min(k, retrieved.size()));

        int hits = (int) topK.stream().filter(relevant::contains).count();
        double recall = (double) hits / relevant.size();
        double precision = topK.isEmpty() ? 0.0 : (double) hits / topK.size();

        int firstHitRank = -1;
        for (int i = 0; i < topK.size(); i++) {
            if (relevant.contains(topK.get(i))) {
                firstHitRank = i + 1;
                break;
            }
        }
        double rr = firstHitRank < 0 ? 0.0 : 1.0 / firstHitRank;

        return new RetrievalMetrics(k, relevant.size(), topK.size(), hits,
                round4(recall), round4(precision), round4(rr), firstHitRank);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public String render() {
        return String.format("recall@%d=%.2f precision@%d=%.2f RR=%.2f (命中 %d/%d，首次命中排名 %s)",
                k, recallAtK, k, precisionAtK, reciprocalRank, hitCount, relevantCount,
                firstHitRank < 0 ? "未命中" : firstHitRank);
    }
}
