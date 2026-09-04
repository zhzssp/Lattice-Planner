package org.zhzssp.memorandum.agenteval.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrajectoryMetrics} 的算术验证。
 *
 * <p>与 {@code ReliabilityMetricsTest} 同一立场：<b>指标算错比没有指标更糟</b>，
 * 因为它会让人放心。这里逐条钉死每个指标该惩罚什么、不该惩罚什么。
 */
@DisplayName("轨迹指标 · 工具选择质量分解")
class TrajectoryMetricsTest {

    private static GoldenTask task() {
        return GoldenTask.of("t");
    }

    /**
     * ★"容许"这一档是真实录制逼出来的，见 {@link GoldenTask} 的说明。
     *
     * <p>它的取舍很容易实现错，所以正反两面都要钉死：
     * 容许集必须<b>真的中性</b>（不惩罚），但又不能<b>宽到把违规吞掉</b>。
     */
    @Nested
    @DisplayName("★容许集：中性，但不得吞掉违规")
    class Tolerated {

        @Test
        @DisplayName("容许工具不计冗余，也不拉低精确率")
        void toleratedIsNeutral() {
            var m = TrajectoryMetrics.of(List.of("task.create", "task.today"),
                    task().expecting("task.create").tolerating("task.today"));

            assertEquals(0, m.redundantCalls(), "容许的调用不算冗余");
            assertEquals(1.0, m.precision(), "容许的调用不该进精确率分母");
            assertEquals(1.0, m.recall());
            assertTrue(m.unexpectedTools().isEmpty(), "容许的不算多调");
            assertEquals(List.of("task.today"), m.toleratedCalled(), "但要如实列出来");
        }

        @Test
        @DisplayName("期望集为空的负例：只读探索全部中性，指标不再判红")
        void pureNegativeCaseWithExploration() {
            var m = TrajectoryMetrics.of(List.of("goal.list", "task.today"),
                    task().tolerating("goal.list", "task.today")
                            .forbidding("task.create"));

            assertEquals(0, m.redundantCalls(),
                    "这正是手写盒子时代把 ambiguous_asks_clarification 判红的地方");
            assertEquals(0, m.forbiddenHits());
        }

        @Test
        @DisplayName("★容许不得越过禁止：同时出现在两个集合时，禁止优先")
        void forbiddenWinsOverTolerated() {
            var m = TrajectoryMetrics.of(List.of("task.create"),
                    task().tolerating("task.create").forbidding("task.create"));

            assertEquals(1, m.forbiddenHits(),
                    "禁用命中必须照常记；否则一次误配就能让负例静默失守");
            assertEquals(List.of("task.create"), m.forbiddenCalled());
        }

        @Test
        @DisplayName("未声明容许的工具照旧计冗余（容许是白名单，不是开闸）")
        void unlistedToolStillCounts() {
            var m = TrajectoryMetrics.of(List.of("task.today", "insight.daily_scores"),
                    task().tolerating("task.today"));

            assertEquals(1, m.redundantCalls(), "只有列进容许集的才中性");
            assertEquals(List.of("insight.daily_scores"), m.unexpectedTools());
        }
    }

    @Nested
    @DisplayName("精确率与召回率")
    class PrecisionRecall {

        @Test
        @DisplayName("完全命中期望：两项均为 1，无冗余")
        void perfect() {
            var m = TrajectoryMetrics.of(List.of("task.create"),
                    task().expecting("task.create"));

            assertEquals(1.0, m.precision());
            assertEquals(1.0, m.recall());
            assertEquals(0, m.redundantCalls());
            assertTrue(m.missingTools().isEmpty());
            assertTrue(m.unexpectedTools().isEmpty());
        }

        @Test
        @DisplayName("多调了期望之外的工具：精确率下降、冗余计数上升，召回率不受影响")
        void extraToolHurtsPrecisionOnly() {
            var m = TrajectoryMetrics.of(List.of("task.create", "note.create"),
                    task().expecting("task.create"));

            assertEquals(0.5, m.precision(), "2 个工具里只有 1 个是期望的");
            assertEquals(1.0, m.recall(), "期望的工具并没有漏");
            assertEquals(1, m.redundantCalls());
            assertEquals(List.of("note.create"), m.unexpectedTools());
        }

        @Test
        @DisplayName("漏调期望工具：召回率下降，精确率不受影响")
        void missingToolHurtsRecallOnly() {
            var m = TrajectoryMetrics.of(List.of("goal.create"),
                    task().expecting("goal.create", "goal.link_task"));

            assertEquals(1.0, m.precision(), "调的那个是对的");
            assertEquals(0.5, m.recall(), "2 个期望里漏了 1 个");
            assertEquals(List.of("goal.link_task"), m.missingTools());
        }

        /**
         * <b>这条最容易实现错。</b>失败后重试同一个工具是正确的自纠行为
         * （见 {@code tool_error_recovery}：第一次参数错、第二次改对）。
         * 若把重复调用算成冗余，指标就会去惩罚一个我们明确想要的行为。
         */
        @Test
        @DisplayName("重复调用期望工具不算冗余——那是自纠，不是浪费")
        void repeatedExpectedCallIsNotRedundant() {
            var m = TrajectoryMetrics.of(List.of("task.search", "task.search"),
                    task().expecting("task.search"));

            assertEquals(0, m.redundantCalls());
            assertEquals(1.0, m.precision());
            assertEquals(2, m.actualCalls());
        }
    }

    @Nested
    @DisplayName("负例（期望集为空）")
    class NegativeCases {

        @Test
        @DisplayName("该沉默时确实没调工具：两项均为满分，而不是 0/0")
        void silenceIsPerfect() {
            var m = TrajectoryMetrics.of(List.of(), task());

            assertEquals(1.0, m.precision(), "没调错任何东西");
            assertEquals(1.0, m.recall(), "没有期望可漏");
            assertEquals(0, m.redundantCalls());
        }

        @Test
        @DisplayName("该沉默却动了手：精确率归零")
        void actingWhenItShouldNotIsPunished() {
            var m = TrajectoryMetrics.of(List.of("task.create"), task());

            assertEquals(0.0, m.precision());
            assertEquals(1, m.redundantCalls());
        }
    }

    @Nested
    @DisplayName("禁用工具")
    class Forbidden {

        @Test
        @DisplayName("命中禁用工具会被单独计数并列出")
        void forbiddenIsCounted() {
            var m = TrajectoryMetrics.of(List.of("task.search", "task.archive"),
                    task().expecting("task.search").forbidding("task.archive"));

            assertEquals(1, m.forbiddenHits());
            assertEquals(List.of("task.archive"), m.forbiddenCalled());
        }

        @Test
        @DisplayName("未命中时为 0")
        void noForbiddenHit() {
            var m = TrajectoryMetrics.of(List.of("task.search"),
                    task().expecting("task.search").forbidding("task.archive"));

            assertEquals(0, m.forbiddenHits());
        }
    }

    @Nested
    @DisplayName("顺序一致性 Kendall's τ")
    class Order {

        @Test
        @DisplayName("完全按参考顺序：τ = 1")
        void perfectOrder() {
            var m = TrajectoryMetrics.of(
                    List.of("goal.create", "task.create", "goal.link_task"),
                    task().inOrder("goal.create", "task.create", "goal.link_task"));

            assertEquals(1.0, m.kendallTau());
        }

        @Test
        @DisplayName("完全颠倒：τ = -1")
        void reversedOrder() {
            var m = TrajectoryMetrics.of(
                    List.of("goal.link_task", "task.create", "goal.create"),
                    task().inOrder("goal.create", "task.create", "goal.link_task"));

            assertEquals(-1.0, m.kendallTau());
        }

        @Test
        @DisplayName("一对逆序：3 对中 2 对一致 → τ ≈ 0.333")
        void partialOrder() {
            // 参考 a,b,c；实际 b,a,c → (a,b) 逆序，(a,c)(b,c) 顺序
            var m = TrajectoryMetrics.of(
                    List.of("b", "a", "c"),
                    task().inOrder("a", "b", "c"));

            assertEquals(0.3333, m.kendallTau(), 0.0001);
        }

        /**
         * 参考顺序里没被调到的工具属于「漏调」，由召回率负责。
         * 若顺序指标也为此扣分，同一个错误会被两个指标各罚一次，
         * 让人误判问题的严重程度。
         */
        @Test
        @DisplayName("漏调的工具不参与顺序计算，不被重复惩罚")
        void missingToolsExcludedFromOrder() {
            var m = TrajectoryMetrics.of(
                    List.of("a", "c"),
                    task().inOrder("a", "b", "c"));

            assertEquals(1.0, m.kendallTau(), "a 在 c 前，顺序没错");
            assertEquals(0.6667, m.recall(), 0.0001, "漏调由召回率惩罚");
        }

        @Test
        @DisplayName("可比工具不足 2 个时记 n/a 而非满分")
        void tooFewToCompare() {
            var m = TrajectoryMetrics.of(List.of("a"), task().inOrder("a", "b"));
            assertNull(m.kendallTau());
        }

        @Test
        @DisplayName("未声明参考顺序的用例记 n/a——单步任务算顺序没有意义")
        void noReferenceOrder() {
            var m = TrajectoryMetrics.of(List.of("task.create"),
                    task().expecting("task.create"));
            assertNull(m.kendallTau());
        }
    }

    @Test
    @DisplayName("inOrder 会把工具一并计入期望集")
    void inOrderImpliesExpected() {
        GoldenTask t = task().inOrder("a", "b");
        assertEquals(2, t.expectedTools().size());
        assertTrue(t.hasReferenceOrder());
    }
}
