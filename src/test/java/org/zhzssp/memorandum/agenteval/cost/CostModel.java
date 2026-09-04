package org.zhzssp.memorandum.agenteval.cost;

/**
 * 把 token 用量折成钱，并顺带算出<b>前缀缓存到底省了多少</b>。
 *
 * <h3>为什么要单独报「缓存省了多少钱」</h3>
 * 本项目为前缀缓存做了不少工作（system prompt 前缀稳定、稳定事实按天粒度刷新、
 * 工具噪声不进上下文）。这些工作此前只能用<b>hash 稳定性</b>间接论证：
 * "前缀没变，所以缓存应该命中"。
 *
 * <p>但"应该命中"和"确实省下了钱"之间隔着一整条上游链路。
 * {@link #savedByCacheUsd} 走的是另一条路：拿上游<b>真实返回</b>的
 * {@code prompt_cache_hit_tokens}，按命中价与未命中价各算一遍，两者之差就是省下的钱。
 * 这是一个**结果侧**的度量——不管中间那套推理对不对，省没省下来，账上见。
 *
 * <h3>★ 这个数字能说什么、不能说什么</h3>
 * <ul>
 *   <li><b>能说</b>：在录制那一刻，这些请求的输入有多大比例被上游按缓存命中价计费。</li>
 *   <li><b>不能说</b>：线上真实账单。评测只跑 13 个短会话，
 *       而缓存命中率高度依赖<b>会话长度与请求间隔</b>——
 *       真实用户连续对话十几轮的命中率，和这里不是一回事。</li>
 *   <li><b>回放时完全不能说</b>：那时的 token 数来自录制盒，
 *       钱是"录制当天花的"，不是"现在会花的"。</li>
 * </ul>
 *
 * <h3>为什么是纯函数</h3>
 * 与 {@code ReliabilityMetrics} / {@code TrajectoryMetrics} / {@code RetrievalMetrics} 一致：
 * 指标算术必须能脱离 Spring、脱离网络单测。
 * 一个只能在完整评测跑起来时才被间接验证的指标，出了错没人会发现。
 */
public final class CostModel {

    private static final double PER_MILLION = 1_000_000.0;

    private CostModel() {
    }

    /**
     * 成本明细。
     *
     * @param inputCacheHitUsd  输入中命中缓存部分的花费
     * @param inputCacheMissUsd 输入中未命中部分的花费
     * @param outputUsd         输出花费
     * @param savedByCacheUsd   ★前缀缓存省下的钱 = 命中的那些 token 若按未命中价计费的差额
     * @param pricedByFallback  是否用了 fallback 单价（模型未登记）。
     *                          true 时数字是<b>按最贵档高估</b>的，报告必须标注
     */
    public record CostEstimate(
            double inputCacheHitUsd,
            double inputCacheMissUsd,
            double outputUsd,
            double savedByCacheUsd,
            boolean pricedByFallback
    ) {
        public double totalUsd() {
            return inputCacheHitUsd + inputCacheMissUsd + outputUsd;
        }

        /**
         * 若完全没有缓存命中，这次要花多少。
         *
         * <p>报告里把它和 {@link #totalUsd()} 并排放，"缓存省了多少"就不需要读者自己做减法。
         */
        public double totalWithoutCacheUsd() {
            return totalUsd() + savedByCacheUsd;
        }

        public CostEstimate plus(CostEstimate o) {
            return new CostEstimate(
                    inputCacheHitUsd + o.inputCacheHitUsd,
                    inputCacheMissUsd + o.inputCacheMissUsd,
                    outputUsd + o.outputUsd,
                    savedByCacheUsd + o.savedByCacheUsd,
                    pricedByFallback || o.pricedByFallback);
        }

        public static final CostEstimate ZERO = new CostEstimate(0, 0, 0, 0, false);
    }

    /**
     * 按用量与单价折算成本。
     *
     * <p>输入侧刻意用 {@code cacheHitTokens + cacheMissTokens} 而不是 {@code promptTokens}：
     * 两者本应相等，但上游偶尔会给出不自洽的组合。用前者可以保证
     * "命中 + 未命中" 的两笔账加起来正好等于实际计费的输入量，
     * 不会出现"分项之和对不上总数"这种在成本报表里最招人怀疑的现象。
     */
    public static CostEstimate estimate(UsageSnapshot usage, String model, PriceTable prices) {
        if (usage == null) return CostEstimate.ZERO;
        PriceTable.Rate r = prices.rateFor(model);

        double hitUsd = usage.cacheHitTokens() / PER_MILLION * r.inputCacheHit();
        double missUsd = usage.cacheMissTokens() / PER_MILLION * r.inputCacheMiss();
        double outUsd = usage.completionTokens() / PER_MILLION * r.output();

        // 省下的钱：命中的 token 若按未命中价计费的差额
        double saved = usage.cacheHitTokens() / PER_MILLION
                * (r.inputCacheMiss() - r.inputCacheHit());

        return new CostEstimate(hitUsd, missUsd, outUsd, Math.max(0, saved),
                !prices.isKnown(model));
    }
}
