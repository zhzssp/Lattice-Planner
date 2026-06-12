package org.zhzssp.memorandum.feature.agent.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zhzssp.memorandum.entity.User;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP SSE 端点 + JSON-RPC 2.0 消息分发。
 *
 * SSE 流程：
 * 1. Client → GET /sse  → 建立 SseEmitter
 * 2. Server → 发送 endpoint 事件（POST URL）
 * 3. Client → POST /mcp/message?sid=xxx  → 发送 JSON-RPC 请求
 * 4. Server → 通过 SseEmitter 推送 JSON-RPC 响应
 */
@Component
public class McpSseEndpoint {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpSseEndpoint.class);

    private final McpAuthService authService;
    private final McpToolAdapter toolAdapter;
    private final McpResourceAdapter resourceAdapter;
    private final McpLocalFileService localFileService;

    @Value("${mcp.server.sse-path:/sse}")
    private String ssePath;

    @Value("${mcp.server.message-path:/mcp/message}")
    private String messagePath;

    @Value("${mcp.server.enabled:true}")
    private boolean enabled;

    /** sessionId → SseEmitter + McpSessionCtx */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, McpSessionCtx> sessions = new ConcurrentHashMap<>();

    public McpSseEndpoint(McpAuthService authService,
                          McpToolAdapter toolAdapter,
                          McpResourceAdapter resourceAdapter,
                          @Lazy McpLocalFileService localFileService) {
        this.authService = authService;
        this.toolAdapter = toolAdapter;
        this.resourceAdapter = resourceAdapter;
        this.localFileService = localFileService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 建立 SSE 连接（GET /sse?token=xxx）。 */
    public SseEmitter connect(String token) {
        User user = authService.authenticate(token);
        McpSessionCtx ctx = new McpSessionCtx(user);
        String sid = ctx.getSessionId();

        SseEmitter emitter = new SseEmitter(0L); // 无超时
        emitters.put(sid, emitter);
        sessions.put(sid, ctx);

        emitter.onCompletion(() -> { emitters.remove(sid); sessions.remove(sid); });
        emitter.onTimeout(() -> { emitters.remove(sid); sessions.remove(sid); });
        emitter.onError(e -> { emitters.remove(sid); sessions.remove(sid); });

        // 发送 endpoint 事件，告知 Client POST 地址
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data(messagePath + "?sid=" + sid));
        } catch (IOException e) {
            log.warn("[MCP] 发送 endpoint 事件失败", e);
        }

        log.info("[MCP] SSE 连接建立：sid={}, user={}", sid, user.getUsername());
        return emitter;
    }

    /** 处理 JSON-RPC 请求（POST /mcp/message?sid=xxx）。 */
    public Map<String, Object> handleMessage(String sid, Map<String, Object> request) {
        McpSessionCtx ctx = sessions.get(sid);
        if (ctx == null) {
            return errorResponse(extractId(request), -32001, "无效的会话 ID");
        }

        String method = (String) request.get("method");
        Object id = request.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        try {
            return switch (method) {
                case "initialize" -> handleInitialize(ctx, id);
                case "ping" -> Map.of("jsonrpc", "2.0", "id", id, "result", Map.of());
                case "tools/list" -> handleToolsList(ctx, id);
                case "tools/call" -> handleToolsCall(ctx, id, params);
                case "resources/list" -> handleResourcesList(ctx, id);
                case "resources/read" -> handleResourcesRead(ctx, id, params);
                default -> errorResponse(id, -32601, "未知方法：" + method);
            };
        } catch (Exception e) {
            return errorResponse(id, -32603, "内部错误：" + e.getMessage());
        }
    }

    /* ---- JSON-RPC Handlers ---- */

    private Map<String, Object> handleInitialize(McpSessionCtx ctx, Object id) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(
                                "tools", Map.of(),
                                "resources", Map.of()
                        ),
                        "serverInfo", Map.of(
                                "name", "lattice-planner",
                                "version", "1.0.0"
                        )
                )
        );
    }

    private Map<String, Object> handleToolsList(McpSessionCtx ctx, Object id) {
        return ctx.withContext(() -> {
            List<Map<String, Object>> tools = toolAdapter.exportMcpTools();
            // S4: 本地文件工具（仅当 mcp.server.local-files-enabled=true 时追加）
            if (localFileService.isEnabled()) {
                tools = new ArrayList<>(tools);
                tools.addAll(localFileService.exportLocalTools());
            }
            return Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("tools", tools)
            );
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(McpSessionCtx ctx, Object id, Map<String, Object> params) {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        return ctx.withContext(() -> {
            // S4: 本地文件工具路由
            if (localFileService.isEnabled() && localFileService.isLocalTool(toolName)) {
                Map<String, Object> result = localFileService.callTool(toolName, arguments);
                return Map.of("jsonrpc", "2.0", "id", id, "result", result);
            }
            Map<String, Object> result = toolAdapter.callTool(toolName, arguments);
            return Map.of("jsonrpc", "2.0", "id", id, "result", result);
        });
    }

    private Map<String, Object> handleResourcesList(McpSessionCtx ctx, Object id) {
        return ctx.withContext(() -> {
            List<Map<String, Object>> resources = resourceAdapter.listResources();
            return Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("resources", resources)
            );
        });
    }

    private Map<String, Object> handleResourcesRead(McpSessionCtx ctx, Object id, Map<String, Object> params) throws Exception {
        String uri = (String) params.get("uri");
        Map<String, Object> result = ctx.withContextThrowing(() -> resourceAdapter.readResource(uri));
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    /* ---- helpers ---- */

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of("code", code, "message", message)
        );
    }

    private Object extractId(Map<String, Object> request) {
        return request.getOrDefault("id", null);
    }

    /** 推送 SSE 事件到客户端。 */
    public void pushEvent(String sid, Map<String, Object> data) {
        SseEmitter emitter = emitters.get(sid);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("message").data(data));
            } catch (IOException e) {
                log.warn("[MCP] 推送 SSE 事件失败：sid={}", sid);
            }
        }
    }
}
