package org.zhzssp.memorandum.feature.agent.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内会话短期记忆。每个 sessionId 对应一个滑动窗口。
 * 窗口大小读 {@code agent.chat.history-window}（条数，含 user / assistant 交错）。
 *
 * <h3>★ 这个配置曾经是死的</h3>
 * 属性写在 {@code application.properties} 里，评测还用 {@code @TestPropertySource}
 * 把它调到 8，想压出折叠。但本类曾经把容量写死为 30，<b>从不读那个属性</b>。
 * 于是 P6 的多轮对照实验里折叠根本不会触发——实验组和对照组走的是同一条路径。
 * 旋钮必须接到这里，评测里改窗口才有意义。
 *
 * <p>除消息窗口外，额外维护每个会话的「最后活跃时间」，供
 * {@code SessionArchiveScheduler} 判定空闲会话并触发长期记忆归档。</p>
 */
@Component
public class ConversationMemory {

    public record Msg(String role, String content) {
    }

    /** 与 {@code application.properties} 默认值对齐；单测无 Spring 时走这条。 */
    public static final int DEFAULT_WINDOW = 30;

    private final int window;

    /** 单测 / 基准用：默认 30，与生产配置一致。 */
    public ConversationMemory() {
        this(DEFAULT_WINDOW);
    }

    /**
     * 生产入口：窗口必须从配置读，不能写死。
     *
     * <p>下限 2：窗口至少要能同时放下一条 user 和一条 assistant，
     * 再小就不是「滑动窗口」而是「只留一句」，折叠阈值也会退化成 1。</p>
     */
    @Autowired
    public ConversationMemory(@Value("${agent.chat.history-window:30}") int historyWindow) {
        if (historyWindow < 2) {
            throw new IllegalArgumentException(
                    "agent.chat.history-window 必须 ≥ 2，实际=" + historyWindow);
        }
        this.window = historyWindow;
    }

    /** 窗口容量（供滚动摘要按比例计算触发阈值）。 */
    public int windowSize() {
        return window;
    }

    private final Map<String, Deque<Msg>> store = new ConcurrentHashMap<>();
    /** sessionId -> 最后一次 append 的时刻，用于空闲归档判定。 */
    private final Map<String, Instant> lastActiveAt = new ConcurrentHashMap<>();
    /** sessionId -> 已开始的用户轮次数，供 facts 的 source_turn 追溯。 */
    private final Map<String, AtomicInteger> turnCounters = new ConcurrentHashMap<>();

    public List<Msg> history(String sid) {
        Deque<Msg> q = store.get(sid);
        return q == null ? List.of() : new ArrayList<>(q);
    }

    /** 当前窗口已用条数（供摘要触发判定）。 */
    public int size(String sid) {
        Deque<Msg> q = store.get(sid);
        return q == null ? 0 : q.size();
    }

    public void append(String sid, String role, String content) {
        Deque<Msg> q = store.computeIfAbsent(sid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Msg(role, content));
            while (q.size() > window) q.pollFirst();
        }
        lastActiveAt.put(sid, Instant.now());
    }

    /**
     * 折叠窗口最老的 {@code count} 条消息为一条摘要。
     *
     * <p>供 {@code ContextCompactor} 调用，<strong>取代</strong>默认的 {@code pollFirst}
     * 无差别丢弃：把最老的 {@code count} 条换成一条 {@code role=user} 的摘要消息，
     * 保留其中「用户设定的约束」这类关键信息，而不是让它们随窗口滑出而静默消失。</p>
     *
     * <p>当 {@code summaryContent} 为空/空白时，语义退化为「纯丢弃」（不留占位消息）——
     * 供整段全是工具噪声、无需摘要也不值得留占位的场景。</p>
     *
     * <p>线程安全：与 {@link #append} 一样在 {@code Deque} 上加锁。</p>
     *
     * @return 实际被替换的消息条数（窗口不足 {@code count} 时按实际数量）
     */
    public int compact(String sid, int count, String role, String summaryContent) {
        Deque<Msg> q = store.get(sid);
        if (q == null || count <= 0) return 0;
        synchronized (q) {
            int actual = Math.min(count, q.size());
            if (actual <= 0) return 0;
            for (int i = 0; i < actual; i++) q.pollFirst();
            if (summaryContent != null && !summaryContent.isBlank()) {
                q.addFirst(new Msg(role, summaryContent));
            }
            return actual;
        }
    }

    /**
     * 开启新一轮，返回该轮在本会话中的序号（第一轮为 1）。
     *
     * <p>轮次不能从消息条数推算：窗口会滑动、会被摘要折叠，条数是会变小的量，
     * 而 {@code source_turn} 要的是「用户第几次开口」这个单调不减的事实。</p>
     */
    public int nextTurn(String sid) {
        return turnCounters.computeIfAbsent(sid, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 当前轮次序号；尚未开始任何轮返回 0。 */
    public int currentTurn(String sid) {
        AtomicInteger c = turnCounters.get(sid);
        return c == null ? 0 : c.get();
    }

    public void clear(String sid) {
        store.remove(sid);
        lastActiveAt.remove(sid);
        turnCounters.remove(sid);
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
