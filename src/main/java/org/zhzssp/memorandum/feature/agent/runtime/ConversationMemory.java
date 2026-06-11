package org.zhzssp.memorandum.feature.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话短期记忆。每个 sessionId 对应一个滑动窗口。
 * 窗口大小见 WINDOW（条数，含 user / assistant 交错）。
 */
@Component
public class ConversationMemory {

    public record Msg(String role, String content) {
    }

    private static final int WINDOW = 30;

    private final Map<String, Deque<Msg>> store = new ConcurrentHashMap<>();

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
    }

    public void clear(String sid) {
        store.remove(sid);
    }
}
