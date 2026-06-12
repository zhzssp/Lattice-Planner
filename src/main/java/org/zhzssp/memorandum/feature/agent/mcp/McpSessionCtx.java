package org.zhzssp.memorandum.feature.agent.mcp;

import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * MCP 连接级会话：持有已认证的 User，在工具调用前注入 AgentContext。
 * 每个 SSE 连接对应一个 McpSessionCtx 实例。
 */
public class McpSessionCtx {

    private final User user;
    private final String sessionId;

    public McpSessionCtx(User user) {
        this.user = user;
        this.sessionId = UUID.randomUUID().toString();
    }

    public User getUser() {
        return user;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** 在 MCP 工具调用前包裹，注入 AgentContext，调用后清理。 */
    public <T> T withContext(Supplier<T> action) {
        AgentContext.set(user, sessionId);
        try {
            return action.get();
        } finally {
            AgentContext.clear();
        }
    }

    /** 无返回值版本。 */
    public void withContext(Runnable action) {
        AgentContext.set(user, sessionId);
        try {
            action.run();
        } finally {
            AgentContext.clear();
        }
    }
}
