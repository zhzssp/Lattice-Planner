package org.zhzssp.memorandum.agenteval.judge;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 裁判的准入判定：<b>它是否值得被引入</b>。
 *
 * <p>裁判更贵、更慢、还带方差。要用它，就得证明<b>现有的确定性判分器都做不到这个水平</b>。
 * 本类把这个判断从测试里的一行内联算术提出来，因为它自己已经是一段有内容的逻辑了——
 * 而这个项目一以贯之的立场是：<b>量表本身也要被量</b>。
 *
 * <h3>坑一：基准必须是"当前最好的确定性判分器"，不是某个固定的历史基线</h3>
 * 原先的门槛拿 {@link KeywordBaseline}（κ ≈ −0.069）当基准。
 * 后来生产判据被修好，κ 从 −0.02 升到 0.605，而 KeywordBaseline 还停在原地。
 * 继续拿它当基准，一个 κ=0.3 的裁判也能"显著优于基线"——
 * <b>可它其实远不如已经上线跑着的那个东西</b>。门槛就此变成自欺。
 *
 * <p>所以基准取<b>全部确定性判分器里最好的那个</b>。
 * 经济学上的问法是："有没有更便宜的东西已经做到这个水平了？"有，就不该付钱。
 *
 * <h3>坑二：固定增量在量程两端含义完全不同</h3>
 * 原先要求"κ 比基线高 0.3"。基线 −0.069 时这意味着 κ ≥ 0.231，很松；
 * 基线 0.605 时却意味着 κ ≥ 0.905，<b>几乎要求完美一致</b>——
 * 同一个数字，在两端一个近乎白送、一个近乎不可能。
 *
 * <p>改成按<b>剩余空间</b>算：要求裁判吃掉"基线到满分"之间至少 {@value #HEADROOM_SHARE}
 * 的差距。基线越高，绝对增量要求越小，但相对难度保持一致。
 *
 * <pre>
 *   required = incumbent + 0.4 × (1 − incumbent)
 *
 *   基线 −0.069  →  需要 0.359     （原固定增量只要 0.231，太松）
 *   基线  0.605  →  需要 0.763     （原固定增量要 0.905，太苛）
 *   基线  1.000  →  需要 &gt; 1.000   （不可能达到 ⇒ 永远拒绝）
 * </pre>
 *
 * <p>最后那条边界是刻意的、也是正确的：<b>若一个零成本的确定性判分器已经完美，
 * 再引入裁判只是徒增成本与方差</b>，此时"拒绝"才是对的答案。
 *
 * <h3>它不能回答什么</h3>
 * n=20 的样本上，κ 的抽样方差很大，<b>0.05 量级的差异毫无统计意义</b>。
 * 本门槛给的是一个工程决策，不是显著性检验。
 * 之所以把阈值定在"吃掉四成剩余空间"这么粗的粒度上，正是因为更细的差别在这个样本量下读不出来。
 */
public record JudgeAdmission(
        String incumbentName,
        double incumbentKappa,
        String judgeName,
        double judgeKappa,
        double requiredKappa,
        boolean admitted
) {

    /** 裁判至少要吃掉"基线到满分"之间这么大比例的差距。 */
    public static final double HEADROOM_SHARE = 0.4;

    /**
     * 以一组确定性判分器中<b>最好的</b>作为基准，判定裁判是否够格。
     *
     * @param incumbents 已有的确定性判分器报告，不可为空
     * @param judge      裁判的报告
     */
    public static JudgeAdmission against(List<CalibrationReport> incumbents, CalibrationReport judge) {
        if (incumbents == null || incumbents.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少要给一个确定性基准。没有对照物的『裁判很好』是不可证伪的");
        }
        CalibrationReport best = incumbents.stream()
                .max(Comparator.comparingDouble(CalibrationReport::kappa))
                .orElseThrow();
        return evaluate(best.scorerName(), best.kappa(), judge.scorerName(), judge.kappa());
    }

    /**
     * 纯算术入口，便于直接对边界取值做验证。
     *
     * <p>阈值先取整再比较，好让<b>报告里印出来的那个数就是实际生效的数</b>——
     * 否则会出现"门槛显示 0.763、裁判 0.763，却判不准入"这种无法自辩的输出。
     * 恰好相等时判不准入：打平不构成引入成本的理由。
     */
    public static JudgeAdmission evaluate(String incumbentName, double incumbentKappa,
                                          String judgeName, double judgeKappa) {
        double required = round4(incumbentKappa + HEADROOM_SHARE * (1.0 - incumbentKappa));
        return new JudgeAdmission(incumbentName, incumbentKappa, judgeName, judgeKappa,
                required, judgeKappa > required);
    }

    /** 剩余空间里已经被吃掉的比例；基线已满分时无定义，返回 NaN。 */
    public double headroomClosed() {
        double headroom = 1.0 - incumbentKappa;
        if (headroom <= 0) return Double.NaN;
        return round4((judgeKappa - incumbentKappa) / headroom);
    }

    public String render() {
        return String.format(
                "── 裁判准入 ──%n"
                        + "  基准（现有最好的确定性判分器）：%s  κ=%.4f%n"
                        + "  裁判：%s  κ=%.4f%n"
                        + "  门槛：κ > %.4f（吃掉剩余空间的 %.0f%%）%n"
                        + "  实际吃掉：%.1f%%   → %s%n",
                incumbentName, incumbentKappa, judgeName, judgeKappa,
                requiredKappa, HEADROOM_SHARE * 100,
                headroomClosed() * 100,
                admitted ? "准入" : "★不准入：应放弃裁判，回头改进确定性判分");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incumbent", incumbentName);
        m.put("incumbentKappa", incumbentKappa);
        m.put("judge", judgeName);
        m.put("judgeKappa", judgeKappa);
        m.put("requiredKappa", requiredKappa);
        m.put("headroomShare", HEADROOM_SHARE);
        m.put("headroomClosed", headroomClosed());
        m.put("admitted", admitted);
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
