package org.zhzssp.memorandum.feature.agent.runtime;

import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolLayer;

import java.util.Set;

/**
 * 思维模式及其<strong>默认工具可见性规则</strong>（方案 K）。
 *
 * <p>把原先散落在 {@link PromptBuilder#resolveTagFilter(String)} 的 switch 收敛到一处，
 * 并补齐「显式禁用（deny）」语义。{@code allowTags} 集合<strong>刻意保持与原实现一致</strong>，
 * 仅<strong>新增</strong> {@code denyTags}——这样便于逐字节对照验证降级路径无回归。</p>
 *
 * <p>为什么需要 deny：工具 tag 过滤是 OR 语义（命中任一 tag 即保留）。以 {@code note.create}
 * （tags={@code note, write}）为例，learn 模式放行 {@code note} 后它仍然可见，
 * 而 learn 模式本应「纯检索问答、只读」。deny 语义正是为了补上这个洞。</p>
 */
public enum AgentMode {

    /** 通用对话：全部工具，无限制。 */
    CHAT("chat", Set.of(), Set.of()),

    /** 规划：任务/目标/规划/读写 + kb 读 + 子代理 + MCP。 */
    PLAN("plan",
            Set.of("task", "goal", "planner", "kb", "read", "write", "subagent", "mcp"),
            Set.of()),

    /** 复盘：只读（任务/目标/insight/笔记/kb 读 + 子代理 + MCP），禁写。 */
    REFLECT("reflect",
            Set.of("task", "goal", "insight", "note", "kb", "read", "subagent", "mcp"),
            Set.of("write")),

    /** 学习：纯检索问答（kb/note 读 + 子代理 + MCP），禁写。 */
    LEARN("learn",
            Set.of("kb", "note", "read", "subagent", "mcp"),
            Set.of("write"));

    private final String label;
    private final Set<String> allowTags;
    private final Set<String> denyTags;

    AgentMode(String label, Set<String> allowTags, Set<String> denyTags) {
        this.label = label;
        this.allowTags = allowTags;
        this.denyTags = denyTags;
    }

    public String label() {
        return label;
    }

    public Set<String> allowTags() {
        return allowTags;
    }

    public Set<String> denyTags() {
        return denyTags;
    }

    /** 由 mode 字符串解析，未知回退到 {@link #CHAT}。 */
    public static AgentMode of(String mode) {
        if (mode == null || mode.isBlank()) return CHAT;
        for (AgentMode m : values()) {
            if (m.label.equalsIgnoreCase(mode)) return m;
        }
        return CHAT;
    }

    /** 生成本模式对应的 {@link ToolLayer}（MODE 层）。 */
    public ToolLayer toLayer() {
        return ToolLayer.of(ToolLayer.ScopeKind.MODE, "MODE(" + label + ")", allowTags, denyTags);
    }
}
