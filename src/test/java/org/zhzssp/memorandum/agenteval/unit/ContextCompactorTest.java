package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.ContextCompactor;
import org.zhzssp.memorandum.feature.agent.runtime.ConversationMemory;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 单元测试：滚动摘要（上下文工程 P1 第一步）。
 *
 * <h3>这组测试守的核心诉求</h3>
 * <p>把「窗口超限即丢弃」升级为「折叠成摘要」，保住关键约束。
 * 最该守住的三条：</p>
 * <ul>
 *   <li><strong>纯工具噪声短路</strong>——待折叠段没有真实对话时，不付 LLM 调用；</li>
 *   <li><strong>失败回退等价旧行为</strong>——LLM 折叠失败时退化为丢弃 + TRUNCATED；</li>
 *   <li><strong>折叠置位 SUMMARIZED</strong>——有损操作必须留痕，且与截断区分。</li>
 * </ul>
 */
class ContextCompactorTest {

    private ConversationMemory memory;
    private LlmGateway llm;
    private ContextCompactor compactor;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory();
        llm = mock(LlmGateway.class);
        compactor = new ContextCompactor(memory, llm);
        ReflectionTestUtils.setField(compactor, "enabled", true);
        ReflectionTestUtils.setField(compactor, "triggerRatio", 0.8);
        ReflectionTestUtils.setField(compactor, "foldSize", 10);
        ReflectionTestUtils.setField(compactor, "summaryMaxChars", 200);
        ReflectionTestUtils.setField(compactor, "minDialogue", 6);
    }

    private TurnOutcome outcome() {
        return new TurnOutcome("sid-1", "chat", "测试");
    }

    /** 塞入 N 条真实对话（user/assistant 交错）。 */
    private void appendDialogue(String sid, int rounds) {
        for (int i = 1; i <= rounds; i++) {
            memory.append(sid, "user", "用户第 " + i + " 条消息，包含约束 deadline 是下周" + i);
            memory.append(sid, "assistant", "好的，第 " + i + " 条回复。");
        }
    }

    @Nested
    @DisplayName("触发条件")
    class Trigger {

        @Test
        @DisplayName("未达阈值不触发")
        void belowThresholdNoTrigger() {
            appendDialogue("s", 3); // 6 条 < 24（30*0.8）
            assertFalse(compactor.compactIfNeeded("s", outcome()));
            verify(llm, never()).generateText(anyString());
        }

        @Test
        @DisplayName("禁用时不触发")
        void disabledNoTrigger() {
            ReflectionTestUtils.setField(compactor, "enabled", false);
            appendDialogue("s", 20); // 40 条，远超阈值
            assertFalse(compactor.compactIfNeeded("s", outcome()));
            verify(llm, never()).generateText(anyString());
        }
    }

    @Nested
    @DisplayName("★纯工具噪声短路")
    class ToolNoiseShortCircuit {

        @Test
        @DisplayName("待折叠段全是工具噪声 → 不付 LLM 调用，直接丢弃")
        void pureNoiseDropsWithoutLlm() {
            String sid = "s";
            // 先塞 25 条真实对话把窗口顶到阈值附近，再塞 15 条工具噪声触发折叠
            appendDialogue(sid, 15);
            for (int i = 0; i < 10; i++) {
                memory.append(sid, "assistant", "{\"tool\":\"task.create\",\"arguments\":{}}");
                memory.append(sid, "user", "[tool_result task.create]\n{}");
            }
            int before = memory.size(sid);
            compactor.compactIfNeeded(sid, outcome());
            // 折叠确实发生了（窗口变小），但 LLM 一次都没被调
            assertTrue(memory.size(sid) < before, "纯工具噪声也应触发折叠（丢弃）");
            verify(llm, never()).generateText(anyString());
        }
    }

    @Nested
    @DisplayName("★折叠成功路径")
    class SuccessfulCompaction {

        @Test
        @DisplayName("有足够真实对话时调用 LLM，折叠后保留摘要消息")
        void compactsWithSummary() {
            String sid = "s";
            appendDialogue(sid, 20); // 40 条，超阈值
            when(llm.generateText(anyString())).thenReturn("用户要求 deadline 为下周五，已建多个任务");
            int before = memory.size(sid);

            assertTrue(compactor.compactIfNeeded(sid, outcome()));

            verify(llm, times(1)).generateText(anyString());
            List<ConversationMemory.Msg> hist = memory.history(sid);
            assertTrue(hist.size() < before, "折叠后窗口应变小");
            assertTrue(hist.get(0).content().startsWith("[对话摘要]"),
                    "最老的折叠段应被一条摘要消息替代，而非凭空消失");
        }

        @Test
        @DisplayName("★折叠置位 SUMMARIZED（与 TRUNCATED 区分）")
        void marksSummarized() {
            String sid = "s";
            appendDialogue(sid, 20);
            when(llm.generateText(anyString())).thenReturn("摘要");
            TurnOutcome o = outcome();
            compactor.compactIfNeeded(sid, o);
            assertTrue(o.degraded());
            assertTrue(o.degradeCauses().contains(TurnOutcome.CAUSE_SUMMARIZED),
                    "折叠是有损操作，必须留痕 SUMMARIZED，而非 TRUNCATED");
        }

        @Test
        @DisplayName("★关键约束在折叠后仍以摘要形式保留在上下文里")
        void constraintSurvives() {
            String sid = "s";
            appendDialogue(sid, 20);
            String summary = "用户要求：项目 X 的 deadline 是下周五，务必完成。";
            when(llm.generateText(anyString())).thenReturn(summary);
            compactor.compactIfNeeded(sid, outcome());
            List<ConversationMemory.Msg> hist = memory.history(sid);
            assertTrue(hist.get(0).content().contains("deadline"),
                    "折叠出的摘要必须保留关键约束，这正是本特性的存在意义");
        }
    }

    @Nested
    @DisplayName("★失败回退")
    class Fallback {

        @Test
        @DisplayName("LLM 折叠失败 → 退化为直接丢弃 + TRUNCATED，不阻断")
        void llmFailureFallsBack() {
            String sid = "s";
            appendDialogue(sid, 20);
            when(llm.generateText(anyString())).thenThrow(new RuntimeException("boom"));
            int before = memory.size(sid);
            TurnOutcome o = outcome();

            // 不抛异常，返回 false（表示未成功摘要）
            assertFalse(compactor.compactIfNeeded(sid, o));
            assertTrue(memory.size(sid) < before, "失败时应退化为丢弃，窗口仍应收缩");
            assertTrue(o.degradeCauses().contains(TurnOutcome.CAUSE_TRUNCATED),
                    "失败回退应按截断口径留痕，而不是 SUMMARIZED");
        }

        @Test
        @DisplayName("LLM 返回空 → 同样退化为丢弃")
        void emptyResponseFallsBack() {
            String sid = "s";
            appendDialogue(sid, 20);
            when(llm.generateText(anyString())).thenReturn("   ");
            TurnOutcome o = outcome();
            assertFalse(compactor.compactIfNeeded(sid, o));
            assertTrue(o.degradeCauses().contains(TurnOutcome.CAUSE_TRUNCATED));
        }
    }
}
