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

    @BeforeEach
    void setUpEvalContext(TestInfo testInfo) {
        // 用例 id 取自 @DisplayName 或方法名，作为录制盒文件名
        this.currentCaseId = resolveCaseId(testInfo);
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
        memory.clear(sessionId);

        // ReAct 循环依赖 ThreadLocal 上下文；测试直接在当前线程执行，需手工建立
        AgentContext.set(testUser, sessionId);

        beginCassette(currentCaseId, trial);
    }

    @AfterEach
    void tearDownEvalContext() {
        long elapsed = System.currentTimeMillis() - caseStartMs;
        try {
            if (transport instanceof RecordingLlmTransport rec) {
                rec.flush();
            }
            // 汇总指标：无论断言是否通过都记录，报告才有意义
            EvalReport.INSTANCE.record(currentCaseId, trial, trace,
                    db != null && db.checkCount() > 0, driftWarnings(), elapsed);
            assertNoUndeclaredToolFailure();
        } finally {
            AgentContext.clear();
            memory.clear(sessionId);
        }
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

    /** 当前是否录制模式（子类可据此跳过纯回放断言）。 */
    protected boolean isRecording() {
        return AgentEvalConfig.isRecordMode();
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
