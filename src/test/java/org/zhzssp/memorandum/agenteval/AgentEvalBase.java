package org.zhzssp.memorandum.agenteval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zhzssp.memorandum.agenteval.report.EvalReport;
import org.zhzssp.memorandum.agenteval.trace.CollectingTraceListener;
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

import java.util.Optional;

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

    protected User testUser;
    protected String sessionId;
    private String currentCaseId;
    private long caseStartMs;

    @BeforeEach
    void setUpEvalContext(TestInfo testInfo) {
        // 用例 id 取自 @DisplayName 或方法名，作为录制盒文件名
        this.currentCaseId = resolveCaseId(testInfo);
        this.sessionId = "eval-" + currentCaseId;
        this.caseStartMs = System.currentTimeMillis();

        this.testUser = buildTestUser();
        org.mockito.Mockito.when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(testUser));
        org.mockito.Mockito.when(userRepository.findAll())
                .thenReturn(java.util.List.of(testUser));

        trace.reset();
        memory.clear(sessionId);

        // ReAct 循环依赖 ThreadLocal 上下文；测试直接在当前线程执行，需手工建立
        AgentContext.set(testUser, sessionId);

        beginCassette(currentCaseId);
    }

    @AfterEach
    void tearDownEvalContext() {
        long elapsed = System.currentTimeMillis() - caseStartMs;
        try {
            if (transport instanceof RecordingLlmTransport rec) {
                rec.flush();
            }
            // 汇总指标：无论断言是否通过都记录，报告才有意义
            EvalReport.INSTANCE.record(currentCaseId, trace, driftWarnings(), elapsed);
        } finally {
            AgentContext.clear();
            memory.clear(sessionId);
        }
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

    /* ---- 内部 ---- */

    private void beginCassette(String caseId) {
        if (transport instanceof RecordingLlmTransport rec) {
            rec.beginCase(caseId);
        } else if (transport instanceof ReplayLlmTransport replay) {
            replay.beginCase(caseId);
        }
    }

    private String resolveCaseId(TestInfo info) {
        String display = info.getDisplayName();
        // JUnit 默认 displayName 形如 "methodName()"，去掉括号
        if (display != null && display.endsWith("()")) {
            display = display.substring(0, display.length() - 2);
        }
        if (display == null || display.isBlank()) {
            display = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("unknown");
        }
        // 规范化为文件名安全的 id
        return display.replaceAll("[^a-zA-Z0-9\\-_\\u4e00-\\u9fa5]", "_");
    }

    private User buildTestUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername("eval-user");
        return u;
    }
}
