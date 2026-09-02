package org.zhzssp.memorandum.agenteval.trial;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.TestWatcher;
import org.zhzssp.memorandum.agenteval.report.EvalReport;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 把一个 {@link EvalTrial} 用例展开成 k 次独立试验，并把每次的<b>成败</b>回填进报告。
 *
 * <h3>它解决的两个问题</h3>
 * <ol>
 *   <li><b>动态试次数</b>：{@code -Dagent.eval.trials=k}，无需改代码重编译。</li>
 *   <li><b>成败归集</b>：{@code EvalReport} 原先只记录"是否收敛"，
 *       但收敛 ≠ 用例通过（可以收敛了却断言失败）。而 {@code pass^k} 要的恰恰是
 *       <b>用例是否通过</b>。断言结果只有 JUnit 知道，所以这里用 {@link TestWatcher}
 *       在用例生命周期结束后把真实成败回填。</li>
 * </ol>
 *
 * <h3>为什么用 ThreadLocal 传递试次号</h3>
 * JUnit 的 {@code @BeforeEach} / 测试体 / {@code @AfterEach} / {@link TestWatcher}
 * 保证在同一线程上顺序执行，因此 ThreadLocal 是这里最直接的通道，
 * 不必为了传一个 int 去改动 {@code AgentEvalBase} 的方法签名或引入参数解析器。
 */
public final class EvalTrialExtension
        implements TestTemplateInvocationContextProvider, TestWatcher {

    /** 试次数系统属性。 */
    public static final String TRIALS_PROPERTY = "agent.eval.trials";

    private static final ThreadLocal<Integer> TRIAL = new ThreadLocal<>();
    private static final ThreadLocal<String> CASE_ID = new ThreadLocal<>();

    /** 配置的试次数，最小为 1。 */
    public static int trialCount() {
        return Math.max(1, Integer.getInteger(TRIALS_PROPERTY, 1));
    }

    /** 当前试次号（从 0 起）。非多试次上下文中为 0。 */
    public static int currentTrial() {
        Integer v = TRIAL.get();
        return v == null ? 0 : v;
    }

    /**
     * 由 {@code AgentEvalBase} 在 {@code @BeforeEach} 里登记本次用例 id。
     *
     * <p>刻意让基类来登记、而不是在这里从测试方法名自行推断：
     * 两处各推断一次就可能推断得不一致，成败会被记到错误的用例上，
     * 而这种错误在报告里<b>看不出来</b>。
     */
    public static void bindCaseId(String caseId) {
        CASE_ID.set(caseId);
    }

    /* ---- 展开试次 ---- */

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getTestMethod()
                .map(m -> m.isAnnotationPresent(EvalTrial.class))
                .orElse(false);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            ExtensionContext context) {
        int k = trialCount();
        return IntStream.range(0, k).mapToObj(i -> new TrialContext(i, k));
    }

    private record TrialContext(int trial, int total) implements TestTemplateInvocationContext {

        @Override
        public String getDisplayName(int invocationIndex) {
            // JUnit 要求非空白，不能因为 k=1 就省略
            return total == 1 ? "单次运行" : "试次 " + (trial + 1) + "/" + total;
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            // 在 @BeforeEach 之前把试次号放进 ThreadLocal，供 AgentEvalBase 载入对应录制
            return List.of((org.junit.jupiter.api.extension.BeforeEachCallback)
                    ctx -> TRIAL.set(trial));
        }
    }

    /* ---- 回填成败 ---- */

    @Override
    public void testSuccessful(ExtensionContext context) {
        finish(true);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        finish(false);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        // 被假设跳过的用例不参与 pass^k 统计，直接清理即可
        clear();
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        clear();
    }

    private void finish(boolean passed) {
        String caseId = CASE_ID.get();
        if (caseId != null) {
            EvalReport.INSTANCE.markOutcome(caseId, currentTrial(), passed);
        }
        clear();
    }

    private void clear() {
        TRIAL.remove();
        CASE_ID.remove();
    }
}
