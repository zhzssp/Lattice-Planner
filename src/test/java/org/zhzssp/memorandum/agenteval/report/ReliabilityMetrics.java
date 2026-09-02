package org.zhzssp.memorandum.agenteval.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code pass@k} 与 {@code pass^k} 的纯函数实现。
 *
 * <h3>定义</h3>
 * 设用例 i 在 k 次独立试验中通过 c_i 次，共 N 个用例：
 * <pre>
 *   pass@k = |{ i : c_i ≥ 1 }| / N     能力：至少成功一次
 *   pass^k = |{ i : c_i = k }| / N     可靠性：每次都成功
 * </pre>
 *
 * <h3>为什么必须同时报出两者</h3>
 * 只报 {@code pass@k} 是粉饰。设单次成功率 p=0.75、k=3：
 * <pre>
 *   pass@3 = 1 - (1-0.75)^3 = 98.4%     看起来近乎完美
 *   pass^3 = 0.75^3         = 42.2%     实际上过半会翻车
 * </pre>
 * 对一个要替用户改数据的助手，后者才是它真实的样子。两者之差即<b>不稳定度</b>。
 *
 * <p>抽成独立的纯函数而非埋在报告聚合里，是为了让这段算术能被单独测试——
 * 指标算错比没有指标更糟，因为它会让人放心。
 */
public record ReliabilityMetrics(
        int cases,
        int k,
        double passAtK,
        double passHatK,
        double instability,
        List<String> flakyCases
) {

    /**
     * @param outcomesByCase 用例 id → 各次试验是否通过。空 list 的用例会被忽略。
     */
    public static ReliabilityMetrics of(Map<String, List<Boolean>> outcomesByCase) {
        int n = 0;
        int maxK = 0;
        long atK = 0;
        long hatK = 0;
        List<String> flaky = new ArrayList<>();

        for (Map.Entry<String, List<Boolean>> e : outcomesByCase.entrySet()) {
            List<Boolean> trials = e.getValue();
            if (trials == null || trials.isEmpty()) continue;
            n++;
            maxK = Math.max(maxK, trials.size());
            long passes = trials.stream().filter(Boolean.TRUE::equals).count();
            if (passes >= 1) atK++;
            if (passes == trials.size()) {
                hatK++;
            } else if (passes >= 1) {
                // 时好时坏的用例单独列出：它们是最该优先排查的，
                // 因为纯失败的用例通常是明确的 bug，而 flaky 往往藏着竞态或提示词歧义
                flaky.add(e.getKey() + "(" + passes + "/" + trials.size() + ")");
            }
        }

        return new ReliabilityMetrics(n, maxK, rate(atK, n), rate(hatK, n),
                rate(atK - hatK, n), List.copyOf(flaky));
    }

    private static double rate(long num, long den) {
        return den == 0 ? 0.0 : Math.round((double) num / den * 10000.0) / 10000.0;
    }
}
