package org.zhzssp.memorandum.feature.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Facts 层（上下文工程 P1 第二步）。
 *
 * <p>把「会话内具体约束」从滑动窗口的静默丢失中抢救出来：每轮异步从用户原话
 * 抽取事实，按变更频率分流注入（稳定 → system，易变 → history 首条）。</p>
 *
 * <h3>三个关键决定（详见设计文档）</h3>
 * <ol>
 *   <li><strong>覆盖而非追加</strong>：同 key 新值把旧值标 {@code SUPERSEDED}，
 *       不删除——保留「这条约束被改过」的历史，也保留用户纠错的余地；</li>
 *   <li><strong>只收 {@code MEDIUM} 以上置信度</strong>：低置信候选直接丢弃，
 *       宁缺勿错——一条错误 fact 会被注入每一轮，污染面大；</li>
 *   <li><strong>{@code REJECTED} 永不再抽</strong>：用户判定错误后，同 key
 *       不再自动重现（与 V4 P3「dismiss 不自动重开」同构）。</li>
 * </ol>
 *
 * <h3>★刻意不提供「让模型主动记 fact」的工具</h3>
 * <p>facts 只能由服务端从用户原话抽取。给模型一个 {@code fact.remember} 工具，
 * 它会热情地记下一堆「用户似乎对性能优化感兴趣」这类猜测，把真正的硬约束淹掉。</p>
 */
@Service
public class FactService {

    private static final Logger log = LoggerFactory.getLogger(FactService.class);

    private final AgentFactRepository factRepo;
    private final LlmGateway llm;
    private final ObjectMapper om;

    @Value("${agent.context.facts.enabled:false}")
    private boolean enabled;

    @Value("${agent.context.facts.max-stable:20}")
    private int maxStable;

    @Value("${agent.context.facts.max-volatile:15}")
    private int maxVolatile;

    @Value("${agent.context.facts.min-confidence:MEDIUM}")
    private String minConfidenceRaw;

    @Value("${agent.context.facts.stable-apply-granularity:DAY}")
    private String stableGranularity;

    public FactService(AgentFactRepository factRepo, LlmGateway llm, ObjectMapper om) {
        this.factRepo = factRepo;
        this.llm = llm;
        this.om = om;
    }

    public boolean enabled() {
        return enabled;
    }

    /* ==================== 注入 ==================== */

    /**
     * 稳定 facts 的注入片段（进 system prompt，参与 memoHash）。
     *
     * <p>返回 {@code ""}（而非 {@code null}）时表示无稳定 facts——由调用方决定
     * 是否省略该段。刻意不在此处拼「暂无」之类占位，避免污染 memoHash 字节。</p>
     */
    public String stableSnippet(Long userId) {
        if (!enabled) return "";
        List<AgentFact> facts = factRepo.findStableActive(userId);
        if (facts.isEmpty()) return "";
        List<AgentFact> limited = facts.subList(0, Math.min(facts.size(), maxStable));
        return format(limited);
    }

    /**
     * 易变 facts 的注入片段（进 history 首条 user 消息，不打穿前缀缓存）。
     *
     * @return {@code null} 表示无易变 facts（调用方应完全不加那段 user 消息）
     */
    public String volatileSnippet(Long userId, String sessionId) {
        if (!enabled || sessionId == null) return null;
        List<AgentFact> facts = factRepo.findVolatileActive(userId, sessionId);
        if (facts.isEmpty()) return null;
        List<AgentFact> limited = facts.subList(0, Math.min(facts.size(), maxVolatile));
        return "[已知事实]\n" + format(limited);
    }

    private String format(List<AgentFact> facts) {
        StringBuilder sb = new StringBuilder();
        for (AgentFact f : facts) {
            sb.append("- ").append(f.getFactValue()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /* ==================== 抽取 ==================== */

    /**
     * 从用户本轮输入异步抽取事实并入库。
     *
     * <p>标 {@code @Async}：抽取含一次 LLM 调用，绝不能在主对话线程上同步执行。
     * 调用方 fire-and-forget，失败静默——事实抽取是增强，绝不能阻断主对话。</p>
     *
     * @param userInput  用户本轮原始输入（非工具结果）
     * @param turnIndex  第几轮（用于 source_turn 追溯）
     */
    @org.springframework.scheduling.annotation.Async
    public void extractAsync(Long userId, String sessionId, String userInput, int turnIndex) {
        if (!enabled) return;
        if (userInput == null || userInput.strip().length() < 20) {
            return; // 太短的输入抽不出可靠事实，跳过（也省 LLM 调用）
        }
        try {
            List<Extracted> extracted = extract(userInput);
            if (extracted.isEmpty()) return;
            applyExtracted(userId, sessionId, userInput, turnIndex, extracted);
        } catch (Exception e) {
            log.warn("[Agent/Fact] 事实抽取失败：{}", e.getMessage());
        }
    }

    /** 抽取出的候选事实。 */
    private record Extracted(String key, String value, AgentFact.Kind kind,
                             AgentFact.Confidence confidence) {
    }

    private List<Extracted> extract(String userInput) {
        String raw;
        try {
            raw = llm.generateText(extractPrompt(userInput));
        } catch (Exception e) {
            log.debug("[Agent/Fact] LLM 抽取调用失败：{}", e.getMessage());
            return List.of();
        }
        return parseExtracted(raw);
    }

    private String extractPrompt(String userInput) {
        return """
                从下面这条用户消息里，抽取「值得记住的具体事实」。只抽取这三类：
                1. 硬约束：时间（deadline、会议时间）、数量、路径、版本号
                2. 决定：用户已拍板的选择（"我们就用 X"、"放弃 Y"）
                3. 稳定偏好：长期习惯（"我习惯早上做深度工作"）

                不要抽取：寒暄、对助手的指令（"帮我建个任务"这类）、临时的疑问。

                每条输出一行 JSON，字段：key（英文短键，如 deadline.project-x）、
                value（一句话中文）、kind（STABLE=长期偏好/跨会话，VOLATILE=本会话约束）、
                confidence（HIGH=原文明确陈述，MEDIUM=需推断）。没有就输出空数组 []。

                用户消息：
                ---
                %s
                ---
                """.formatted(userInput);
    }

    private List<Extracted> parseExtracted(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Extracted> out = new ArrayList<>();
        AgentFact.Confidence floor = parseFloor();
        for (String line : raw.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.equals("[]")) continue;
            if (s.startsWith("```")) continue;
            // 去掉可能的行首列表符号
            s = s.replaceFirst("^[-*]\\s*", "");
            if (!s.startsWith("{")) continue;
            try {
                JsonNode n = om.readTree(s);
                String key = n.path("key").asText("").strip();
                String value = n.path("value").asText("").strip();
                AgentFact.Kind kind = "STABLE".equalsIgnoreCase(n.path("kind").asText())
                        ? AgentFact.Kind.STABLE : AgentFact.Kind.VOLATILE;
                AgentFact.Confidence conf = AgentFact.Confidence.of(n.path("confidence").asText());
                if (key.isEmpty() || value.isEmpty()) continue;
                if (conf.ordinal() > floor.ordinal()) continue; // 低于下限的丢弃
                out.add(new Extracted(key, value, kind, conf));
            } catch (Exception e) {
                // 单行解析失败不影响其余行
            }
        }
        return out;
    }

    private AgentFact.Confidence parseFloor() {
        // HIGH=0, MEDIUM=1；下限为 MEDIUM 时，HIGH/MEDIUM 都收
        return AgentFact.Confidence.of(minConfidenceRaw);
    }

    /* ==================== 落库（覆盖语义） ==================== */

    @Transactional
    protected void applyExtracted(Long userId, String sessionId, String userInput,
                                  int turnIndex, List<Extracted> extracted) {
        Set<String> seenKeys = new LinkedHashSet<>();
        for (Extracted e : extracted) {
            String key = normalizeKey(e.key());
            if (key.isEmpty() || !seenKeys.add(key)) continue;

            // ★先查不限状态的最近一条：必须能看到 REJECTED，否则「标错永不再抽」会失效
            AgentFact latest = factRepo.findTopByUserIdAndFactKeyOrderByUpdatedAtDesc(
                    userId, key).orElse(null);
            if (latest != null && latest.getStatus() == AgentFact.Status.REJECTED) {
                continue; // 用户判定错误，永不再抽
            }

            // 有 ACTIVE 旧值 → 覆盖：标 SUPERSEDED 保留历史
            AgentFact active = factRepo.findByUserIdAndFactKeyAndStatus(
                    userId, key, AgentFact.Status.ACTIVE).orElse(null);
            if (active != null) {
                active.setStatus(AgentFact.Status.SUPERSEDED);
                active.setUpdatedAt(java.time.LocalDateTime.now());
                factRepo.save(active);
            }

            AgentFact f = new AgentFact();
            f.setUserId(userId);
            f.setSessionId(e.kind() == AgentFact.Kind.VOLATILE ? sessionId : null);
            f.setKind(e.kind());
            f.setFactKey(key);
            f.setFactValue(trimTo(e.value(), 512));
            f.setSourceQuote(trimTo(userInput, 512));
            f.setSourceTurn(turnIndex);
            f.setConfidence(e.confidence());
            f.setStatus(AgentFact.Status.ACTIVE);
            factRepo.save(f);
        }
    }

    /* ==================== 用户纠正 ==================== */

    /**
     * 用户标记一条 fact 为错误。
     *
     * <p>标 {@code REJECTED} 后同 key 永不再抽——用户判定过的东西不该被自动重开
     * （与 V4 P3「dismiss 不自动重开」同构）。</p>
     */
    @Transactional
    public void reject(Long userId, Long factId) {
        factRepo.findById(factId).filter(f -> f.getUserId().equals(userId)).ifPresent(f -> {
            f.setStatus(AgentFact.Status.REJECTED);
            f.setUpdatedAt(java.time.LocalDateTime.now());
            factRepo.save(f);
        });
    }

    public List<AgentFact> listForUser(Long userId) {
        return factRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /* ==================== 内部 ==================== */

    private String normalizeKey(String key) {
        String k = key.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9.\\-_]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return k.length() > 64 ? k.substring(0, 64) : k;
    }

    private String trimTo(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
