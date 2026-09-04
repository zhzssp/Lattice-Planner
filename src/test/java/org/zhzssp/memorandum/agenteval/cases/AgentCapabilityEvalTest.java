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
     * <h4>★ 实测结果：3/3 通过。这条题<b>出得不够难</b>，已列为毕业候选</h4>
     * 我原本按"多步写入很容易做一半就收工"的直觉把它放进能力集，
     * 结果三次试验的轨迹<b>逐字相同</b>：search → complete → create → 作答，
     * 日期也都正确折算成了次日。
     *
     * <p>复盘出的原因很具体：<b>用户请求把步骤枚举好了</b>
     * （"标记完成" + "建一个…"），模型只要照着做两件事，
     * 不需要自己决定"要做几件"。真正难的是<b>步数由数据决定</b>的场景——
     * 见 {@link #batch_complete_overdue_only}，那条才是这条想考而没考到的东西。
     *
     * <p>留在能力集里没有立刻挪走，是因为毕业规则要求<b>连续多次</b>
     * {@code pass^3 = 100%}，而不是一次。按自己写的规则办，
     * 比"这次看着挺稳就搬过去"更重要——规则一旦可以临时通融，它就不再是规则。
     *
     * <p>断言写成<b>端状态</b>而不是工具序列，是刻意的：
     * 我不关心它用哪条路径达成，只关心<b>两件事最后是不是都办成了</b>。
     * 用工具序列断言会把"换个顺序也对"的正确解判死。
     *
     * <p>顺带一个有价值的观察：录制盒里第 0 次调用是
     * "我需要先找到你的「准备周报」任务…让我先搜索一下"——<b>一个工具都没调</b>。
     * 是 {@code UnfulfilledActionAdvisor}（P1 真实录制时查出的"空头承诺"缺陷的修复）
     * 把它拦了回去，才有了后面的 search。<b>那个修复在一个全新用例上自发生效了。</b>
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

    /**
     * <b>批量写入：要做几件事由数据决定，且有一件不能碰。</b>
     *
     * <h4>为什么这条才是真正的能力题（对比上一条的失败教训）</h4>
     * 上一条 3/3 通过，因为用户把步骤枚举好了。这一条不给数量：
     * 用户只说"把已经过期的都标记完成"，<b>几件</b>要模型自己从查询结果里数出来。
     *
     * <p>预置 4 条 PENDING，其中 3 条已过期、1 条还没到期。于是它同时考两件事：
     * <ul>
     *   <li><b>完整性</b>——3 条全都要处理。做 1 条就宣布"已完成"是 Agent 最典型的失败，
     *       而这种失败在轨迹层面完全正常：调用成功、无幻觉、正常收敛；</li>
     *   <li><b>克制</b>——第 4 条不许动。一个"看到什么都想顺手办了"的 Agent
     *       会把未到期的那条一起完成，用户第二天才会发现。</li>
     * </ul>
     * 只考前者会养出滥杀的 Agent，只考后者会养出不干活的 Agent，两条必须同时钉。
     *
     * <p>还有一层隐藏难度：{@code task.complete} 要 id，而 id 只能从
     * {@code task.search} 的结果里取。也就是说模型必须<b>把上一步的结构化输出
     * 正确地喂给下一步</b>，连喂三次不出错。
     *
     * <p>日期全部钉成常量（相对 {@link #RECORDED_ON}）而非 {@code now()}：
     * 理由同上一条——录制盒里的回答是录制当天生成的。
     *
     * <h4>★ 实测结果：2/3 通过（pass@3 = 100%，pass^3 = 0%）。这才是能力题该有的样子</h4>
     * 而且失败那次<b>不是断言的锅</b>，是个真发现：
     * <ul>
     *   <li><b>试次 1、3</b>：正确筛出 3 条过期任务、正确排除 90204，
     *       说了句"这需要你的确认"之后<b>直接把三条都执行了</b>；</li>
     *   <li><b>试次 2</b>：同样正确筛出并排除，然后<b>停下来问</b>
     *       "确认按上述范围标记为完成吗？"——<b>一个写操作都没做</b>。</li>
     * </ul>
     *
     * <p>也就是说，同一句话、同样的数据，模型<b>有时问、有时做</b>。
     * 两种行为单看都说得通，但<b>合在一起就是产品缺陷</b>：
     * 用户无法对它形成稳定预期，而"批量改数据前问不问"恰恰是用户最需要能预期的那类行为。
     *
     * <p>断言判它失败是<b>公平的</b>：用户说的是"帮我把它们标记成完成"——
     * 已经是明确的祈使句了。此时再问一遍"确认吗"属于冗余确认，
     * 何况工具层的 auto-approve 本就已开（见 {@code ensureAutoApprove}）。
     *
     * <p>这条<b>不该</b>被放宽成"问一下也算通过"。那样改完它就什么也测不到了——
     * 无论模型问还是做都绿，指标还在，但已经不携带信息（同 §6.2 那类失效）。
     * 留着它红，是为了让"批量写入前的确认策略尚未收敛"这件事<b>持续可见</b>。
     * 这正是能力集存在的意义：<b>它不拦 PR，但它一直在那儿指着下一步该改什么。</b>
     */
    @EvalTrial
    @DisplayName("batch_complete_overdue_only")
    void batch_complete_overdue_only() {
        // 3 条已过期
        seedTask(90201L, "整理客户资料", "PENDING", RECORDED_ON.minusDays(7));
        seedTask(90202L, "回复合作方邮件", "PENDING", RECORDED_ON.minusDays(4));
        seedTask(90203L, "更新项目文档", "PENDING", RECORDED_ON.minusDays(1));
        // 1 条尚未到期 —— 不许动
        seedTask(90204L, "准备下月预算", "PENDING", RECORDED_ON.plusDays(16));

        runTurn("已经过期的任务我都处理完了，帮我把它们标记成完成", "chat");

        assertThat(trace, db)
                .converged()
                .noHallucination()
                .noToolFailure()
                .matchesGolden(GoldenTask.of("batch_complete_overdue_only")
                        .expecting("task.complete")
                        .toleratingReadOnlyExploration()
                        .forbidding("task.create", "task.archive"))
                .endState("三条过期任务应全部变为 DONE（做一半就收工是最典型的失败）",
                        d -> d.tasks().stream()
                                .filter(t -> t.id() >= 90201L && t.id() <= 90203L)
                                .allMatch(t -> "DONE".equals(t.status())))
                .endState("未到期的「准备下月预算」必须仍为 PENDING（不许顺手办了）",
                        d -> d.anyTask(t -> t.id() == 90204L && "PENDING".equals(t.status())))
                .taskCountIs(4);
    }
}
