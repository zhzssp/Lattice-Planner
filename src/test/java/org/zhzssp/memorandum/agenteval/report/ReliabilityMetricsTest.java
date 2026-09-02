package org.zhzssp.memorandum.agenteval.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReliabilityMetrics} 的算术验证。
 *
 * <p>这组测试的意义在于：pass^k 是<b>唯一一个在没有真实录制时也能被完全验证的 P1 产物</b>。
 * 指标算错比没有指标更危险——它会让人放心。
 */
@DisplayName("可靠性指标 pass@k / pass^k")
class ReliabilityMetricsTest {

    private static Map<String, List<Boolean>> outcomes(Object... pairs) {
        Map<String, List<Boolean>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<Boolean> v = (List<Boolean>) pairs[i + 1];
            m.put((String) pairs[i], v);
        }
        return m;
    }

    @Test
    @DisplayName("全部试次都通过时，pass@k 与 pass^k 均为 1")
    void allPass() {
        var m = ReliabilityMetrics.of(outcomes(
                "a", List.of(true, true, true),
                "b", List.of(true, true, true)));

        assertEquals(2, m.cases());
        assertEquals(3, m.k());
        assertEquals(1.0, m.passAtK());
        assertEquals(1.0, m.passHatK());
        assertEquals(0.0, m.instability());
        assertTrue(m.flakyCases().isEmpty());
    }

    /**
     * <b>本组最重要的一条</b>：区分「能力」与「可靠」。
     *
     * <p>a 三次里过了一次 → 计入 pass@3 但不计入 pass^3。
     * 若两个指标算得一样，说明实现把两者混为一谈了。
     */
    @Test
    @DisplayName("时好时坏的用例计入 pass@k 但不计入 pass^k")
    void flakyCountsTowardCapabilityNotReliability() {
        var m = ReliabilityMetrics.of(outcomes(
                "a", List.of(true, false, false),
                "b", List.of(true, true, true)));

        assertEquals(1.0, m.passAtK(), "两个用例都至少成功过一次");
        assertEquals(0.5, m.passHatK(), "只有 b 是三次全过");
        assertEquals(0.5, m.instability(), "不稳定度 = pass@k - pass^k");
        assertEquals(List.of("a(1/3)"), m.flakyCases());
    }

    @Test
    @DisplayName("全部试次都失败的用例，两个指标都不计入，且不算 flaky")
    void allFail() {
        var m = ReliabilityMetrics.of(outcomes(
                "a", List.of(false, false),
                "b", List.of(true, true)));

        assertEquals(0.5, m.passAtK());
        assertEquals(0.5, m.passHatK());
        // 纯失败是明确的 bug，不属于"时好时坏"，不该混进 flaky 列表干扰排查
        assertTrue(m.flakyCases().isEmpty());
    }

    @Test
    @DisplayName("k=1 时两个指标必然相等——这正是单试次测不出稳定性的原因")
    void singleTrialCannotMeasureStability() {
        var m = ReliabilityMetrics.of(outcomes(
                "a", List.of(true),
                "b", List.of(false)));

        assertEquals(1, m.k());
        assertEquals(m.passAtK(), m.passHatK());
        assertEquals(0.0, m.instability());
    }

    /**
     * 用工业界常引的那组数字做一次端到端校核：
     * 单次成功率 0.75、k=3 时，pass@3 ≈ 98.4% 而 pass^3 ≈ 42.2%。
     * 这里用 8 个用例构造出接近该比例的分布，验证两个指标确实会显著分离。
     */
    @Test
    @DisplayName("能力与可靠会显著分离：pass@k 高而 pass^k 低")
    void capabilityAndReliabilityDiverge() {
        var m = ReliabilityMetrics.of(outcomes(
                "c1", List.of(true, true, true),
                "c2", List.of(true, true, true),
                "c3", List.of(true, true, true),
                "c4", List.of(true, false, true),
                "c5", List.of(false, true, true),
                "c6", List.of(true, true, false),
                "c7", List.of(true, false, false),
                "c8", List.of(false, false, true)));

        assertEquals(1.0, m.passAtK(), "8 个用例都至少成功过一次");
        assertEquals(0.375, m.passHatK(), "只有 3/8 是三次全过");
        assertEquals(5, m.flakyCases().size());
    }

    @Test
    @DisplayName("空输入不炸，返回全零")
    void empty() {
        var m = ReliabilityMetrics.of(Map.of());
        assertEquals(0, m.cases());
        assertEquals(0.0, m.passAtK());
        assertEquals(0.0, m.passHatK());
    }
}
