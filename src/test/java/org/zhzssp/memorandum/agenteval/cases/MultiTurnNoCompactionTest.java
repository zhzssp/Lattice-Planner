package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.TestPropertySource;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;

import static org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert.assertThat;

/**
 * 多轮金标集 · <b>折叠关闭</b>的对照组（P6·G1）。
 *
 * <h3>这是一次对照实验，不是一道门禁</h3>
 * 它跑的剧本、断言与 {@link MultiTurnEvalTest#constraint_retention_across_turns}
 * <b>逐字相同</b>，唯一的差别是 {@code compaction.enabled=false}。
 *
 * <p>所以它的价值不在"过不过"，而在<b>它和实验组的差</b>：
 * <ul>
 *   <li>对照组<b>判红</b>而实验组<b>通过</b> → 滚动摘要确实把约束带过了窗口边界，
 *       那几次摘要 LLM 调用<b>买到了东西</b>；</li>
 *   <li>两边都通过 → 说明这个剧本压根没压出窗口，<b>题出得不合格</b>，
 *       该去调窗口或加轮数，而不是拿它当"折叠有效"的证据。</li>
 * </ul>
 *
 * <h3>为什么打能力集标签</h3>
 * 因为<b>期望它红</b>。放进回归集，CI 会被一个"按设计就该失败"的用例长期拦住，
 * 接着必然有人给它加 {@code @Disabled}——然后这个对照就永久消失了。
 * 打上能力集标签，它不拦 PR，但每次跑都会把差值报出来。
 *
 * <p><b>注意</b>：它红<b>不是</b>缺陷。真正该警惕的是它<b>变绿</b>——
 * 那意味着这道题不再能区分开关，实验失效了，需要重新出题。
 *
 * <h3>★ 实测结果：对照组也 3/3 通过 —— 这次实验<b>不成立</b></h3>
 * 按上面自己写的判据，两边都过意味着<b>题出得不合格</b>，
 * <b>不能</b>拿它当"折叠有效"的证据。查了录制盒，原因很具体：
 *
 * <p><b>模型在自己的回复里反复复述这条约束。</b>
 * 六轮里第 1、3、6 轮的答复都含 {@code [WORK]} 字样
 * （"以后我帮你建任务时，标题都会以 [WORK] 开头"）。
 * 于是约束<b>每轮都被重新写回近期窗口</b>，窗口淘汰根本够不着它——
 * 折叠开不开都一样。
 *
 * <h3>这个否定结果比一个漂亮的正结果更值钱</h3>
 * 它直接动摇了 {@code ContextEngineeringBenchmark} 那个"关闭折叠时早期约束留存 0/5"的数字：
 * 那个基准用的是<b>桩摘要器</b>，且假定约束<b>静静躺在历史里等着被淘汰</b>。
 * 而真实模型会<b>自我复述</b>，把它一次次救回窗口内。
 * 也就是说：<b>那个 0/5 很可能高估了真实世界里的损失。</b>
 *
 * <p>所以现在的诚实结论是：<b>我尚无端到端证据证明滚动摘要在真实多轮里的收益</b>，
 * 手上只有机制层的桩基准。这不等于折叠没用，等于<b>我还没测到</b>。
 *
 * <p>要把这个实验做成，需要一条模型<b>不会顺口复述</b>的约束
 * （复述动机来自它在总结自己的能力时会把约束一起带上），
 * 且轮数要多到复述本身也被挤出去。那是下一步的事。
 *
 * <p><b>没有继续调参数直到它给出我想要的结果</b>——
 * 一个被调到"终于显示出收益"的实验，证明的是调参能力，不是收益。
 */
@Tag("agent-eval-capability")
@TestPropertySource(properties = {
        "agent.chat.history-window=8",
        "agent.context.compaction.enabled=false",
})
@DisplayName("多轮对照组（折叠关）")
class MultiTurnNoCompactionTest extends MultiTurnEvalBase {

    /**
     * 与实验组同一剧本、同一断言，只关掉折叠。
     *
     * <p>断言刻意<b>不</b>反向写成"应当失败"。反向断言会把
     * "折叠关掉后约束丢了"固化成一条<b>必须成立</b>的契约——
     * 可万一哪天模型强到不靠折叠也记得住，那是好事，
     * 反向断言却会因此判红，逼着人去把一个进步改回去。
     * <b>对照组只负责如实记录，不负责规定结果。</b>
     */
    @EvalTrial
    @DisplayName("constraint_retention_no_compaction")
    void constraint_retention_no_compaction() {
        runConstraintRetentionScript();

        assertThat(trace, db)
                .turnCountIs(6)
                .everyTurnConverged()
                .noHallucination()
                .endState("第 6 轮应当真的建出任务来", d -> d.taskCount() == 1)
                .endState("标题是否仍保留第 1 轮的 " + CONSTRAINT_PREFIX + " 前缀"
                                + "（折叠已关，第 1 轮应已被窗口直接丢弃）",
                        d -> d.anyTask(t -> t.title() != null
                                && t.title().startsWith(CONSTRAINT_PREFIX)));
    }
}
