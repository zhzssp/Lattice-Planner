package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.zhzssp.memorandum.agenteval.AgentEvalBase;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;

import java.time.LocalDate;

import static org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert.assertThat;

/**
 * L2 <b>能力集</b>：Agent 目前<b>做不到或做不稳</b>的事。
 *
 * <h3>它和回归集（{@link AgentTrajectoryEvalTest}）是两种东西</h3>
 * <table border="1">
 *   <tr><th></th><th>回归集</th><th>能力集（本类）</th></tr>
 *   <tr><td>目标通过率</td><td>≈100%</td><td>30~60%</td></tr>
 *   <tr><td>变红意味着</td><td>出 bug 了</td><td>本来就没做到</td></tr>
 *   <tr><td>拦不拦 PR</td><td>拦</td><td><b>不拦</b>（{@code ignoreFailures}）</td></tr>
 *   <tr><td>跑的时机</td><td>每次 PR</td><td>夜间 / 发版前</td></tr>
 * </table>
 *
 * <h3>为什么必须物理隔开，而不是"心里有数就行"</h3>
 * 因为一个全绿的评测套件<b>对「下一步该改什么」零信号</b>。
 * 它能告诉你有没有退步，不能告诉你还差多远。
 *
 * <p>而如果把难用例混进会拦 PR 的套件里，只会有两种结局，
 * 且都不是"把 Agent 改好"：
 * <ol>
 *   <li>为了让 CI 变绿，把断言改松——于是它不再是一座山；</li>
 *   <li>干脆不加这个用例——于是评测永远只覆盖已经做到的事。</li>
 * </ol>
 * 两种结局都以"评测很健康"的样子呈现。所以隔离不是流程洁癖，
 * 是<b>为了让"做不到"能够被如实记录下来而不被修正压力扭曲</b>。
 *
 * <h3>毕业与退休</h3>
 * <ul>
 *   <li><b>毕业</b>：某用例连续多次 {@code pass^3 = 100%} → 移进回归集，从此掉了就是 bug。</li>
 *   <li><b>退休</b>：回归集里连续多月 100% 且相关代码不再变动 → 可以删，
 *       防评测饱和（每个用例都有回放成本，长期零信号的用例是纯负债）。</li>
 * </ul>
 * 详见 {@code docs/Agent评测体系使用指南.md}。
 */
@Tag("agent-eval-capability")
@DisplayName("Agent 能力集（不拦 PR）")
class AgentCapabilityEvalTest extends AgentEvalBase {

    /** 本类录制盒的录制日期。重新录制后要同步改这里，否则日期类断言会错判。 */
    private static final LocalDate RECORDED_ON = LocalDate.of(2026, 9, 4);

    /**
     * <b>单轮内的多步写入</b>：一句话里包含两个写操作 + 一次日期推算。
     *
     * <p>为什么这条属于能力集而不是回归集：它要求模型在<b>一个 turn 内</b>
     * 连续走完 search → complete → create 三步，中途还要把"明天"折算成具体日期。
     * 只要有一步之后模型觉得"我已经答完了"，端状态就少一半——
     * 而这种"做了一半就收工"恰恰是 Agent 最常见的失败模式，
     * 且它在轨迹层面看起来完全正常（调用都成功、没有幻觉、也收敛了）。
     *
     * <p>断言写成<b>端状态</b>而不是工具序列，是刻意的：
     * 我不关心它用哪条路径达成，只关心<b>两件事最后是不是都办成了</b>。
     * 用工具序列断言会把"换个顺序也对"的正确解判死。
     */
    @EvalTrial
    @DisplayName("multi_step_write_end_state")
    void multi_step_write_end_state() {
        seedTask(90101L, "准备周报", "PENDING");

        runTurn("把「准备周报」标记完成，另外帮我建一个明天截止的「整理会议纪要」", "chat");

        // 钉死成常量，<不>用 LocalDate.now().plusDays(1)：
        // 录制盒里的回答是录制当天生成的，日期已经固定在里面了。
        // 用 now() 会让这条断言从第二天起<必然>失败——那不是"能力不足"，
        // 是断言自己坏了。能力集允许变红，但必须为正当理由变红。
        LocalDate tomorrow = RECORDED_ON.plusDays(1);

        assertThat(trace, db)
                .converged()
                .noHallucination()
                .noToolFailure()
                .matchesGolden(GoldenTask.of("multi_step_write_end_state")
                        .expecting("task.complete", "task.create")
                        .toleratingReadOnlyExploration()
                        .forbidding("task.archive", "goal.create"))
                // ↓ 三条端状态断言，缺一不可：做一半也算失败
                .endState("原任务应变为 DONE",
                        d -> d.anyTask(t -> t.id() == 90101L && "DONE".equals(t.status())))
                .endState("应新建「整理会议纪要」",
                        d -> d.anyTask(t -> "整理会议纪要".equals(t.title())))
                .endState("新任务截止日期应推算为明天 " + tomorrow,
                        d -> d.anyTask(t -> "整理会议纪要".equals(t.title())
                                && tomorrow.equals(t.deadlineDate())));
    }
}
