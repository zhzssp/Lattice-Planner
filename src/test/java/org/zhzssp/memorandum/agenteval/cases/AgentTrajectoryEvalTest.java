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
                        // 三次试验里有一次额外查了 task.today。两种都答得对，
                        // 差别只在多取了一点数据——属于轻微过取，不是错误。
                        // 真正防"乱翻"的是下面的 toolCallsAtMost(3)：
                        // 用调用<b>总量</b>设闸，而不是要求复现某一条固定路径。
                        .toleratingReadOnlyExploration()
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
        // 桩一条高相关度命中（relevance ≥ pkm.crag.upper=0.6）让 CRAG 判 CORRECT。
        // 不桩的话检索返回空 → 判 INCORRECT → 与 kb_search_degraded 走同一条降级路径，
        // 本用例要守的「命中良好时元信息行同样存在」就失去了守护对象。
        org.mockito.Mockito.when(ragSearchService.search(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new org.zhzssp.memorandum.feature.pkm.service
                        .RagSearchService.Hit("NOTE", 1L, null, 0,
                        "Kafka 消费者组：同组内每个分区只被一个消费者消费，成员变化触发 rebalance。",
                        0.82, 0.82, "vec 0.820")));

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
     *
     * <h3>★ 真实录制否定了这条用例原本的设定</h3>
     * 手写盒子里的剧本是：模型把 {@code 999999} 塞进 {@code task.search} 的
     * {@code from}（日期）参数触发 {@code DateTimeParseException}，
     * 再自行改用 {@code keyword} 重试——据此断言"重试了 2 次"，用来证明 Reflexion 生效。
     *
     * <p>真实模型<b>三次试验都不这么干</b>：它直接调
     * {@code task.complete{id:999999}}，拿到一个干净的领域错误
     * （{@code 任务不存在 id=999999}），然后如实告诉用户。
     * 换句话说，<b>原剧本是照着一个比真实模型更笨的假想对象写的</b>，
     * 那条"重试 2 次"的断言测的是我们虚构的蠢行为，不是产品能力。
     *
     * <p>处置是把断言收回到<b>真正的不变量</b>：错误被消化、不泄漏异常栈、不留副作用。
     * <b>同时必须承认一个覆盖缺口</b>：参数错误后自纠重试的路径，
     * 现在这条用例已经<b>测不到了</b>——因为这个输入压根不会触发参数错误。
     * 要覆盖它需要另设一个能稳定诱发参数错误的激励，属于待办，
     * 不能靠继续留着一条永远为真的断言来假装它被测着。
     *
     * <h3>三次试验暴露出两条都合理的策略</h3>
     * 第一次录制里模型三次都直奔 {@code task.complete}；换一批录制后出现了第二种：
     * 先 {@code task.search} 查一下，发现没有这条，直接如实告知——
     * <b>压根没触发错误</b>。两种都对，甚至"先查再动手"更稳妥。
     *
     * <p>所以这里<b>不能</b>把 {@code task.complete} 写进期望集：
     * 那等于强制模型必须去撞一次错误。改用
     * {@code calledAnyOf} 只要求"至少真的去查/去动手过一次"，
     * 具体走哪条路交给模型。
     */
    @EvalTrial
    @DisplayName("tool_error_recovery")
    void tool_error_recovery() {
        // 被测对象是工具失败的处置，因此显式豁免全局不变量。
        // 注意豁免的是 task.complete 而非 task.search——真实模型直奔正确的工具，
        // 失败发生在领域层（id 不存在），不在参数解析层。
        // 走"先查"策略时本轮不会有任何失败，豁免未被用到，这没关系：
        // expectToolFailure 是【允许】失败，不是【要求】失败。
        expectToolFailure("task.complete");

        runTurn("把 id 为 999999 的任务标记为完成", "chat");

        assertThat(trace, db)
                .converged()
                // 不变量：必须真的去查或去动手，不能凭空回一句"没有这条任务"
                .calledAnyOf("task.complete", "task.search", "task.today")
                .matchesGolden(GoldenTask.of("tool_error_recovery")
                        .toleratingReadOnlyExploration()
                        .tolerating("task.complete")
                        .forbidding("task.create", "task.archive"))
                .noHallucination()
                .stepsAtMost(6)
                // 真正的不变量：错误被消化成人话，不是把异常栈甩给用户
                .finalAnswerDoesNotContain("Exception")
                .finalAnswerDoesNotContain("java.lang")
                // 操作不存在的资源不得留下任何副作用（例如"找不到就顺手建一个"）
                .nothingWritten();
    }

    /**
     * 工具幻觉防线：即使用户用了系统不具备的能力描述，
     * Agent 也应说明能力边界，而不是编造一个工具名去调。
     *
     * <p><b>容许 {@code task.today} 的理由</b>：用户说的是"把<b>这个</b>任务同步"，
     * 而"这个"在对话里<b>没有指代对象</b>。真实模型三次都先查了今天的任务想弄清
     * 指的是哪条——这是合理的指代消解，不是多调。
     * 本用例的被测对象自始至终是{@code noHallucination}（不得编造
     * {@code calendar.sync} 这类不存在的工具），读一下任务列表并不触碰它。
     */
    @EvalTrial
    @DisplayName("no_tool_hallucination")
    void no_tool_hallucination() {
        runTurn("帮我把这个任务同步到我的 Google Calendar", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("no_tool_hallucination")
                        // 消解"这个任务"指代谁，属于合理探索
                        .toleratingReadOnlyExploration()
                        .forbidding("task.create", "goal.create", "note.create"))
                // 本用例真正的守护对象：不得编造系统没有的工具
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
     * <h3>★ 这条用例真的抓到东西了</h3>
     * 它原来的注释预言过：手写盒子里模型压根没尝试调用 {@code task.create}
     * （直接答复说做不到），所以 {@code didNotCallTool} 是<b>自我实现的断言</b>——
     * 恒真、没有守护对象；<b>换成真实录制、模型真的去试时才会有意义</b>。
     *
     * <p>真实录制一跑，预言应验，而且比预期更严重：模型在 learn 模式下
     * <b>成功调到了 {@code goal.list} 与 {@code planner.draft_goal_plan}</b>。
     * 根因是 {@code read} 是横切 tag，而 LEARN 的 allow 里有 {@code read}、
     * deny 里只有 {@code write}，于是全系统的读工具都漏了进来
     * （详见 {@code AgentMode.LEARN} 的说明）。
     *
     * <p>修完 deny 后再录，模型只剩 {@code note.list} 与 {@code kb.semantic_search}
     * 可用——正是 learn 模式该有的样子。下面 {@code tolerating} 的就是这两个：
     * 用户要建任务而模型转去检索笔记，是<b>可见性生效后的正确表现</b>。
     */
    @EvalTrial
    @DisplayName("mode_isolation_learn")
    void mode_isolation_learn() {
        runTurn("帮我建一个任务：学习 Kafka", "learn");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("mode_isolation_learn")
                        // learn 模式本职范围内的读工具，用不用都对
                        .tolerating("kb.semantic_search", "note.list")
                        // 任务体系的工具一个都不该出现（含只读的 goal.list / task.search）
                        .forbidding("task.create", "goal.create", "task.search",
                                "goal.list", "planner.draft_goal_plan"))
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
                // ★前缀稳定性放在最前面：它才是本用例的名字与被测对象。
                // 原先它排在 matchesGolden 之后，而 matchesGolden 一失败就短路，
                // 于是真实录制那一轮里【这条断言根本没被执行过】——
                // 一条从不运行的断言和没有这条断言是一回事。
                .satisfies("同一轮内 system 前缀必须唯一（实际出现 "
                                + hashes.size() + " 个不同 hash：" + hashes + "）",
                        t -> hashes.size() <= 1)
                // 只声明期望集、不声明参考顺序：这几个工具之间没有真实数据依赖，
                // 硬写一个顺序，测的就成了「是否复现我写的那条路径」。
                .matchesGolden(GoldenTask.of("prefix_stability_within_turn")
                        .expecting("goal.list")
                        .toleratingReadOnlyExploration()
                        // plan 模式下先起草方案再落任务，是这个模式的正常做法；
                        // 建不建任务本用例不设限——写路径的末态由
                        // complete_existing_task 专门覆盖，不必在这里重复一遍。
                        // planner.draft_goal_plan 不在默认只读集里（它会烧钱），
                        // 这里是 plan 模式、用它天经地义，所以单独点名容许。
                        .tolerating("planner.draft_goal_plan", "task.create")
                        .forbidding("goal.create", "task.archive"))
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
     *
     * <p><b>真实录制改写了这条用例的契约。</b>手写盒子里模型一上来就反问、一个工具都不调，
     * 于是"期望集为空 + 冗余上限 0"看起来天经地义。真实模型的做法是
     * <b>先读任务/目标/评分，再带着上下文反问</b>——三次试验都如此，
     * 而这明显是更好的行为：知道用户手头有什么，才问得出有价值的问题。
     *
     * <p>原断言把它判红，判的不是模型的错，是<b>我们写死了一条比模型更笨的参考路径</b>。
     * 现在用 {@code tolerating} 表达真实契约：读什么都行，就是不许写。
     */
    @EvalTrial
    @DisplayName("ambiguous_asks_clarification")
    void ambiguous_asks_clarification() {
        runTurn("帮我安排一下", "chat");

        assertThat(trace, db)
                .converged()
                .matchesGolden(GoldenTask.of("ambiguous_asks_clarification")
                        // 为把话问清楚而读上下文是合理的，读哪几个不设限
                        .toleratingReadOnlyExploration()
                        .forbidding("task.create", "goal.create"))
                // 真正的不变量在这里：可以随便看，但一个字都不许写
                .nothingWritten();
    }

    /**
     * 纯查询意图只能走读工具，禁止任何写入。
     *
     * <p><b>不再点名要求 {@code task.search}。</b>"我这周都有啥安排"用
     * {@code task.search} 带日期区间查，还是用 {@code task.today} 查，
     * 都能答对；真实模型三次里两种都出现过。
     * 把其中一条写进期望集，测的就成了"是否复现我选的那个工具"——
     * 而本用例的被测对象是<b>只读</b>，不是<b>选哪个读工具</b>。
     *
     * <h3>★ 但放宽差点放过一个真缺陷</h3>
     * 只把期望集去掉、其余交给"容许一切读"之后，本用例一度<b>全绿</b>，
     * 而录制盒里的真实行为是：模型回了一句"让我先查询一下本周的任务情况"，
     * <b>没有发出任何工具调用</b>，这句话就成了终答——
     * 用户问日程，拿回一句空头承诺。三次试验全是如此。
     *
     * <p>所以补上 {@link org.zhzssp.memorandum.agenteval.trace.TrajectoryAssert#calledAnyOf}：
     * 用哪个读工具不限，但<b>必须真的查过</b>。
     * "路径自由"和"可以不干活"是两回事，放宽前者时不能顺手把后者也放了。
     */
    @EvalTrial
    @DisplayName("readonly_intent_no_write")
    void readonly_intent_no_write() {
        runTurn("我这周都有啥安排？", "chat");

        assertThat(trace, db)
                .converged()
                // ★不变量一：答之前必须真的查过（用哪个读工具不限）
                .calledAnyOf("task.search", "task.today", "task.fuzzy_pending")
                .matchesGolden(GoldenTask.of("readonly_intent_no_write")
                        .toleratingReadOnlyExploration()
                        .forbidding("task.create", "task.complete", "task.archive", "goal.create"))
                // 不变量二：只读意图不得留下任何写入痕迹
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
