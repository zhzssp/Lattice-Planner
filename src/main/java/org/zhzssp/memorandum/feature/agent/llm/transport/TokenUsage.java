package org.zhzssp.memorandum.feature.agent.llm.transport;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次 LLM 调用的 token 用量，已归一化为与上游厂商无关的形状。
 *
 * <h3>为什么要单独抽出来</h3>
 * 同一份 {@code usage} 现在有两个消费方：线上的 {@code PrefixCacheMetrics}（观测前缀缓存命中率）
 * 与评测侧的成本核算。两边若各写一份解析，就会出现<b>校准报告描述的不是线上真正在跑的那个东西</b>——
 * 这个教训在 {@code DisclosureInspector} 上已经付过一次学费，那次是判分器与生产判据各写一份关键词表。
 *
 * <h3>为什么必须容忍两种上游格式</h3>
 * <ul>
 *   <li><b>DeepSeek</b>：{@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens}，
 *       两者<b>不相交且相加等于</b> {@code prompt_tokens}；</li>
 *   <li><b>OpenAI 兼容</b>：只给 {@code prompt_tokens_details.cached_tokens}，
 *       未命中数需要用 {@code prompt_tokens} 相减。</li>
 * </ul>
 * 本项目支持多 provider 路由，两种都会遇到。
 *
 * <h3>{@code present} 为什么是必要的一个字段，而不能用「全 0」代替</h3>
 * 「上游没返回 usage」和「上游返回了但确实是 0」在成本核算里是<b>两件不同的事</b>：
 * 前者意味着这次调用的开销<b>未知</b>，后者意味着<b>没有开销</b>。
 * 把未知当成 0 累加进总量，会得到一个偏低且没人察觉的成本数字——
 * 而成本指标一旦偏低，它就不再有任何门禁价值。
 * 所以调用方能够、也应当报出「有多少次调用是缺 usage 的」。
 *
 * @param present          上游是否真的返回了可解析的 usage
 * @param promptTokens     输入 token 总数（含缓存命中部分）
 * @param completionTokens 输出 token 数
 * @param cacheHitTokens   输入中命中前缀缓存的部分（计费远低于未命中）
 * @param cacheMissTokens  输入中未命中的部分
 */
public record TokenUsage(
        boolean present,
        long promptTokens,
        long completionTokens,
        long cacheHitTokens,
        long cacheMissTokens
) {

    /** 上游未返回 usage 时的空值。 */
    public static final TokenUsage ABSENT = new TokenUsage(false, 0, 0, 0, 0);

    /**
     * 解析上游 {@code usage} 节点；无法解析时返回 {@link #ABSENT}。
     *
     * <p><b>任何异常都吞掉并返回 ABSENT</b>：用量统计是观测，
     * 不能因为上游多返回/少返回一个字段就把主链路打断。
     */
    public static TokenUsage parse(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return ABSENT;
        try {
            long prompt = usage.path("prompt_tokens").asLong(0);
            long completion = usage.path("completion_tokens").asLong(0);

            long hit = usage.path("prompt_cache_hit_tokens").asLong(-1);
            long miss = usage.path("prompt_cache_miss_tokens").asLong(-1);
            if (hit >= 0 || miss >= 0) {
                return new TokenUsage(true, prompt, completion,
                        Math.max(0, hit), Math.max(0, miss));
            }

            long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(-1);
            if (cached >= 0) {
                return new TokenUsage(true, prompt, completion,
                        cached, Math.max(0, prompt - cached));
            }

            // 有 token 数但完全没有缓存信息：仍是有效 usage，只是缓存维度未知。
            // 此时把输入全部记为「未命中」——成本上这是<b>保守</b>方向（未命中更贵），
            // 宁可高估也不要让成本指标偏低。
            if (prompt > 0 || completion > 0) {
                return new TokenUsage(true, prompt, completion, 0, prompt);
            }
            return ABSENT;
        } catch (Exception ignore) {
            return ABSENT;
        }
    }

    /** 前缀缓存命中率；无输入 token 时返回 0。 */
    public double cacheHitRate() {
        long input = cacheHitTokens + cacheMissTokens;
        return input == 0 ? 0.0 : (double) cacheHitTokens / input;
    }
}
