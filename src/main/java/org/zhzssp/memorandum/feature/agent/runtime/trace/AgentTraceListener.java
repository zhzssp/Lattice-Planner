package org.zhzssp.memorandum.feature.agent.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 执行轨迹监听器。
 *
 * <p><strong>动机</strong>：ReAct 循环的内部决策序列（第几步调了什么工具、结果是否出错、
 * 最终是给出答案还是耗尽步数）此前只体现在日志和 WebSocket 推送里，
 * 无法被程序化断言，也无法统计。本接口把这些事件显式化，服务两个消费方：
 * <ul>
 *   <li>生产：{@code AgentMetricsTraceListener} 统计步数分布、工具失败率、幻觉次数</li>
 *   <li>测试：评测框架的收集器，据此断言轨迹并算工具选择准确率</li>
 * </ul>
 *
 * <p><strong>实现约定</strong>：所有方法必须是<strong>非阻塞、不抛异常</strong>的。
 * 调用方（{@code AgentOrchestrator}）会用 try-catch 包裹，但监听器自身也应保证
 * 不因观测逻辑影响主链路。
 *
 * <p>Spring 会把所有实现注入为 {@code List<AgentTraceListener>}，
 * 无实现时为空列表，因此该机制是零配置、可选的。
 */
public interface AgentTraceListener {

    /** 一轮用户输入开始处理。 */
    default void onTurnStart(String sessionId, String userInput, String mode) {}

    /**
     * 即将发起第 step 步 LLM 调用。
     *
     * @param step       从 0 开始的步序号
     * @param prefixHash 本次使用的 system 前缀 hash，可用于验证前缀稳定性
     */
    default void onLlmCall(String sessionId, int step, String prefixHash) {}

    /** LLM 决定调用某工具（已解析出工具名与参数，尚未执行）。 */
    default void onToolCall(String sessionId, int step, String tool, JsonNode arguments) {}

    /**
     * 工具执行完毕。
     *
     * @param isError    执行是否失败（含异常与工具自身返回的 error 语义）
     * @param elapsedMs  工具执行耗时
     */
    default void onToolResult(String sessionId, int step, String tool,
                              String resultJson, boolean isError, long elapsedMs) {}

    /**
     * LLM 调用了一个不存在的工具——即<strong>工具幻觉</strong>。
     * 这是评估 Agent 质量的关键负向指标。
     */
    default void onUnknownTool(String sessionId, int step, String tool) {}

    /** 触发了高危工具确认弹窗，并得到用户裁决。 */
    default void onConfirmDecision(String sessionId, int step, String tool, boolean approved) {}

    /** 正常给出终态自然语言答复。 */
    default void onFinalAnswer(String sessionId, int usedSteps, String answer) {}

    /** 步数耗尽而未收敛——工具循环的直接证据。 */
    default void onStepsExhausted(String sessionId, int maxSteps) {}

    /** LLM 调用失败导致本轮中止。 */
    default void onLlmFailure(String sessionId, int step, String message) {}

    /* ---------------- 方案 E：参数前置校验 ---------------- */

    /**
     * 工具调用<strong>因参数校验未通过而未被执行</strong>。
     *
     * <p>与 {@link #onToolResult} 的 {@code isError} 区别：这里工具根本没跑，
     * 无任何副作用。该指标反映「工具 schema 是否易被模型正确填写」。
     *
     * @param params 有问题的参数名列表
     */
    default void onArgumentsRejected(String sessionId, int step, String tool, java.util.List<String> params) {}

    /* ---------------- 方案 D：显式 Reflexion ---------------- */

    /**
     * 一次<strong>自修复尝试</strong>：该工具此前已在本轮失败过，模型现在再试一次。
     *
     * <p>配合 {@link #onRepairOutcome} 可算出自修复成功率——这是衡量
     * 「回灌的错误信息是否真的可操作」的唯一客观指标。
     */
    default void onRepairAttempt(String sessionId, int step, String tool, int priorFailures) {}

    /** 自修复尝试的结果。 */
    default void onRepairOutcome(String sessionId, int step, String tool, boolean success) {}

    /**
     * 注入了显式策略提示（区别于单纯回灌错误 JSON）。
     *
     * @param failureMode {@code ReflexionAdvisor.FailureMode} 名称
     */
    default void onStrategyHint(String sessionId, int step, String tool,
                                String failureMode, int failCount) {}

    /**
     * 模型无视封禁提示再次调用了已封禁工具，被<strong>执行层短路阻断</strong>。
     *
     * <p>这个计数直接回答「建议层够不够」：若长期为 0，说明模型遵从提示，
     * 强制层是冗余保险；若不为 0，就证明了强制层的必要性。
     */
    default void onToolBanned(String sessionId, int step, String tool, String failureMode) {}

    /* ---------------- 方案 L：轮次收尾归因 ---------------- */

    /**
     * 一轮用户输入<strong>真正结束</strong>（统一收尾出口）。
     *
     * <p>与 {@link #onFinalAnswer} 的区别：{@code onFinalAnswer} 在终态答复产生的
     * <strong>那一刻</strong>触发；{@code onTurnEnd} 在<strong>所有结束路径</strong>
     * 收敛后触发，且携带粘性降级标记——用于归因「这一轮是否在丢过信息的情况下作答」。</p>
     *
     * @param reason        {@code TurnEndReason} 名称
     * @param degraded      本轮是否发生过信息丢失（粘性，只增不减）
     * @param degradeCauses 降级原因集合（如 TRUNCATED / CRAG_DEGRADED）
     */
    default void onTurnEnd(String sessionId, String reason, int usedSteps,
                           boolean degraded, java.util.Set<String> degradeCauses) {}
}
