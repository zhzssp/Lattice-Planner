package org.zhzssp.memorandum.feature.agent.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个远程 MCP Server 的客户端连接。
 * 生命周期：启动时连接 → 运行中代理调用 → 关闭时断连。
 *
 * 封装 McpSseClient，提供更高层的方法：
 * - connect()：SSE 连接 + initialize 握手 + tools/list 发现
 * - callTool()：代理远程工具调用
 * - disconnect()：断开连接
 * - getRemoteTools()：获取发现的工具列表
 */
public class McpClientConnection {

    private static final Logger log = LoggerFactory.getLogger(McpClientConnection.class);

    private final String serverName;
    private final String sseUrl;
    private final String authToken;
    private final int connectTimeout;
    private final int callTimeout;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    private McpSseClient sseClient;
    private List<McpRemoteTool> remoteTools = new ArrayList<>();
    private volatile boolean connected = false;

    public McpClientConnection(String serverName, String sseUrl, String authToken,
                                int connectTimeout, int callTimeout,
                                com.fasterxml.jackson.databind.ObjectMapper om) {
        this.serverName = serverName;
        this.sseUrl = sseUrl;
        this.authToken = authToken;
        this.connectTimeout = connectTimeout;
        this.callTimeout = callTimeout;
        this.om = om;
    }

    /** 连接到远程 MCP Server。 */
    public void connect() throws Exception {
        log.info("[MCP Client] 正在连接 {} ({})...", serverName, sseUrl);
        try {
            sseClient = new McpSseClient(sseUrl, authToken, connectTimeout, callTimeout, om);
            sseClient.connect();

            // 发现远程工具
            this.remoteTools = sseClient.listTools(serverName);
            this.connected = true;
            log.info("[MCP Client] {} 连接成功，发现 {} 个工具", serverName, remoteTools.size());
            for (McpRemoteTool rt : remoteTools) {
                log.info("[MCP Client]   - {} : {}", rt.fullName(), rt.description());
            }
        } catch (Exception e) {
            this.connected = false;
            log.warn("[MCP Client] {} 连接失败：{}", serverName, e.getMessage());
            throw e;
        }
    }

    /** 代理 tools/call 请求。 */
    public McpToolResult callTool(String originalName, Map<String, Object> args) {
        if (!connected || sseClient == null) {
            return McpToolResult.error("MCP Server " + serverName + " 未连接");
        }
        return sseClient.callTool(originalName, args);
    }

    /** 断开连接。 */
    public void disconnect() {
        connected = false;
        if (sseClient != null) {
            sseClient.disconnect();
            sseClient = null;
        }
        remoteTools.clear();
        log.info("[MCP Client] {} 已断开", serverName);
    }

    /** 重新连接（先断开再连）。 */
    public void reconnect() throws Exception {
        disconnect();
        connect();
    }

    public boolean isConnected() {
        return connected;
    }

    public String getServerName() {
        return serverName;
    }

    public String getSseUrl() {
        return sseUrl;
    }

    public List<McpRemoteTool> getRemoteTools() {
        return remoteTools;
    }
}
