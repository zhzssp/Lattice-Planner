package org.zhzssp.memorandum.feature.agent.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生产环境的轨迹指标收集器（ReAct 层可观测）。
 *
 * <p>此前可观测只覆盖了 RAG serving / CRAG / 前缀缓存，
 * <strong>ReAct 循环自身是黑盒</strong>——不知道平均几步收敛、哪些工具常失败、
 * 模型多久编一次不存在的工具名。本类补齐这一层。
 *
 * <p>暴露在 {@code GET /api/agent/trace/stats}。
 */
@Component
public class AgentTraceMetrics implements AgentTraceListener {

    private final AtomicLong turns = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolErrors = new AtomicLong();
    private final AtomicLong unknownTools = new AtomicLong();
    private final AtomicLong finalAnswers = new AtomicLong();
    private final AtomicLong stepsExhausted = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();
    private final AtomicLong confirmApproved = new AtomicLong();
    private final AtomicLong confirmRejected = new AtomicLong();

    /** 成功收敛的总步数，用于算平均步数 */
    private final AtomicLong totalUsedSteps = new AtomicLong();
    /** 步数直方图：步数 → 次数 */
    private final Map<Integer, AtomicLong> stepHistogram = new ConcurrentHashMap<>();

    /** 工具级统计：toolName → [调用次数, 失败次数, 累计耗时ms] */
    private final Map<String, ToolStat> perTool = new ConcurrentHashMap<>();

    /* ---- 方案 E：参数前置校验 ---- */
    private final AtomicLong argsRejected = new AtomicLong();
    /** 被拒参数名 → 次数，用于定位「哪个参数的描述写得不好」 */
    private final Map<String, AtomicLong> rejectedParams = new ConcurrentHashMap<>();

    /* ---- 方案 D：显式 Reflexion ---- */
    private final AtomicLong repairAttempts = new AtomicLong();
    private final AtomicLong repairSuccesses = new AtomicLong();
    private final AtomicLong strategyHints = new AtomicLong();
    private final AtomicLong bannedBlocks = new AtomicLong();
    /** 失败模式 → 次数，反映 Agent 最常撞哪种墙 */
    private final Map<String, AtomicLong> failureModes = new ConcurrentHashMap<>();

    /* ---- 方案 L：轮次收尾归因 ---- */
    /** 结束原因 → 次数 */
    private final Map<String, AtomicLong> turnEndReasons = new ConcurrentHashMap<>();
    /** 降级轮次计数（粘性标记置位的轮次） */
    private final AtomicLong degradedTurns = new AtomicLong();
    /** 降级原因 → 次数 */
    private final Map<String, AtomicLong> degradeCauses = new ConcurrentHashMap<>();
    /* ---- 方案 L（L3）：收尾顾问 steer ---- */
    /** steer 注入次数 */
    private final AtomicLong steerInjected = new AtomicLong();
    /** 顾问名 → steer 次数 */
    private final Map<String, AtomicLong> steerByAdvisor = new ConcurrentHashMap<>();

    /* ---- 方案 K（K3）：工具可见性拦截 ---- */
    /** 不可见工具被拦截的次数（越界，区别于幻觉） */
    private final AtomicLong notVisibleBlocked = new AtomicLong();
    /** 工具名 → 越界次数 */
    private final Map<String, AtomicLong> notVisibleByTool = new ConcurrentHashMap<>();

    @Override
    public void onTurnStart(String sessionId, String userInput, String mode) {
        turns.incrementAndGet();
    }

    @Override
    public void onLlmCall(String sessionId, int step, String prefixHash) {
        llmCalls.incrementAndGet();
    }

    @Override
    public void onToolCall(String sessionId, int step, String tool, JsonNode arguments) {
        toolCalls.incrementAndGet();
    }

    @Override
    public void onToolResult(String sessionId, int step, String tool,
                             String resultJson, boolean isError, long elapsedMs) {
        ToolStat s = perTool.computeIfAbsent(tool, k -> new ToolStat());
        s.invocations.incrementAndGet();
        s.totalMs.addAndGet(Math.max(0, elapsedMs));
        if (isError) {
            s.failures.incrementAndGet();
            toolErrors.incrementAndGet();
        }
    }

    @Override
    public void onUnknownTool(String sessionId, int step, String tool) {
        unknownTools.incrementAndGet();
    }

    @Override
    public void onConfirmDecision(String sessionId, int step, String tool, boolean approved) {
        (approved ? confirmApproved : confirmRejected).incrementAndGet();
    }

    @Override
    public void onFinalAnswer(String sessionId, int usedSteps, String answer) {
        finalAnswers.incrementAndGet();
        totalUsedSteps.addAndGet(usedSteps);
        stepHistogram.computeIfAbsent(usedSteps, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void onStepsExhausted(String sessionId, int maxSteps) {
        stepsExhausted.incrementAndGet();
    }

    @Override
    public void onLlmFailure(String sessionId, int step, String message) {
        llmFailures.incrementAndGet();
    }

    @Override
    public void onArgumentsRejected(String sessionId, int step, String tool, java.util.List<String> params) {
        argsRejected.incrementAndGet();
        if (params != null) {
            for (String p : params) {
                rejectedParams.computeIfAbsent(tool + "." + p, k -> new AtomicLong()).incrementAndGet();
            }
        }
    }

    @Override
    public void onRepairAttempt(String sessionId, int step, String tool, int priorFailures) {
        repairAttempts.incrementAndGet();
    }

    @Override
    public void onRepairOutcome(String sessionId, int step, String tool, boolean success) {
        if (success) repairSuccesses.incrementAndGet();
    }

    @Override
    public void onStrategyHint(String sessionId, int step, String tool, String failureMode, int failCount) {
        strategyHints.incrementAndGet();
        if (failureMode != null) {
            failureModes.computeIfAbsent(failureMode, k -> new AtomicLong()).incrementAndGet();
        }
    }

    @Override
    public void onToolBanned(String sessionId, int step, String tool, String failureMode) {
        bannedBlocks.incrementAndGet();
    }

    @Override
    public void onTurnEnd(String sessionId, String reason, int usedSteps,
                          boolean degraded, java.util.Set<String> causes) {
        if (reason != null) {
            turnEndReasons.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        }
        if (degraded) {
            degradedTurns.incrementAndGet();
            if (causes != null) {
                for (String c : causes) {
                    degradeCauses.computeIfAbsent(c, k -> new AtomicLong()).incrementAndGet();
                }
            }
        }
    }

    @Override
    public void onTurnSteered(String sessionId, int step, String advisorName, int steerCount) {
        steerInjected.incrementAndGet();
        if (advisorName != null) {
            steerByAdvisor.computeIfAbsent(advisorName, k -> new AtomicLong()).incrementAndGet();
        }
    }

    @Override
    public void onToolNotVisible(String sessionId, int step, String tool, String reason) {
        notVisibleBlocked.incrementAndGet();
        if (tool != null) {
            notVisibleByTool.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /* ---- 派生指标 ---- */

    /**
     * 自修复成功率：失败后重试并成功的比例。
     *
     * <p>这是评估「回灌给 LLM 的错误信息质量」的核心指标——
     * 错误信息越可操作，这个数字越高。方案 E（参数前置校验）的效果
     * 就体现在这里：开关前后对比即可量化。
     */
    public double selfRepairRate() {
        long n = repairAttempts.get();
        return n == 0 ? 0.0 : (double) repairSuccesses.get() / n;
    }

    /** 收敛率：正常给出答复的轮次占比（相对于 步数耗尽 + LLM 失败）。 */
    public double convergenceRate() {
        long total = finalAnswers.get() + stepsExhausted.get() + llmFailures.get();
        return total == 0 ? 0.0 : (double) finalAnswers.get() / total;
    }

    /**
     * 干净收敛率（方案 L）：排除「丢过信息但最后蒙对」的降级轮次。
     *
     * <p>与 {@link #convergenceRate()} 的差值即为「被高估的部分」——
     * 这是衡量截断/降级对实际体验影响的关键数字。</p>
     */
    public double cleanConvergenceRate() {
        long total = finalAnswers.get() + stepsExhausted.get() + llmFailures.get();
        if (total == 0) return 0.0;
        long clean = finalAnswers.get() - degradedTurns.get();
        return (double) Math.max(0, clean) / total;
    }

    /** 平均收敛步数（仅统计成功收敛的轮次）。 */
    public double avgStepsToConverge() {
        long n = finalAnswers.get();
        return n == 0 ? 0.0 : (double) totalUsedSteps.get() / n;
    }

    /** 工具失败率。 */
    public double toolErrorRate() {
        long total = toolCalls.get();
        return total == 0 ? 0.0 : (double) toolErrors.get() / total;
    }

    /**
     * 工具幻觉率：调用不存在工具的次数 / 总工具调用次数。
     * 这个指标越低越好，能反映工具描述是否清晰、schema 是否易懂。
     */
    public double hallucinationRate() {
        long total = toolCalls.get() + unknownTools.get();
        return total == 0 ? 0.0 : (double) unknownTools.get() / total;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turns", turns.get());
        m.put("llmCalls", llmCalls.get());
        m.put("avgLlmCallsPerTurn", turns.get() == 0 ? 0.0
                : round2((double) llmCalls.get() / turns.get()));

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("finalAnswers", finalAnswers.get());
        outcome.put("stepsExhausted", stepsExhausted.get());
        outcome.put("llmFailures", llmFailures.get());
        outcome.put("convergenceRate", round4(convergenceRate()));
        outcome.put("avgStepsToConverge", round2(avgStepsToConverge()));
        m.put("outcome", outcome);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("calls", toolCalls.get());
        tools.put("errors", toolErrors.get());
        tools.put("errorRate", round4(toolErrorRate()));
        tools.put("unknownTools", unknownTools.get());
        tools.put("hallucinationRate", round4(hallucinationRate()));
        m.put("tools", tools);

        // K：工具可见性拦截（越界，区别于幻觉）
        Map<String, Object> visibility = new LinkedHashMap<>();
        visibility.put("notVisibleBlocked", notVisibleBlocked.get());
        Map<String, Long> nvByTool = new java.util.LinkedHashMap<>();
        notVisibleByTool.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> nvByTool.put(e.getKey(), e.getValue().get()));
        visibility.put("notVisibleByTool", nvByTool);
        m.put("visibility", visibility);

        // E：参数前置校验
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("argumentsRejected", argsRejected.get());
        validation.put("rejectionRate", round4(
                toolCalls.get() == 0 ? 0.0 : (double) argsRejected.get() / toolCalls.get()));
        Map<String, Long> topParams = new java.util.LinkedHashMap<>();
        rejectedParams.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> topParams.put(e.getKey(), e.getValue().get()));
        validation.put("topRejectedParams", topParams);
        m.put("argValidation", validation);

        // D：显式 Reflexion
        Map<String, Object> reflexion = new LinkedHashMap<>();
        reflexion.put("repairAttempts", repairAttempts.get());
        reflexion.put("repairSuccesses", repairSuccesses.get());
        reflexion.put("selfRepairRate", round4(selfRepairRate()));
        reflexion.put("strategyHintsInjected", strategyHints.get());
        reflexion.put("bannedToolCallsBlocked", bannedBlocks.get());
        Map<String, Long> modes = new java.util.TreeMap<>();
        failureModes.forEach((k, v) -> modes.put(k, v.get()));
        reflexion.put("failureModes", modes);
        m.put("reflexion", reflexion);

        // L：轮次收尾归因
        Map<String, Object> turnEnd = new LinkedHashMap<>();
        Map<String, Long> reasons = new java.util.TreeMap<>();
        turnEndReasons.forEach((k, v) -> reasons.put(k, v.get()));
        turnEnd.put("reasons", reasons);
        turnEnd.put("degradedTurns", degradedTurns.get());
        turnEnd.put("degradeRate", round4(
                finalAnswers.get() == 0 ? 0.0
                        : (double) degradedTurns.get() / finalAnswers.get()));
        Map<String, Long> causes = new java.util.TreeMap<>();
        degradeCauses.forEach((k, v) -> causes.put(k, v.get()));
        turnEnd.put("degradeCauses", causes);
        turnEnd.put("cleanConvergenceRate", round4(cleanConvergenceRate()));
        turnEnd.put("steerInjected", steerInjected.get());
        Map<String, Long> steerAdv = new java.util.TreeMap<>();
        steerByAdvisor.forEach((k, v) -> steerAdv.put(k, v.get()));
        turnEnd.put("steerByAdvisor", steerAdv);
        m.put("turnEnd", turnEnd);

        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("approved", confirmApproved.get());
        confirm.put("rejected", confirmRejected.get());
        m.put("confirm", confirm);

        Map<Integer, Long> hist = new java.util.TreeMap<>();
        stepHistogram.forEach((k, v) -> hist.put(k, v.get()));
        m.put("stepHistogram", hist);

        Map<String, Object> byTool = new LinkedHashMap<>();
        perTool.forEach((name, s) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            long inv = s.invocations.get();
            t.put("invocations", inv);
            t.put("failures", s.failures.get());
            t.put("errorRate", inv == 0 ? 0.0 : round4((double) s.failures.get() / inv));
            t.put("avgMs", inv == 0 ? 0.0 : round2((double) s.totalMs.get() / inv));
            byTool.put(name, t);
        });
        m.put("byTool", byTool);
        return m;
    }

    private static final class ToolStat {
        final AtomicLong invocations = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong totalMs = new AtomicLong();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
