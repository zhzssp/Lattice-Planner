package org.zhzssp.memorandum.agenteval.report;

import org.zhzssp.memorandum.agenteval.golden.GoldenTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具选择质量的分解指标：把"轨迹不对"拆成可定位的几种具体错法。
 *
 * <h3>为什么要分解</h3>
 * {@code calledTool("task.create")} 只能回答"对不对"，回答不了"为什么不对"。
 * 而漏调、多调、顺序错这三种错法的修法完全不同：
 * 漏调多半是工具描述不清，多调多半是提示词鼓励了过度行动，顺序错才是规划能力问题。
 * 一个混在一起的通过率会把这三者的信号全部抹平。
 *
 * <h3>定义</h3>
 * 设 A = 实际调用序列（含重复），Aset = 其去重集合，E = 期望工具集：
 * <pre>
 *   精确率 = |Aset ∩ E| / |Aset|      惩罚多调
 *   召回率 = |Aset ∩ E| / |E|         惩罚漏调
 *   冗余调用 = A 中工具不属于 E 的调用次数
 *   禁用命中 = A 中工具属于禁用集的调用次数
 *   顺序一致性 = Kendall's τ（仅当声明了参考顺序）
 * </pre>
 *
 * <p><b>冗余只统计"期望集外的调用"，不把重复调用同一个期望工具算作冗余。</b>
 * 因为失败后重试同一个工具是合理行为（见 {@code tool_error_recovery}：
 * 第一次参数错、第二次改对），把它算成冗余会惩罚正确的自纠。
 */
public record TrajectoryMetrics(
        int actualCalls,
        int expectedCount,
        double precision,
        double recall,
        int redundantCalls,
        int forbiddenHits,
        /** 顺序一致性；未声明参考顺序或可比工具不足 2 个时为 null（记 n/a）。 */
        Double kendallTau,
        List<String> unexpectedTools,
        List<String> missingTools,
        List<String> forbiddenCalled
) {

    public static TrajectoryMetrics of(List<String> actual, GoldenTask task) {
        List<String> a = actual == null ? List.of() : actual;
        Set<String> expected = task.expectedTools();
        Set<String> forbidden = task.forbiddenTools();

        Set<String> aset = new LinkedHashSet<>(a);
        Set<String> hit = new LinkedHashSet<>(aset);
        hit.retainAll(expected);

        List<String> unexpected = aset.stream().filter(t -> !expected.contains(t)).toList();
        List<String> missing = expected.stream().filter(t -> !aset.contains(t)).sorted().toList();
        List<String> forbiddenCalled = aset.stream().filter(forbidden::contains).toList();

        // 空集约定：没调任何工具 → 精确率满分（没调错东西）；没有期望 → 召回满分（没东西可漏）。
        // 不做这个约定的话，负例（期望集为空）会得到 0/0，指标变成 NaN 或误判为失败。
        double precision = aset.isEmpty() ? 1.0 : round4((double) hit.size() / aset.size());
        double recall = expected.isEmpty() ? 1.0 : round4((double) hit.size() / expected.size());

        int redundant = (int) a.stream().filter(t -> !expected.contains(t)).count();
        int forbiddenHits = (int) a.stream().filter(forbidden::contains).count();

        Double tau = task.hasReferenceOrder() ? kendallTau(a, task.referenceOrder()) : null;

        return new TrajectoryMetrics(a.size(), expected.size(), precision, recall,
                redundant, forbiddenHits, tau, unexpected, missing, forbiddenCalled);
    }

    /**
     * Kendall's τ：实际首次调用顺序与参考顺序的一致性，取值 [-1, 1]，1 为完全一致。
     *
     * <p>只在<b>两边都出现</b>的工具上计算。参考顺序里没被调到的工具属于"漏调"，
     * 由召回率负责，不该在这里被重复惩罚一次——
     * 一个错误被两个指标各扣一次分，会让人误判问题的严重程度。
     *
     * <p>可比工具不足 2 个时无法定义顺序，返回 null。
     */
    static Double kendallTau(List<String> actual, List<String> reference) {
        List<Integer> actualPos = new ArrayList<>();
        for (String tool : reference) {
            int idx = actual.indexOf(tool);   // 首次出现位置
            if (idx >= 0) actualPos.add(idx);
        }
        int n = actualPos.size();
        if (n < 2) return null;

        int concordant = 0;
        int discordant = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                // 参考顺序里 i 在 j 前；实际里也该如此
                if (actualPos.get(i) < actualPos.get(j)) concordant++;
                else discordant++;
            }
        }
        int total = concordant + discordant;
        return total == 0 ? null : round4((double) (concordant - discordant) / total);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 人类可读摘要，断言失败时输出。 */
    public String render() {
        StringBuilder sb = new StringBuilder("轨迹指标:\n");
        sb.append(String.format("  调用 %d 次，期望工具 %d 个%n", actualCalls, expectedCount));
        sb.append(String.format("  精确率 %.2f   召回率 %.2f   冗余 %d   禁用命中 %d%n",
                precision, recall, redundantCalls, forbiddenHits));
        sb.append("  顺序一致性 ").append(kendallTau == null ? "n/a（未声明参考顺序）" : kendallTau).append('\n');
        if (!missingTools.isEmpty()) sb.append("  漏调: ").append(missingTools).append('\n');
        if (!unexpectedTools.isEmpty()) sb.append("  多调: ").append(unexpectedTools).append('\n');
        if (!forbiddenCalled.isEmpty()) sb.append("  ★禁用工具被调用: ").append(forbiddenCalled).append('\n');
        return sb.toString();
    }
}
