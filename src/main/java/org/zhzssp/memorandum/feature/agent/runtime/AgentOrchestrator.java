package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
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
    private final AgentChatWebSocketHandler ws;
    private final ObjectMapper om;

    @Value("${agent.chat.max-steps:8}")
    private int maxSteps;

    public AgentOrchestrator(LlmGateway llm,
                             ToolRegistry registry,
                             ToolCallParser parser,
                             PromptBuilder promptBuilder,
                             ConversationMemory memory,
                             ToolConfirmCoordinator confirmCoordinator,
                             @Lazy AgentChatWebSocketHandler ws,
                             ObjectMapper om) {
        this.llm = llm;
        this.registry = registry;
        this.parser = parser;
        this.promptBuilder = promptBuilder;
        this.memory = memory;
        this.confirmCoordinator = confirmCoordinator;
        this.ws = ws;
        this.om = om;
    }

    public void handleUserTurn(String sid, String userInput, String mode, String longTermMemo) {
        memory.append(sid, "user", userInput);
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
                try {
                    llmRaw = llm.generateChat(msgs);
                } catch (Exception ex) {
                    log.warn("[Agent] LLM 调用失败：{}", ex.getMessage());
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
                    ws.sendAssistant(sid, finalAnswer);
                    ws.sendDone(sid);
                    return;
                }

                ToolDefinition def = registry.get(call.name());
                String callId = UUID.randomUUID().toString();
                ws.sendToolStart(sid, callId, call.name(), call.arguments());

                if (def == null) {
                    String err = "{\"error\":\"UNKNOWN_TOOL\",\"tool\":\"" + call.name() + "\"}";
                    ws.sendToolResult(sid, callId, err);
                    appendToolTrace(sid, call.name(), call.arguments(), err);
                    continue;
                }

                if (def.requiresConfirm()) {
                    boolean ok;
                    try {
                        ok = confirmCoordinator.askUser(sid, callId, call.name(), call.arguments()).get();
                    } catch (Exception ex) {
                        ok = false;
                    }
                    if (!ok) {
                        String rej = "{\"status\":\"USER_REJECTED\"}";
                        ws.sendToolResult(sid, callId, rej);
                        appendToolTrace(sid, call.name(), call.arguments(), rej);
                        continue;
                    }
                }

                String resultJson;
                try {
                    Object result = registry.invoke(call.name(), call.arguments());
                    resultJson = result == null ? "null" : om.writeValueAsString(result);
                } catch (Throwable t) {
                    Map<String, String> err = new LinkedHashMap<>();
                    err.put("error", t.getClass().getSimpleName());
                    err.put("message", String.valueOf(t.getMessage()));
                    try {
                        resultJson = om.writeValueAsString(err);
                    } catch (JsonProcessingException jpe) {
                        resultJson = "{\"error\":\"" + t.getClass().getSimpleName() + "\"}";
                    }
                    log.warn("[Agent] 工具执行失败 tool={} err={}", call.name(), t.getMessage());
                }
                ws.sendToolResult(sid, callId, resultJson);
                appendToolTrace(sid, call.name(), call.arguments(), resultJson);
            }
            ws.sendAssistant(sid, "（已达最大推理步数 " + maxSteps + "，请换种说法或拆分为更小的步骤）");
            ws.sendDone(sid);
        } catch (Exception ex) {
            log.error("[Agent] handleUserTurn 异常", ex);
            ws.sendError(sid, "Agent 处理异常：" + ex.getMessage());
            ws.sendDone(sid);
        }
    }

    private void appendToolTrace(String sid, String tool, Object args, String resultJson) {
        try {
            Map<String, Object> assistantTurn = new LinkedHashMap<>();
            assistantTurn.put("tool", tool);
            assistantTurn.put("arguments", args);
            memory.append(sid, "assistant", om.writeValueAsString(assistantTurn));
        } catch (JsonProcessingException ignore) {
        }
        memory.append(sid, "user", "[tool_result " + tool + "]\n" + truncate(resultJson, 4000));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
