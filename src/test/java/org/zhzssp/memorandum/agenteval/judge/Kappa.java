package org.zhzssp.memorandum.agenteval.judge;

import java.util.List;
import java.util.Map;

/**
 * Cohen's κ：扣掉"瞎猜也能蒙对"的那部分之后，判分器与人工标注还剩多少一致。
 *
 * <h3>为什么不能只报一致率</h3>
 * 标签分布一旦不均衡，一致率就会虚高得离谱。假设 80% 的样本人工标的都是同一档，
 * 一个<b>无脑全判那一档</b>的判分器能拿到 80% 一致率——但它的信息量是零。
 * κ 把这份"运气"扣掉：全靠蒙的判分器 κ≈0，完美一致 κ=1，
 * <b>系统性反着判则 κ&lt;0</b>。
 *
 * <p>经验阈值：κ&lt;0.2 几乎等于随机，0.4~0.6 算中等，&gt;0.6 才谈得上可信。
 *
 * <h3>抽出来成泛型的原因</h3>
 * 诚实度（P3）与忠实度（P4）是两套彼此无关的标签，但统计口径必须<b>逐字相同</b>，
 * 否则两个数字没法放在一起看。复制一遍公式迟早会分叉——
 * 而统计公式分叉是最难发现的那类 bug：两边都跑得出数，只是含义悄悄变了。
 */
final class Kappa {

    private Kappa() {}

    /**
     * @param confusion  行=人工标注，列=判分器输出
     * @param categories 参与计算的类别（<b>不含"不确定"档</b>）
     * @param n          参与计算的样本数
     */
    static <T> double cohen(Map<T, Map<T, Integer>> confusion, List<T> categories, int n) {
        if (n == 0) return 0.0;

        double observed = 0;
        for (T c : categories) {
            observed += confusion.get(c).getOrDefault(c, 0);
        }
        double po = observed / n;

        double pe = 0;
        for (T c : categories) {
            int rowSum = categories.stream().mapToInt(x -> confusion.get(c).getOrDefault(x, 0)).sum();
            int colSum = categories.stream().mapToInt(x -> confusion.get(x).getOrDefault(c, 0)).sum();
            pe += ((double) rowSum / n) * ((double) colSum / n);
        }

        // pe==1 表示两侧都只用了同一个类别：此时"一致"不携带任何信息，
        // 按 0 处理而不是让分母归零。这不是回避除零，
        // 而是一个有意义的边界——没有可区分的信息。
        if (pe >= 1.0) return 0.0;
        return (po - pe) / (1 - pe);
    }
}
