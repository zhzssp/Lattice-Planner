package org.zhzssp.memorandum.feature.agent.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP Client 连接管理器。
 * 读取配置 → 创建 McpClientConnection → 管理生命周期 + 健康检查 + 自动重连。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpClientProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    private final List<McpClientConnection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean initialized = false;

    public McpClientManager(McpClientProperties properties,
                            com.fasterxml.jackson.databind.ObjectMapper om) {
        this.properties = properties;
        this.om = om;
    }

    /** 启动时连接所有配置的 MCP Server。 */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[MCP Client] MCP Client 未启用（mcp.client.enabled=false）");
            return;
        }

        log.info("[MCP Client] 初始化 MCP Client，配置了 {} 个 Server",
                properties.getServers().size());

        for (var entry : properties.getServers().entrySet()) {
            String name = entry.getKey();
            McpClientProperties.ServerConfig cfg = entry.getValue();

            if (!cfg.isEnabled()) {
                log.info("[MCP Client] 跳过禁用的 Server：{}", name);
                continue;
            }

            if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                log.warn("[MCP Client] Server {} 未配置 URL，跳过", name);
                continue;
            }

            McpClientConnection conn = new McpClientConnection(
                    name, cfg.getUrl(), cfg.getToken(),
                    cfg.getConnectTimeout(), cfg.getCallTimeout(), om);
            connections.add(conn);

            try {
                conn.connect();
            } catch (Exception e) {
                log.warn("[MCP Client] Server {} 启动时连接失败，将在健康检查时重试：{}",
                        name, e.getMessage());
            }
        }

        initialized = true;
        log.info("[MCP Client] 初始化完成，{} 个连接（{} 个已连接）",
                connections.size(), connections.stream().filter(McpClientConnection::isConnected).count());
    }

    /** 定期健康检查 + 自动重连（每 30 秒）。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void healthCheck() {
        if (!properties.isEnabled() || !initialized) return;

        for (McpClientConnection conn : connections) {
            if (!conn.isConnected()) {
                try {
                    log.info("[MCP Client] 尝试重连：{}", conn.getServerName());
                    conn.reconnect();
                    log.info("[MCP Client] 重连成功：{}", conn.getServerName());
                    // 通知 ToolProxy 重新注册
                    notifyReconnect(conn);
                } catch (Exception e) {
                    log.warn("[MCP Client] 重连失败：{} - {}", conn.getServerName(), e.getMessage());
                }
            }
        }
    }

    /** 重连后通知外部注册（由 McpToolProxy 监听）。 */
    private void notifyReconnect(McpClientConnection conn) {
        // 通过 Spring 事件或直接回调——由 McpToolProxy.setClientManager 调用
        // 这里用简单的回调机制
        if (reconnectCallback != null) {
            reconnectCallback.accept(conn);
        }
    }

    @FunctionalInterface
    public interface ReconnectCallback {
        void accept(McpClientConnection conn);
    }

    private ReconnectCallback reconnectCallback;

    public void setReconnectCallback(ReconnectCallback callback) {
        this.reconnectCallback = callback;
    }

    /** 获取所有连接。 */
    public List<McpClientConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    /** 获取指定名称的连接。 */
    public McpClientConnection getConnection(String serverName) {
        return connections.stream()
                .filter(c -> c.getServerName().equals(serverName))
                .findFirst()
                .orElse(null);
    }

    /** 获取所有已连接的 Server 的远程工具总数。 */
    public int totalRemoteTools() {
        return connections.stream()
                .filter(McpClientConnection::isConnected)
                .mapToInt(c -> c.getRemoteTools().size())
                .sum();
    }

    /** 手动触发重连指定 Server。 */
    public void reconnectServer(String serverName) throws Exception {
        McpClientConnection conn = getConnection(serverName);
        if (conn == null) {
            throw new IllegalArgumentException("未找到 MCP Server：" + serverName);
        }
        conn.reconnect();
        notifyReconnect(conn);
    }

    /** 是否启用。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
