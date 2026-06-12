package org.zhzssp.memorandum.feature.agent.runtime;

import org.zhzssp.memorandum.entity.User;

/**
 * 线程级 Agent 上下文：保存当前 ReAct 推理线程对应的用户与会话 id。
 *
 * 由 AgentChatWebSocketHandler 在派生推理线程时调用 set()，结束时 clear()。
 * 工具实现可通过 AgentContext.requireUser() 获取调用者，从而实现多用户隔离。
 */
public final class AgentContext {

    private static final ThreadLocal<User> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    /** 当前推理层级：0=主 Agent，>=1=子代理。用于防止子代理递归再起子代理。 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AgentContext() {
    }

    public static void set(User user, String sessionId) {
        USER.set(user);
        SESSION.set(sessionId);
    }

    public static void clear() {
        USER.remove();
        SESSION.remove();
        DEPTH.remove();
    }

    /** 当前推理层级（0 为主 Agent）。 */
    public static int depth() {
        return DEPTH.get();
    }

    /** 进入子代理：depth++（由 SubAgentRunner 包裹调用）。 */
    public static void enterSub() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /** 退出子代理：depth--。 */
    public static void exitSub() {
        DEPTH.set(Math.max(0, DEPTH.get() - 1));
    }

    public static User requireUser() {
        User u = USER.get();
        if (u == null) {
            throw new IllegalStateException("AgentContext.user 未初始化（不在 Agent 推理线程中调用？）");
        }
        return u;
    }

    public static String sessionId() {
        return SESSION.get();
    }
}
