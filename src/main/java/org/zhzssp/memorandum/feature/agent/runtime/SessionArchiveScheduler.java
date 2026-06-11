package org.zhzssp.memorandum.feature.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.UserRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话空闲归档调度器：长期记忆「写入闭环」的触发器。
 *
 * <p>背景：{@link LongTermMemoryService#archive} 此前无任何调用方（死代码），
 * 导致 AGENT_MEMO 永不落库、长期记忆形同虚设。本调度器补全触发链路：</p>
 *
 * <ol>
 *   <li>{@code AgentChatWebSocketHandler} 每次收到 chat 时登记 {@code sid -> userId}；</li>
 *   <li>定时扫描 {@link ConversationMemory#idleSessions}（按 last-active 判空闲）；</li>
 *   <li>对空闲会话调用 {@code archive(user, history)} 凝练落库，再 {@code clear(sid)}。</li>
 * </ol>
 *
 * <p>采用「空闲判定」而非「连接关闭即归档」，可容忍页面刷新 / 短暂断线重连，
 * 避免把一段未结束的对话过早切断归档。</p>
 */
@Component
public class SessionArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionArchiveScheduler.class);

    private final ConversationMemory memory;
    private final LongTermMemoryService longTermMemoryService;
    private final UserRepository userRepository;

    /** 会话空闲多少分钟后归档为长期记忆（复用既有配置项）。 */
    @Value("${agent.chat.session-idle-archive-minutes:30}")
    private int idleMinutes;

    /** sessionId -> userId，归档时据此解析 User。 */
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    public SessionArchiveScheduler(ConversationMemory memory,
                                   LongTermMemoryService longTermMemoryService,
                                   UserRepository userRepository) {
        this.memory = memory;
        this.longTermMemoryService = longTermMemoryService;
        this.userRepository = userRepository;
    }

    /** 由 WS handler 在每轮 chat 时调用，建立 / 刷新 sid -> userId 映射。 */
    public void register(String sid, Long userId) {
        if (sid == null || userId == null) return;
        sessionUsers.put(sid, userId);
    }

    /**
     * 周期性扫描空闲会话并归档。默认每 5 分钟一轮（fixedDelay：上一轮跑完再计时）。
     * 标 {@code @Async} 让含 LLM 凝练的归档在异步池中执行，不阻塞调度线程。
     */
    @Async
    @Scheduled(fixedDelayString = "${agent.chat.archive-sweep-interval-ms:300000}")
    public void sweep() {
        Set<String> idle = memory.idleSessions(Duration.ofMinutes(idleMinutes));
        if (idle.isEmpty()) return;
        log.info("[Agent] 长期记忆归档扫描：空闲会话数={}", idle.size());
        for (String sid : idle) {
            archiveSession(sid);
        }
    }

    /**
     * 归档单个会话：取历史 -> clear -> 凝练落库。
     *
     * <p>用 {@code sessionUsers.remove} 的原子返回值兜底去重：并发或重复触发时，
     * 第二次拿到 null 直接跳过，避免对同一会话重复归档。</p>
     */
    public void archiveSession(String sid) {
        Long uid = sessionUsers.remove(sid);
        List<ConversationMemory.Msg> history = memory.history(sid);
        memory.clear(sid);
        if (uid == null || history.isEmpty()) return;
        User user = userRepository.findById(uid).orElse(null);
        if (user == null) return;
        try {
            longTermMemoryService.archive(user, history);
            log.info("[Agent] 会话 {} 已归档为长期记忆，userId={}", sid, uid);
        } catch (Exception ex) {
            log.warn("[Agent] 会话 {} 归档失败：{}", sid, ex.getMessage());
        }
    }
}
