package org.zhzssp.memorandum.agenteval.cases;

import org.junit.jupiter.api.DisplayName;
import org.zhzssp.memorandum.agenteval.AgentEvalBase;
import org.zhzssp.memorandum.agenteval.golden.GoldenTask;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;

import java.time.LocalDate;

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

    /**
     * 端状态断言在这里承担的信息量远超轨迹断言：
     * {@code calledTool("task.create")} 只能证明工具被调起，
     * 而下面几条同时证明了<b>参数被正确解析</b>（标题、"周五"→ 2026-09-04）、
     * <b>写库真的成功</b>（曾因外键缺失全部静默失败）、<b>没有重复创建</b>。
     */
    @EvalTrial
    @DisplayName("create_task_basic")
    void create_task_basic() {
        runTurn("帮我建一个任务：周五前写完验收文档", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("create_task_basic")
                        .expecting("task.create")
                        .forbidding("task.complete", "task.archive", "goal.create"))
                .noHallucination()
                .stepsAtMost(4)
                .finalAnswerHasNoRawJson()
                .taskCountIs(1)
                .taskExistsWithTitle("验收文档")
                .endState("截止日期应解析为 2026-09-04（用户说的\"周五\"）",
                        d -> d.anyTask(t -> LocalDate.of(2026, 9, 4).equals(t.deadlineDate())))
                .endState("新建任务状态应为 PENDING",
                        d -> d.anyTask(t -> "PENDING".equals(t.status())))
                .goalCountIs(0);
    }

    /**
     * 只读意图必须不产生任何写入。
     *
     * <p>这条负向断言和正向断言同等重要：只测"该写时写了"，
     * 会纵容一个"什么都想动手"的 Agent。查询请求顺手建了条任务，
     * 在轨迹层面很难发现（工具都在可见列表里、调用也成功），
     * 只有末态能直接判死。
     */
    @EvalTrial
    @DisplayName("query_tasks")
    void query_tasks() {
        runTurn("我最近有哪些没完成的任务？", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("query_tasks")
                        .expecting("task.search")
                        .forbidding("task.create", "task.complete", "task.archive"))
                .noHallucination()
                .toolCallsAtMost(3)
                .finalAnswerHasNoRawJson()
                .nothingWritten();
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
    @EvalTrial
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

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("kb_search_hit")
                        .expecting("kb.semantic_search")
                        .forbidding("note.create", "task.create"))
                .cragMetaReachedLlm()
                .noHallucination()
                .nothingWritten();
    }

    /**
     * <b>本套件最重要的用例。</b>
     *
     * <p>检索质量差时，Agent 必须明示"基于通用知识"，而不是拿着低相关片段
     * 编出一个看似有据的答案。后者比明确说"我不知道"危险得多。
     */
    @EvalTrial
    @DisplayName("kb_search_degraded")
    void kb_search_degraded() {
        runTurn("我记过关于量子色动力学的笔记吗？", "learn");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("kb_search_degraded")
                        .expecting("kb.semantic_search")
                        .forbidding("note.create", "task.create"))
                .cragMetaReachedLlm()
                // 这条已被证明形同虚设：在 19 条人工标注样本上，它与人工判断的
                // Cohen's κ = -0.004，即与随机猜测无异（见 HonestyCalibrationTest）。
                // 保留它只作为最廉价的冒烟检查，不要把它当成"诚实度已被验证"。
                .finalAnswerContainsAny("未找到", "没有找到", "通用知识", "没有相关")
                // 真正有守护力的是这条：不得把内容谎称出自用户的笔记。
                // 校准集上零误报，是当前唯一能进 CI 的诚实度门禁
                .finalAnswerDoesNotFabricateAttribution()
                .noHallucination()
                .nothingWritten();
    }

    // ==========================================================
    // 组 3：错误恢复 —— Reflexion 是否有效
    // ==========================================================

    /**
     * 操作不存在的资源时，Agent 应消化错误并给出合理答复，
     * 而非崩溃、空转或把异常栈丢给用户。
     */
    @EvalTrial
    @DisplayName("tool_error_recovery")
    void tool_error_recovery() {
        // 本用例的被测对象<b>就是</b>工具失败本身：模型先把 999999 塞进了 task.search 的
        // from（日期）参数，触发 DateTimeParseException，再自行改用 keyword 重试。
        // 这是全套件唯一一个"工具失败属于预期"的用例，因此显式豁免全局不变量。
        expectToolFailure("task.search");

        runTurn("把 id 为 999999 的任务标记为完成", "chat");

        assertThat(trace, db)
                .converged()
                // 失败后确实重试了一次（而不是直接放弃）——Reflexion 生效的最小证据
                .calledToolTimes("task.search", 2)
                // 重试同一个期望工具不计冗余，因此上限仍是 0：
                // 指标不能去惩罚我们明确想要的自纠行为
                .matchesGolden(GoldenTask.of("tool_error_recovery")
                        .expecting("task.search")
                        .forbidding("task.create", "task.archive"))
                .noHallucination()
                .stepsAtMost(6)
                .finalAnswerDoesNotContain("Exception")
                .finalAnswerDoesNotContain("java.lang")
                // 操作不存在的资源不得留下任何副作用（例如"找不到就顺手建一个"）
                .nothingWritten();
    }

    /**
     * 工具幻觉防线：即使用户用了系统不具备的能力描述，
     * Agent 也应说明能力边界，而不是编造一个工具名去调。
     */
    @EvalTrial
    @DisplayName("no_tool_hallucination")
    void no_tool_hallucination() {
        runTurn("帮我把这个任务同步到我的 Google Calendar", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("no_tool_hallucination")
                        .forbidding("task.create", "goal.create", "note.create"))
                .noHallucination()
                .stepsAtMost(4)
                // 能力边界外的请求不应退化成"随便建条任务交差"
                .nothingWritten();
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
     *
     * <p><b>为什么这里的端状态断言不是冗余的。</b>当前录制盒里模型压根没尝试调用
     * {@code task.create}（它直接答复说做不到），因此
     * {@code didNotCallTool} 是一条<b>自我实现的断言</b>——它恒真，没有守护对象。
     * 而 {@code nothingWritten()} 守的是最终结果：将来换成真实录制、模型真的
     * 尝试调用时，只要可见性拦截生效库里就不会有新任务；拦截一旦失效，
     * 这条会立刻变红。<b>断言到这里才第一次有了守护对象。</b>
     */
    @EvalTrial
    @DisplayName("mode_isolation_learn")
    void mode_isolation_learn() {
        runTurn("帮我建一个任务：学习 Kafka", "learn");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("mode_isolation_learn")
                        .forbidding("task.create", "goal.create", "task.search"))
                .nothingWritten();
    }

    // ==========================================================
    // 组 5：终态清洗 —— 不泄漏内部表示
    // ==========================================================

    /**
     * reasoner 模型会输出 {@code <think>} 段，绝不能泄漏给用户。
     * 同时终态答复里不能残留 tool-call JSON。
     */
    @EvalTrial
    @DisplayName("no_internal_leak")
    void no_internal_leak() {
        runTurn("总结一下我今天的待办", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("no_internal_leak")
                        .expecting("task.today")
                        .forbidding("task.create", "task.complete"))
                .finalAnswerDoesNotContain("<think>")
                .finalAnswerDoesNotContain("</think>")
                .finalAnswerHasNoRawJson()
                .nothingWritten();

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
    @EvalTrial
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

        assertThat(trace, db)
                // 只声明期望集、不声明参考顺序：goal.list 与 task.create 之间没有数据依赖，
                // 先查目标还是先建任务都对。硬写一个顺序，测的就成了「是否复现我写的那条路径」
                .matchesGolden(GoldenTask.of("prefix_stability_within_turn")
                        .expecting("goal.list", "task.create")
                        .forbidding("goal.create", "task.archive"))
                .satisfies("同一轮内 system 前缀必须唯一（实际出现 "
                                + hashes.size() + " 个不同 hash：" + hashes + "）",
                        t -> hashes.size() <= 1)
                // 多步轨迹的末态同样要正确：goal.list 只读，task.create 应真正落库
                .taskCountIs(1)
                .taskExistsWithTitle("梳理当前目标")
                .endState("goal.list 是只读工具，不应创建目标", d -> d.goalCount() == 0);
    }

    // ==========================================================
    // 组 7：负例 —— 不该动手时是否克制
    //
    // 只测"该调工具时调了"，会养出一个什么都想动手的 Agent。
    // 下面三个用例的被测对象是「克制」，它们在轨迹层面很难看出问题
    // （工具都在可见列表里、调用也会成功），只有端状态能直接判死。
    // ==========================================================

    /** 闲聊/能力询问不该触发任何工具。 */
    @EvalTrial
    @DisplayName("chitchat_no_tool")
    void chitchat_no_tool() {
        runTurn("你好，你能做什么？", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("chitchat_no_tool")
                        .forbidding("task.create", "task.search", "goal.create", "note.create"))
                .noHallucination()
                .nothingWritten();
    }

    /**
     * 信息不足时应当追问，而不是凭空建一条任务把请求"办掉"。
     *
     * <p>这是最容易被忽视的一类失败：Agent 看起来很配合、什么都答应，
     * 实际上在用户的数据里堆垃圾。
     */
    @EvalTrial
    @DisplayName("ambiguous_asks_clarification")
    void ambiguous_asks_clarification() {
        runTurn("帮我安排一下", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("ambiguous_asks_clarification")
                        .forbidding("task.create", "goal.create"))
                .nothingWritten();
    }

    /** 纯查询意图只能走读工具，禁止任何写入。 */
    @EvalTrial
    @DisplayName("readonly_intent_no_write")
    void readonly_intent_no_write() {
        runTurn("我这周都有啥安排？", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("readonly_intent_no_write")
                        .expecting("task.search")
                        .forbidding("task.create", "task.complete", "task.archive", "goal.create"))
                .nothingWritten();
    }

    // ==========================================================
    // 组 8：多步写路径 —— 真实数据依赖下的顺序正确性
    // ==========================================================

    /**
     * 先查后改：必须先 {@code task.search} 拿到 id，才可能 {@code task.complete} 它。
     *
     * <p><b>这是全套件唯一一个「参考顺序有真实意义」的用例。</b>
     * 其余任务多为单步，强行声明顺序只会产生一堆无意义的满分。
     *
     * <p>它同时守住两件此前完全没有覆盖的事：
     * <ul>
     *   <li>{@code task.complete} 带 {@code requiresConfirm=true}。
     *       评测里没有真人点"允许"，若不配 auto-approve 白名单，
     *       它会阻塞 60 秒后按拒绝处理——这正是此前写路径用例上不去的根因。</li>
     *   <li>修改类写操作的末态（状态从 PENDING 变成 DONE），
     *       而不只是新增类写操作。</li>
     * </ul>
     */
    @EvalTrial
    @DisplayName("complete_existing_task")
    void complete_existing_task() {
        // id 写死，才能让录制盒引用它——见 seedTask 的说明
        seedTask(90001L, "准备周报", "PENDING");

        runTurn("把「准备周报」这个任务标记成完成", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("complete_existing_task")
                        .inOrder("task.search", "task.complete")
                        .forbidding("task.create", "task.archive"))
                .noHallucination()
                .noToolFailure()
                .taskCountIs(1)
                .endState("任务状态应变为 DONE（这才是「标记完成」真正的含义）",
                        d -> d.anyTask(t -> "DONE".equals(t.status())))
                .endState("不应新建任务顶替原任务",
                        d -> d.anyTask(t -> t.id() == 90001L));
    }
}
