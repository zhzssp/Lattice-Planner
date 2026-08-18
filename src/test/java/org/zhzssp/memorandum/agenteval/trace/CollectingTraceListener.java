package org.zhzssp.memorandum.agenteval.trace;

import com.fasterxml.jackson.databind.JsonNode;
import org.zhzssp.memorandum.feature.agent.runtime.trace.AgentTraceListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 评测用的轨迹收集器：把 {@link AgentTraceListener} 事件流收集成结构化轨迹。
 *
 * <p>线程安全：子代理运行在独立线程，事件可能并发到达，因此用同步集合。
 */
public class CollectingTraceListener implements AgentTraceListener {

    /** 轨迹事件类型 */
    public enum EventType {
        TURN_START, LLM_CALL, TOOL_CALL, TOOL_RESULT,
        UNKNOWN_TOOL, CONFIRM, FINAL_ANSWER, STEPS_EXHAUSTED, LLM_FAILURE
    }

    /**
     * 单条轨迹事件。
     *
     * @param type      事件类型
     * @param step      ReAct 步序号（无步概念的事件为 -1）
     * @param tool      涉及的工具名（无则 null）
     * @param payload   载荷：工具参数 JSON / 结果 JSON / 最终答复文本
     * @param isError   工具是否失败
     * @param elapsedMs 工具耗时
     */
    public record TraceEvent(
            EventType type,
            int step,
            String tool,
            String payload,
            boolean isError,
            long elapsedMs
    ) {}

    private final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
    private volatile String userInput;
    private volatile String mode;
    private volatile int usedSteps = -1;
    private volatile String finalAnswer;
    private volatile boolean exhausted;
    private volatile String llmFailure;

    public void reset() {
        events.clear();
        userInput = null;
        mode = null;
        usedSteps = -1;
        finalAnswer = null;
        exhausted = false;
        llmFailure = null;
    }

    /* ---- AgentTraceListener 实现 ---- */

    @Override
    public void onTurnStart(String sessionId, String userInput, String mode) {
        this.userInput = userInput;
        this.mode = mode;
        events.add(new TraceEvent(EventType.TURN_START, -1, null, userInput, false, 0));
    }

    @Override
    public void onLlmCall(String sessionId, int step, String prefixHash) {
        events.add(new TraceEvent(EventType.LLM_CALL, step, null, prefixHash, false, 0));
    }

    @Override
    public void onToolCall(String sessionId, int step, String tool, JsonNode arguments) {
        events.add(new TraceEvent(EventType.TOOL_CALL, step, tool,
                arguments == null ? null : arguments.toString(), false, 0));
    }

    @Override
    public void onToolResult(String sessionId, int step, String tool,
                            String resultJson, boolean isError, long elapsedMs) {
        events.add(new TraceEvent(EventType.TOOL_RESULT, step, tool, resultJson, isError, elapsedMs));
    }

    @Override
    public void onUnknownTool(String sessionId, int step, String tool) {
        events.add(new TraceEvent(EventType.UNKNOWN_TOOL, step, tool, null, true, 0));
    }

    @Override
    public void onConfirmDecision(String sessionId, int step, String tool, boolean approved) {
        events.add(new TraceEvent(EventType.CONFIRM, step, tool,
                String.valueOf(approved), !approved, 0));
    }

    @Override
    public void onFinalAnswer(String sessionId, int usedSteps, String answer) {
        this.usedSteps = usedSteps;
        this.finalAnswer = answer;
        events.add(new TraceEvent(EventType.FINAL_ANSWER, usedSteps, null, answer, false, 0));
    }

    @Override
    public void onStepsExhausted(String sessionId, int maxSteps) {
        this.exhausted = true;
        events.add(new TraceEvent(EventType.STEPS_EXHAUSTED, maxSteps, null, null, true, 0));
    }

    @Override
    public void onLlmFailure(String sessionId, int step, String message) {
        this.llmFailure = message;
        events.add(new TraceEvent(EventType.LLM_FAILURE, step, null, message, true, 0));
    }

    /* ---- 查询 ---- */

    public List<TraceEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** 实际被执行的工具名序列（按调用顺序，含重复）。 */
    public List<String> toolSequence() {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.type() == EventType.TOOL_CALL)
                    .map(TraceEvent::tool)
                    .toList();
        }
    }

    /** 被判定为幻觉的工具名（LLM 编造的不存在工具）。 */
    public List<String> hallucinatedTools() {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.type() == EventType.UNKNOWN_TOOL)
                    .map(TraceEvent::tool)
                    .toList();
        }
    }

    /** 执行失败的工具名。 */
    public List<String> failedTools() {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.type() == EventType.TOOL_RESULT && e.isError())
                    .map(TraceEvent::tool)
                    .toList();
        }
    }

    /** 某工具的全部返回结果 JSON。 */
    public List<String> resultsOf(String tool) {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.type() == EventType.TOOL_RESULT && tool.equals(e.tool()))
                    .map(TraceEvent::payload)
                    .toList();
        }
    }

    /** LLM 调用次数。 */
    public long llmCallCount() {
        synchronized (events) {
            return events.stream().filter(e -> e.type() == EventType.LLM_CALL).count();
        }
    }

    public String userInput() { return userInput; }
    public String mode() { return mode; }
    public int usedSteps() { return usedSteps; }
    public String finalAnswer() { return finalAnswer; }
    public boolean isExhausted() { return exhausted; }
    public String llmFailure() { return llmFailure; }

    /** 是否正常收敛（给出终态答复，而非步数耗尽或 LLM 失败）。 */
    public boolean converged() {
        return finalAnswer != null && !exhausted && llmFailure == null;
    }

    /** 人类可读的轨迹渲染，断言失败时输出便于定位。 */
    public String render() {
        StringBuilder sb = new StringBuilder("Agent 轨迹:\n");
        synchronized (events) {
            for (TraceEvent e : events) {
                sb.append(String.format("  [step %2d] %-16s", e.step(), e.type()));
                if (e.tool() != null) sb.append(" tool=").append(e.tool());
                if (e.isError()) sb.append(" (ERROR)");
                if (e.payload() != null) {
                    String p = e.payload().replaceAll("\\s+", " ");
                    sb.append(" payload=").append(p.length() > 160 ? p.substring(0, 160) + "…" : p);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
