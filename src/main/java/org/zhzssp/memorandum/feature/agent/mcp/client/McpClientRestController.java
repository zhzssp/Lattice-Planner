jiangpackage org.zhzssp.memorandum.feature.agent.mcp.client;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * MCP Client REST API：提供已连接的 MCP Server 状态查询和手动重连。
 */
@RestController
@RequestMapping("/api/mcp/client")
public class McpClientRestController {

    private final McpClientManager clientManager;

    public McpClientRestController(McpClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /** 列出所有 MCP Client 连接及其状态。 */
    @GetMapping("/servers")
    public ResponseEntity<?> listServers() {
        if (!clientManager.isEnabled()) {
            return ResponseEntity.ok(Map.of("enabled", false, "servers", List.of()));
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpClientConnection conn : clientManager.getConnections()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", conn.getServerName());
            info.put("url", conn.getSseUrl());
            info.put("connected", conn.isConnected());
            info.put("toolCount", conn.getRemoteTools().size());
            // 工具列表
            List<Map<String, String>> tools = new ArrayList<>();
            for (McpRemoteTool rt : conn.getRemoteTools()) {
                tools.add(Map.of("name", rt.fullName(), "description", rt.description()));
            }
            info.put("tools", tools);
            servers.add(info);
        }

        return ResponseEntity.ok(Map.of("enabled", true, "servers", servers));
    }

    /** 手动触发重连。 */
    @PostMapping("/servers/{name}/reconnect")
    public ResponseEntity<?> reconnect(@PathVariable String name) {
        try {
            clientManager.reconnectServer(name);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "重连成功：" + name));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", "重连失败：" + e.getMessage()));
        }
    }
}
