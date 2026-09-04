package org.zhzssp.memorandum.agenteval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zhzssp.memorandum.agenteval.cost.BudgetBaseline;
import org.zhzssp.memorandum.agenteval.cost.BudgetGate;
import org.zhzssp.memorandum.agenteval.cost.UsageAccumulator;
import org.zhzssp.memorandum.agenteval.cost.UsageSnapshot;
import org.zhzssp.memorandum.agenteval.db.EvalDbProbe;
import org.zhzssp.memorandum.agenteval.report.EvalReport;
import org.zhzssp.memorandum.agenteval.trace.CollectingTraceListener;
import org.zhzssp.memorandum.agenteval.trial.EvalTrialExtension;
import org.zhzssp.memorandum.agenteval.transport.RecordingLlmTransport;
import org.zhzssp.memorandum.agenteval.transport.ReplayLlmTransport;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.runtime.AgentOrchestrator;
import org.zhzssp.memorandum.feature.agent.runtime.ConversationMemory;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 评测基座。
 *
 * <h3>测试边界（重要）</h3>
 * 本套件评测的是 <b>Agent 的决策质量</b>：给定用户意图，它是否选对了工具、
 * 顺序是否合理、错误是否能自纠、质量信号是否正确传递、是否会编造工具。
 *
 * <p><b>不</b>评测：数据库 CRUD 正确性、向量检索召回率、MySQL 原生 SQL。
 * 那些属于各自模块的职责，且会引入外部依赖使评测无法在 CI 常态运行。
 * 因此 {@link RagSearchService} / {@link EmbeddingClient} 一律 mock。
 *
 * <h3>运行方式</h3>
 * <pre>
 * ./gradlew agentEval                            # 回放，离线零成本
 * ./gradlew agentEval -Dagent.eval.mode=record   # 录制，需 DEEPSEEK_API_KEY
 * </pre>
 */
@Tag("agent-eval")
@SpringBootTest
@ActiveProfiles("agenteval")
@Import(AgentEvalConfig.class)
@ExtendWith(EvalTrialExtension.class)
public abstract class AgentEvalBase {

    @Autowired
    protected AgentOrchestrator orchestrator;

    @Autowired
    protected ConversationMemory memory;

    @Autowired
    protected CollectingTraceListener trace;

    @Autowired
    protected LlmTransport transport;

    /** P5：token / 活体字符数 / 上游延迟的累计器。每个用例开始前清零。 */
    @Autowired
    protected UsageAccumulator usage;

    /** WebSocket 推送在评测中无意义，mock 掉避免真实发帧。 */
    @MockitoBean
    protected AgentChatWebSocketHandler webSocketHandler;

    /** 含 MySQL ngram FULLTEXT 原生 SQL，H2 无法执行 —— 必须 mock。 */
    @MockitoBean
    protected RagSearchService ragSearchService;

    /** 会调用外部 embedding 服务 —— 必须 mock。 */
    @MockitoBean
    protected EmbeddingClient embeddingClient;

    @MockitoBean
    protected UserRepository userRepository;

    /** 用于把评测用户真正写进 H2，见 {@link #ensureUserRow()}。 */
    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    protected User testUser;
    protected String sessionId;

    /** 端状态探针：校验"世界被改成了什么样"，而不只是"工具被调用过"。 */
    protected EvalDbProbe db;

    /** 本用例声明的预期工具失败，见 {@link #expectToolFailure}。 */
    private final Set<String> expectedToolFailures = new HashSet<>();

    private String currentCaseId;
    private int trial;
    private long caseStartMs;

    /** 本试次的判分台账，见 {@link org.zhzssp.memorandum.agenteval.trace.CheckLedger}。 */
    private org.zhzssp.memorandum.agenteval.trace.CheckLedger ledger;

    /** 能力集标签，与 build.gradle 的 {@code agentEvalCapability} 任务对应。 */
    static final String CAPABILITY_TAG = "agent-eval-capability";

    @BeforeEach
    void setUpEvalContext(TestInfo testInfo) {
        // 显式置空：tearDown 靠它判断 setUp 是否走完（见那里的说明）。
        // 不依赖"JUnit 每个方法新建实例"这一默认行为——它可以被 @TestInstance 改掉，
        // 而那种改动不会有任何提示，只会让收尾逻辑在某天开始读到上一个用例的残留。
        this.sessionId = null;

        // 用例 id 取自 @DisplayName 或方法名，作为录制盒文件名
        this.currentCaseId = resolveCaseId(testInfo);
        skipIfCapabilityNotYetRecorded(testInfo);
        this.trial = EvalTrialExtension.currentTrial();
        // 让扩展知道本次跑的是哪个用例，它才能在 JUnit 生命周期结束后回填成败
        EvalTrialExtension.bindCaseId(currentCaseId);
        // 试次之间必须用不同 session，否则会话记忆会跨试次串味，失败彼此相关
        this.sessionId = "eval-" + currentCaseId + (trial > 0 ? "-t" + trial : "");
        this.caseStartMs = System.currentTimeMillis();

        this.testUser = buildTestUser();
        ensureUserRow();
        cleanBusinessData();
        ensureAutoApprove();
        this.db = new EvalDbProbe(jdbcTemplate, testUser.getId());
        org.mockito.Mockito.when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(testUser));
        org.mockito.Mockito.when(userRepository.findAll())
                .thenReturn(java.util.List.of(testUser));

        expectedToolFailures.clear();
        trace.reset();
        usage.reset();
        this.ledger = org.zhzssp.memorandum.agenteval.trace.CheckLedger.begin();
        memory.clear(sessionId);

        // ReAct 循环依赖 ThreadLocal 上下文；测试直接在当前线程执行，需手工建立
        AgentContext.set(testUser, sessionId);

        beginCassette(currentCaseId, trial);
    }

    @AfterEach
    void tearDownEvalContext() {
        // setUp 中途中止（例如能力集用例尚未录制而被 assume 跳过）时，
        // sessionId 等字段还是 null。此时若照常收尾，会以一个 NPE 收场——
        // 而那个 NPE 会<b>盖掉真正的原因</b>，让"这个用例还没录"看起来像是代码崩了。
        if (sessionId == null) return;

        long elapsed = System.currentTimeMillis() - caseStartMs;
        try {
            if (transport instanceof RecordingLlmTransport rec) {
                rec.flush();
            }
            // 汇总指标：无论断言是否通过都记录，报告才有意义
            EvalReport.INSTANCE.record(currentCaseId, trial, trace,
                    db != null && db.checkCount() > 0, driftWarnings(), elapsed,
                    usage.snapshot(), recordedModel(), recordedLatenciesMs(),
                    ledger);
            assertNoUndeclaredToolFailure();
            assertWithinBudget();
            settleChecks();
        } finally {
            AgentContext.clear();
            org.zhzssp.memorandum.agenteval.trace.CheckLedger.clear();
            memory.clear(sessionId);
        }
    }

    /**
     * <b>结算判分台账</b>：把本试次记下的全部失败一次性抛出。
     *
     * <p>断言不再"首次失败即抛"（见 {@code CheckLedger} 的说明），
     * 所以必须有人负责最后把账算了。放在这里而不是让用例末尾调
     * {@code .verify()}，理由和 {@link #assertNoUndeclaredToolFailure} 完全一样：
     * <b>依赖自觉的规则迟早会被漏掉，而漏掉的后果是断言静默地不判。</b>
     * 那是本项目已经栽过两次的事故形状。
     *
     * <p>排在成本门禁之后：成本超支通常是"改了 prompt"，属于全局性原因，
     * 先报它能省去逐条看功能失败的功夫。
     */
    private void settleChecks() {
        if (ledger == null || !ledger.hasFailure()) return;
        throw new AssertionError("用例 [" + currentCaseId + "] 试次 " + trial
                + " 有 " + ledger.failures().size() + " 条判定未通过：\n\n"
                + ledger.render());
    }

    /**
     * <b>全局不变量</b>：没有声明预期失败的用例，不允许出现任何工具执行失败。
     *
     * <p>这条检查是本套件最重要的一道门禁，因为它防守的是<b>假绿</b>而非功能缺陷。
     * 真实发生过的情况是：评测用户从未落库，所有写工具都因外键约束失败，
     * 而九个用例依旧全绿——因为断言只问"工具被调用过吗"，没人问"它成功了吗"。
     * 失败信息当时只静静躺在 {@code report.json} 的 {@code failedTools} 里。
     *
     * <p>放在 tearDown 里无条件执行，而不是让各用例自己记得加
     * {@code noToolFailure()}：<b>依赖自觉的规则迟早会被漏掉，门禁不会。</b>
     */
    private void assertNoUndeclaredToolFailure() {
        List<String> undeclared = trace.failedTools().stream()
                .filter(t -> !expectedToolFailures.contains(t))
                .toList();
        if (!undeclared.isEmpty()) {
            throw new AssertionError(
                    "用例 [" + currentCaseId + "] 出现未声明的工具执行失败：" + undeclared
                    + "\n这几乎总是环境或数据问题（外键缺失、表名保留字、数据未清理），而非模型决策问题。"
                    + "\n换句话说：评测正在假绿——工具被调用了，但根本没成功。"
                    + "\n若该失败确属用例意图（如错误恢复用例），请在用例内调用 expectToolFailure(工具名)。\n\n"
                    + trace.render()
                    + (db == null ? "" : "\n" + db.render()));
        }
    }

    /** 基线只读一次：每个用例都去解析一遍 JSON 纯属浪费，且基线在一次运行内不会变。 */
    private static final java.util.Map<String, java.util.Map<String, Long>> BUDGET_BASELINE =
            BudgetBaseline.load();

    /**
     * <b>成本门禁</b>（P5）：本用例的活体开销不得超出已提交的预算基线。
     *
     * <h4>它守的是别处守不到的那一种退化</h4>
     * 往 system prompt 或工具描述里多写两句话，会发生什么？
     * 调用次数不变、工具序列不变、端状态不变、所有既有断言<b>全绿</b>——
     * 只是从此每一次调用都贵了一点。这是本套件里<b>唯一不会让任何东西变红</b>的退化。
     *
     * <p>而基于 token 的门禁在回放下<b>结构上</b>看不见它：回放返回的 usage
     * 来自录制盒，是录制当天的 token 数。所以这道门禁跑在
     * {@code requestChars}——由本次真实发出的 messages 算出的活体字符数。
     *
     * <h4>为什么判定放在每个用例的 tearDown，而不是报告里</h4>
     * 报告在 JVM 退出时才产出，那时已经没有任何测试可供判失败了。
     * 放在这里还有个附带好处：<b>失败精确指向那个用例</b>，
     * 而不是丢出一句"某处超支了"让人自己去翻。
     *
     * <p>写基线模式下不判——那次运行的<b>目的</b>就是产生新基线。
     */
    private void assertWithinBudget() {
        if (BudgetBaseline.isWriteMode()) return;
        // 本次的 k 超出基线写入时的 k：基线只见过更少的试次，比下去必然整片误报。
        // 宁可不判也不误报——一道会误报的门禁，会教人连它报真问题时一起忽略。
        if (!BudgetBaseline.assertTrialsCompatible()) {
            warnOnceAboutTrialMismatch();
            return;
        }
        java.util.Map<String, Long> base = BUDGET_BASELINE.get(currentCaseId);
        if (base == null) return;   // 新用例尚未登记，报告里会列出来，但不判红

        UsageSnapshot u = usage.snapshot();
        java.util.Map<String, Long> actual = java.util.Map.of(
                "llmCalls", u.llmCalls(),
                "requestChars", u.requestChars());

        List<BudgetGate.Verdict> over = BudgetGate.overruns(
                BudgetGate.check(java.util.Map.of(currentCaseId, base),
                        java.util.Map.of(currentCaseId, actual)));
        if (!over.isEmpty()) {
            throw new AssertionError("用例 [" + currentCaseId + "] 超出成本预算：\n"
                    + BudgetGate.render(over));
        }
    }

    private static boolean trialMismatchWarned = false;

    /**
     * 口径不匹配只提醒一次：每个用例都喊一遍，等于把这条重要提示淹进刷屏里。
     */
    private static synchronized void warnOnceAboutTrialMismatch() {
        if (trialMismatchWarned) return;
        trialMismatchWarned = true;
        System.out.println("[AgentEval] ⚠ 成本门禁本次【未判定】：基线是在 k="
                + BudgetBaseline.loadTrials() + " 下写的，本次跑 k="
                + BudgetBaseline.currentTrials() + "。\n"
                + "           基线取各试次最大值，k 变大后它必然偏小，硬判会整片误报。\n"
                + "           重建基线：gradlew agentEval \"-Dagent.eval.budget=write\" \"-Dagent.eval.trials="
                + BudgetBaseline.currentTrials() + "\"");
    }

    /**
     * 声明本用例<b>预期会发生</b>的工具执行失败，豁免上面的全局不变量。
     *
     * <p>刻意做成"用例内显式调用"而不是"子类覆写方法"：覆写是类级的，
     * 会给整个测试类开口子，而工具失败的豁免必须精确到单个用例——
     * 否则同类里新加的用例会悄悄继承这份豁免，门禁就被稀释了。
     * 写在用例体内还有个好处：豁免声明和断言彼此相邻，读代码时一眼可见。
     */
    protected void expectToolFailure(String... tools) {
        expectedToolFailures.addAll(Set.of(tools));
    }

    /** 当前试次序号（从 0 起）。由 {@link EvalTrialExtension} 注入。 */
    protected int currentTrial() {
        return trial;
    }

    /* ---- 供子类使用的辅助方法 ---- */

    /**
     * 驱动一轮 Agent 对话。
     *
     * @param userInput 用户输入
     * @param mode      对话模式：chat / plan / reflect / learn
     */
    protected void runTurn(String userInput, String mode) {
        orchestrator.handleUserTurn(sessionId, userInput, mode, null);
    }

    protected void runTurn(String userInput) {
        runTurn(userInput, "chat");
    }

    /** 回放模式下检测到的 prompt 漂移警告。 */
    protected java.util.List<String> driftWarnings() {
        return (transport instanceof ReplayLlmTransport replay)
                ? replay.driftWarnings() : java.util.List.of();
    }

    /**
     * 录制盒里存下的<b>录制当时</b>的上游耗时。
     *
     * <p>录制模式下返回空——那时的耗时由 {@code UsageAccumulator} 直接测到，
     * 是"本次运行"的值，语义与"历史录制值"不同，不能混为一谈。
     */
    private java.util.List<Long> recordedLatenciesMs() {
        return (transport instanceof ReplayLlmTransport replay)
                ? replay.recordedLatenciesMs() : java.util.List.of();
    }

    /**
     * 本用例计价所用的模型名。
     *
     * <p>回放时取自录制盒（当初真正调的那个模型），而不是当前配置——
     * 因为要折算的 token 本来就是那次调用产生的。用当前配置计价，
     * 会出现「换了模型配置，历史 token 就换了单价」这种前后不一致的成本曲线。
     */
    private String recordedModel() {
        try {
            return org.zhzssp.memorandum.agenteval.cassette.CassetteStore
                    .exists(currentCaseId)
                    ? org.zhzssp.memorandum.agenteval.cassette.CassetteStore
                        .load(currentCaseId).getRecordedModel()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前是否录制模式（子类可据此跳过纯回放断言）。 */
    protected boolean isRecording() {
        return AgentEvalConfig.isRecordMode();
    }

    /**
     * <b>能力集</b>用例尚无录制盒时跳过；回归集用例缺盒子仍然是硬错误。
     *
     * <h4>为什么两类套件在这里必须区别对待</h4>
     * <ul>
     *   <li><b>回归集</b>缺录制盒 = 有人删了资产，必须立刻炸响；</li>
     *   <li><b>能力集</b>缺录制盒 = 这座山还没开始爬，是正常状态。</li>
     * </ul>
     *
     * <p>能力集<b>本来就允许失败</b>，也正因如此更要区分两种红：
     * 「Agent 做不到」有意义，「盒子还没录」没有意义且会污染通过率。
     * 一个 30~60% 的目标通过率，只有在分母干净时才读得出东西。
     *
     * <p>判定必须放在 {@code @BeforeEach} 的<b>最前面</b>：
     * 缺盒子的异常在 {@code beginCassette} 就抛了，等到测试方法体里再 assume 已经晚了。
     */
    private void skipIfCapabilityNotYetRecorded(TestInfo testInfo) {
        if (isRecording()) return;   // 这次运行的目的就是去录它
        if (!testInfo.getTags().contains(CAPABILITY_TAG)) return;

        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.zhzssp.memorandum.agenteval.cassette.CassetteStore.exists(currentCaseId),
                () -> "尚未录制：" + currentCaseId
                        + "（跑 gradlew agentEvalCapability \"-Dagent.eval.mode=record\" 录制）");
    }

    /**
     * 以<b>指定 id</b> 预置一条任务，供「先查后改」这类多步用例引用。
     *
     * <p>必须显式指定 id 而不能靠自增：H2 的 IDENTITY 在 {@code delete} 后<b>不会回退</b>，
     * 跨用例持续递增。若让它自增，录制盒里就没法写死那个 id，
     * 而「先 search 拿 id、再 complete 该 id」恰恰是这类用例要考察的真实数据依赖。
     *
     * <p>id 取 90000 段，与自增区间拉开距离，避免撞号。
     */
    protected void seedTask(long id, String title, String status) {
        jdbcTemplate.update(
                "insert into memo (id, title, status, created_at, user_id) values (?, ?, ?, ?, ?)",
                id, title, status, java.time.LocalDateTime.now(), testUser.getId());
    }

    /**
     * 带<b>截止日期</b>预置任务，供"筛选条件由数据决定"的用例使用。
     *
     * <p>与无日期的重载分开而不是加个可空参数：多数用例并不关心 deadline，
     * 让它们都写一个 {@code null} 只会让"这个用例到底在乎不在乎日期"变得看不出来。
     *
     * <p>存 {@code atStartOfDay()}，与 {@code task.create} 的落库方式一致——
     * 否则预置数据和 Agent 写入的数据在时分秒上不同构，
     * 断言里就得为两者各写一套比对逻辑。
     */
    protected void seedTask(long id, String title, String status, java.time.LocalDate deadline) {
        jdbcTemplate.update(
                "insert into memo (id, title, status, deadline, created_at, user_id)"
                        + " values (?, ?, ?, ?, ?, ?)",
                id, title, status,
                deadline == null ? null : deadline.atStartOfDay(),
                java.time.LocalDateTime.now(), testUser.getId());
    }

    /* ---- 内部 ---- */

    private void beginCassette(String caseId, int trial) {
        if (transport instanceof RecordingLlmTransport rec) {
            rec.beginCase(caseId, trial);
        } else if (transport instanceof ReplayLlmTransport replay) {
            replay.beginCase(caseId, trial);
        }
    }

    /**
     * 解析用例 id（即录制盒文件名）。
     *
     * <p><b>刻意直接读方法上的 {@code @DisplayName} 注解，而不是
     * {@code TestInfo.getDisplayName()}</b>：多试次模式下后者会变成
     * "试次 2/3" 这类调用级名称，用它当文件名会去加载一个根本不存在的录制盒。
     */
    private String resolveCaseId(TestInfo info) {
        String name = info.getTestMethod()
                .map(m -> {
                    DisplayName dn = m.getAnnotation(DisplayName.class);
                    return (dn != null && !dn.value().isBlank()) ? dn.value() : m.getName();
                })
                .orElse("unknown");
        // 规范化为文件名安全的 id
        return name.replaceAll("[^a-zA-Z0-9\\-_\\u4e00-\\u9fa5]", "_");
    }

    private User buildTestUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername("eval-user");
        return u;
    }

    /**
     * 把评测用户真正插进 H2。
     *
     * <p>{@link UserRepository} 是 mock，用户对象只活在内存里，从不落库；
     * 而 {@code task.create} 这类写工具会插入指向 user 的外键，缺行即 23506。
     * 缺了这一步，写工具在评测里其实<strong>全部执行失败</strong>，
     * 却因为断言只检查「工具被调用过」而始终显示通过——
     * 报告里的 {@code failedTools} 是唯一能看出来的地方。
     */
    private void ensureUserRow() {
        jdbcTemplate.update(
                "MERGE INTO user (id, username, password) KEY(id) VALUES (?, ?, ?)",
                testUser.getId(), testUser.getUsername(), "eval-not-a-real-credential");
    }

    /**
     * 每个用例开始前清空业务数据。
     *
     * <p>评测库是 {@code jdbc:h2:mem:agenteval;DB_CLOSE_DELAY=-1}，<b>整个 JVM 共享一份</b>，
     * 用例之间数据会残留。不清的话，"本用例不应写入任何任务"这类负向断言
     * 会被上一个用例建的任务污染，而且污染方向是<b>让断言失败</b>——
     * 更糟的是执行顺序一变，结论就跟着变。端状态断言要求可复现的基线。
     *
     * <p>删除顺序照顾外键：先删引用方（link/memo/note），再删被引用方（goal）。
     */
    private void cleanBusinessData() {
        Long uid = testUser.getId();
        jdbcTemplate.update("delete from link");
        jdbcTemplate.update("delete from memo where user_id = ?", uid);
        jdbcTemplate.update("delete from note where user_id = ?", uid);
        jdbcTemplate.update("delete from goal where user_id = ?", uid);
    }

    /**
     * 给评测用户配上 auto-approve 白名单，让需要确认的写工具能在评测中真正执行。
     *
     * <p><b>不配这个的后果比想象中严重。</b>{@code goal.create} / {@code goal.link_task} /
     * {@code task.complete} / {@code task.archive} 都带 {@code requiresConfirm=true}，
     * 而评测里没有真人去点"允许"：{@code ToolConfirmCoordinator.askUser} 会
     * <b>阻塞整整 60 秒后按拒绝处理</b>。于是这些工具在评测中
     * 既跑不通、又拖慢套件，结果就是<b>没人敢给它们写用例</b>——
     * P0 发现的"全套件只有 2 个用例走写路径"，根子正在这里。
     *
     * <p>免确认在这里是正当的：确认弹窗是<b>UI 层的人工闸门</b>，
     * 不属于 Agent 的决策质量。要评测确认链路本身，应当单开用例显式构造，
     * 而不是让它把所有写路径用例一起拖住。
     */
    private void ensureAutoApprove() {
        Long uid = testUser.getId();
        jdbcTemplate.update("delete from user_preference where user_id = ?", uid);
        jdbcTemplate.update(
                "insert into user_preference (user_id, show_future_tasks, show_statistics, "
                        + "agent_auto_approve_tools) values (?, ?, ?, ?)",
                uid, true, false,
                String.join(",", CONFIRM_REQUIRING_TOOLS));
    }

    /**
     * 带 {@code requiresConfirm=true} 的工具全集。
     *
     * <p>刻意写成显式清单而非"全部工具"：{@code checkpoint.run} 这类
     * 在用户机器上执行真实命令的工具属于<b>硬例外</b>，
     * {@code ToolApprovalPolicy.NEVER_AUTO_APPROVE_PREFIXES} 会无视白名单强制弹窗。
     * 把它写进来只会造成"配了却不生效"的误解。
     */
    private static final List<String> CONFIRM_REQUIRING_TOOLS = List.of(
            "task.complete", "task.archive",
            "goal.create", "goal.archive", "goal.link_task"
    );
}
