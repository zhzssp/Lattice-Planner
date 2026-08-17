package org.zhzssp.memorandum.feature.agent.subagent;

import java.util.Set;

/**
 * 子代理角色：每个角色 = 一段专精 system prompt + 一个最小工具 tag 子集 + 独立步数预算。
 *
 * <p>注意：{@code ToolRegistry.exportSchemas} 的 tag 过滤是 OR 语义（命中任一 tag 即保留），
 * 因此这里只声明<strong>领域 tag</strong>（如 task/goal/kb），不放通用的 read/write
 * （否则会命中所有工具，失去隔离意义）。领域内本就同时含读/写工具，足够角色完成职责，
 * 写工具仍各自 requiresConfirm 走确认弹窗。</p>
 *
 * <p><strong>本地文档读取</strong>：早期的 {@code local} tag（Electron LocalBridge 时代的
 * {@code local.read_file} / {@code local.read_pdf}）已随 {@code LocalDocTools} 下线而失效，
 * 该 tag 下现仅剩两个返回 {@code WRITE_DISABLED} 的写工具。需要读本地文档的角色改用
 * {@code mcp} tag，通过 loopback MCP 通道调用 {@code mcp.loopback.local.read_document}。</p>
 */
public enum SubAgentRole {

    /** 规划专家：读素材 -> 拆解 -> 建目标/任务落库。需要 mcp/kb/planner/task/goal 领域。 */
    PLANNER(
            "规划专家",
            Set.of("mcp", "kb", "planner", "task", "goal"),
            6,
            """
            你是 Lattice-Planner 的【规划专家】子代理，在独立上下文中工作。
            职责：把用户素材（本地文档 / 目标描述）拆解为可执行的目标与任务并落库。
            建议路径：必要时 mcp.loopback.local.read_document 读素材（本地 txt/md/pdf/docx/xlsx）->
            planner.draft_goal_plan 拆解 -> goal.create 建目标 ->
            task.create 逐条建任务 -> 必要时 goal.link_task 关联。
            读本地文件只能用 mcp.loopback.local.* 系列工具（read_document / list_dir），
            严禁调用不带 mcp. 前缀的 local.* 工具（已下线，调用必然失败）。
            完成后只用 3~6 行中文总结"建了哪个目标、拆出几个任务、有何重点"，
            不要复述原文、不要输出 JSON、不要罗列每条任务全文。
            """),

    /** 复盘专家：聚合周期数据产出结构化复盘报告。读多个领域。 */
    REFLECTION(
            "复盘专家",
            Set.of("insight", "task", "goal", "note", "kb"),
            6,
            """
            你是 Lattice-Planner 的【复盘专家】子代理，在独立上下文中工作。
            职责：聚合指定周期的分数 / 任务 / 目标 / 笔记，产出结构化复盘报告。
            建议路径：insight.daily_scores / insight.summarize_period 取数 ->
            task.search / goal.list_all 补充进度 ->（必要时）kb.semantic_search 检索过往笔记。
            最终用简洁 Markdown 输出三段：【亮点】【问题】【下一步建议】，不要输出 JSON。
            """),

    /** 检索专家：对一个问题做多跳知识库 + 文档检索综合。只读领域。 */
    RESEARCH(
            "检索专家",
            Set.of("kb", "note", "mcp"),
            6,
            """
            你是 Lattice-Planner 的【检索专家】子代理，在独立上下文中工作。
            职责：围绕给定问题做多跳检索并综合出有出处的答案。
            建议路径：kb.semantic_search 召回 ->（必要时）kb.lookup_by_title /
            kb.list_backlinks / mcp.loopback.local.read_document 深入 -> 综合命中片段作答。
            读本地文件只能用 mcp.loopback.local.* 系列工具（read_document / list_dir），
            严禁调用不带 mcp. 前缀的 local.* 工具（已下线，调用必然失败）。
            引用某篇笔记用 [[标题]] 写法；kb.semantic_search 返回的 _meta 行中
            grade=INCORRECT 或 degraded=true 时，须明示"未找到强相关笔记"。
            最终给出简洁中文答案，不要输出 JSON，不要堆砌原始片段。
            """);

    private final String label;
    private final Set<String> toolTags;
    private final int maxSteps;
    private final String systemPrompt;

    SubAgentRole(String label, Set<String> toolTags, int maxSteps, String systemPrompt) {
        this.label = label;
        this.toolTags = toolTags;
        this.maxSteps = maxSteps;
        this.systemPrompt = systemPrompt;
    }

    /** 角色中文名（用于前端子代理卡片展示）。 */
    public String label() {
        return label;
    }

    public Set<String> toolTags() {
        return toolTags;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
