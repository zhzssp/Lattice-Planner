package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.runtime.ConversationMemory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L1 单元测试：会话轮次计数。
 *
 * <h3>这组测试守的核心诉求</h3>
 * <p>轮次是 {@code agent_fact.source_turn} 的唯一来源，而 source_turn 是「用户能核对
 * 软件凭什么说我有这条约束」的前提。它必须单调递增，且<strong>不能从消息条数推算</strong>
 * ——窗口会滑动、会被摘要折叠，条数是个会变小的量。</p>
 */
class ConversationMemoryTurnTest {

    @Test
    @DisplayName("轮次从 1 开始逐轮递增，按会话隔离")
    void countsPerSession() {
        ConversationMemory memory = new ConversationMemory();

        assertEquals(0, memory.currentTurn("s1"), "尚未开始任何轮时为 0");
        assertEquals(1, memory.nextTurn("s1"), "第一轮应为 1，而非 0");
        assertEquals(2, memory.nextTurn("s1"));
        assertEquals(2, memory.currentTurn("s1"), "currentTurn 不应推进计数");

        assertEquals(1, memory.nextTurn("s2"), "另一会话独立计数");
        assertEquals(2, memory.currentTurn("s1"), "s2 的推进不影响 s1");
    }

    @Test
    @DisplayName("★窗口被折叠/滑出后轮次仍继续递增，不随消息条数回退")
    void survivesWindowEviction() {
        ConversationMemory memory = new ConversationMemory();
        int window = ConversationMemory.windowSize();

        for (int i = 0; i < window + 10; i++) {
            memory.nextTurn("s");
            memory.append("s", "user", "第 " + i + " 条");
        }
        // 折叠掉最老的一批，模拟滚动摘要
        memory.compact("s", 10, "user", "[对话摘要]\n略");

        assertEquals(window + 10, memory.currentTurn("s"),
                "轮次记录的是「用户第几次开口」，不能随窗口条数变小而回退");
        assertEquals(window + 11, memory.nextTurn("s"));
    }

    @Test
    @DisplayName("clear 会一并清掉轮次，新会话不继承旧计数")
    void clearResetsTurn() {
        ConversationMemory memory = new ConversationMemory();
        memory.nextTurn("s");
        memory.nextTurn("s");

        memory.clear("s");

        assertEquals(0, memory.currentTurn("s"));
        assertEquals(1, memory.nextTurn("s"), "clear 后应重新从 1 开始");
    }
}
