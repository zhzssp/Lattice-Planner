package org.zhzssp.memorandum.agenteval.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁判准入门槛自身的验证。
 *
 * <p>门槛是一段会失效的逻辑，而且<b>它失效时的表现恰恰是"一切看起来都通过了"</b>——
 * 这是本项目最不能接受的一类故障。所以门槛自己也要被测。
 *
 * <p>下面的实测值来自真实校准结果：
 * {@link KeywordBaseline} κ = −0.069，{@link ProductionDisclosureBaseline} 修复后 κ = 0.605。
 */
@DisplayName("裁判准入 · 门槛算术")
class JudgeAdmissionTest {

    /** 真实测得的关键词基线。生产判据修好之后，它已不再是最强的确定性对照。 */
    private static final double KEYWORD_KAPPA = -0.069;
    /** 真实测得的生产降级明示判据（C-5 缺陷修复后）。 */
    private static final double PRODUCTION_KAPPA = 0.605;

    private static CalibrationReport report(String name, double kappa) {
        return new CalibrationReport(name, 20, 0, 20, 0, 0.0, kappa, 0.0,
                java.util.Map.of(), List.of());
    }

    @Nested
    @DisplayName("这次要修的缺陷")
    class TheDefect {

        /**
         * 缺陷本体：拿过期的弱基线当对照，会放行一个<b>明显不如线上现有实现</b>的裁判。
         *
         * <p>κ = 0.3 的裁判在旧规则（KeywordBaseline + 0.3 = 0.231）下能过，
         * 可它连已经上线跑着的 0.605 都远远不如。这条测试把那个漏洞钉死。
         */
        @Test
        @DisplayName("★κ=0.3 的裁判：旧的弱基线会放行，取最强基线后被拒")
        void staleBaselineWouldAdmitAnInferiorJudge() {
            double inferiorJudge = 0.3;

            assertTrue(inferiorJudge > KEYWORD_KAPPA + 0.3,
                    "前提校验：这个裁判在旧规则下确实能过，否则本测试没有守护到东西");

            var a = JudgeAdmission.against(
                    List.of(report("关键词基线", KEYWORD_KAPPA),
                            report("生产降级明示判据", PRODUCTION_KAPPA)),
                    report("LLM 裁判", inferiorJudge));

            assertFalse(a.admitted(), "裁判远不如线上现有实现，必须拒绝");
            assertEquals("生产降级明示判据", a.incumbentName(), "基准应取两者中更强的那个");
        }

        @Test
        @DisplayName("基准取最强者，与传入顺序无关")
        void picksStrongestIncumbentRegardlessOfOrder() {
            var weakFirst = JudgeAdmission.against(
                    List.of(report("弱", KEYWORD_KAPPA), report("强", PRODUCTION_KAPPA)),
                    report("裁判", 0.5));
            var strongFirst = JudgeAdmission.against(
                    List.of(report("强", PRODUCTION_KAPPA), report("弱", KEYWORD_KAPPA)),
                    report("裁判", 0.5));

            assertEquals(strongFirst.requiredKappa(), weakFirst.requiredKappa());
            assertEquals("强", weakFirst.incumbentName());
        }

        @Test
        @DisplayName("没有对照物时拒绝作答，而不是默认放行")
        void refusesWithoutAnIncumbent() {
            assertThrows(IllegalArgumentException.class,
                    () -> JudgeAdmission.against(List.of(), report("裁判", 0.9)));
        }
    }

    @Nested
    @DisplayName("按剩余空间取阈值，而非固定增量")
    class HeadroomRelative {

        /**
         * 固定增量 +0.3 在高基线处近乎不可达：0.605 + 0.3 = 0.905，
         * 已是"几乎完美一致"。按剩余空间算只要 0.763，是个能被认真尝试的目标。
         */
        @Test
        @DisplayName("高基线：阈值明显低于固定增量，否则等于变相禁止引入裁判")
        void highBaselineStaysReachable() {
            var a = JudgeAdmission.evaluate("生产判据", PRODUCTION_KAPPA, "裁判", 0.0);

            assertEquals(0.763, a.requiredKappa());
            assertTrue(a.requiredKappa() < PRODUCTION_KAPPA + 0.3,
                    "高基线处固定增量会要求近乎完美，剩余空间口径必须更宽松");
        }

        /**
         * 反过来，在负基线处固定增量太松：−0.069 + 0.3 = 0.231，
         * 而 κ=0.231 本身就属于"弱一致"。剩余空间口径要求 0.359。
         */
        @Test
        @DisplayName("负基线：阈值高于固定增量，不许靠打败一个比随机还差的东西过关")
        void negativeBaselineDemandsMore() {
            var a = JudgeAdmission.evaluate("关键词基线", KEYWORD_KAPPA, "裁判", 0.0);

            assertEquals(0.3586, a.requiredKappa());
            assertTrue(a.requiredKappa() > KEYWORD_KAPPA + 0.3,
                    "基线为负说明它比随机还差，险胜它不能证明任何事");
        }

        @Test
        @DisplayName("阈值恰好等于『吃掉四成剩余空间』")
        void thresholdIsExactlyFortyPercentOfHeadroom() {
            double incumbent = 0.5;
            var a = JudgeAdmission.evaluate("基准", incumbent, "裁判", 0.7);

            assertEquals(0.7, a.requiredKappa());
            assertEquals(JudgeAdmission.HEADROOM_SHARE, a.headroomClosed(), 1e-9);
        }
    }

    @Nested
    @DisplayName("边界")
    class Edges {

        @Test
        @DisplayName("打平判不准入：相同判别力不构成付出成本的理由")
        void tieIsRejected() {
            var a = JudgeAdmission.evaluate("基准", 0.605, "裁判", 0.605);
            assertFalse(a.admitted());
        }

        @Test
        @DisplayName("恰好压线（等于阈值）判不准入，超过一点即准入")
        void thresholdIsExclusive() {
            assertFalse(JudgeAdmission.evaluate("基准", 0.605, "裁判", 0.763).admitted());
            assertTrue(JudgeAdmission.evaluate("基准", 0.605, "裁判", 0.7631).admitted());
        }

        /**
         * 若一个零成本的确定性判分器已经完美，任何裁判都不该被引入——
         * 它只能带来成本与方差，不可能带来判别力。此时"永远拒绝"才是正确答案。
         */
        @Test
        @DisplayName("确定性判分器已完美：裁判即便同样完美也不准入")
        void nothingBeatsAPerfectFreeScorer() {
            var a = JudgeAdmission.evaluate("完美基准", 1.0, "裁判", 1.0);

            assertEquals(1.0, a.requiredKappa());
            assertFalse(a.admitted(), "已有零成本的完美方案，引入裁判纯属徒增成本");
            assertTrue(Double.isNaN(a.headroomClosed()), "没有剩余空间时该报 NaN 而不是编一个数");
        }

        @Test
        @DisplayName("明显更好的裁判准入")
        void clearlyBetterJudgeIsAdmitted() {
            var a = JudgeAdmission.against(
                    List.of(report("关键词基线", KEYWORD_KAPPA),
                            report("生产降级明示判据", PRODUCTION_KAPPA)),
                    report("LLM 裁判", 0.88));

            assertTrue(a.admitted());
            assertTrue(a.headroomClosed() > 0.6, "吃掉六成以上剩余空间，值得为它付成本");
        }
    }
}
