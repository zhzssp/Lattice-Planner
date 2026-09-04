package org.zhzssp.memorandum.agenteval.cost;

/**
 * 一个用例（单次试验）的 LLM 用量快照。
 *
 * <p>字段刻意分成<b>两组</b>，因为它们在回放模式下的可信度截然不同，
 * 理由见 {@link UsageAccumulator} 的类注释。混着用会得到一个测不出 prompt 膨胀的成本门禁。
 *
 * @param llmCalls          LLM 调用次数（两种模式下都真实——它由 Agent 实际循环次数决定）
 * @param callsWithUsage    其中<b>带回 usage</b> 的次数。小于 {@code llmCalls} 说明
 *                          token 统计是<b>不完整</b>的，成本会偏低，报告必须把这个数亮出来
 * @param promptTokens      ★<b>回放时来自录制盒</b>，反映录制当天的 prompt
 * @param completionTokens  ★同上
 * @param cacheHitTokens    ★同上。输入中命中前缀缓存的部分
 * @param cacheMissTokens   ★同上
 * @param requestChars      <b>活体</b>：本次真实发出的 messages 的 content 字符总数
 * @param responseChars     <b>活体</b>：响应正文字符总数（回放时正文来自录制盒，
 *                          所以它反映的是"录制的回答有多长"，不是当前模型会答多长）
 * @param upstreamLatencyMs 上游真实耗时；<b>回放时为 null</b>——
 *                          回放不联网，那点毫秒是本机回放速度，与线上延迟毫无关系。
 *                          与其报一个 0 让人误读，不如报 null 并在报告里写明 n/a
 */
public record UsageSnapshot(
        long llmCalls,
        long callsWithUsage,
        long promptTokens,
        long completionTokens,
        long cacheHitTokens,
        long cacheMissTokens,
        long requestChars,
        long responseChars,
        Long upstreamLatencyMs
) {

    public static final UsageSnapshot EMPTY =
            new UsageSnapshot(0, 0, 0, 0, 0, 0, 0, 0, null);

    /** token 统计是否完整。false 时成本必然偏低。 */
    public boolean usageComplete() {
        return llmCalls > 0 && callsWithUsage == llmCalls;
    }

    /** 前缀缓存命中率（输入侧）。没有 token 数据时返回 null 而不是 0——那是两回事。 */
    public Double cacheHitRate() {
        long input = cacheHitTokens + cacheMissTokens;
        return input == 0 ? null : (double) cacheHitTokens / input;
    }

    /**
     * 实测「几个字符折合一个输入 token」。
     *
     * <p>这个值把活体字符数与真实 token 数<b>系在一起</b>：
     * 门禁跑在字符上（回放里唯一可信的量），而这个比值告诉你
     * "涨了 3000 字符" 大约对应多少 token、多少钱。
     *
     * <p>它是<b>本项目语料实测</b>的，不是从别处抄来的经验系数——
     * 中英混排 + 大段工具目录的 prompt，比值和纯中文/纯英文都不一样。
     *
     * <p>只有当 token 统计完整时才有意义，否则返回 null。
     */
    public Double charsPerPromptToken() {
        if (promptTokens <= 0 || requestChars <= 0 || !usageComplete()) return null;
        return (double) requestChars / promptTokens;
    }

    /**
     * 相加（把多个试次/用例合并成总量）。延迟只在两边都有时相加。
     *
     * <p>延迟这一段刻意<b>不用三元表达式</b>：三元的分支里只要出现一个基本类型，
     * 整个表达式的类型就退化为基本类型，于是<b>所有</b>分支都被拆箱——
     * 精心写好的 null 判断会被 {@code +} 悄悄拆掉，两边都是 null 时直接 NPE。
     * 这个坑是 {@code CostModelTest.plusHandlesLatency} 抓出来的。
     */
    public UsageSnapshot plus(UsageSnapshot o) {
        Long lat;
        if (upstreamLatencyMs == null) {
            lat = o.upstreamLatencyMs;
        } else if (o.upstreamLatencyMs == null) {
            lat = upstreamLatencyMs;
        } else {
            lat = upstreamLatencyMs + o.upstreamLatencyMs;
        }
        return new UsageSnapshot(
                llmCalls + o.llmCalls,
                callsWithUsage + o.callsWithUsage,
                promptTokens + o.promptTokens,
                completionTokens + o.completionTokens,
                cacheHitTokens + o.cacheHitTokens,
                cacheMissTokens + o.cacheMissTokens,
                requestChars + o.requestChars,
                responseChars + o.responseChars,
                lat);
    }
}
