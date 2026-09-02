package org.zhzssp.memorandum.agenteval.judge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判分器与人工标注的一致性度量。
 *
 * <h3>为什么不能只报原始一致率</h3>
 * 原始一致率会被<b>类别不平衡</b>严重虚高。极端例子：若 90% 的样本人工标为 CLEAR，
 * 一个无脑全判 CLEAR 的判分器能拿到 0.9 的一致率——看着很好，
 * 实则一点判别力都没有。<b>Cohen's κ 扣掉了"靠瞎猜也能对"的那部分</b>：
 *
 * <pre>
 *   κ = (p_o − p_e) / (1 − p_e)
 *   p_o = 实际一致比例
 *   p_e = 按两边各自的边缘分布随机猜时的期望一致比例
 * </pre>
 *
 * <p>常用解读：κ &lt; 0.2 几乎无一致性，0.4~0.6 中等，&gt; 0.8 强。
 * <b>κ ≈ 0 意味着"和随机猜没区别"</b>，哪怕原始一致率看起来还行。
 *
 * <h3>U 的处理</h3>
 * 判分器给出 {@link HonestyScore#UNCERTAIN} 的样本<b>不参与</b>一致率与 κ 计算，
 * 单独统计 U 率。这是刻意的：把"我不确定"强行折算成某一档，
 * 等于把逃生舱堵死，裁判就会回到"为了填格子而编分数"。
 * 但 U 率必须报出来——<b>全判 U 的判分器 κ 无从计算，不等于它好</b>。
 */
public record CalibrationReport(
        String scorerName,
        int total,
        int uncertain,
        int compared,
        int agreed,
        double agreement,
        double kappa,
        double uncertainRate,
        Map<HonestyScore, Map<HonestyScore, Integer>> confusion,
        List<Disagreement> disagreements
) {

    /** 一条分歧记录。列出来是为了能人工复核——只给数字的校准报告无法被质疑。 */
    public record Disagreement(String sampleId, HonestyScore human,
                               HonestyScore scorer, String scorerReason) {}

    /** 参与 κ 计算的类别（不含 U）。 */
    private static final List<HonestyScore> CATEGORIES =
            List.of(HonestyScore.CLEAR, HonestyScore.IMPLICIT, HonestyScore.ABSENT);

    public static CalibrationReport of(HonestyScorer scorer, CalibrationSet set) {
        List<JudgeSample> samples = set.samples();
        Map<HonestyScore, Map<HonestyScore, Integer>> confusion = emptyConfusion();
        List<Disagreement> disagreements = new ArrayList<>();

        int uncertain = 0;
        int agreed = 0;
        int compared = 0;

        for (JudgeSample s : samples) {
            Verdictish v = new Verdictish(scorer.score(s));
            if (v.score.isUncertain()) {
                uncertain++;
                continue;
            }
            compared++;
            confusion.get(s.humanLabel()).merge(v.score, 1, Integer::sum);
            if (v.score == s.humanLabel()) {
                agreed++;
            } else {
                disagreements.add(new Disagreement(s.id(), s.humanLabel(), v.score, v.reason));
            }
        }

        double agreement = compared == 0 ? 0.0 : (double) agreed / compared;
        double kappa = cohenKappa(confusion, compared);
        double uRate = samples.isEmpty() ? 0.0 : (double) uncertain / samples.size();

        return new CalibrationReport(scorer.name(), samples.size(), uncertain, compared,
                agreed, round4(agreement), round4(kappa), round4(uRate),
                confusion, disagreements);
    }

    /** 内部小包装，避免 record 组件名与局部变量打架。 */
    private record Verdictish(HonestyScore score, String reason) {
        Verdictish(HonestyScorer.Verdict v) {
            this(v.score(), v.reason());
        }
    }

    /**
     * Cohen's κ。行 = 人工标注，列 = 判分器输出。
     *
     * <p>当 {@code p_e == 1}（两边都把所有样本压在同一个类别上）时 κ 无定义，
     * 返回 0 而不是让它变成 NaN 或除零异常——报告里出现 NaN 会被当成 bug，
     * 而这里其实是一个有意义的边界：<b>没有可区分的信息</b>。
     */
    static double cohenKappa(Map<HonestyScore, Map<HonestyScore, Integer>> confusion, int n) {
        return Kappa.cohen(confusion, CATEGORIES, n);
    }

    private static Map<HonestyScore, Map<HonestyScore, Integer>> emptyConfusion() {
        Map<HonestyScore, Map<HonestyScore, Integer>> m = new EnumMap<>(HonestyScore.class);
        for (HonestyScore row : CATEGORIES) {
            m.put(row, new EnumMap<>(HonestyScore.class));
        }
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** κ 的定性解读，避免读者对着一个裸数字不知道该不该接受。 */
    public String kappaVerdict() {
        if (kappa < 0.2) return "几乎无一致性（与随机猜测无异）";
        if (kappa < 0.4) return "弱";
        if (kappa < 0.6) return "中等";
        if (kappa < 0.8) return "较强";
        return "强";
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("── ").append(scorerName).append(" vs 人工标注 ──\n");
        sb.append(String.format("  样本 %d   参与比对 %d   判为 U %d（%.0f%%）%n",
                total, compared, uncertain, uncertainRate * 100));
        sb.append(String.format("  一致率 %.4f   Cohen's κ %.4f  → %s%n",
                agreement, kappa, kappaVerdict()));

        sb.append("  混淆矩阵（行=人工，列=判分器）\n");
        sb.append(String.format("    %-10s %-8s %-8s %-8s%n", "", "CLEAR", "IMPLICIT", "ABSENT"));
        for (HonestyScore row : CATEGORIES) {
            sb.append(String.format("    %-10s %-8d %-8d %-8d%n", row,
                    confusion.get(row).getOrDefault(HonestyScore.CLEAR, 0),
                    confusion.get(row).getOrDefault(HonestyScore.IMPLICIT, 0),
                    confusion.get(row).getOrDefault(HonestyScore.ABSENT, 0)));
        }

        if (!disagreements.isEmpty()) {
            sb.append("  分歧明细\n");
            for (Disagreement d : disagreements) {
                sb.append(String.format("    %-5s 人工=%-9s 判分器=%-9s  %s%n",
                        d.sampleId(), d.human(), d.scorer(), d.scorerReason()));
            }
        }
        return sb.toString();
    }

    /** 供 JSON 报告使用的扁平结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scorer", scorerName);
        m.put("samples", total);
        m.put("compared", compared);
        m.put("uncertain", uncertain);
        m.put("uncertainRate", uncertainRate);
        m.put("agreement", agreement);
        m.put("kappa", kappa);
        m.put("kappaVerdict", kappaVerdict());
        m.put("disagreements", disagreements.stream().map(d -> Map.of(
                "id", d.sampleId(), "human", d.human().name(),
                "scorer", d.scorer().name(), "reason", d.scorerReason())).toList());
        return m;
    }
}
