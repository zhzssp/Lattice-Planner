package org.zhzssp.memorandum.feature.agent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.policy.ToolConfirmCoordinator;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.runtime.AgentOrchestrator;
import org.zhzssp.memorandum.feature.agent.runtime.LongTermMemoryService;
import org.zhzssp.memorandum.feature.agent.tool.LocalBridgeProxy;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 对话 WebSocket 端点：/ws/agent/{sessionId}
 *
 * 协议（JSON 文本帧）：
 *  C->S: {"msgType":"chat","text":"...","mode":"chat|plan|reflect"}
 *  C->S: {"msgType":"localResult","reqId":"...","result":{...}}
 *  C->S: {"msgType":"confirmReply","reqId":"...","approved":true}
 *  S->C: assistant / toolStart / toolResult / localCall / confirmReq / done / error
 */
@Component
public class AgentChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentChatWebSocketHandler.class);

    private final ObjectMapper om;
    private final UserRepository userRepository;
    private final AgentOrchestrator orchestrator;
    private final LocalBridgeProxy localBridgeProxy;
    private final ToolConfirmCoordinator confirmCoordinator;
    private final LongTermMemoryService longTermMemoryService;

    /** sessionId -> WebSocketSession */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public AgentChatWebSocketHandler(ObjectMapper om,
                                     UserRepository userRepository,
                                     @Lazy AgentOrchestrator orchestrator,
                                     LocalBridgeProxy localBridgeProxy,
                                     ToolConfirmCoordinator confirmCoordinator,
                                     LongTermMemoryService longTermMemoryService) {
        this.om = om;
        this.userRepository = userRepository;
        this.orchestrator = orchestrator;
        this.localBridgeProxy = localBridgeProxy;
        this.confirmCoordinator = confirmCoordinator;
        this.longTermMemoryService = longTermMemoryService;
    }

    private String sidOf(WebSocketSession s) {
        String path = s.getUri() == null ? "" : s.getUri().getPath();
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (session.getPrincipal() == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignore) {
            }
            return;
        }
        sessions.put(sidOf(session), session);
        log.info("[Agent] WS connected sid={} user={}", sidOf(session), session.getPrincipal().getName());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sid = sidOf(session);
        sessions.remove(sid);
        log.info("[Agent] WS closed sid={} status={}", sid, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = om.readTree(message.getPayload());
        String type = root.path("msgType").asText("");
        String sid = sidOf(session);

        Principal p = session.getPrincipal();
        if (p == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        switch (type) {
            case "chat" -> {
                User user = userRepository.findByUsername(p.getName()).orElse(null);
                if (user == null) {
                    sendError(sid, "用户不存在");
                    return;
                }
                String text = root.path("text").asText("");
                String mode = root.path("mode").asText("chat");
                Thread t = new Thread(() -> {
                    AgentContext.set(user, sid);
                    try {
                        String memo = longTermMemoryService.snippetFor(user);
                        orchestrator.handleUserTurn(sid, text, mode, memo);
                    } catch (Exception ex) {
                        log.error("[Agent] turn error", ex);
                        sendError(sid, "Agent 处理异常：" + ex.getMessage());
                    } finally {
                        AgentContext.clear();
                    }
                }, "agent-" + sid);
                t.setDaemon(true);
                t.start();
            }
            case "localResult" -> localBridgeProxy.onLocalResult(
                    root.path("reqId").asText(),
                    root.path("result"));
            case "confirmReply" -> confirmCoordinator.onReply(
                    root.path("reqId").asText(),
                    root.path("approved").asBoolean(false));
            default -> sendError(sid, "未知 msgType：" + type);
        }
    }

    /* -------- 服务端发送侧 -------- */

    public void sendAssistant(String sid, String text) {
        send(sid, Map.of("msgType", "assistant", "text", text == null ? "" : text));
    }

    public void sendToolStart(String sid, String callId, String tool, Object args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msgType", "toolStart");
        m.put("callId", callId);
        m.put("tool", tool);
        m.put("args", args);
        send(sid, m);
    }

    public void sendToolResult(String sid, String callId, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msgType", "toolResult");
        m.put("callId", callId);
        m.put("result", result);
        send(sid, m);
    }

    public void sendLocalCall(String sid, String reqId, String tool, Object args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msgType", "localCall");
        m.put("reqId", reqId);
        m.put("tool", tool);
        m.put("args", args);
        send(sid, m);
    }

    public void sendConfirmReq(String sid, String reqId, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msgType", "confirmReq");
        m.put("reqId", reqId);
        m.put("summary", summary);
        send(sid, m);
    }

    public void sendDone(String sid) {
        send(sid, Map.of("msgType", "done"));
    }

    public void sendError(String sid, String msg) {
        send(sid, Map.of("msgType", "error", "message", msg == null ? "" : msg));
    }

    private void send(String sid, Object payload) {
        WebSocketSession s = sessions.get(sid);
        if (s == null || !s.isOpen()) return;
        synchronized (s) {
            try {
                s.sendMessage(new TextMessage(om.writeValueAsString(payload)));
            } catch (JsonProcessingException jpe) {
                log.warn("[Agent] payload JSON 序列化失败：{}", jpe.getMessage());
            } catch (Exception ex) {
                log.warn("[Agent] WS 发送失败 sid={} err={}", sid, ex.getMessage());
            }
        }
    }
}
