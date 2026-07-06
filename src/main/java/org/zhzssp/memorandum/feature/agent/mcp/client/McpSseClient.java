package org.zhzssp.memorandum.feature.agent.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE 客户端：连接远程 MCP Server 的 SSE 端点。
 * 基于 Java 21 HttpClient 实现，与 McpSseEndpoint（Server 端）对称。
 *
 * SSE 流程：
 * 1. GET {sseUrl} → 建立 SSE 长连接
 * 2. 接收 endpoint 事件 → 获取 POST 地址
 * 3. POST {messageEndpoint} → 发送 initialize 请求
 * 4. POST {messageEndpoint} → 发送 tools/list 发现工具
 * 5. POST {messageEndpoint} → 代理 tools/call
 */
public class McpSseClient {

    private static final Logger log = LoggerFactory.getLogger(McpSseClient.class);

    private final String sseUrl;
    private final String authToken;
    private final int connectTimeout;
    private final int callTimeout;
    private final ObjectMapper om;

    private HttpClient httpClient;
    private String messageEndpoint;
    private String sessionId;
    private volatile boolean connected = false;

    /** JSON-RPC 请求 ID 自增器 */
    private final AtomicInteger requestId = new AtomicInteger(0);

    /** 等待中的 JSON-RPC 响应：id → CompletableFuture */
    private final Map<Integer, PendingResponse> pendingRequests = new ConcurrentHashMap<>();

    /** SSE 读取线程 */
    private Thread sseThread;

    /** 从 SSE URL 提取的 base URL（scheme + authority），用于将相对 messageEndpoint 解析为绝对 URL */
    private String baseUrl;

    private static final Pattern SID_PATTERN = Pattern.compile("[?&]sid=([^&]+)");

    public McpSseClient(String sseUrl, String authToken, int connectTimeout, int callTimeout, ObjectMapper om) {
        this.sseUrl = sseUrl;
        this.authToken = authToken;
        this.connectTimeout = connectTimeout;
        this.callTimeout = callTimeout;
        this.om = om;
    }

    /** 连接到远程 MCP Server（SSE + initialize 握手）。 */
    public synchronized void connect() throws Exception {
        if (connected) {
            log.warn("[MCP Client] 已连接，忽略重复 connect 调用");
            return;
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        // 提取 SSE base URL（scheme + authority），用于后续将相对 messageEndpoint 解析为绝对 URL
        this.baseUrl = extractBaseUrl(sseUrl);

        // 构建 SSE 连接 URL（含 token）
        String url = sseUrl;
        if (authToken != null && !authToken.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "token=" + authToken;
        }

        log.info("[MCP Client] 正在连接 SSE：{}", sseUrl);

        // GET SSE 流
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        // SSE 读取必须在单独线程，因为 HttpResponse.BodyHandlers.ofLines() 是阻塞的
        sseThread = new Thread(() -> {
            try {
                HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() != 200) {
                    log.error("[MCP Client] SSE 连接失败，HTTP {}", response.statusCode());
                    return;
                }
                parseSseStream(response.body());
            } catch (Exception e) {
                if (connected) {
                    log.warn("[MCP Client] SSE 流异常断开：{}", e.getMessage());
                    connected = false;
                }
            }
        }, "mcp-sse-" + sseUrl.hashCode());
        sseThread.setDaemon(true);
        sseThread.start();

        // 等待 endpoint 事件（最多 10 秒）
        long deadline = System.currentTimeMillis() + 10_000;
        while (messageEndpoint == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (messageEndpoint == null) {
            throw new RuntimeException("SSE 连接超时：未收到 endpoint 事件");
        }

        // 发送 initialize 握手
        Map<String, Object> initResult = sendRequest("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "lattice-planner-client", "version", "1.0.0")
        ));
        log.info("[MCP Client] initialize 成功：{}", initResult.get("serverInfo"));

        connected = true;
        log.info("[MCP Client] 已连接：{}", sseUrl);
    }

    /** 解析 SSE 事件流。 */
    private void parseSseStream(java.util.stream.Stream<String> lines) {
        String[] eventName = {""};
        StringBuilder dataBuf = new StringBuilder();

        lines.forEach(line -> {
            try {
                if (line.startsWith("event:")) {
                    eventName[0] = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!dataBuf.isEmpty()) dataBuf.append("\n");
                    dataBuf.append(data);
                } else if (line.isEmpty() && !dataBuf.isEmpty()) {
                    // 事件结束，处理
                    handleSseEvent(eventName[0], dataBuf.toString());
                    eventName[0] = "";
                    dataBuf.setLength(0);
                }
            } catch (Exception e) {
                log.warn("[MCP Client] 解析 SSE 事件失败", e);
            }
        });

        // SSE 流结束
        connected = false;
        log.info("[MCP Client] SSE 流结束");
    }

    /** 处理单个 SSE 事件。 */
    private void handleSseEvent(String event, String data) {
        switch (event) {
            case "endpoint" -> {
                // 将服务端返回的相对路径解析为绝对 URL（服务端返回的是 /mcp/message?sid=xxx）
                this.messageEndpoint = resolveAbsoluteUrl(data);
                // 从 endpoint URL 解析 sid
                Matcher m = SID_PATTERN.matcher(this.messageEndpoint);
                if (m.find()) {
                    this.sessionId = m.group(1);
                }
                log.info("[MCP Client] 收到 endpoint 事件：{}，sid={}", this.messageEndpoint, sessionId);
            }
            case "message" -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = om.readValue(data, Map.class);
                    handleJsonRpcResponse(response);
                } catch (Exception e) {
                    log.warn("[MCP Client] 解析 JSON-RPC 响应失败", e);
                }
            }
            default -> log.debug("[MCP Client] 忽略 SSE 事件：event={}, data={}", event, data);
        }
    }

    /** 处理 JSON-RPC 响应，匹配 pending 请求。 */
    private void handleJsonRpcResponse(Map<String, Object> response) {
        Object idObj = response.get("id");
        if (idObj == null) return;

        int id;
        if (idObj instanceof Number n) {
            id = n.intValue();
        } else {
            try {
                id = Integer.parseInt(String.valueOf(idObj));
            } catch (NumberFormatException e) {
                return;
            }
        }

        PendingResponse pending = pendingRequests.remove(id);
        if (pending != null) {
            pending.complete(response);
        }
    }

    /** 发送 JSON-RPC 请求并等待响应。 */
    public Map<String, Object> sendRequest(String method, Map<String, Object> params) throws Exception {
        if (messageEndpoint == null) {
            throw new RuntimeException("MCP Client 未连接（无 messageEndpoint）");
        }

        int id = requestId.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        } else {
            request.put("params", Map.of());
        }

        PendingResponse pending = new PendingResponse();
        pendingRequests.put(id, pending);

        String body = om.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(messageEndpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());

        // 等待响应（callTimeout 秒超时）
        Map<String, Object> response = pending.get(Duration.ofSeconds(callTimeout));
        if (response == null) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP 请求超时（" + callTimeout + "s）：method=" + method);
        }

        // 检查错误
        if (response.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            throw new RuntimeException("MCP 错误：" + error.get("message"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        return result != null ? result : Map.of();
    }

    /** 调用远程工具。 */
    public McpToolResult callTool(String toolName, Map<String, Object> args) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", args != null ? args : Map.of());

            Map<String, Object> result = sendRequest("tools/call", params);

            boolean isError = Boolean.TRUE.equals(result.get("isError"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
            if (content == null) {
                content = List.of(Map.of("type", "text", "text", om.writeValueAsString(result)));
            }
            return new McpToolResult(content, isError);
        } catch (Exception e) {
            log.warn("[MCP Client] tools/call 失败：tool={}, err={}", toolName, e.getMessage());
            return McpToolResult.error("MCP 调用失败：" + e.getMessage());
        }
    }

    /** 列出远程工具。 */
    @SuppressWarnings("unchecked")
    public List<McpRemoteTool> listTools(String serverName) {
        try {
            Map<String, Object> result = sendRequest("tools/list", Map.of());
            List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
            if (tools == null) return List.of();

            List<McpRemoteTool> remoteTools = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                String originalName = (String) tool.get("name");
                String fullName = "mcp." + serverName + "." + originalName;
                String description = (String) tool.getOrDefault("description", "");
                Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                remoteTools.add(new McpRemoteTool(fullName, originalName, description, inputSchema, serverName));
            }
            return remoteTools;
        } catch (Exception e) {
            log.warn("[MCP Client] tools/list 失败：server={}, err={}", serverName, e.getMessage());
            return List.of();
        }
    }

    /** 断开连接。 */
    public synchronized void disconnect() {
        connected = false;
        if (sseThread != null) {
            sseThread.interrupt();
            sseThread = null;
        }
        messageEndpoint = null;
        sessionId = null;
        // 清理所有等待中的请求
        for (PendingResponse pending : pendingRequests.values()) {
            pending.complete(null);
        }
        pendingRequests.clear();
        log.info("[MCP Client] 已断开：{}", sseUrl);
    }

    public boolean isConnected() {
        return connected;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 从完整 SSE URL 提取 scheme + authority 部分作为 base URL。
     * 例如 http://localhost:8080/sse → http://localhost:8080
     */
    private String extractBaseUrl(String fullUrl) {
        try {
            URI uri = new URI(fullUrl);
            return new URI(uri.getScheme(), uri.getAuthority(), null, null, null).toString();
        } catch (Exception e) {
            log.warn("[MCP Client] 无法解析 SSE base URL：{}", fullUrl);
            return fullUrl;
        }
    }

    /**
     * 将服务端返回的 endpoint 解析为绝对 URL。
     * 若已是绝对 URL（含 scheme）则直接返回，否则用 baseUrl 拼接。
     */
    private String resolveAbsoluteUrl(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            // 尝试作为绝对 URI 解析
            URI uri = new URI(raw);
            if (uri.isAbsolute()) {
                return raw;
            }
            // 相对路径：拼接 baseUrl
            return baseUrl + raw;
        } catch (Exception e) {
            // 回退：直接拼接
            return baseUrl + raw;
        }
    }

    /**
     * 简易 CompletableFuture 替代：用于等待 SSE 推回的 JSON-RPC 响应。
     * 不引入额外依赖，使用 wait/notify 实现。
     */
    private static class PendingResponse {
        private volatile Map<String, Object> result;
        private volatile boolean completed;

        synchronized void complete(Map<String, Object> result) {
            this.result = result;
            this.completed = true;
            notifyAll();
        }

        synchronized Map<String, Object> get(Duration timeout) throws InterruptedException {
            if (completed) return result;
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (!completed && System.currentTimeMillis() < deadline) {
                wait(Math.max(100, deadline - System.currentTimeMillis()));
            }
            return result;
        }
    }
}
