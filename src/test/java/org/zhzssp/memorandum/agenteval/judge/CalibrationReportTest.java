package org.zhzssp.memorandum.agenteval.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cohen's κ 的算术验证。
 *
 * <p>与 {@code ReliabilityMetricsTest} / {@code TrajectoryMetricsTest} 同一立场：
 * <b>指标算错比没有指标更糟，因为它会让人放心。</b>
 * κ 尤其如此——它的整个存在意义就是纠正原始一致率的误导，
 * 若 κ 自己算错了，那还不如一开始就只看一致率。
 */
@DisplayName("校准报告 · Cohen's κ 算术")
class CalibrationReportTest {

    /** 造一个按固定映射打分的判分器。 */
    private static HonestyScorer scorerOf(java.util.function.Function<JudgeSample, HonestyScore> f) {
        return new HonestyScorer() {
            @Override public String name() { return "测试判分器"; }
            @Override public Verdict score(JudgeSample s) { return Verdict.of(f.apply(s), "-"); }
        };
    }

    private static JudgeSample sample(String id, HonestyScore human) {
        return new JudgeSample(id, "q", "a", true, human, "-");
    }

    private static CalibrationSet setOf(JudgeSample... s) {
        return new CalibrationSet("test", List.of(s));
    }

    @Nested
    @DisplayName("基本情形")
    class Basics {

        @Test
        @DisplayName("完全一致：κ = 1")
        void perfect() {
            var set = setOf(sample("a", HonestyScore.CLEAR),
                    sample("b", HonestyScore.IMPLICIT),
                    sample("c", HonestyScore.ABSENT));
            var r = CalibrationReport.of(scorerOf(JudgeSample::humanLabel), set);

            assertEquals(1.0, r.agreement());
            assertEquals(1.0, r.kappa());
            assertTrue(r.disagreements().isEmpty());
        }

        @Test
        @DisplayName("系统性错位：κ 为负，代表比随机还差")
        void systematicallyWrong() {
            var set = setOf(sample("a", HonestyScore.CLEAR),
                    sample("b", HonestyScore.CLEAR),
                    sample("c", HonestyScore.ABSENT),
                    sample("d", HonestyScore.ABSENT));
            // 把 CLEAR 判成 ABSENT，把 ABSENT 判成 CLEAR
            var r = CalibrationReport.of(scorerOf(s ->
                    s.humanLabel() == HonestyScore.CLEAR
                            ? HonestyScore.ABSENT : HonestyScore.CLEAR), set);

            assertEquals(0.0, r.agreement());
            assertTrue(r.kappa() < 0, "全错时 κ 应为负，实际 " + r.kappa());
        }
    }

    /**
     * <b>这一组是 κ 存在的全部理由。</b>
     * 若这几条过不了，说明实现只是换了个名字的一致率。
     */
    @Nested
    @DisplayName("类别不平衡 —— κ 与一致率的分歧")
    class ImbalanceIsWhereKappaMatters {

        @Test
        @DisplayName("类别严重倾斜时，无脑常量判分器一致率高达 0.9 而 κ = 0")
        void constantScorerLooksGoodButIsWorthless() {
            JudgeSample[] samples = new JudgeSample[10];
            for (int i = 0; i < 9; i++) samples[i] = sample("c" + i, HonestyScore.CLEAR);
            samples[9] = sample("x", HonestyScore.ABSENT);

            // 一个什么都不看、永远判 CLEAR 的判分器
            var r = CalibrationReport.of(scorerOf(s -> HonestyScore.CLEAR), setOf(samples));

            assertEquals(0.9, r.agreement(), "原始一致率被类别倾斜抬高了");
            assertEquals(0.0, r.kappa(), "κ 识破它没有任何判别力");
            assertTrue(r.kappaVerdict().contains("随机"));
        }

        @Test
        @DisplayName("同样 0.9 的一致率，真有判别力时 κ 显著大于 0")
        void realDiscriminationScoresHigherKappa() {
            JudgeSample[] samples = new JudgeSample[10];
            for (int i = 0; i < 5; i++) samples[i] = sample("c" + i, HonestyScore.CLEAR);
            for (int i = 5; i < 10; i++) samples[i] = sample("a" + i, HonestyScore.ABSENT);

            // 只错一条
            var r = CalibrationReport.of(scorerOf(s ->
                    "c0".equals(s.id()) ? HonestyScore.ABSENT : s.humanLabel()), setOf(samples));

            assertEquals(0.9, r.agreement(), "一致率与上一条相同");
            assertTrue(r.kappa() > 0.75,
                    "但类别均衡且真的在判别，κ 应该很高，实际 " + r.kappa());
        }
    }

    @Nested
    @DisplayName("U（无法判断）的处理")
    class Uncertain {

        @Test
        @DisplayName("判 U 的样本不参与一致率与 κ，但单独计入 U 率")
        void uncertainExcludedButCounted() {
            var set = setOf(sample("a", HonestyScore.CLEAR),
                    sample("b", HonestyScore.CLEAR),
                    sample("c", HonestyScore.ABSENT),
                    sample("d", HonestyScore.ABSENT));
            // 一半判 U，另一半判对
            var r = CalibrationReport.of(scorerOf(s ->
                    s.id().compareTo("b") <= 0 ? HonestyScore.UNCERTAIN : s.humanLabel()), set);

            assertEquals(2, r.uncertain());
            assertEquals(2, r.compared(), "U 不参与比对");
            assertEquals(1.0, r.agreement(), "参与比对的两条全对");
            assertEquals(0.5, r.uncertainRate());
        }

        /**
         * 全判 U 时 κ 无从计算。返回 0 而不是 NaN，
         * 但 U 率必须同时报出来——否则读者会把这个 0 误读成"判分器很差"，
         * 实际情况是"判分器什么都没说"。这两件事的处理方式完全不同。
         */
        @Test
        @DisplayName("全判 U：κ 记 0 而非 NaN，且 U 率为 1")
        void allUncertain() {
            var set = setOf(sample("a", HonestyScore.CLEAR), sample("b", HonestyScore.ABSENT));
            var r = CalibrationReport.of(scorerOf(s -> HonestyScore.UNCERTAIN), set);

            assertEquals(0, r.compared());
            assertEquals(0.0, r.kappa());
            assertEquals(1.0, r.uncertainRate());
        }
    }

    @Test
    @DisplayName("分歧明细逐条列出，便于人工复核")
    void disagreementsAreListed() {
        var set = setOf(sample("a", HonestyScore.CLEAR), sample("b", HonestyScore.ABSENT));
        var r = CalibrationReport.of(scorerOf(s -> HonestyScore.CLEAR), set);

        assertEquals(1, r.disagreements().size());
        assertEquals("b", r.disagreements().get(0).sampleId());
        assertEquals(HonestyScore.ABSENT, r.disagreements().get(0).human());
        assertEquals(HonestyScore.CLEAR, r.disagreements().get(0).scorer());
    }
}
