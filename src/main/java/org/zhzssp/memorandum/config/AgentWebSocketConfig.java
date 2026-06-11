package org.zhzssp.memorandum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.zhzssp.memorandum.feature.agent.chat.AgentChatWebSocketHandler;

/**
 * 注册 Agent 对话 WebSocket 端点：/ws/agent/{sessionId}
 *
 * 同源握手会自动携带 JSESSIONID Cookie，Spring Security 会把已登录 User 写入 Principal。
 */
@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentChatWebSocketHandler handler;

    public AgentWebSocketConfig(AgentChatWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent/**")
                .setAllowedOriginPatterns("*"); // 兼容 Electron 与 http://localhost
    }
}
