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

    /**
     * 通用对话：全部工具，无限制。
     *
     * <p><strong>V4 新增 denyTags 的技术原因</strong>：CHAT 的 {@code allowTags} 为空集
     * 表示「不收窄」，因此新增的 Codex 工具会自动出现在它的工具列表里。
     * 这会改变 {@code exportSchemas} 的输出字节，从而让方案 A 的评测录制
     * （cassette 按 messages_hash 命中）全部失效——那是本项目最有价值的工程资产。</p>
     *
     * <p>用 deny 显式排除后，CHAT 的 schema 与 V3 逐字节一致。
     * 这也是产品上正确的：知识仓库操作应在专用模式下进行，避免通用对话里误触。</p>
     */
    CHAT("chat", Set.of(), Set.of("codex", "exec", "checkpoint")),

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
            Set.of("write")),

    /**
     * 研读（V4）：面向知识仓库的纯检索问答。
     *
     * <p>与 {@link #LEARN} 的区别：LEARN 查的是随手写的笔记（{@code kb} / {@code note}），
     * STUDY 还能查 Git 管理的知识仓库（{@code codex}）。禁一切写与执行。</p>
     */
    STUDY("study",
            Set.of("codex", "kb", "note", "read", "subagent", "mcp"),
            Set.of("write", "task", "goal", "exec")),

    /**
     * 策展（V4）：整理知识仓库（挂域、补引用、修死链、开 PR）。
     *
     * <p>刻意 deny {@code task} / {@code goal}：整理知识时不该动任务体系，
     * 避免「让它整理笔记，结果顺手改了我的任务」这类越界。</p>
     */
    CURATE("curate",
            Set.of("codex", "kb", "read", "write", "subagent"),
            Set.of("task", "goal", "insight", "exec")),

    /**
     * 验证（V4）：跑知识落地检验（checkpoint）。
     *
     * <p><strong>这是唯一开放受限执行（{@code exec} tag）的模式。</strong>
     * 把「能执行命令」收窄到单一模式，是权限治理的正确做法——
     * 配合方案 K 的执行层强制，其余模式下调用 exec 类工具会被短路拦截，
     * 而不是仅仅从 prompt 里隐藏（提示层约束模型完全可能无视）。</p>
     */
    VERIFY("verify",
            Set.of("codex", "checkpoint", "lab", "exec", "read"),
            Set.of("write", "task", "goal"));

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
