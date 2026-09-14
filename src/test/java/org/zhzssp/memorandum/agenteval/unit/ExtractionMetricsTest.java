package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.facts.ExtractionMetrics;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtractionMetricsTest {

    @Test
    @DisplayName("期望片段都命中：精确率与召回都是 1")
    void perfectMatch() {
        var s = ExtractionMetrics.score(
                List.of("项目 X 的 deadline 是下周五"),
                List.of(List.of("下周五")));
        assertEquals(1, s.tp());
        assertEquals(0, s.fp());
        assertEquals(0, s.fn());
        assertEquals(1.0, s.precision());
        assertEquals(1.0, s.recall());
    }

    @Test
    @DisplayName("寒暄不该抽出：抽出一条算误报")
    void chitchatIsFalsePositive() {
        var s = ExtractionMetrics.score(List.of("用户今天心情不错"), List.of());
        assertEquals(0, s.tp());
        assertEquals(1, s.fp());
        assertEquals(1.0, s.recall(), "没有期望时召回记 1，避免分母为 0 伪造 0 分");
        assertEquals(0.0, s.precision());
    }

    @Test
    @DisplayName("漏抽记 fn，多抽记 fp")
    void missAndExtra() {
        var s = ExtractionMetrics.score(
                List.of("预算上限 8000 元", "用户似乎喜欢咖啡"),
                List.of(List.of("8000"), List.of("v2.3.1")));
        assertEquals(1, s.tp());
        assertEquals(1, s.fp());
        assertEquals(1, s.fn());
        assertEquals(0.5, s.precision());
        assertEquals(0.5, s.recall());
    }
}
