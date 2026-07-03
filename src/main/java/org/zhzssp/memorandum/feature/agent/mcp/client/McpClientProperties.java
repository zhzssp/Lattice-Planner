package org.zhzssp.memorandum.feature.agent.mcp.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Client 配置绑定。
 * 配置格式：mcp.client.servers.{name}.url / .token / .enabled
 */
@Data
@ConfigurationProperties(prefix = "mcp.client")
public class McpClientProperties {

    private boolean enabled = false;

    private Map<String, ServerConfig> servers = new LinkedHashMap<>();

    @Data
    public static class ServerConfig {
        /** SSE 端点 URL，如 http://localhost:3001/sse */
        private String url;
        /** 可选认证 token */
        private String token;
        /** 是否启用该 Server 连接 */
        private boolean enabled = true;
        /** 连接超时（秒） */
        private int connectTimeout = 30;
        /** 调用超时（秒） */
        private int callTimeout = 30;
    }
}
