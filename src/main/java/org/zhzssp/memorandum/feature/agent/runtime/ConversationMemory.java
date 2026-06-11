package org.zhzssp.memorandum.feature.agent.runtime;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话短期记忆。每个 sessionId 对应一个滑动窗口。
 * 窗口大小见 WINDOW（条数，含 user / assistant 交错）。
 *
 * <p>除消息窗口外，额外维护每个会话的「最后活跃时间」，供
 * {@code SessionArchiveScheduler} 判定空闲会话并触发长期记忆归档。</p>
 */
@Component
public class ConversationMemory {

    public record Msg(String role, String content) {
    }

    private static final int WINDOW = 30;

    private final Map<String, Deque<Msg>> store = new ConcurrentHashMap<>();
    /** sessionId -> 最后一次 append 的时刻，用于空闲归档判定。 */
    private final Map<String, Instant> lastActiveAt = new ConcurrentHashMap<>();

    public List<Msg> history(String sid) {
        Deque<Msg> q = store.get(sid);
        return q == null ? List.of() : new ArrayList<>(q);
    }

    public void append(String sid, String role, String content) {
        Deque<Msg> q = store.computeIfAbsent(sid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Msg(role, content));
            while (q.size() > WINDOW) q.pollFirst();
        }
        lastActiveAt.put(sid, Instant.now());
    }

    public void clear(String sid) {
        store.remove(sid);
        lastActiveAt.remove(sid);
    }

    /** 该会话最后活跃时刻；从未活跃返回 null。 */
    public Instant lastActiveAt(String sid) {
        return lastActiveAt.get(sid);
    }

    /** 当前持有记忆的全部会话 id 快照。 */
    public Set<String> sessionIds() {
        return new HashSet<>(store.keySet());
    }

    /**
     * 返回空闲时长已超过 idleThreshold 的会话 id 集合。
     * 仅依据「最后活跃时间」判定，与 WebSocket 是否在线无关——
     * 这样既能覆盖「关连接后再不回来」，也能容忍刷新/短暂断线重连。
     */
    public Set<String> idleSessions(Duration idleThreshold) {
        Instant deadline = Instant.now().minus(idleThreshold);
        Set<String> idle = new HashSet<>();
        for (Map.Entry<String, Instant> e : lastActiveAt.entrySet()) {
            if (e.getValue() != null && e.getValue().isBefore(deadline)) {
                idle.add(e.getKey());
            }
        }
        return idle;
    }
}
