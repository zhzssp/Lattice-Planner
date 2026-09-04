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
 *
 * <h3>关于 toleratedTools（★真实录制逼出来的第三种语义）</h3>
 * 原先只有"期望"和"禁止"两档，于是<b>期望集为空的负例</b>会退化成
 * "调任何工具都算冗余"——因为冗余的定义是"调了期望集之外的工具"。
 *
 * <p>手写录制盒时这个缺陷看不出来：盒子里的模型被写成一上来就答复，一个工具都不调。
 * 换成真实录制后立刻暴露——用户说"帮我安排一下"，真实模型先去读了任务和目标
 * 才反问"你想安排什么"，<b>这是更好的行为，却被判红</b>。
 *
 * <p>所以补第三档：<b>容许但不要求</b>。它表达的是"这些工具你用不用都行"，
 * 与"期望"（不用就是漏）和"禁止"（用了就是错）都不同。
 * 刻意做成<b>按名字列举</b>而不是"放宽冗余上限到 N 次"：
 * 后者只约束数量不约束种类，一个用例调三个不相干的写工具照样能过——
 * 而这类负例真正在意的恰恰是<b>调了什么</b>，不是调了几个。
 */
public record GoldenTask(
        String caseId,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        Set<String> toleratedTools,
        List<String> referenceOrder,
        int maxRedundantCalls
) {

    /**
     * 无副作用的只读工具全集（即带 {@code read} tag 的那批），
     * 供 {@link #toleratingReadOnlyExploration()} 使用。
     *
     * <h3>为什么要有这个常量，而不是每个用例自己列</h3>
     * 按用例逐个列举时，模型换一个同义读工具（{@code goal.list} → {@code goal.list_all}、
     * 多读一次 {@code note.list}）用例就红一次，然后我们把它补进名单——
     * 这个循环的终点是<b>"改到绿为止"</b>，而不是"契约写对了"。
     * 真实录制里连着两轮都栽在这上面，说明按名字列举这条路本身就是错的。
     *
     * <p>这些用例真正的契约从来不是"必须调这几个读工具"，
     * 而是<b>"读什么都行，就是不许写"</b>。那就把它照原样写出来。
     *
     * <p><b>刻意排除 {@code planner.draft_goal_plan}</b>：它虽然带 {@code read}
     * （确实不落库），但会起一个子规划器、一次 5~9 次 LLM 调用。
     * "无副作用"和"廉价"是两件事，这里要的是后者——
     * 一个随口一问就烧钱的工具不该被默认容许。
     */
    public static final Set<String> READ_ONLY_TOOLS = Set.of(
            "task.search", "task.today", "task.fuzzy_pending",
            "goal.list", "goal.list_all",
            "note.list",
            "kb.semantic_search", "kb.lookup_by_title",
            "kb.list_backlinks", "kb.list_ingested_docs",
            "insight.daily_scores", "insight.summarize_period"
    );

    public static GoldenTask of(String caseId) {
        return new GoldenTask(caseId, Set.of(), Set.of(), Set.of(), List.of(), 0);
    }

    /** 声明期望调用的工具（无序）。 */
    public GoldenTask expecting(String... tools) {
        Set<String> merged = new LinkedHashSet<>(expectedTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, Set.copyOf(merged), forbiddenTools,
                toleratedTools, referenceOrder, maxRedundantCalls);
    }

    /** 声明明确不该调用的工具。任何一次命中都应判红。 */
    public GoldenTask forbidding(String... tools) {
        Set<String> merged = new LinkedHashSet<>(forbiddenTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, expectedTools, Set.copyOf(merged),
                toleratedTools, referenceOrder, maxRedundantCalls);
    }

    /**
     * 声明<b>容许但不要求</b>的工具：调了不算多调，不调也不算漏调。
     *
     * <p>典型用途是"为了把话问清楚而先去读一读"这类合理探索。
     * 它在指标上是<b>中性</b>的——既不计入冗余，也不进精确率的分母，
     * 但仍会在断言失败的输出里单独列出来，不藏起来。
     */
    public GoldenTask tolerating(String... tools) {
        Set<String> merged = new LinkedHashSet<>(toleratedTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, expectedTools, forbiddenTools,
                Set.copyOf(merged), referenceOrder, maxRedundantCalls);
    }

    /**
     * 容许<b>任意只读工具</b>的探索，见 {@link #READ_ONLY_TOOLS}。
     *
     * <p>适用于契约是"不许写"而非"必须走某条读路径"的用例：
     * 负例、澄清类、能力边界类。它把断言的立场从
     * "复现我选的那条路径"换成"别产生副作用"——后者才是这些用例真正在守的东西。
     *
     * <p>注意它<b>不会</b>放宽 {@code forbidding}：禁用集始终优先，
     * 所以"容许一切读"不等于"什么都能过"。
     */
    public GoldenTask toleratingReadOnlyExploration() {
        return tolerating(READ_ONLY_TOOLS.toArray(String[]::new));
    }

    /**
     * 声明参考顺序，<b>并自动把这些工具计入期望集</b>——
     * 声明了顺序却不算作期望，是最容易犯的配置错误。
     */
    public GoldenTask inOrder(String... tools) {
        Set<String> merged = new LinkedHashSet<>(expectedTools);
        merged.addAll(List.of(tools));
        return new GoldenTask(caseId, Set.copyOf(merged), forbiddenTools,
                toleratedTools, List.of(tools), maxRedundantCalls);
    }

    /** 允许的冗余调用次数上限（即调用了期望集与容许集之外的工具的次数）。 */
    public GoldenTask maxRedundant(int n) {
        return new GoldenTask(caseId, expectedTools, forbiddenTools,
                toleratedTools, referenceOrder, n);
    }

    /** 是否声明了参考顺序（未声明时不计算顺序一致性，记 n/a）。 */
    public boolean hasReferenceOrder() {
        return referenceOrder != null && referenceOrder.size() >= 2;
    }
}
