package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.TestPropertySource;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;

import static org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert.assertThat;

/**
 * G1′ 实验组：不透明标记 + 窗口压到折叠<b>必然</b>触发。
 *
 * <p>独立成类，是因为 {@code @TestPropertySource} 只能打在类上。
 * 若跟 P6 那两道回归题共用 window=8，旧盒子会多出摘要调用、录制耗尽。
 *
 * <p>三种合法结论事先写在这里。2026-09-14 实测（k=1，控 token 未扩到 k=3）：
 * <b>开过关不过</b>——折叠开组窗口里出现了 {@code [对话摘要]}，任务备注带
 * {@code ref:7f3a}；关组建出了任务但备注为空。这是第一道成立的端到端对照，
 * 不是 pass^3。</p>
 *
 * @see MultiTurnNoCompactionTest#opaque_constraint_no_compaction 对照组
 */
@Tag("agent-eval-capability")
@TestPropertySource(properties = {
        "agent.chat.history-window=8",
        "agent.context.compaction.enabled=true",
        "agent.context.compaction.min-dialogue=4",
        "agent.context.compaction.fold-size=6",
})
@DisplayName("G1′ 多轮对照（折叠开）")
class OpaqueConstraintEvalTest extends MultiTurnEvalBase {

    @EvalTrial
    @DisplayName("opaque_constraint_retention")
    void opaque_constraint_retention() {
        runOpaqueConstraintScript();
        if (!historyHasSummary()) {
            throw new AssertionError(
                    "实验无效：折叠开启组窗口里没有 [对话摘要]。"
                            + "窗口旋钮或触发阈值没生效，不能拿这条当折叠证据。");
        }

        assertThat(trace, db)
                .turnCountIs(OPAQUE_TURN_COUNT)
                .everyTurnConverged()
                .noHallucination()
                .noToolFailure()
                .matchesGolden(GoldenTask.of("opaque_constraint_retention")
                        .expecting("task.create")
                        .toleratingReadOnlyExploration()
                        .tolerating("note.create"))
                .endState("最后一轮应当建出任务", d -> d.taskCount() == 1)
                .endState("备注必须带上第 1 轮的 " + OPAQUE_TOKEN
                                + "（此时原始窗口已不够长，只能来自折叠摘要）",
                        d -> d.anyTask(t -> t.description() != null
                                && t.description().contains(OPAQUE_TOKEN)))
                .endState("标题主体仍应是「" + OPAQUE_TITLE + "」",
                        d -> d.anyTask(t -> t.titleContains(OPAQUE_TITLE)));
    }
}
