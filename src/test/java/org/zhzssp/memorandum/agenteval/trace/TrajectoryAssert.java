package org.zhzssp.memorandum.agenteval.trace;

import org.zhzssp.memorandum.agenteval.db.EvalDbProbe;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;
import org.zhzssp.memorandum.agenteval.report.EvalReport;
import org.zhzssp.memorandum.agenteval.report.TrajectoryMetrics;
import org.zhzssp.memorandum.agenteval.trial.EvalTrialExtension;

import java.util.List;
import java.util.function.Predicate;

/**
 * 轨迹断言 DSL（流式 API）。
 *
 * <p>设计要点：<b>每次断言失败都附上完整轨迹渲染</b>（有端状态探针时连库内容一并打印）。
 * Agent 测试失败时最痛苦的是"不知道它到底走了什么路"，
 * 因此这里牺牲一点错误消息的简洁性，换取定位效率。
 *
 * <p>用法：
 * <pre>
 * assertThat(trace, db)
 *     .converged()
 *     .calledTool("kb.semantic_search")
 *     .thenCalledTool("task.create")
 *     .noHallucination()
 *     .stepsAtMost(6)
 *     .endState("库里确实多了这条任务", d -&gt; d.anyTask(t -&gt; t.titleContains("验收文档")));
 * </pre>
 */
public final class TrajectoryAssert {

    private final CollectingTraceListener trace;

    /** 端状态探针；用不带 db 的入口时为 null，此时调用 {@link #endState} 会直接失败。 */
    private final EvalDbProbe db;

    private TrajectoryAssert(CollectingTraceListener trace, EvalDbProbe db) {
        this.trace = trace;
        this.db = db;
    }

    public static TrajectoryAssert assertThat(CollectingTraceListener trace) {
        return new TrajectoryAssert(trace, null);
    }

    /** 带端状态探针的入口。只有用这个才能调 {@link #endState}。 */
    public static TrajectoryAssert assertThat(CollectingTraceListener trace, EvalDbProbe db) {
        return new TrajectoryAssert(trace, db);
    }

    /* ---- 结果态断言 ---- */

    /** 断言正常收敛（给出终态答复）。 */
    public TrajectoryAssert converged() {
        if (!trace.converged()) {
            String reason = trace.isExhausted() ? "步数耗尽"
                    : trace.llmFailure() != null ? ("LLM 失败: " + trace.llmFailure())
                    : "未产生终态答复";
            fail("期望 Agent 正常收敛，实际：" + reason);
        }
        return this;
    }

    /** 断言步数耗尽（用于验证防工具循环机制本身）。 */
    public TrajectoryAssert stepsExhausted() {
        if (!trace.isExhausted()) {
            fail("期望步数耗尽，但 Agent 正常收敛了");
        }
        return this;
    }

    /* ---- 工具调用断言 ---- */

    /** 断言调用过某工具（任意位置）。 */
    public TrajectoryAssert calledTool(String tool) {
        if (!trace.toolSequence().contains(tool)) {
            fail("期望调用工具 [" + tool + "]，实际调用序列：" + trace.toolSequence());
        }
        return this;
    }

    /**
     * 断言<b>至少</b>调用了给定集合里的某一个工具。
     *
     * <h3>它补的是一个真实漏过去的洞</h3>
     * 起因是把 {@code readonly_intent_no_write} 的 {@code expecting("task.search")}
     * 放宽成"哪个读工具都行"——理由本身没错（用 {@code task.search} 还是
     * {@code task.today} 查本周安排都对），但放宽<b>过头</b>了：
     * 变成了"一个都不查也行"。
     *
     * <p>随后真实录制里就出现了三次全中的坏行为：模型回了一句
     * "让我先查询一下本周的任务情况"，<b>却根本没发出工具调用</b>，
     * 这句话直接成了终答。用户问"我这周都有啥安排"，拿到的是一句空头承诺。
     * 而放宽后的断言让它<b>全绿通过</b>。
     *
     * <p>教训是：把"必须走这条路径"放宽时，不能顺手把"必须真的去查"一起丢掉。
     * 前者是路径，后者是不变量。这个方法就是用来单独守住后者的。
     */
    public TrajectoryAssert calledAnyOf(String... tools) {
        List<String> actual = trace.toolSequence();
        for (String t : tools) {
            if (actual.contains(t)) return this;
        }
        fail("期望至少调用 " + List.of(tools) + " 其中之一，实际调用序列：" + actual
                + "\n注意：一次都没查就作答，等于把答案编出来——"
                + "哪怕它嘴上说「让我查一下」也一样。");
        return this;
    }

    /** 断言未调用过某工具。 */
    public TrajectoryAssert didNotCallTool(String tool) {
        if (trace.toolSequence().contains(tool)) {
            fail("期望不调用工具 [" + tool + "]，但它被调用了。序列：" + trace.toolSequence());
        }
        return this;
    }

    /**
     * 断言工具按给定<b>相对顺序</b>出现（允许中间夹杂其它工具）。
     *
     * <p>刻意不做严格相等：Agent 多调一次检索确认信息是合理行为，
     * 强行要求精确序列会让测试极度脆弱、频繁误报。
     */
    public TrajectoryAssert calledToolsInOrder(String... tools) {
        List<String> actual = trace.toolSequence();
        int cursor = 0;
        for (String expected : tools) {
            boolean found = false;
            while (cursor < actual.size()) {
                if (actual.get(cursor++).equals(expected)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                fail("期望工具按顺序出现 " + List.of(tools) + "，在 [" + expected
                        + "] 处中断。实际序列：" + actual);
            }
        }
        return this;
    }

    /** 语义糖：紧接上一次断言之后再调用了某工具（等价于加入顺序链）。 */
    public TrajectoryAssert thenCalledTool(String tool) {
        return calledTool(tool);
    }

    /** 断言某工具的调用次数。 */
    public TrajectoryAssert calledToolTimes(String tool, int times) {
        long actual = trace.toolSequence().stream().filter(tool::equals).count();
        if (actual != times) {
            fail("期望工具 [" + tool + "] 调用 " + times + " 次，实际 " + actual + " 次");
        }
        return this;
    }

    /** 断言工具调用总数不超过上限（防止 Agent 过度调用）。 */
    public TrajectoryAssert toolCallsAtMost(int max) {
        int actual = trace.toolSequence().size();
        if (actual > max) {
            fail("工具调用次数 " + actual + " 超过上限 " + max);
        }
        return this;
    }

    /* ---- 质量断言 ---- */

    /** 断言无工具幻觉（未调用不存在的工具）。 */
    public TrajectoryAssert noHallucination() {
        List<String> h = trace.hallucinatedTools();
        if (!h.isEmpty()) {
            fail("检测到工具幻觉，Agent 调用了不存在的工具：" + h);
        }
        return this;
    }

    /** 断言无工具执行失败。 */
    public TrajectoryAssert noToolFailure() {
        List<String> f = trace.failedTools();
        if (!f.isEmpty()) {
            fail("存在工具执行失败：" + f);
        }
        return this;
    }

    /** 断言收敛步数不超过上限。 */
    public TrajectoryAssert stepsAtMost(int max) {
        if (trace.usedSteps() > max) {
            fail("收敛步数 " + trace.usedSteps() + " 超过上限 " + max);
        }
        return this;
    }

    /** 断言 LLM 调用次数不超过上限（成本控制）。 */
    public TrajectoryAssert llmCallsAtMost(int max) {
        long actual = trace.llmCallCount();
        if (actual > max) {
            fail("LLM 调用次数 " + actual + " 超过上限 " + max);
        }
        return this;
    }

    /* ---- 终态答复断言 ---- */

    public TrajectoryAssert finalAnswerContains(String fragment) {
        String a = trace.finalAnswer();
        if (a == null || !a.contains(fragment)) {
            fail("终态答复应包含 [" + fragment + "]，实际：" + a);
        }
        return this;
    }

    /**
     * 检索降级时，答复不得把内容谎称出自用户的笔记。
     *
     * <p>这条比 {@link #finalAnswerContainsAny} 硬得多，因为它守的是
     * <b>危害本身</b>而不是某种措辞：用户读到"根据你的笔记，……"会默认
     * 这句是自己写过的，从而放弃核实。校准集上它零误报（见
     * {@code judge/AttributionRedFlag}），适合当 CI 门禁——
     * 门禁误报一次，人就开始习惯性忽略它。
     */
    public TrajectoryAssert finalAnswerDoesNotFabricateAttribution() {
        String hit = org.zhzssp.memorandum.agenteval.judge.AttributionRedFlag
                .detect(trace.finalAnswer());
        if (hit != null) {
            fail("检索已降级，答复却用「" + hit + "」把内容归到用户的笔记上。"
                    + "这会让用户放弃核实，是本项目最不能接受的一类失败。实际答复：\n"
                    + trace.finalAnswer());
        }
        return this;
    }

    public TrajectoryAssert finalAnswerContainsAny(String... fragments) {
        String a = trace.finalAnswer();
        if (a != null) {
            for (String f : fragments) {
                if (a.contains(f)) return this;
            }
        }
        fail("终态答复应包含 " + List.of(fragments) + " 之一，实际：" + a);
        return this;
    }

    public TrajectoryAssert finalAnswerDoesNotContain(String fragment) {
        String a = trace.finalAnswer();
        if (a != null && a.contains(fragment)) {
            fail("终态答复不应包含 [" + fragment + "]，实际：" + a);
        }
        return this;
    }

    /**
     * 断言终态答复中<b>不含裸露的 tool-call JSON</b>。
     *
     * <p>这是真实踩过的问题：模型有时会在自然语言里残留 JSON，
     * 若 {@code cleanForDisplay} 失效就会泄漏给用户。
     */
    public TrajectoryAssert finalAnswerHasNoRawJson() {
        String a = trace.finalAnswer();
        if (a != null && a.contains("\"tool\"") && a.contains("{")) {
            fail("终态答复泄漏了 tool-call JSON：" + a);
        }
        return this;
    }

    /* ---- CRAG 专项断言 ---- */

    /**
     * 断言 CRAG 的降级信号<b>真的到达了 LLM</b>。
     *
     * <p>专门防守一个真实发生过的缺陷：{@code grade}/{@code degraded} 曾只在
     * 零命中时返回，导致"有命中但质量差"这一最危险场景的降级信号被丢弃，
     * Self-RAG 链路实际断开。组件单测全过，但集成层断了。
     */
    public TrajectoryAssert cragMetaReachedLlm() {
        List<String> results = trace.resultsOf("kb.semantic_search");
        if (results.isEmpty()) {
            fail("未调用 kb.semantic_search，无法校验 CRAG 元信息");
        }
        for (String r : results) {
            if (r == null || !r.contains("\"_meta\"")
                    || !r.contains("\"grade\"") || !r.contains("\"degraded\"")) {
                fail("kb.semantic_search 返回未包含 CRAG 元信息（_meta/grade/degraded），"
                        + "Self-RAG 信号链路断裂。实际返回：" + truncate(r, 500));
            }
        }
        return this;
    }

    /** 断言检索被判定为指定 grade。 */
    public TrajectoryAssert cragGradeWas(String grade) {
        List<String> results = trace.resultsOf("kb.semantic_search");
        boolean matched = results.stream()
                .anyMatch(r -> r != null && r.contains("\"grade\":\"" + grade + "\""));
        if (!matched) {
            fail("期望 CRAG grade=" + grade + "，实际返回：" + truncate(String.join(" | ", results), 500));
        }
        return this;
    }

    /* ---- 轨迹契约断言 ---- */

    /**
     * 按 {@link GoldenTask} 声明的契约校验工具选择质量，并把分解指标登记进报告。
     *
     * <p>它同时做四件断言，每一条对应一种<b>具体的错法</b>：
     * <ul>
     *   <li><b>禁用工具命中必须为 0</b>——最硬的一条。调了明确不该调的工具，
     *       无论结果如何都是错的</li>
     *   <li><b>召回率必须为 1</b>——期望的工具一个都不能漏</li>
     *   <li><b>冗余调用不超过声明上限</b>——防"什么都想动手"</li>
     *   <li><b>顺序一致性 τ ≥ 0.85</b>——仅当声明了参考顺序</li>
     * </ul>
     *
     * <p>把它们分开报而不是合成一个通过率，是因为三种错法的修法完全不同：
     * 漏调多半是工具描述不清，多调多半是提示词鼓励了过度行动，顺序错才是规划问题。
     */
    public TrajectoryAssert matchesGolden(GoldenTask task) {
        TrajectoryMetrics m = TrajectoryMetrics.of(trace.toolSequence(), task);
        EvalReport.INSTANCE.recordTrajectory(
                task.caseId(), EvalTrialExtension.currentTrial(), m);

        if (m.forbiddenHits() > 0) {
            failWith(m, "调用了明确禁用的工具：" + m.forbiddenCalled());
        }
        if (m.recall() < 1.0) {
            failWith(m, "漏调了期望工具：" + m.missingTools());
        }
        if (m.redundantCalls() > task.maxRedundantCalls()) {
            failWith(m, "冗余调用 " + m.redundantCalls() + " 次，超过上限 "
                    + task.maxRedundantCalls() + "。多调的工具：" + m.unexpectedTools());
        }
        if (m.kendallTau() != null && m.kendallTau() < ORDER_THRESHOLD) {
            failWith(m, "顺序一致性 τ=" + m.kendallTau() + " 低于阈值 " + ORDER_THRESHOLD
                    + "。参考顺序 " + task.referenceOrder() + "，实际 " + trace.toolSequence());
        }
        return this;
    }

    /**
     * 顺序一致性阈值。不取 1.0 是刻意的：
     * Agent 中途多查一次确认信息是合理行为，要求精确复现参考路径会让测试极度脆弱。
     */
    private static final double ORDER_THRESHOLD = 0.85;

    private void failWith(TrajectoryMetrics m, String message) {
        fail(message + "\n\n" + m.render());
    }

    /* ---- 端状态断言 ---- */

    /**
     * 断言数据库<b>末态</b>符合预期。
     *
     * <p>这是判分粒度上最重要的一次升级：轨迹断言只能证明"工具被调用过"，
     * 而这里证明"事情被做对了"。参数解析错误、写库失败、写到别的用户名下，
     * 都只有在这一层才暴露得出来。
     *
     * <p>刻意接受任意 {@link Predicate} 而非提供一堆专用方法：
     * 每个用例要验的末态差异很大，穷举 API 只会让 DSL 膨胀且仍然不够用。
     */
    public TrajectoryAssert endState(String description, Predicate<EvalDbProbe> p) {
        if (db == null) {
            fail("该用例未提供 EvalDbProbe，无法做端状态断言。请改用 assertThat(trace, db)");
        }
        db.markChecked();
        if (!p.test(db)) {
            fail("端状态断言未通过：" + description);
        }
        return this;
    }

    /** 断言任务总数。{@code n=0} 用于"只读意图不得写库"这类负向断言。 */
    public TrajectoryAssert taskCountIs(int n) {
        return endState("任务总数应为 " + n, d -> d.taskCount() == n);
    }

    /** 断言目标总数。 */
    public TrajectoryAssert goalCountIs(int n) {
        return endState("目标总数应为 " + n, d -> d.goalCount() == n);
    }

    /**
     * 断言库里存在标题含指定片段的任务。
     *
     * <p>比 {@code calledTool("task.create")} 强得多：它还顺带验证了参数解析
     * ——标题被截断、编码错乱、写进了别的用户名下，都会在这里翻车。
     */
    public TrajectoryAssert taskExistsWithTitle(String fragment) {
        return endState("应存在标题含 [" + fragment + "] 的任务",
                d -> d.anyTask(t -> t.titleContains(fragment)));
    }

    /** 断言库与目标表都没有任何写入（只读意图的完整表达）。 */
    public TrajectoryAssert nothingWritten() {
        return endState("本用例不应产生任何写入（任务与目标均应为空）",
                d -> d.taskCount() == 0 && d.goalCount() == 0);
    }

    /* ---- 自定义断言逃生口 ---- */

    public TrajectoryAssert satisfies(String description, java.util.function.Predicate<CollectingTraceListener> p) {
        if (!p.test(trace)) {
            fail("自定义断言未通过：" + description);
        }
        return this;
    }

    /* ---- 内部 ---- */

    private void fail(String message) {
        String detail = message + "\n\n" + trace.render();
        // 有探针时把真实库内容一并打印：端状态失败若不给这个，几乎无法定位
        if (db != null) {
            detail += "\n" + db.render();
        }
        throw new AssertionError(detail);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
