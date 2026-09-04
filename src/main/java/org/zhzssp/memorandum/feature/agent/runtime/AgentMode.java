package org.zhzssp.memorandum.feature.agent.runtime;

import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolLayer;

import java.util.Set;

/**
 * 思维模式及其<strong>默认工具可见性规则</strong>（方案 K）。
 *
 * <p>把原先散落在 {@code PromptBuilder} 里的模式 → tag switch 收敛到一处，
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
    CHAT("chat", Set.of(), Tags.CODEX_FAMILY),

    /**
     * 规划：任务/目标/规划/读写 + kb 读 + 子代理 + MCP。
     *
     * <p><strong>V4 P2 修正</strong>：原先认为「新工具不带旧 tag，所以对旧模式天然不可见」，
     * 这个判断是<strong>错的</strong>。tag 过滤是 OR 语义，而 Codex 工具为了参与
     * {@code write}/{@code read} 的统一治理必须带这两个 tag——
     * {@code repo.sync} 带 {@code write}、{@code doc.search} 带 {@code read}，
     * 于是它们全部命中 PLAN 的 allow 而变得可见，PLAN 的工具 schema 随之改变，
     * 方案 A 的 cassette 会静默失效。</p>
     *
     * <p>修法与 CHAT 一致：<strong>只加 deny，不动 allow</strong>。
     * 因为没有任何 V3 工具携带 {@code Tags.CODEX_FAMILY} 里的 tag
     * （V3 工具的 tag 只有 task/goal/planner/note/kb/insight/subagent/read/write/local/mcp），
     * 加 deny 对旧工具列表是逐字节无影响的。</p>
     */
    PLAN("plan",
            Set.of("task", "goal", "planner", "kb", "read", "write", "subagent", "mcp"),
            Tags.CODEX_FAMILY),

    /**
     * 复盘：只读（任务/目标/insight/笔记/kb 读 + 子代理 + MCP），禁写。
     *
     * <p><strong>补 deny {@code planner}</strong>：allow 集里刻意没有 {@code planner}，
     * 但 {@code planner.draft_goal_plan} 带 {@code read}，靠 {@code read} 就能命中 allow。
     * 见 {@link #LEARN} 上的说明——同一个洞。</p>
     */
    REFLECT("reflect",
            Set.of("task", "goal", "insight", "note", "kb", "read", "subagent", "mcp"),
            Tags.plus("write", "planner")),

    /**
     * 学习：纯检索问答（kb/note 读 + 子代理 + MCP），禁写。
     *
     * <p><strong>★ 真实录制查出的越界（原先 deny 只有 write）。</strong>
     * 症状：learn 模式下模型成功调到了 {@code goal.list} 与
     * {@code planner.draft_goal_plan}，与本模式「纯检索问答」的承诺不符。</p>
     *
     * <h3>根因：{@code read} 是一个横切 tag</h3>
     * tag 过滤是 OR 语义，而 {@code read} 挂在<strong>每个域的每个读工具</strong>上：
     * {@code goal.list}={goal,read}、{@code task.today}={task,read}、
     * {@code insight.daily_scores}={insight,read}、
     * {@code planner.draft_goal_plan}={planner,read}。
     * 于是 allow 里只要有 {@code read}，全系统的读工具就<strong>全部命中</strong>，
     * 除非把每个域逐个 deny 掉。
     *
     * <p>这正是 {@link Tags#CODEX_FAMILY} 注释里点名的失效方式——
     * 「忘记同步 deny 列表」。当时只为 V4 的 Codex 族建了族常量，
     * <strong>V3 的业务域 tag 没做同样的事</strong>，于是 LEARN 漏到今天。
     * {@link #STUDY} 是 V4 才加的，作者当时补对了 {@code task}/{@code goal}，
     * 但那份修正<strong>没有回填到 LEARN</strong>。
     *
     * <h3>为什么 {@code planner} 必须一起 deny</h3>
     * {@code planner.draft_goal_plan} 会起一个子规划器，<strong>一次 5~9 次 LLM 调用</strong>。
     * 在一个「纯检索问答」的模式里放一个随口一问就会花钱的工具，
     * 与 STUDY 当初 deny {@code doc} 的理由逐字相同。
     */
    LEARN("learn",
            Set.of("kb", "note", "read", "subagent", "mcp"),
            Tags.plus("write", "task", "goal", "insight", "planner")),

    /**
     * 研读（V4）：面向知识仓库的纯检索问答。
     *
     * <p>与 {@link #LEARN} 的区别：LEARN 查的是随手写的笔记（{@code kb} / {@code note}），
     * STUDY 还能查 Git 管理的知识仓库（{@code codex}）。禁一切写与执行。</p>
     *
     * <p><strong>V4 P4 补 deny {@code doc}</strong>：P4 的 {@code distill.draft} /
     * {@code exam.draft} 带 {@code read}（它们确实不写任何文件），于是会命中 STUDY 的 allow。
     * 但一次起草是 5~9 次 LLM 调用——在一个「纯研读」的模式里放一个随口一问就会花钱的工具，
     * 与这个模式的承诺不符。{@code doc} tag 只挂在会产出文件的那批工具上
     * （{@code doc.write} / {@code doc.insert_backref} / {@code distill.*} / {@code exam.*}），
     * 检索类的 {@code doc.search} / {@code doc.read} / {@code doc.outline} 不带它，
     * 所以这条 deny 不会影响研读本身。</p>
     */
    STUDY("study",
            Set.of("codex", "kb", "note", "read", "subagent", "mcp"),
            // 补 insight / planner：与 LEARN 同因——两者都只带 {域, read}，
            // 靠 read 命中 allow。原先只 deny 了 task/goal，等于漏了另外两个域。
            Set.of("write", "task", "goal", "insight", "planner", "exec", "doc")),

    /**
     * 策展（V4）：整理知识仓库（挂域、补引用、修死链、开 PR、蒸馏、出题）。
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
     *
     * <p>同样 deny {@code doc}：跑验收时不该顺手起草文件。</p>
     */
    VERIFY("verify",
            Set.of("codex", "checkpoint", "lab", "exec", "read"),
            Set.of("write", "task", "goal", "doc"));

    /**
     * V4 tag 常量持有类。
     *
     * <p>为什么要单独一个嵌套类：Java 禁止枚举常量的构造参数引用<em>本枚举</em>的静态字段
     * （前向引用），放到嵌套类里才能被常量列表引用。</p>
     */
    private static final class Tags {

        /**
         * V4 新增的全部 tag 族。
         *
         * <p>凡不打算暴露 Codex 能力的模式，都应 deny 这一整族而非逐个列举——
         * 将来新增 Codex 工具时只要沿用族内 tag，就不会再次泄漏到旧模式。
         * 「忘记同步 deny 列表」是这类治理最典型的失效方式。</p>
         */
        static final Set<String> CODEX_FAMILY =
                Set.of("codex", "doc", "git", "checkpoint", "lab", "exec");

        /**
         * 安全边界 tag：子代理必须无条件继承的那一类 deny。
         *
         * <p>判据是「绕过它能不能造成父模式明令禁止的<b>副作用</b>」：
         * {@code write} 会落库/写文件，{@code exec} 会在用户机器上跑命令，
         * 二者都能。而域 tag（task/goal/planner/…）绕过后只是多读了点东西，
         * 属于范围问题，不属于安全问题。</p>
         */
        static final Set<String> SAFETY = Set.of("write", "exec");

        /** CODEX_FAMILY 加上若干额外 deny tag。 */
        static Set<String> plus(String... extra) {
            java.util.Set<String> s = new java.util.LinkedHashSet<>(CODEX_FAMILY);
            s.addAll(java.util.List.of(extra));
            return java.util.Collections.unmodifiableSet(s);
        }

        private Tags() {}
    }

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

    /**
     * 子代理从父对话<b>继承</b>的 deny 子集。
     *
     * <h3>为什么不是全量继承</h3>
     * mode 的 deny 其实混着两类语义，它们对「显式委派」的态度正好相反：
     * <ul>
     *   <li><b>安全边界</b>（{@code write} / {@code exec}）——<b>必须</b>继承。
     *       否则「learn 模式不能写，但我委派个子代理就能写了」，
     *       委派本身就成了一条提权路径，K5 这层设计的意义也就没了。</li>
     *   <li><b>范围边界</b>（{@code task}/{@code goal}/{@code insight}/{@code planner} 等域 tag）——
     *       <b>不该</b>继承。它表达的是「本模式的<i>对话</i>不谈这个」，
     *       而不是「这个能力有危险」。用户经 {@code subagent.plan} 显式委派一个
     *       PLANNER 时，正是明确要它去规划；此时再把 {@code planner} 挡掉，
     *       这个角色就成了空壳。</li>
     * </ul>
     *
     * <p>这个区分是补 LEARN 越界时逼出来的：给 LEARN 加上域 deny 之后，
     * 「learn 委派 PLANNER」的既有用例立刻变红，说明<b>两类语义此前被混在一个集合里</b>，
     * 只是以前 LEADN 的 deny 里恰好只有 {@code write}（纯安全边界），才一直没暴露。
     */
    public Set<String> inheritableDenyTags() {
        Set<String> s = new java.util.LinkedHashSet<>(denyTags);
        s.retainAll(Tags.SAFETY);
        return java.util.Collections.unmodifiableSet(s);
    }

    /** 生成供<b>子代理继承</b>的 MODE 层：只带安全边界，不带范围边界。 */
    public ToolLayer toInheritedLayer() {
        return ToolLayer.of(ToolLayer.ScopeKind.MODE, "MODE(" + label + ")继承",
                allowTags, inheritableDenyTags());
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
