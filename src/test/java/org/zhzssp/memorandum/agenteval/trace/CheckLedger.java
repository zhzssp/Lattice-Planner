package org.zhzssp.memorandum.agenteval.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个用例（单个试次）的<b>判分台账</b>：记下每一条断言的成败，而不是一失败就抛。
 *
 * <h3>为什么要把"抛异常"推迟到用例收尾</h3>
 * 原来的 {@code TrajectoryAssert} 是<b>首次失败即抛</b>，于是链上后面的断言<b>一条都不会跑</b>。
 * 这在真实排查里吃过亏：{@code batch_complete_overdue_only} 挂在
 * "漏调了期望工具 task.complete"，异常一抛，后面两条端状态断言就再没执行过——
 * 我因此<b>不知道</b>筛选是否正确、那条不该碰的任务有没有被误伤，
 * 只能自己去翻录制盒。而那两条恰恰是判断"错得多离谱"的关键信息。
 *
 * <p>换句话说：<b>首次失败即抛，等于每次失败都只告诉你排在最前面的那个症状。</b>
 * 症状的排序取决于我写断言的顺序，和缺陷的严重程度毫无关系。
 *
 * <h3>部分得分（Anthropic 评测框架的"partial credit"）</h3>
 * 全判完之后，"过了几条 / 共几条"自然就有了。二值判定下
 * "筛选全对但一个都没写" 和 "全错" 都记 0 分，这两种情况的<b>改进距离差得远</b>，
 * 而能力集恰恰要回答的就是"还差多远"。
 *
 * <h3>安全性：不会因为忘了写终结调用而静默失效</h3>
 * 台账由 {@code AgentEvalBase} 在 {@code @AfterEach} 里<b>无条件</b>结算。
 * 没有"必须记得调 {@code .verify()}"这种约定——
 * 依赖自觉的规则迟早会被漏掉，而漏掉的后果是断言<b>静默地不判</b>，
 * 那正是本项目反复栽过的那类事故（见 §6.11、§6.13）。
 */
public final class CheckLedger {

    /**
     * 一条判定。
     *
     * @param name    判定名（端状态断言用其描述文本，轨迹断言用方法语义）
     * @param passed  是否通过
     * @param detail  失败详情；通过时为 null
     * @param scored  是否计入部分得分。轨迹类断言（如"无幻觉"）是<b>不变量</b>而非
     *                能力刻度，混进分母会稀释掉真正想量的东西
     */
    public record Check(String name, boolean passed, String detail, boolean scored) {}

    private static final ThreadLocal<CheckLedger> CURRENT = new ThreadLocal<>();

    private final List<Check> checks = new ArrayList<>();

    /** 开一本新台账并绑定到当前线程。每个试次开始时调用。 */
    public static CheckLedger begin() {
        CheckLedger l = new CheckLedger();
        CURRENT.set(l);
        return l;
    }

    /**
     * 当前线程的台账；未开启时返回一本<b>游离</b>的台账而不是 null。
     *
     * <p>返回游离台账而非抛异常，是为了让 {@code TrajectoryAssert} 能被
     * 纯单测直接使用（那里没有评测基座去 begin）。代价是那种场景下不结算，
     * 但那种场景本来就是单测自己在判 —— 它会自己抛。
     */
    public static CheckLedger current() {
        CheckLedger l = CURRENT.get();
        return l == null ? new CheckLedger() : l;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public void record(String name, boolean passed, String detail, boolean scored) {
        checks.add(new Check(name, passed, detail, scored));
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

    public List<Check> failures() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }

    public boolean hasFailure() {
        return checks.stream().anyMatch(c -> !c.passed());
    }

    /** 计入得分的判定总数。 */
    public int scoredTotal() {
        return (int) checks.stream().filter(Check::scored).count();
    }

    /** 计入得分且通过的判定数。 */
    public int scoredPassed() {
        return (int) checks.stream().filter(c -> c.scored() && c.passed()).count();
    }

    /**
     * 部分得分 [0,1]；没有任何计分项时返回 null（记 n/a，而不是伪造一个 1.0）。
     *
     * <p>无计分项返回 1.0 会让"这个用例根本没做端状态断言"和
     * "端状态全对"在报告里长得一模一样——那是又一种假绿。
     */
    public Double partialScore() {
        int total = scoredTotal();
        if (total == 0) return null;
        return Math.round((double) scoredPassed() / total * 10000) / 10000.0;
    }

    /** 人类可读的失败汇总。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        List<Check> failed = failures();
        sb.append("判定台账：").append(checks.size() - failed.size())
                .append('/').append(checks.size()).append(" 通过");
        Double score = partialScore();
        if (score != null) {
            sb.append("　　部分得分 ").append(scoredPassed()).append('/')
                    .append(scoredTotal()).append(" = ").append(score);
        }
        sb.append('\n');
        for (Check c : checks) {
            sb.append("  ").append(c.passed() ? "✓" : "✗").append(' ')
                    .append(c.scored() ? "[计分] " : "[不变量] ")
                    .append(c.name()).append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("\n失败详情：\n");
            for (int i = 0; i < failed.size(); i++) {
                sb.append("── 失败 ").append(i + 1).append('/').append(failed.size())
                        .append(" ──\n").append(failed.get(i).detail()).append('\n');
            }
        }
        return sb.toString();
    }
}
