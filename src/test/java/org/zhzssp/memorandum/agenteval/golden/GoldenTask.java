package org.zhzssp.memorandum.agenteval.golden;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个评测任务的<b>轨迹契约</b>：期望调哪些工具、禁止调哪些、参考顺序是什么。
 *
 * <p>把这些声明从断言里抽出来单独成对象，是为了让它们可被<b>度量</b>而不只是被<b>校验</b>：
 * 同一份声明既用于判定用例过不过，也用于算工具选择精确率/召回率，
 * 进而回答"它为什么不对"——是漏调了、多调了，还是顺序错了。
 *
 * <h3>关于 referenceOrder 的立场（借鉴 τ-bench）</h3>
 * 它是<b>一条参考路径，不是唯一正确路径</b>，只在存在真实数据依赖时才声明
 * （例如必须先 {@code goal.create} 拿到 id，才能 {@code goal.link_task}）。
 * 把它当唯一解会让评测退化成"是否复现了我写的那条路径"——
 * 那测的是相似度，不是正确性。本项目多数任务是单步的，一刀切会产生大量无意义的满分。
 *
 * <h3>关于 forbiddenTools</h3>
 * 负例声明和正例同等重要。只测"该调工具时调了"，会纵容一个<b>什么都想动手</b>的 Agent：
 * 用户只是问一句"我这周有啥安排"，它顺手建了条任务——
 * 这在轨迹层面很难发现（工具都在可见列表里、调用也成功），却是实打实的伤害。
 */
public record GoldenTask(
        String caseId,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        List<String> referenceOrder,
        int maxRedundantCalls
) {

    public static GoldenTask of(String caseId) {
        return new GoldenTask(caseId, Set.of(), Set.of(), List.of(), 0);
    }

    /** 声明期望调用的工具（无序）。 */
    public GoldenTask expecting(String... tools) {
        Set<String> merged = new LinkedHashSet<>(expectedTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, Set.copyOf(merged), forbiddenTools,
                referenceOrder, maxRedundantCalls);
    }

    /** 声明明确不该调用的工具。任何一次命中都应判红。 */
    public GoldenTask forbidding(String... tools) {
        Set<String> merged = new LinkedHashSet<>(forbiddenTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, expectedTools, Set.copyOf(merged),
                referenceOrder, maxRedundantCalls);
    }

    /**
     * 声明参考顺序，<b>并自动把这些工具计入期望集</b>——
     * 声明了顺序却不算作期望，是最容易犯的配置错误。
     */
    public GoldenTask inOrder(String... tools) {
        Set<String> merged = new LinkedHashSet<>(expectedTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, Set.copyOf(merged), forbiddenTools,
                List.of(tools), maxRedundantCalls);
    }

    /** 允许的冗余调用次数上限（即调用了期望集之外的工具的次数）。 */
    public GoldenTask maxRedundant(int n) {
        return new GoldenTask(caseId, expectedTools, forbiddenTools, referenceOrder, n);
    }

    /** 是否声明了参考顺序（未声明时不计算顺序一致性，记 n/a）。 */
    public boolean hasReferenceOrder() {
        return referenceOrder != null && referenceOrder.size() >= 2;
    }
}
