package org.zhzssp.memorandum.feature.agent.runtime;

/**
 * 工具噪声判定（纯函数）。
 *
 * <p>判定一条记忆是否为「工具调用 trace」——这类消息在对话归档与上下文折叠时都应剔除：
 * 它们占历史条数的比重最大（一次 ReAct 每步产生 2 条），却不承载任何用户语义。
 * 把它们喂给 LLM 做摘要，除了浪费一次调用外，摘要出的内容也对用户毫无价值。</p>
 *
 * <p>从 {@code LongTermMemoryService#isToolNoise} 抽出为独立工具，供
 * 「长期记忆归档」与「滚动摘要」两处复用，避免判定规则漂移。</p>
 */
public final class ToolNoiseFilter {

    private ToolNoiseFilter() {
    }

    /**
     * 是否为工具调用 trace：
     * <ul>
     *   <li>assistant 侧：{@code {"tool":...,"arguments":...}} 这类 JSON；</li>
     *   <li>user 侧：以 {@code [tool_result } 开头的工具结果回灌。</li>
     * </ul>
     */
    public static boolean isToolNoise(ConversationMemory.Msg m) {
        if (m == null || m.content() == null) return true;
        String c = m.content().trim();
        if (c.isEmpty()) return true;
        if (c.startsWith("[tool_result ")) return true;
        return "assistant".equals(m.role()) && c.startsWith("{\"tool\"");
    }
}
