package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.AgentEvalBase;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert.assertThat;

/**
 * L2 轨迹评测：Agent 决策质量回归集。
 *
 * <p>每个用例对应一个录制盒（{@code src/test/resources/agent-eval/cassettes/<caseId>.json}）。
 * 用例 id 取自方法名，不要随意改名——改名等于丢弃录制。
 *
 * <h3>断言哲学</h3>
 * 只断言<b>决策层面的不变量</b>，不断言模型的具体措辞：
 * <ul>
 *   <li>✅ "应该调用 task.create" —— 稳定，反映意图理解正确</li>
 *   <li>✅ "不应产生工具幻觉" —— 稳定，反映 schema 清晰</li>
 *   <li>✅ "降级信号必须到达 LLM" —— 稳定，防集成层断裂</li>
 *   <li>❌ "答复必须等于某句话" —— 脆弱，模型换个说法就误报</li>
 * </ul>
 */
@DisplayName("Agent 轨迹评测")
class AgentTrajectoryEvalTest extends AgentEvalBase {

    // ==========================================================
    // 组 1：基础工具调用 —— 意图 → 工具选择是否正确
    // ==========================================================

    @Test
    @DisplayName("create_task_basic")
    void create_task_basic() {
        runTurn("帮我建一个任务：周五前写完验收文档", "chat");

        assertThat(trace)
                .converged()
                .calledTool("task.create")
                .noHallucination()
                .stepsAtMost(4)
                .finalAnswerHasNoRawJson();
    }

    @Test
    @DisplayName("query_tasks")
    void query_tasks() {
        runTurn("我最近有哪些没完成的任务？", "chat");

        assertThat(trace)
                .converged()
                .noHallucination()
                .toolCallsAtMost(3)
                .finalAnswerHasNoRawJson();
    }

    // ==========================================================
    // 组 2：CRAG / Self-RAG —— 质量信号是否正确传递
    // ==========================================================

    /**
     * 命中质量好时，正常引用。
     *
     * <p>注意断言里包含 {@code cragMetaReachedLlm()}——即使命中质量好，
     * 元信息行也必须存在。这条断言专门守护一个真实发生过的缺陷：
     * {@code grade}/{@code degraded} 曾只在零命中时返回。
     */
    @Test
    @DisplayName("kb_search_hit")
    void kb_search_hit() {
        // 桩一条高分命中（score ≥ pkm.crag.upper=0.6）让 CRAG 判 CORRECT。
        // 不桩的话检索返回空 → 判 INCORRECT → 与 kb_search_degraded 走同一条降级路径，
        // 本用例要守的「命中良好时元信息行同样存在」就失去了守护对象。
        org.mockito.Mockito.when(ragSearchService.search(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new org.zhzssp.memorandum.feature.pkm.service
                        .RagSearchService.Hit("NOTE", 1L, null, 0,
                        "Kafka 消费者组：同组内每个分区只被一个消费者消费，成员变化触发 rebalance。",
                        0.82, "kw")));

        runTurn("我之前记过关于 Kafka 消费者组的笔记吗？", "learn");

        assertThat(trace)
                .converged()
                .calledTool("kb.semantic_search")
                .cragMetaReachedLlm()
                .noHallucination();
    }

    /**
     * <b>本套件最重要的用例。</b>
     *
     * <p>检索质量差时，Agent 必须明示"基于通用知识"，而不是拿着低相关片段
     * 编出一个看似有据的答案。后者比明确说"我不知道"危险得多。
     */
    @Test
    @DisplayName("kb_search_degraded")
    void kb_search_degraded() {
        runTurn("我记过关于量子色动力学的笔记吗？", "learn");

        assertThat(trace)
                .converged()
                .calledTool("kb.semantic_search")
                .cragMetaReachedLlm()
                .finalAnswerContainsAny("未找到", "没有找到", "通用知识", "没有相关")
                .noHallucination();
    }

    // ==========================================================
    // 组 3：错误恢复 —— Reflexion 是否有效
    // ==========================================================

    /**
     * 操作不存在的资源时，Agent 应消化错误并给出合理答复，
     * 而非崩溃、空转或把异常栈丢给用户。
     */
    @Test
    @DisplayName("tool_error_recovery")
    void tool_error_recovery() {
        runTurn("把 id 为 999999 的任务标记为完成", "chat");

        assertThat(trace)
                .converged()
                .noHallucination()
                .stepsAtMost(6)
                .finalAnswerDoesNotContain("Exception")
                .finalAnswerDoesNotContain("java.lang");
    }

    /**
     * 工具幻觉防线：即使用户用了系统不具备的能力描述，
     * Agent 也应说明能力边界，而不是编造一个工具名去调。
     */
    @Test
    @DisplayName("no_tool_hallucination")
    void no_tool_hallucination() {
        runTurn("帮我把这个任务同步到我的 Google Calendar", "chat");

        assertThat(trace)
                .converged()
                .noHallucination()
                .stepsAtMost(4);
    }

    // ==========================================================
    // 组 4：模式隔离 —— 工具 tag 过滤是否生效
    // ==========================================================

    /**
     * learn 模式的工具集是 {kb, note, read, subagent, mcp}，<b>不含 task</b>。
     * 因此即使用户要求建任务，{@code task.create} 也不在可见工具列表里。
     *
     * <p>这验证的是 {@code PromptBuilder.resolveTagFilter} 的隔离实际生效，
     * 而不只是写在配置里。
     */
    @Test
    @DisplayName("mode_isolation_learn")
    void mode_isolation_learn() {
        runTurn("帮我建一个任务：学习 Kafka", "learn");

        assertThat(trace)
                .converged()
                .didNotCallTool("task.create")
                .didNotCallTool("goal.create");
    }

    // ==========================================================
    // 组 5：终态清洗 —— 不泄漏内部表示
    // ==========================================================

    /**
     * reasoner 模型会输出 {@code <think>} 段，绝不能泄漏给用户。
     * 同时终态答复里不能残留 tool-call JSON。
     */
    @Test
    @DisplayName("no_internal_leak")
    void no_internal_leak() {
        runTurn("总结一下我今天的待办", "chat");

        assertThat(trace)
                .converged()
                .finalAnswerDoesNotContain("<think>")
                .finalAnswerDoesNotContain("</think>")
                .finalAnswerHasNoRawJson();

        assertNotNull(trace.finalAnswer(), "必须有终态答复");
    }

    // ==========================================================
    // 组 6：前缀稳定性 —— 服务端缓存能否命中的前提
    // ==========================================================

    /**
     * 同一轮内多步 ReAct 必须复用同一个 system 前缀。
     *
     * <p>若 prefixHash 在同一轮内发生变化，说明前缀在漂移，
     * 上游 automatic prefix caching 将永远无法命中——
     * 这会静默地让 token 成本翻倍，且没有任何报错。
     */
    @Test
    @DisplayName("prefix_stability_within_turn")
    void prefix_stability_within_turn() {
        runTurn("帮我看看有哪些目标，然后建一个相关任务", "plan");

        var hashes = trace.events().stream()
                .filter(e -> e.type() == org.zhzssp.memorandum.agenteval.trace
                        .CollectingTraceListener.EventType.LLM_CALL)
                .map(org.zhzssp.memorandum.agenteval.trace
                        .CollectingTraceListener.TraceEvent::payload)
                .distinct()
                .toList();

        assertThat(trace).satisfies(
                "同一轮内 system 前缀必须唯一（实际出现 " + hashes.size() + " 个不同 hash：" + hashes + "）",
                t -> hashes.size() <= 1);
    }
}
