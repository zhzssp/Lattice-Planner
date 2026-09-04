package org.zhzssp.memorandum.agenteval.cost;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成本门禁的<b>纯判定逻辑</b>：把「本次实测」与「已提交的预算基线」逐用例比对。
 *
 * <h3>★ 这道门禁到底在守什么（想清楚之后结论有点反直觉）</h3>
 * 直觉上成本门禁该守「LLM 调用次数」。但在回放模式下它<b>几乎是多余的</b>：
 * Agent 若比录制时多调一次，{@code ReplayLlmTransport} 会因<b>录制耗尽</b>直接抛错，
 * 根本轮不到门禁说话；若少调，那是变便宜了。
 *
 * <p>真正只有这道门禁能守的是 <b>{@code requestChars}——prompt 的活体字符数</b>：
 * <ul>
 *   <li>它<b>不受录制盒固定</b>，由当前代码实时算出；</li>
 *   <li>往 system prompt / 工具描述里塞东西，<b>不会</b>改变调用次数，
 *       也<b>不会</b>让任何既有断言变红——它只是让每一次调用都变贵一点；</li>
 *   <li>而回放返回的 {@code usage} 仍是录制当天的 token 数，
 *       所以<b>基于 token 的成本门禁在回放下结构上看不见这次膨胀</b>。</li>
 * </ul>
 * 一句话：<b>prompt 膨胀是这套评测里唯一「不会让任何东西变红」的退化</b>，
 * 而它恰恰是最容易发生的一种——每个人都觉得自己只是"再补一句说明"。
 *
 * <h3>为什么基线是一份提交进仓库的文件</h3>
 * 因为预算的<b>放宽必须是一次可评审的动作</b>。
 * 写在代码常量里，改它和改任何一行代码没有区别，review 时混在 diff 里根本注意不到；
 * 写成独立的数据文件，"这次改动把 A 用例的 prompt 预算提高了 35%" 会作为一条
 * 孤零零的数据 diff 出现在 PR 里，想装看不见都难。
 *
 * <p>这与「发现缺陷后写成断言而不是写进 issue」是同一条思路：
 * <b>把判断固化成一个会自己说话的东西</b>。
 *
 * <h3>基线也会腐烂，所以要检测</h3>
 * 只查"超没超"是不够的。改动让成本<b>降下来</b>之后，若没人更新基线，
 * 基线就一直停在高位，等于给未来的回退预留了一大截白跑的空间——
 * 门禁看着在，实际已经松了。
 * 所以实测显著低于基线时会产出 {@link Status#STALE}，提醒回收这部分余量。
 */
public final class BudgetGate {

    /**
     * prompt 字符数的允许涨幅——<b>0.5%，远严于设计初稿的 20%</b>。
     *
     * <h4>为什么敢这么严：这个量在回放下没有噪声</h4>
     * 实测过：往 system prompt 加 5 行说明，再跑一遍，13 个用例的字符数增量
     * <b>恰好都是 149 × 该用例的调用次数</b>，一个字符不多不少。
     * 也就是说，除了我改的那 5 行，两次运行之间<b>没有任何东西变化</b>——
     * 录制盒固定了模型回复，H2 每个用例前清库固定了工具结果。
     *
     * <p>既然测量本身零噪声，容差就<b>不该是"给成长留的预算"</b>，
     * 而只该覆盖那点真实的抖动（见 {@link #ABSOLUTE_ALLOWANCE}）。
     *
     * <h4>20% 的容差会漏掉什么（这才是把它砍到 0.5% 的真正理由）</h4>
     * 上面那次改动只占 1.1%。也就是说 20% 的门禁能<b>连续放过十几次</b>
     * "我就再补一句说明"——而每一次都不会让任何断言变红。
     * 等到它终于触线，早就没人说得清这 20% 是哪几次改动攒出来的了。
     * <b>预算门禁的价值不在于拦住暴涨，而在于让每一次"顺手加两句"都当场显形。</b>
     */
    public static final double CHARS_TOLERANCE = 0.005;

    /**
     * 字符数的绝对宽容量：无论基线多小，都至少允许这么多字符的抖动。
     *
     * <p>它对应的是<b>两个真实的、无害的</b>抖动来源：
     * <ul>
     *   <li>prompt 里的日期串——月/日跨过 10 会多一个字符；</li>
     *   <li>工具结果里的自增主键——id 从 9 涨到 10 也会多一个字符，
     *       而 id 取决于用例执行顺序，JUnit 并不保证顺序恒定。</li>
     * </ul>
     * 两者加起来撑死几个字符，64 已经很宽。
     * 刻意<b>不</b>把它设成"能容下一句话"——那就变成成长预算了。
     */
    public static final long ABSOLUTE_ALLOWANCE = 64;

    /**
     * 判为「基线已过时」的下界：实测低于基线的这个比例。
     *
     * <p>比涨幅容忍度松得多（20% vs 0.5%）是有意的，而且理由和"上涨"那侧正相反：
     * <b>超支要判红，所以宁可严；过时只多印一行提示，严了纯属噪声</b>。
     * 两类误报的代价差着量级，阈值就不该对称。
     *
     * <p>另外，下降 0.5% 去提示"基线该收了"也没有意义——
     * 收回来的那点余量还不够写这条提示的字数。
     */
    public static final double STALE_BELOW = 0.20;

    public enum Status {
        /** 在预算内。 */
        OK,
        /** ★超支：必须判红。 */
        OVER,
        /** 实测显著低于基线，基线该往下收了（不判红）。 */
        STALE,
        /** 基线里没有这个用例（新增用例尚未登记预算）。 */
        UNTRACKED,
        /** 基线里有、但本次没跑到（用例被删或被过滤掉了）。 */
        MISSING
    }

    /**
     * 单条判定。
     *
     * @param allowed 允许的上限；{@link Status#UNTRACKED} / {@link Status#MISSING} 时无意义
     */
    public record Verdict(String caseId, String metric, long baseline, long actual,
                          long allowed, Status status) {

        /** 相对基线的涨跌幅；基线为 0 时返回 null（除不了）。 */
        public Double delta() {
            return baseline == 0 ? null : (double) (actual - baseline) / baseline;
        }
    }

    private BudgetGate() {
    }

    /**
     * 逐用例比对。
     *
     * @param baseline 已提交的基线：caseId → 指标名 → 值
     * @param actual   本次实测，同构
     */
    public static List<Verdict> check(Map<String, Map<String, Long>> baseline,
                                      Map<String, Map<String, Long>> actual) {
        List<Verdict> out = new ArrayList<>();

        Set<String> allCases = new LinkedHashSet<>(baseline.keySet());
        allCases.addAll(actual.keySet());

        for (String caseId : allCases) {
            Map<String, Long> base = baseline.get(caseId);
            Map<String, Long> act = actual.get(caseId);

            if (base == null) {
                // 新用例：报出来但不判红——否则加一个用例就得先跑一次再补基线，很反人类
                act.forEach((metric, v) ->
                        out.add(new Verdict(caseId, metric, 0, v, 0, Status.UNTRACKED)));
                continue;
            }
            if (act == null) {
                base.forEach((metric, v) ->
                        out.add(new Verdict(caseId, metric, v, 0, v, Status.MISSING)));
                continue;
            }

            Set<String> metrics = new LinkedHashSet<>(base.keySet());
            metrics.addAll(act.keySet());
            for (String metric : metrics) {
                Long b = base.get(metric);
                Long a = act.get(metric);
                if (b == null) {
                    out.add(new Verdict(caseId, metric, 0, a == null ? 0 : a, 0, Status.UNTRACKED));
                    continue;
                }
                if (a == null) {
                    out.add(new Verdict(caseId, metric, b, 0, b, Status.MISSING));
                    continue;
                }
                out.add(judge(caseId, metric, b, a));
            }
        }
        return out;
    }

    /**
     * 单个指标的判定。
     *
     * <p>两个指标用<b>不同</b>的判据，理由见类注释：
     * <ul>
     *   <li>{@code llmCalls}：<b>零容忍</b>。回放下它是确定性的，
     *       任何上涨都意味着行为真的变了；而在小数字上算百分比毫无意义
     *       （1→2 是 +100%，2→3 是 +50%，同样是多一次调用）。</li>
     *   <li>{@code requestChars}：允许 {@value #CHARS_TOLERANCE} 或
     *       {@value #ABSOLUTE_ALLOWANCE} 字符中的<b>较大者</b>，只为容下日期/自增 id
     *       那点抖动。取较大者是为了让小 prompt 的用例也有绝对余量——
     *       否则 13000 字符的 0.5% 是 65，而 200 字符的 0.5% 只有 1，
     *       一个 id 多一位就红了。</li>
     * </ul>
     */
    static Verdict judge(String caseId, String metric, long baseline, long actual) {
        long allowed = "llmCalls".equals(metric)
                ? baseline
                : baseline + Math.max(ABSOLUTE_ALLOWANCE,
                        (long) Math.floor(baseline * CHARS_TOLERANCE));

        if (actual > allowed) {
            return new Verdict(caseId, metric, baseline, actual, allowed, Status.OVER);
        }
        if (baseline > 0 && actual < baseline * (1 - STALE_BELOW)) {
            return new Verdict(caseId, metric, baseline, actual, allowed, Status.STALE);
        }
        return new Verdict(caseId, metric, baseline, actual, allowed, Status.OK);
    }

    /** 超支项。非空即应判红。 */
    public static List<Verdict> overruns(List<Verdict> verdicts) {
        return verdicts.stream().filter(v -> v.status() == Status.OVER).toList();
    }

    /**
     * 把判定渲染成人话。
     *
     * <p>失败信息里<b>必须</b>写清楚"怎样才是正当地更新基线"。
     * 一道只会喊失败、不告诉你下一步的门禁，最终一定会被人直接删掉——
     * 这比没有门禁更糟，因为删掉的那一刻通常没人记得它当初在守什么。
     */
    public static String render(List<Verdict> verdicts) {
        StringBuilder sb = new StringBuilder();
        List<Verdict> over = overruns(verdicts);
        List<Verdict> stale = verdicts.stream().filter(v -> v.status() == Status.STALE).toList();
        List<Verdict> untracked = verdicts.stream().filter(v -> v.status() == Status.UNTRACKED).toList();

        if (!over.isEmpty()) {
            sb.append("★ 超出预算基线：\n");
            for (Verdict v : over) {
                sb.append(String.format("   %-32s %-14s 基线=%-8d 实测=%-8d 上限=%-8d (%+.1f%%)%n",
                        v.caseId(), v.metric(), v.baseline(), v.actual(), v.allowed(),
                        v.delta() == null ? 0 : v.delta() * 100));
            }
            sb.append("""

                    这不一定是 bug——但它必须是一个<有意识的>决定。
                    · 若这次改动<不该>让成本上涨：回去看是不是往 prompt / 工具描述里多塞了东西。
                      requestChars 是活体测量，回放里的 token 数是录制值，看不出膨胀，只有这一项看得出。
                    · 若上涨是<预期内>的（新增了必要的能力）：重新生成基线并提交——
                          gradlew agentEval "-Dagent.eval.budget=write"
                      基线文件的 diff 会把「这次把预算放宽了多少」明明白白摆进 code review。
                    """);
        }
        if (!stale.isEmpty()) {
            sb.append("\n基线已偏高（实测显著低于基线，等于给回退预留了余量，建议回收）：\n");
            for (Verdict v : stale) {
                sb.append(String.format("   %-32s %-14s 基线=%-8d 实测=%-8d (%+.1f%%)%n",
                        v.caseId(), v.metric(), v.baseline(), v.actual(),
                        v.delta() == null ? 0 : v.delta() * 100));
            }
        }
        if (!untracked.isEmpty()) {
            sb.append("\n未登记预算的用例（新增用例，跑一次 -Dagent.eval.budget=write 即可纳入）：\n");
            untracked.stream().map(Verdict::caseId).distinct()
                    .forEach(c -> sb.append("   ").append(c).append('\n'));
        }
        return sb.toString();
    }
}
