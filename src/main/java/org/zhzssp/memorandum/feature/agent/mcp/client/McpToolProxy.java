package org.zhzssp.memorandum.feature.agent.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 远程工具的本地代理。
 * 注册到 ToolRegistry 的 MCP 工具入口，调用时走 McpClientConnection 而非反射。
 *
 * 启动后扫描所有已连接的 MCP Server，将发现的远程工具注册到 ToolRegistry。
 * 重连时自动重新注册。
 */
@Component
public class McpToolProxy {

    private static final Logger log = LoggerFactory.getLogger(McpToolProxy.class);

    private final McpClientManager clientManager;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper om;

    public McpToolProxy(McpClientManager clientManager, ToolRegistry toolRegistry, ObjectMapper om) {
        this.clientManager = clientManager;
        this.toolRegistry = toolRegistry;
        this.om = om;

        // 注入自身到 ToolRegistry（避免循环依赖：ToolRegistry 不依赖 McpToolProxy）
        toolRegistry.setMcpProxy(this);

        // 注册重连回调
        clientManager.setReconnectCallback(this::onServerReconnect);
    }

    /** 启动后注册所有已连接 Server 的远程工具。 */
    @EventListener(ApplicationReadyEvent.class)
    public void registerRemoteTools() {
        if (!clientManager.isEnabled()) return;

        for (McpClientConnection conn : clientManager.getConnections()) {
            if (conn.isConnected()) {
                registerToolsFromServer(conn);
            }
        }
    }

    /** 注册单个 Server 的远程工具。 */
    private void registerToolsFromServer(McpClientConnection conn) {
        for (McpRemoteTool rt : conn.getRemoteTools()) {
            toolRegistry.registerMcpTool(rt);
            log.info("[MCP Client] 注册远程工具：{} (from {})", rt.fullName(), rt.serverName());
        }
    }

    /** Server 重连后重新注册其工具。 */
    private void onServerReconnect(McpClientConnection conn) {
        // 先移除旧工具
        toolRegistry.unregisterMcpTools(conn.getServerName());
        // 再注册新发现的工具
        registerToolsFromServer(conn);
    }

    /**
     * 代理调用远程 MCP 工具。
     * 由 ToolRegistry.invoke() 委托调用。
     */
    public Object invoke(String fullName, JsonNode args) {
        // 1. 解析 fullName → serverName + originalName
        // 格式：mcp.{serverName}.{originalName}
        if (!fullName.startsWith("mcp.")) {
            return Map.of("error", "INVALID_MCP_TOOL_NAME", "message", "工具名不以 mcp. 开头：" + fullName);
        }

        String rest = fullName.substring(4); // 去掉 "mcp."
        int dotIdx = rest.indexOf('.');
        if (dotIdx < 0) {
            return Map.of("error", "INVALID_MCP_TOOL_NAME", "message", "工具名格式错误：" + fullName);
        }

        String serverName = rest.substring(0, dotIdx);
        String originalName = rest.substring(dotIdx + 1);

        // 2. 获取对应连接
        McpClientConnection conn = clientManager.getConnection(serverName);
        if (conn == null || !conn.isConnected()) {
            return Map.of("error", "MCP_SERVER_DISCONNECTED", "server", serverName,
                    "message", "MCP Server " + serverName + " 未连接");
        }

        // 3. 转换 args → Map<String, Object>
        Map<String, Object> argsMap = new LinkedHashMap<>();
        if (args != null && !args.isEmpty()) {
            argsMap = om.convertValue(args, Map.class);
        }

        // 4. 调用远程工具
        McpToolResult result = conn.callTool(originalName, argsMap);

        // 5. 转换为 Agent 可用的返回值
        if (result.isError()) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("error", "MCP_TOOL_ERROR");
            errorResult.put("server", serverName);
            errorResult.put("tool", originalName);
            errorResult.put("message", result.extractText());
            return errorResult;
        }

        // 返回提取的文本内容
        String text = result.extractText();
        return text.isEmpty() ? Map.of("result", "（无内容）") : text;
    }
}
