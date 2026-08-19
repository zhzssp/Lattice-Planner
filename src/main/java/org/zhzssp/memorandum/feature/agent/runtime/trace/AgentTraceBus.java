package org.zhzssp.memorandum.feature.agent.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 轨迹事件分发器：把事件安全地广播给所有 {@link AgentTraceListener}。
 *
 * <p>存在的唯一理由是<strong>异常隔离</strong>——观测代码绝不能影响主链路。
 * 任何监听器抛异常都在这里被吞掉并降级为 debug 日志，
 * 这样 {@code AgentOrchestrator} 里的埋点调用可以写得很干净，
 * 不需要每处都套 try-catch。
 *
 * <p>Spring 注入所有 {@link AgentTraceListener} 实现；无实现时为空列表，
 * 此时所有 emit 调用都是几乎零开销的空循环。
 */
@Component
public class AgentTraceBus {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceBus.class);

    private final List<AgentTraceListener> listeners;

    public AgentTraceBus(List<AgentTraceListener> listeners) {
        this.listeners = listeners == null ? List.of() : listeners;
        log.info("[AgentTrace] 已装载 {} 个轨迹监听器", this.listeners.size());
    }

    private void emit(Consumer<AgentTraceListener> action) {
        for (AgentTraceListener l : listeners) {
            try {
                action.accept(l);
            } catch (Exception e) {
                log.debug("[AgentTrace] 监听器 {} 处理事件异常（已忽略）：{}",
                        l.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public void turnStart(String sid, String input, String mode) {
        emit(l -> l.onTurnStart(sid, input, mode));
    }

    public void llmCall(String sid, int step, String prefixHash) {
        emit(l -> l.onLlmCall(sid, step, prefixHash));
    }

    public void toolCall(String sid, int step, String tool, JsonNode args) {
        emit(l -> l.onToolCall(sid, step, tool, args));
    }

    public void toolResult(String sid, int step, String tool,
                           String resultJson, boolean isError, long elapsedMs) {
        emit(l -> l.onToolResult(sid, step, tool, resultJson, isError, elapsedMs));
    }

    public void unknownTool(String sid, int step, String tool) {
        emit(l -> l.onUnknownTool(sid, step, tool));
    }

    public void confirmDecision(String sid, int step, String tool, boolean approved) {
        emit(l -> l.onConfirmDecision(sid, step, tool, approved));
    }

    public void finalAnswer(String sid, int usedSteps, String answer) {
        emit(l -> l.onFinalAnswer(sid, usedSteps, answer));
    }

    public void stepsExhausted(String sid, int maxSteps) {
        emit(l -> l.onStepsExhausted(sid, maxSteps));
    }

    public void llmFailure(String sid, int step, String message) {
        emit(l -> l.onLlmFailure(sid, step, message));
    }

    public void argumentsRejected(String sid, int step, String tool, List<String> params) {
        emit(l -> l.onArgumentsRejected(sid, step, tool, params));
    }

    public void repairAttempt(String sid, int step, String tool, int priorFailures) {
        emit(l -> l.onRepairAttempt(sid, step, tool, priorFailures));
    }

    public void repairOutcome(String sid, int step, String tool, boolean success) {
        emit(l -> l.onRepairOutcome(sid, step, tool, success));
    }

    public void strategyHint(String sid, int step, String tool, String failureMode, int failCount) {
        emit(l -> l.onStrategyHint(sid, step, tool, failureMode, failCount));
    }

    public void toolBanned(String sid, int step, String tool, String failureMode) {
        emit(l -> l.onToolBanned(sid, step, tool, failureMode));
    }

    public void turnEnd(String sid, String reason, int usedSteps,
                        boolean degraded, java.util.Set<String> degradeCauses) {
        emit(l -> l.onTurnEnd(sid, reason, usedSteps, degraded, degradeCauses));
    }
}
