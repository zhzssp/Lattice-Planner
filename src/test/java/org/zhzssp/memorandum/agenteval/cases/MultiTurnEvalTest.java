package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;

import static org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert.assertThat;

/**
 * 多轮金标集 · <b>折叠开启</b>（P6·G1）——上下文工程的活体验证。
 *
 * <h3>窗口为什么调到 8 条</h3>
 * 生产默认 {@code history-window=30}，六轮闲聊压根撑不满，
 * 折叠<b>根本不会触发</b>，这道题就退化成"模型上下文够长吗"，什么也没测到。
 * 调到 8 条，第 1 轮的约束在第 6 轮到来前必然已被挤出原始窗口，
 * <b>只有折叠把它带过去，这题才做得出来。</b>
 *
 * <p>用 {@code @TestPropertySource} 而不是改评测 profile：改 profile 会波及
 * 那 13 条已录制的单轮用例——它们的 prompt 会变、录制盒会集体失效。
 * 代价是 Spring 要为这个属性组合另开一个上下文（启动慢几秒），值得。
 *
 * @see MultiTurnNoCompactionTest 对照组
 */
@TestPropertySource(properties = {
        "agent.chat.history-window=8",
        "agent.context.compaction.enabled=true",
        // 降低折叠门槛：默认要 6 条真实对话才折，六轮剧本刚好卡在边上
        "agent.context.compaction.min-dialogue=4",
})
@DisplayName("多轮金标集（折叠开）")
class MultiTurnEvalTest extends MultiTurnEvalBase {

    /**
     * <b>跨轮约束保持</b>：第 1 轮立下的规矩，第 6 轮还算不算数。
     *
     * <p><b>实测 3/3 通过。</b>但请注意它<b>证明不了</b>"折叠有效"：
     * 对照组（{@link MultiTurnNoCompactionTest}，关掉折叠）<b>同样 3/3</b>，
     * 因为模型会在自己的回复里反复复述这条约束，把它一次次救回近期窗口。
     * 它能证明的是<b>跨轮约束确实被遵守了</b>——这本身就是单轮用例覆盖不到的东西，
     * 值得作为回归门禁；但别拿它当上下文工程的功劳。
     *
     * <p>断言写在<b>端状态</b>上（库里那条任务的标题长什么样），
     * 而不是"模型有没有在答复里提到前缀"——后者可以靠嘴上说说骗过去，
     * 前者骗不了：标题是什么就是什么。
     *
     * <p>{@code taskCountIs(1)} 不是凑数：跑偏的一种常见形态是
     * 模型在中间某轮"顺手"先建了个任务，最后又建一个。
     * 只断言"存在带前缀的任务"会让这种情况照样绿。
     */
    @EvalTrial
    @DisplayName("constraint_retention_across_turns")
    void constraint_retention_across_turns() {
        runConstraintRetentionScript();

        assertThat(trace, db)
                .turnCountIs(6)
                .everyTurnConverged()
                .noHallucination()
                .noToolFailure()
                .matchesGolden(GoldenTask.of("constraint_retention_across_turns")
                        .expecting("task.create")
                        .toleratingReadOnlyExploration()
                        // 模型常主动提议"把这个习惯记成笔记，新会话也能检索到"——
                        // 它知道自己没有跨会话记忆。这是合理行为，不该判成多调。
                        .tolerating("note.create"))
                .endState("第 6 轮应当真的建出任务来", d -> d.taskCount() == 1)
                .endState("标题应保留第 1 轮立下的 " + CONSTRAINT_PREFIX + " 前缀"
                                + "（该约束此时已被挤出原始窗口，只能来自折叠摘要）",
                        d -> d.anyTask(t -> t.title() != null
                                && t.title().startsWith(CONSTRAINT_PREFIX)))
                .endState("标题主体仍应是「" + TARGET_TITLE + "」（别为了满足前缀把内容改没了）",
                        d -> d.anyTask(t -> t.titleContains(TARGET_TITLE)));
    }

    /**
     * <b>跨轮指代消解</b>：第 2 轮的"第二个"指的是第 1 轮查询结果里的哪一条。
     *
     * <p>只有两轮，不涉及窗口压力——它考的是另一件事：
     * <b>上一轮的工具返回结果，有没有以模型能用的形式留在上下文里</b>。
     * 单轮用例永远测不到这条：单轮里工具结果就在眼前。
     *
     * <p>这里刻意让三条任务的标题<b>互不相似</b>，
     * 免得模型靠猜标题也能蒙对——那样就测不出它是否真的在用序号定位。
     */
    @EvalTrial
    @DisplayName("cross_turn_reference_resolution")
    void cross_turn_reference_resolution() {
        seedTask(90301L, "预订团建场地", "PENDING");
        seedTask(90302L, "修复登录页样式", "PENDING");
        seedTask(90303L, "整理年度报销单据", "PENDING");

        runTurn("我现在有哪些没做完的任务？按顺序列出来。", "chat");
        runTurn("把第二个标记成完成。", "chat");

        assertThat(trace, db)
                .turnCountIs(2)
                .everyTurnConverged()
                .noHallucination()
                .noToolFailure()
                .matchesGolden(GoldenTask.of("cross_turn_reference_resolution")
                        .expecting("task.complete")
                        .toleratingReadOnlyExploration()
                        .forbidding("task.create", "task.archive"))
                .endState("「第二个」应解析为 90302（第 1 轮列表里的第二条）",
                        d -> d.anyTask(t -> t.id() == 90302L && "DONE".equals(t.status())))
                .endState("另外两条不该被牵连",
                        d -> d.tasks().stream()
                                .filter(t -> t.id() == 90301L || t.id() == 90303L)
                                .allMatch(t -> "PENDING".equals(t.status())));
    }
}
