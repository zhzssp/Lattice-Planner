package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnEndReason;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.runtime.turn.advisor.AnnouncedActionInspector;
import org.zhzssp.memorandum.feature.agent.runtime.turn.advisor.UnfulfilledActionAdvisor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 单元测试：空头承诺检测（{@link UnfulfilledActionAdvisor}）。
 *
 * <h3>被测缺陷</h3>
 * 真实录制里三次全中：用户问"我这周都有啥安排？"，模型回
 * "让我查询一下本周的任务情况。"——<b>然后一个工具都没调就收尾了</b>。
 * 这句话被当成终答发给用户，而轮次记的是 {@code FINAL_ANSWER}（干净完成）。
 *
 * <h3>这组测试重点防的是误报</h3>
 * 这个顾问会让轮次<b>多跑一次 LLM 调用</b>，误报的代价是实打实的钱和延迟。
 * 所以下面负例（不该触发）比正例还多——闲聊、已经调过工具、完成时表述，
 * 三类都必须放行。
 */
@DisplayName("空头承诺：宣告了动作却零工具调用")
class UnfulfilledActionTest {

    private static UnfulfilledActionAdvisor advisor() {
        UnfulfilledActionAdvisor a = new UnfulfilledActionAdvisor();
        ReflectionTestUtils.setField(a, "enabled", true);
        return a;
    }

    /** 造一个"模型自认为答完了"的收尾上下文。 */
    private static TurnOutcome outcome(String finalAnswer, int usedSteps) {
        TurnOutcome o = new TurnOutcome("sid", "chat", "我这周都有啥安排？");
        o.propose(TurnEndReason.FINAL_ANSWER, finalAnswer, usedSteps);
        return o;
    }

    @Nested
    @DisplayName("短语检测")
    class Detector {

        @Test
        @DisplayName("★复现真实录制里的原话")
        void realRecordedAnswer() {
            String real = "我需要先查看你本周的任务安排。今天是 2026-09-04（周五），"
                    + "本周应该是 2026-08-31（周一）到 2026-09-06（周日）。\n\n"
                    + "让我查询一下本周的任务情况。";
            assertNotNull(AnnouncedActionInspector.detect(real),
                    "这就是三次试验里模型的真实回答，必须能被识别");
            assertTrue(AnnouncedActionInspector.announcesUnfulfilledAction(real));
        }

        @Test
        @DisplayName("完成时表述不算宣告：动作已经发生了")
        void pastTenseIsNotAnnouncement() {
            assertNull(AnnouncedActionInspector.detect("我查了一下，你这周有 3 件事。"));
            assertNull(AnnouncedActionInspector.detect("已为你查到本周的全部安排。"));
            assertNull(AnnouncedActionInspector.detect("根据查询结果，你这周没有任务。"));
        }

        @Test
        @DisplayName("正常答复与闲聊不得命中")
        void normalAnswersDoNotMatch() {
            assertNull(AnnouncedActionInspector.detect(
                    "你好，我可以帮你管理任务和目标。直接说要做什么就行。"));
            assertNull(AnnouncedActionInspector.detect("这周你没有待办任务。"));
            assertNull(AnnouncedActionInspector.detect(null));
            assertNull(AnnouncedActionInspector.detect(""));
        }

        @Test
        @DisplayName("命中时返回具体短语，供 steer 引用原话")
        void returnsThePhrase() {
            assertEquals("让我查询", AnnouncedActionInspector.detect("好的，让我查询一下。"));
        }
    }

    @Nested
    @DisplayName("顾问触发条件")
    class Trigger {

        @Test
        @DisplayName("★宣告 + 零工具调用 → 注入 steer")
        void firesOnUnfulfilledPromise() {
            Optional<String> steer = advisor()
                    .onTurnStopping(outcome("让我查询一下本周的任务情况。", 0));

            assertTrue(steer.isPresent(), "这正是要修的缺陷，必须触发");
            assertTrue(steer.get().contains("让我查询"), "steer 应把原话引回去");
            assertTrue(steer.get().contains("工具调用"));
        }

        /**
         * 最关键的一条负例。闲聊答复同样是"零工具调用 + FINAL_ANSWER"，
         * 只靠 {@code usedSteps == 0} 判定会把所有闲聊全部误伤，
         * 平白多花一次 LLM 调用。
         */
        @Test
        @DisplayName("★闲聊零工具收尾是正当的，不得误伤")
        void doesNotFireOnChitchat() {
            Optional<String> steer = advisor().onTurnStopping(
                    outcome("你好，我可以帮你管理待办任务和目标。", 0));
            assertTrue(steer.isEmpty(), "闲聊本来就不需要工具");
        }

        /**
         * 另一侧的负例：真调过工具就不算空头承诺，
         * 哪怕答复里出现"让我再确认一下"这类措辞。
         */
        @Test
        @DisplayName("★调过工具就不算空头承诺（usedSteps > 0 直接放行）")
        void doesNotFireWhenToolsRan() {
            Optional<String> steer = advisor().onTurnStopping(
                    outcome("查到了 3 条。让我查询一下是否还有更多。", 2));
            assertTrue(steer.isEmpty(), "它确实在干活，不该被推回去");
        }

        @Test
        @DisplayName("每轮只 steer 一次（幂等收敛）")
        void steersAtMostOnce() {
            TurnOutcome o = outcome("让我查询一下。", 0);
            o.recordSteer("unfulfilled-action");
            assertTrue(advisor().onTurnStopping(o).isEmpty(),
                    "已经推过一次就不再推，否则可能来回拉扯");
        }

        @Test
        @DisplayName("步数耗尽不触发：那句提示语本就是系统写的")
        void doesNotFireOnExhausted() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "x");
            o.propose(TurnEndReason.STEPS_EXHAUSTED, "（已达最大推理步数，让我查询一下）", 0);
            assertTrue(advisor().onTurnStopping(o).isEmpty());
        }

        @Test
        @DisplayName("开关关闭时完全不介入")
        void respectsDisableSwitch() {
            UnfulfilledActionAdvisor a = new UnfulfilledActionAdvisor();
            ReflectionTestUtils.setField(a, "enabled", false);
            assertTrue(a.onTurnStopping(outcome("让我查询一下。", 0)).isEmpty());
        }

        @Test
        @DisplayName("排在降级明示之前：没干活时谈不上降级明示")
        void runsBeforeDisclosure() {
            assertTrue(advisor().order() < 10);
            assertFalse(advisor().name().isBlank());
        }
    }
}
