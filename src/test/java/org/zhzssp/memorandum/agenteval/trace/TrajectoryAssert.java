package org.zhzssp.memorandum.agenteval.trace;

import java.util.List;

/**
 * 轨迹断言 DSL（流式 API）。
 *
 * <p>设计要点：<b>每次断言失败都附上完整轨迹渲染</b>。
 * Agent 测试失败时最痛苦的是"不知道它到底走了什么路"，
 * 因此这里牺牲一点错误消息的简洁性，换取定位效率。
 *
 * <p>用法：
 * <pre>
 * assertThat(trace)
 *     .converged()
 *     .calledTool("kb.semantic_search")
 *     .thenCalledTool("task.create")
 *     .noHallucination()
 *     .stepsAtMost(6)
 *     .finalAnswerContains("已创建");
 * </pre>
 */
public final class TrajectoryAssert {

    private final CollectingTraceListener trace;

    private TrajectoryAssert(CollectingTraceListener trace) {
        this.trace = trace;
    }

    public static TrajectoryAssert assertThat(CollectingTraceListener trace) {
        return new TrajectoryAssert(trace);
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

    /* ---- 自定义断言逃生口 ---- */

    public TrajectoryAssert satisfies(String description, java.util.function.Predicate<CollectingTraceListener> p) {
        if (!p.test(trace)) {
            fail("自定义断言未通过：" + description);
        }
        return this;
    }

    /* ---- 内部 ---- */

    private void fail(String message) {
        throw new AssertionError(message + "\n\n" + trace.render());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
