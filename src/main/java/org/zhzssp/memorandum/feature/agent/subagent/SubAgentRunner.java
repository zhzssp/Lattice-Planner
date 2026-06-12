package org.zhzssp.memorandum.feature.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.runtime.ToolCallParser;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 子代理内核：在<strong>独立的局部上下文</strong>里跑一段精简 ReAct 循环。
 *
 * <p>与主 {@code AgentOrchestrator} 的关键区别：</p>
 * <ul>
 *   <li>用局部 {@code List<Map>} 作为短期记忆，<strong>绝不</strong>写 {@code ConversationMemory(sid)}；</li>
 *   <li>工具集 = {@code registry.exportSchemas(role.toolTags())}，按角色最小化；</li>
 *   <li>只把最终 {@link SubAgentResult#finalText()} 回给主 Agent，素材全文 / 中间 JSON 全部丢弃；</li>
 *   <li>写工具确认仍走 {@link ToolConfirmCoordinator#askUser}（弹窗回到主用户 WS）；</li>
 *   <li>{@code AgentContext.enterSub()/exitSub()} 包裹，配合 depth 护栏防止递归。</li>
 * </ul>
 */
@Component
public class SubAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRunner.class);

    private final LlmGateway llm;
    private final ToolRegistry registry;
    private final ToolCallParser parser;
    private final ToolConfirmCoordinator confirm;
    private final ObjectMapper om;
    private final AgentChatWebSocketHandler ws;

    @Value("${agent.subagent.max-steps:6}")
    private int maxStepsCap;

    @Value("${agent.subagent.result-max-chars:4000}")
    private int resultMaxChars;

    public SubAgentRunner(LlmGateway llm,
                          ToolRegistry registry,
                          ToolCallParser parser,
                          ToolConfirmCoordinator confirm,
                          ObjectMapper om,
                          @Lazy AgentChatWebSocketHandler ws) {
        this.llm = llm;
        this.registry = registry;
        this.parser = parser;
        this.confirm = confirm;
        this.om = om;
        this.ws = ws;
    }

    /**
     * 运行一个子代理：发出 subagentStart/End 可视化事件并包裹核心 ReAct 循环。
     *
     * @param role        角色（决定 system prompt + 工具子集 + 步数预算）
     * @param instruction 主 Agent 委派的具体任务描述
     * @param parentSid   主会话 id，确认弹窗与可视化事件据此回到主用户 WS
     */
    public SubAgentResult run(SubAgentRole role, String instruction, String parentSid) {
        String subId = UUID.randomUUID().toString();
        emitStart(parentSid, subId, role, instruction);
        SubAgentResult result;
        AgentContext.enterSub();
        try {
            result = doRun(role, instruction);
        } catch (Exception e) {
            log.error("[SubAgent:{}] 运行异常", role, e);
            result = new SubAgentResult(role.name(), "子代理异常：" + e.getMessage(), 0, List.of(), true);
        } finally {
            AgentContext.exitSub();
        }
        emitEnd(parentSid, subId, role, result);
        return result;
    }

    /** 核心 ReAct 循环（depth 已由 {@link #run} 包裹处理）。 */
    private SubAgentResult doRun(SubAgentRole role, String instruction) {
        List<String> used = new ArrayList<>();
        int effectiveMaxSteps = Math.min(role.maxSteps(), Math.max(1, maxStepsCap));
        {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(msg("system", buildSystemPrompt(role)));
            msgs.add(msg("user", instruction == null ? "" : instruction));

            for (int step = 0; step < effectiveMaxSteps; step++) {
                String raw;
                try {
                    raw = llm.generateChat(msgs);
                } catch (Exception ex) {
                    log.warn("[SubAgent:{}] LLM 调用失败：{}", role, ex.getMessage());
                    return new SubAgentResult(role.name(),
                            "子代理 LLM 调用失败：" + ex.getMessage(), step, used, true);
                }

                ToolCallParser.ToolCall call = parser.parse(raw);
                if (call == null) {
                    // 终态：自然语言结论
                    String finalText = parser.cleanForDisplay(raw);
                    if (finalText.isBlank()) {
                        finalText = "（子代理未返回内容）";
                    }
                    return new SubAgentResult(role.name(),
                            truncate(finalText, resultMaxChars), step, used, false);
                }

                used.add(call.name());
                msgs.add(msg("assistant", raw));

                ToolDefinition def = registry.get(call.name());
                if (def == null) {
                    feed(msgs, call.name(),
                            "{\"error\":\"UNKNOWN_TOOL\",\"tool\":\"" + call.name() + "\"}");
                    continue;
                }

                if (def.requiresConfirm()) {
                    boolean ok;
                    try {
                        // 确认弹窗回到主用户 WS：worker 线程的 sessionId 已被设为 parentSid
                        ok = confirm.askUser(AgentContext.sessionId(), UUID.randomUUID().toString(),
                                call.name(), call.arguments()).get();
                    } catch (Exception ex) {
                        ok = false;
                    }
                    if (!ok) {
                        feed(msgs, call.name(), "{\"status\":\"USER_REJECTED\"}");
                        continue;
                    }
                }

                feed(msgs, call.name(), safeInvoke(call));
            }

            return new SubAgentResult(role.name(),
                    "（子代理已达最大步数 " + effectiveMaxSteps + "，返回阶段性结论：已执行工具 " + used + "）",
                    effectiveMaxSteps, used, true);
        }
    }

    /* ---------------- 可视化事件 ---------------- */

    private void emitStart(String parentSid, String subId, SubAgentRole role, String instruction) {
        if (ws == null || parentSid == null) return;
        try {
            ws.sendSubAgentStart(parentSid, subId, role.name(), role.label(),
                    truncate(instruction, 500));
        } catch (Exception ex) {
            log.debug("[SubAgent:{}] 发送 start 事件失败：{}", role, ex.getMessage());
        }
    }

    private void emitEnd(String parentSid, String subId, SubAgentRole role, SubAgentResult r) {
        if (ws == null || parentSid == null) return;
        try {
            ws.sendSubAgentEnd(parentSid, subId, role.name(), role.label(),
                    r.finalText(), r.steps(), r.toolsUsed(), r.truncated());
        } catch (Exception ex) {
            log.debug("[SubAgent:{}] 发送 end 事件失败：{}", role, ex.getMessage());
        }
    }

    /* ---------------- 内部工具 ---------------- */

    private String safeInvoke(ToolCallParser.ToolCall call) {
        try {
            Object result = registry.invoke(call.name(), call.arguments());
            return result == null ? "null" : om.writeValueAsString(result);
        } catch (Throwable t) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("error", t.getClass().getSimpleName());
            err.put("message", String.valueOf(t.getMessage()));
            try {
                return om.writeValueAsString(err);
            } catch (Exception ex) {
                return "{\"error\":\"" + t.getClass().getSimpleName() + "\"}";
            }
        }
    }

    /** 把工具结果作为 user 消息回灌局部上下文（Reflexion：错误 JSON 也喂回，让子代理自纠）。 */
    private void feed(List<Map<String, String>> msgs, String tool, String resultJson) {
        msgs.add(msg("user", "[tool_result " + tool + "]\n" + truncate(resultJson, resultMaxChars)));
    }

    private String buildSystemPrompt(SubAgentRole role) {
        String toolsJson;
        try {
            toolsJson = om.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(registry.exportSchemas(role.toolTags()));
        } catch (Exception ex) {
            toolsJson = "[]";
        }
        return """
                %s
                今天是 %s。你在独立上下文中工作，结果会被汇报给主 Agent。

                【可用工具】（必须使用工具完成读写操作，不要编造数据）
                %s

                【输出协议】（严格遵守）
                - 如需调用工具：仅输出一个 JSON 对象，形如
                  {"tool":"kb.semantic_search","arguments":{"query":"周报"}}
                  不要解释，不要 Markdown 围栏，不要附带其他文字。
                - 一次只能调用一个工具；看到工具结果后再决定下一步。
                - 不可编造任务/目标/笔记的 id，所有 id 必须来自工具返回的真实数据。
                - 当你已完成职责：直接输出最终中文结论（自然语言，不要再输出 JSON）。
                - 你不能委派其它子代理（subagent.* 工具对你不可见）。
                """.formatted(role.systemPrompt().strip(), LocalDate.now(), toolsJson);
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
