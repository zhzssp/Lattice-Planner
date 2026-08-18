package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.policy.ToolApprovalPolicy;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 主循环（精简 ReAct）：
 *  user 输入 -> [N 步] LLM -> (终态自然语言 OR 调用工具) -> 工具结果回灌 -> ...
 *
 * 工具失败：错误 JSON 喂回 LLM，等价于一次 Reflexion，让 LLM 自我纠偏。
 * 高危工具：先经 ToolConfirmCoordinator 走 UI 确认。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final LlmGateway llm;
    private final ToolRegistry registry;
    private final ToolCallParser parser;
    private final PromptBuilder promptBuilder;
    private final ConversationMemory memory;
    private final ToolConfirmCoordinator confirmCoordinator;
    private final ToolApprovalPolicy approvalPolicy;
    private final AgentChatWebSocketHandler ws;
    private final ObjectMapper om;
    private final org.zhzssp.memorandum.feature.agent.runtime.trace.AgentTraceBus trace;
    private final ReflexionAdvisor reflexionAdvisor;

    @Value("${agent.chat.max-steps:8}")
    private int maxSteps;

    public AgentOrchestrator(LlmGateway llm,
                             ToolRegistry registry,
                             ToolCallParser parser,
                             PromptBuilder promptBuilder,
                             ConversationMemory memory,
                             ToolConfirmCoordinator confirmCoordinator,
                             ToolApprovalPolicy approvalPolicy,
                             @Lazy AgentChatWebSocketHandler ws,
                             ObjectMapper om,
                             org.zhzssp.memorandum.feature.agent.runtime.trace.AgentTraceBus trace,
                             ReflexionAdvisor reflexionAdvisor) {
        this.llm = llm;
        this.registry = registry;
        this.parser = parser;
        this.promptBuilder = promptBuilder;
        this.memory = memory;
        this.confirmCoordinator = confirmCoordinator;
        this.approvalPolicy = approvalPolicy;
        this.ws = ws;
        this.om = om;
        this.trace = trace;
        this.reflexionAdvisor = reflexionAdvisor;
    }

    public void handleUserTurn(String sid, String userInput, String mode, String longTermMemo) {
        memory.append(sid, "user", userInput);
        trace.turnStart(sid, userInput, mode);
        // D：Reflexion 状态是单轮作用域的（新一轮用户输入可能已补齐信息，不能继承上轮封禁）
        ReflexionAdvisor.TurnState reflexion = reflexionAdvisor.newTurn();
        // P3：前缀在 turn 内构造一次，各 ReAct 步复用
        PrefixCache.CachedPrefix prefix;
        try {
            prefix = promptBuilder.buildPrefix(mode, longTermMemo);
        } catch (Exception ex) {
            log.warn("[Agent] 前缀构造失败：{}", ex.getMessage());
            ws.sendAssistant(sid, "系统初始化失败：" + ex.getMessage());
            ws.sendDone(sid);
            return;
        }
        try {
            for (int step = 0; step < maxSteps; step++) {
                var msgs = promptBuilder.assemble(prefix, memory.history(sid));
                String llmRaw;
                trace.llmCall(sid, step, prefix.prefixHash());
                try {
                    llmRaw = llm.generateChat(msgs);
                } catch (Exception ex) {
                    log.warn("[Agent] LLM 调用失败：{}", ex.getMessage());
                    trace.llmFailure(sid, step, ex.getMessage());
                    ws.sendAssistant(sid, "LLM 调用失败：" + ex.getMessage());
                    ws.sendDone(sid);
                    return;
                }

                ToolCallParser.ToolCall call = parser.parse(llmRaw);

                if (call == null) {
                    String finalAnswer = parser.cleanForDisplay(llmRaw);
                    if (finalAnswer.isBlank()) {
                        finalAnswer = "（模型未返回内容，请重试或换一个表述）";
                    }
                    memory.append(sid, "assistant", finalAnswer);
                    trace.finalAnswer(sid, step, finalAnswer);
                    ws.sendAssistant(sid, finalAnswer);
                    ws.sendDone(sid);
                    return;
                }

                ToolDefinition def = registry.get(call.name());
                String callId = UUID.randomUUID().toString();
                ws.sendToolStart(sid, callId, call.name(), call.arguments());

                if (def == null) {
                    String err = "{\"error\":\"UNKNOWN_TOOL\",\"tool\":\"" + call.name() + "\"}";
                    trace.unknownTool(sid, step, call.name());
                    // D：工具幻觉同样纳入失败计数——同一个不存在的名字被反复调用是真实的循环来源
                    String hint = advise(reflexion, sid, step, call.name(),
                            ReflexionAdvisor.FailureMode.UNKNOWN_TOOL);
                    ws.sendToolResult(sid, callId, err);
                    appendToolTrace(sid, call.name(), call.arguments(), err, hint);
                    continue;
                }

                trace.toolCall(sid, step, call.name(), call.arguments());

                // D · 执行层强制：已封禁的工具直接短路，不真正执行。
                // 只做提示层（"请勿再调用"）时模型完全可能无视，这一层才是真正的"强制换工具"。
                if (reflexion.isBanned(call.name())) {
                    String blocked = writeJson(reflexionAdvisor.bannedResult(reflexion, call.name()),
                            "{\"error\":\"" + ReflexionAdvisor.BANNED_ERROR + "\"}");
                    trace.toolBanned(sid, step, call.name(), reflexion.lastMode(call.name()).name());
                    log.info("[Agent] 阻断已封禁工具调用 tool={} sid={}", call.name(), sid);
                    ws.sendToolResult(sid, callId, blocked);
                    appendToolTrace(sid, call.name(), call.arguments(), blocked, null);
                    continue;
                }

                if (approvalPolicy.needsConfirm(AgentContext.requireUser(), def)) {
                    boolean ok;
                    try {
                        ok = confirmCoordinator.askUser(sid, callId, call.name(), call.arguments()).get();
                    } catch (Exception ex) {
                        ok = false;
                    }
                    trace.confirmDecision(sid, step, call.name(), ok);
                    if (!ok) {
                        String rej = "{\"status\":\"USER_REJECTED\"}";
                        // D：用户拒绝是不可重试的确定性结果，立即封禁避免反复弹窗骚扰用户
                        String hint = advise(reflexion, sid, step, call.name(),
                                ReflexionAdvisor.FailureMode.USER_REJECTED);
                        ws.sendToolResult(sid, callId, rej);
                        appendToolTrace(sid, call.name(), call.arguments(), rej, hint);
                        continue;
                    }
                }

                // E/D：本次调用是否为「失败后的自修复尝试」，用于统计自修复成功率
                boolean repairAttempt = reflexion.isRepairAttempt(call.name());
                if (repairAttempt) {
                    trace.repairAttempt(sid, step, call.name(), reflexion.failures(call.name()));
                }

                String resultJson;
                boolean isError = false;
                long t0 = System.currentTimeMillis();
                try {
                    Object result = registry.invoke(call.name(), call.arguments());
                    resultJson = result == null ? "null" : om.writeValueAsString(result);
                    isError = looksLikeError(resultJson);
                } catch (Throwable t) {
                    isError = true;
                    Map<String, String> err = new LinkedHashMap<>();
                    err.put("error", t.getClass().getSimpleName());
                    err.put("message", String.valueOf(t.getMessage()));
                    resultJson = writeJson(err,
                            "{\"error\":\"" + t.getClass().getSimpleName() + "\"}");
                    log.warn("[Agent] 工具执行失败 tool={} err={}", call.name(), t.getMessage());
                }
                trace.toolResult(sid, step, call.name(), resultJson, isError,
                        System.currentTimeMillis() - t0);

                String hint = null;
                if (isError) {
                    ReflexionAdvisor.FailureMode fm = reflexionAdvisor.classify(resultJson);
                    // E：参数校验拒绝单独埋点——工具根本没执行，无副作用，与业务失败性质不同
                    if (fm == ReflexionAdvisor.FailureMode.INVALID_ARGUMENTS) {
                        trace.argumentsRejected(sid, step, call.name(), rejectedParams(resultJson));
                    }
                    hint = advise(reflexion, sid, step, call.name(), fm);
                } else {
                    reflexionAdvisor.onSuccess(reflexion, call.name());
                }
                if (repairAttempt) {
                    trace.repairOutcome(sid, step, call.name(), !isError);
                }

                ws.sendToolResult(sid, callId, resultJson);
                appendToolTrace(sid, call.name(), call.arguments(), resultJson, hint);
            }
            trace.stepsExhausted(sid, maxSteps);
            ws.sendAssistant(sid, "（已达最大推理步数 " + maxSteps + "，请换种说法或拆分为更小的步骤）");
            ws.sendDone(sid);
        } catch (Exception ex) {
            log.error("[Agent] handleUserTurn 异常", ex);
            ws.sendError(sid, "Agent 处理异常：" + ex.getMessage());
            ws.sendDone(sid);
        }
    }

    /** 记录失败并生成策略提示，同时埋点。返回 null 表示本次不注入额外提示。 */
    private String advise(ReflexionAdvisor.TurnState st, String sid, int step,
                          String tool, ReflexionAdvisor.FailureMode mode) {
        if (mode == ReflexionAdvisor.FailureMode.NONE) return null;
        String hint = reflexionAdvisor.onFailure(st, tool, mode);
        if (hint != null) {
            trace.strategyHint(sid, step, tool, mode.name(), st.failures(tool));
        }
        return hint;
    }

    /** 从 INVALID_ARGUMENTS 错误里取出出问题的参数名，供指标定位「哪个参数最常被填错」。 */
    private java.util.List<String> rejectedParams(String resultJson) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode issues = om.readTree(resultJson).path("issues");
            if (issues.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode i : issues) {
                    String p = i.path("param").asText(null);
                    if (p != null && !p.isBlank()) names.add(p);
                }
            }
        } catch (Exception ignore) {
            // 埋点辅助信息，解析失败无所谓
        }
        return names;
    }

    /** 序列化，失败时退回给定的兜底 JSON 串。 */
    private String writeJson(Object value, String fallback) {
        try {
            return om.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    /**
     * 判断工具返回值是否表达了「失败」语义。
     *
     * <p>工具约定用 {@code {"error": "..."}} 表达业务失败（而非抛异常），
     * 因此仅靠 try-catch 无法统计真实失败率——这里做一次轻量识别，
     * 让 {@code toolErrorRate} 指标反映实际情况。</p>
     */
    private boolean looksLikeError(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(resultJson);
            if (!n.isObject()) {
                // kb.semantic_search 等返回数组：首元素可能是 CRAG _meta 行，不算失败
                return false;
            }
            if (n.has("error")) return true;
            com.fasterxml.jackson.databind.JsonNode isErr = n.path("isError");
            return isErr.isBoolean() && isErr.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把一次工具调用与结果回灌短期记忆。
     *
     * <p>{@code strategyHint} 是 D 注入的显式策略提示，<strong>只进 LLM 上下文，
     * 不进 WebSocket</strong>——它是给模型的内部引导，让用户在 UI 上看到
     * 「⛔ 禁止再次调用…」这类措辞只会造成困惑。</p>
     */
    private void appendToolTrace(String sid, String tool, Object args,
                                 String resultJson, String strategyHint) {
        try {
            Map<String, Object> assistantTurn = new LinkedHashMap<>();
            assistantTurn.put("tool", tool);
            assistantTurn.put("arguments", args);
            memory.append(sid, "assistant", om.writeValueAsString(assistantTurn));
        } catch (JsonProcessingException ignore) {
        }
        String body = "[tool_result " + tool + "]\n" + truncate(resultJson, 4000);
        if (strategyHint != null && !strategyHint.isBlank()) {
            // 提示放在结果之后：模型先看到发生了什么，再看到该怎么办
            body = body + "\n\n[策略提示]\n" + strategyHint;
        }
        memory.append(sid, "user", body);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
