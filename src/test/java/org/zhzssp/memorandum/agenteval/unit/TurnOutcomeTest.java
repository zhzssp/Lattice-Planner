package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnEndReason;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 单元测试：{@link TurnOutcome}（方案 L）。
 *
 * <p>核心验证「粘性降级标记」：一旦置位不可清除——
 * 本轮发生过信息丢失，后续步骤正常收敛不得把轮次「洗白」成干净完成。</p>
 */
class TurnOutcomeTest {

    private TurnOutcome outcome() {
        return new TurnOutcome("sid-1", "chat", "帮我总结今天的待办");
    }

    @Nested
    @DisplayName("粘性降级标记")
    class StickyDegraded {

        @Test
        @DisplayName("初始不降级")
        void initiallyClean() {
            TurnOutcome o = outcome();
            assertFalse(o.degraded());
            assertTrue(o.degradeCauses().isEmpty());
        }

        @Test
        @DisplayName("markDegraded 置位后不可清除")
        void markIsSticky() {
            TurnOutcome o = outcome();
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            assertTrue(o.degraded());
            // 再次 propose（模拟后续步骤正常收敛）不应清除降级标记
            o.propose(TurnEndReason.FINAL_ANSWER, "已完成", 3);
            assertTrue(o.degraded(), "降级标记必须粘性：正常收敛不得清除");
            assertEquals(TurnEndReason.FINAL_ANSWER, o.reason());
        }

        @Test
        @DisplayName("多个降级原因累积")
        void multipleCausesAccumulate() {
            TurnOutcome o = outcome();
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.markDegraded(TurnOutcome.CAUSE_CRAG_DEGRADED);
            assertEquals(2, o.degradeCauses().size());
            assertTrue(o.degradeCauses().contains("TRUNCATED"));
            assertTrue(o.degradeCauses().contains("CRAG_DEGRADED"));
        }

        @Test
        @DisplayName("同一原因重复标记不重复计数")
        void duplicateCauseIgnored() {
            TurnOutcome o = outcome();
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            assertEquals(1, o.degradeCauses().size());
        }
    }

    @Nested
    @DisplayName("结束原因 propose")
    class Propose {

        @Test
        @DisplayName("propose 记录原因/答复/步数")
        void proposeRecords() {
            TurnOutcome o = outcome();
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 4);
            assertEquals(TurnEndReason.FINAL_ANSWER, o.reason());
            assertEquals("答复", o.finalAnswer());
            assertEquals(4, o.usedSteps());
        }
    }
}
