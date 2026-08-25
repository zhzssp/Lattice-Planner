package org.zhzssp.memorandum.feature.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 滚动摘要（上下文工程 P1 第一步）。
 *
 * <p>把当前「窗口超限即 {@code pollFirst} 无差别丢弃」升级为「先把最老 N 条折叠成
 * 一条摘要」——折叠保留的是「用户设定的约束」这类关键信息，丢弃掉的是冗余措辞。
 * 解决的核心问题：用户第 3 轮说的「deadline 是下周五」，在第 35 轮时不该随窗口
 * 滑出而<strong>静默消失</strong>（它和模型都不知道信息丢了，于是照着错的时间排期）。</p>
 *
 * <h3>三个关键决定（详见设计文档）</h3>
 * <ol>
 *   <li><strong>纯工具噪声短路</strong>：待折叠区剔除工具 trace 后若为空，直接丢弃、
 *       不付 LLM 调用。一次 10 步 ReAct 产生 20 条工具噪声，不短路会为无价值内容反复烧钱；</li>
 *   <li><strong>摘要走 {@code role=user}</strong>：与既有 {@code appendToolTrace} 立场一致，
 *       OpenAI 兼容接口对非标准 role 行为不一，user 最稳；且摘要<strong>不能进 system</strong>，
 *       否则破坏前缀字节稳定、打穿上游 prompt cache；</li>
 *   <li><strong>失败回退等价旧行为</strong>：LLM 折叠失败 → 直接丢弃 + 置位
 *       {@code CAUSE_TRUNCATED}，绝不因摘要失败阻断对话。</li>
 * </ol>
 *
 * <h3>与长期记忆归档的区别</h3>
 * <p>{@code LongTermMemoryService.archive} 是「会话空闲后、跨会话、凝练成画像」；
 * 本类是「会话内、窗口将满时、折叠成摘要」——前者回答"这人是谁"，后者保住
 * "这段对话里还没完成的约束"。两者不可互相替代。</p>
 */
@Component
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private final ConversationMemory memory;
    private final LlmGateway llm;

    @Value("${agent.context.compaction.enabled:false}")
    private boolean enabled;

    /** 触发折叠的窗口占用比例（0.8 = 窗口用到 80% 时折叠）。 */
    @Value("${agent.context.compaction.trigger-ratio:0.8}")
    private double triggerRatio;

    /** 单次折叠的消息条数。 */
    @Value("${agent.context.compaction.fold-size:10}")
    private int foldSize;

    /** 摘要长度上限（字符）。 */
    @Value("${agent.context.compaction.summary-max-chars:200}")
    private int summaryMaxChars;

    /** 触发折叠所需的最少真实对话条数（剔除工具噪声后）。 */
    @Value("${agent.context.compaction.min-dialogue:6}")
    private int minDialogue;

    public ContextCompactor(ConversationMemory memory, LlmGateway llm) {
        this.memory = memory;
        this.llm = llm;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 若窗口接近满则折叠最老的一段。
     *
     * <p>在每次 {@code appendToolTrace} 之后调用，确保 ReAct 密集产生历史时也能及时折叠。
     * {@code outcome} 可为 null（调用方无收尾上下文时跳过降级标记，如启动期）。</p>
     *
     * @return 是否发生了折叠（供日志/测试断言）
     */
    public boolean compactIfNeeded(String sid, TurnOutcome outcome) {
        if (!enabled) return false;
        int size = memory.size(sid);
        if (size < triggerThreshold()) return false;

        List<ConversationMemory.Msg> history = memory.history(sid);
        int actual = Math.min(foldSize, history.size());
        if (actual <= 0) return false;
        List<ConversationMemory.Msg> window = history.subList(0, actual);

        // ★纯工具噪声短路：没有任何真实对话值得折叠，直接丢弃，不付 LLM 调用
        List<ConversationMemory.Msg> dialogue = window.stream()
                .filter(m -> !ToolNoiseFilter.isToolNoise(m))
                .toList();
        if (dialogue.size() < minDialogue) {
            // 整段（或接近整段）是工具噪声 → 纯丢弃，不留占位消息
            memory.compact(sid, actual, "user", "");
            if (outcome != null) outcome.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            log.debug("[Agent] 滚动摘要：待折叠段仅 {} 条真实对话，直接丢弃 {} 条", dialogue.size(), actual);
            return true;
        }

        String summary = summarize(dialogue);
        if (summary == null) {
            // 折叠失败 → 回退等价旧行为：直接丢弃 + 截断降级
            memory.compact(sid, actual, "user", "");
            if (outcome != null) outcome.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            log.warn("[Agent] 滚动摘要折叠失败，回退为直接丢弃 {} 条", actual);
            return false;
        }

        memory.compact(sid, actual, "user", "[对话摘要]\n" + summary);
        if (outcome != null) outcome.markDegraded(TurnOutcome.CAUSE_SUMMARIZED);
        log.info("[Agent] 滚动摘要：折叠 {} 条（其中 {} 条真实对话）为 1 条摘要", actual, dialogue.size());
        return true;
    }

    private int triggerThreshold() {
        return Math.max(1, (int) (ConversationMemory.windowSize() * triggerRatio));
    }

    private String summarize(List<ConversationMemory.Msg> dialogue) {
        String dialog = dialogue.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n"));
        try {
            String out = llm.generateText(
                    "请把下面这段用户与助手的对话压缩为一段不超过 200 字的摘要。"
                            + "必须保留：用户提出的约束、要求、待办、决定（尤其是时间、人名、数量、路径等具体值）。"
                            + "可以丢弃：寒暄、已完成的中间过程。直接输出摘要，不要任何前缀或解释：\n\n"
                            + dialog);
            if (out == null || out.isBlank()) return null;
            String s = out.strip();
            return s.length() > summaryMaxChars ? s.substring(0, summaryMaxChars) + "…" : s;
        } catch (Exception e) {
            log.warn("[Agent] 滚动摘要 LLM 调用失败：{}", e.getMessage());
            return null;
        }
    }
}
