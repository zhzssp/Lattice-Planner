package org.zhzssp.memorandum.agenteval.facts;

import java.util.ArrayList;
import java.util.List;

/**
 * Facts 抽取的判定级计分：按「期望片段是否出现在某条抽取 value 里」匹配。
 *
 * <p>不比 key。模型自己起的英文短键不稳定，比 key 只会把抽取质量测成命名习惯。</p>
 */
public final class ExtractionMetrics {

    private ExtractionMetrics() {
    }

    /**
     * @param extractedValues 模型抽出的 value 列表
     * @param expectedTokens  每条期望事实是一组必须同时出现的子串
     */
    public static Score score(List<String> extractedValues, List<List<String>> expectedTokens) {
        List<String> extracted = extractedValues == null ? List.of() : extractedValues;
        List<List<String>> expected = expectedTokens == null ? List.of() : expectedTokens;

        boolean[] hitExpected = new boolean[expected.size()];
        int tp = 0;
        int fp = 0;
        for (String value : extracted) {
            int match = indexOfMatch(value, expected, hitExpected);
            if (match >= 0) {
                if (!hitExpected[match]) {
                    hitExpected[match] = true;
                    tp++;
                }
            } else if (!expected.isEmpty() || !value.isBlank()) {
                // 期望为空时，任何抽出都是误抽
                if (expected.isEmpty()) fp++;
                else fp++;
            }
        }
        int fn = 0;
        for (boolean hit : hitExpected) {
            if (!hit) fn++;
        }
        return new Score(tp, fp, fn);
    }

    private static int indexOfMatch(String value, List<List<String>> expected, boolean[] already) {
        if (value == null) return -1;
        String v = value.toLowerCase();
        for (int i = 0; i < expected.size(); i++) {
            if (already[i]) continue;
            List<String> tokens = expected.get(i);
            boolean all = true;
            for (String t : tokens) {
                if (t == null || !v.contains(t.toLowerCase())) {
                    all = false;
                    break;
                }
            }
            if (all && !tokens.isEmpty()) return i;
        }
        return -1;
    }

    public record Score(int tp, int fp, int fn) {
        public double precision() {
            int den = tp + fp;
            return den == 0 ? 1.0 : Math.round((double) tp / den * 10000) / 10000.0;
        }

        public double recall() {
            int den = tp + fn;
            return den == 0 ? 1.0 : Math.round((double) tp / den * 10000) / 10000.0;
        }
    }

    public static Score plus(Score a, Score b) {
        return new Score(a.tp + b.tp, a.fp + b.fp, a.fn + b.fn);
    }

    public static List<Score> accumulate(List<Score> scores) {
        return new ArrayList<>(scores);
    }
}
