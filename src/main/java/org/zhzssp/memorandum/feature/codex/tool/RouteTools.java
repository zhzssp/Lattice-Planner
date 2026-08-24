package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.route.RouteService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定线工具（P4）。
 *
 * <h3>为什么它是只读的、而且没有「生成学习计划」这个动作</h3>
 * <p>把定线结果一键变成目标/任务很诱人，但 P3 已经有 {@code gap.to_learning_plan} 了。
 * 再加一条产生目标的路径，会出现两个来源不同、内容重叠的任务列表——
 * 而<strong>两个待办列表里必然有一个被遗忘</strong>，被遗忘的那个会让人不再相信任何一个。</p>
 *
 * <p>所以这里只回答「现在该干什么」，要落成任务仍走既有的目标体系。</p>
 */
@Component
public class RouteTools {

    private static final int MAX_ACTIONS = 8;

    private final RouteService route;

    public RouteTools(RouteService route) {
        this.route = route;
    }

    @AgentTool(name = "route.next", tags = {"codex", "read"},
            description = "回答「我现在该干什么」：按紧急度给出下一步行动，每条都附依据。"
                    + "用户问「接下来学什么/我该做什么/现在进度如何」时调用。"
                    + "★结果是对库里已有记录的确定性计算，不含推测——"
                    + "所以转述时要连「依据」一起说，不要只说结论，也不要自己补充别的建议。")
    public Map<String, Object> next(
            @ToolParam(value = "repoId", desc = "仓库 id；省略则用第一个启用的仓库") Long repoId
    ) {
        User u = AgentContext.requireUser();
        RouteService.Route r = route.compute(u.getId(), repoId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", r.repoName());
        m.put("summary", r.summary());

        List<Map<String, Object>> acts = new ArrayList<>();
        for (RouteService.Action a : r.actions().stream().limit(MAX_ACTIONS).toList()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", a.kind());
            x.put("what", a.what());
            x.put("why", a.why());
            x.put("ref", a.ref());
            acts.add(x);
        }
        m.put("actions", acts);
        if (r.actions().size() > MAX_ACTIONS) {
            m.put("_truncated", "共 " + r.actions().size() + " 条，只返回最紧急的 "
                    + MAX_ACTIONS + " 条。");
        }
        // caveats 不是免责声明而是口径说明，必须让模型看到并转述
        m.put("_caveats", r.caveats());
        return m;
    }

    @AgentTool(name = "route.stages", tags = {"codex", "read"},
            description = "读→做→验阶段表：每个主题有没有配套 lab、有多少条检验、通过了几条。"
                    + "用户问「我的知识体系哪里是空的/哪些学了没验」时调用。"
                    + "★labExists=false 表示没有动手项目，那个主题现在无法出可执行的题，"
                    + "这一点必须说明，否则用户会以为是软件不肯出题。")
    public Map<String, Object> stages(
            @ToolParam(value = "repoId", desc = "仓库 id") Long repoId
    ) {
        User u = AgentContext.requireUser();
        RouteService.Route r = route.compute(u.getId(), repoId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteService.Stage s : r.stages()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("topic", s.topic());
            x.put("guide", s.guidePath());
            x.put("maturity", s.maturity());
            x.put("lab", s.labDir());
            x.put("labExists", s.labExists());
            x.put("checkpoints", s.checkpointTotal());
            x.put("passed", s.passed());
            x.put("failed", s.failed());
            x.put("todo", s.todo());
            x.put("l2Passed", s.l2Passed());
            x.put("hasAgentDraftedItems", s.agentDrafted());
            rows.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", r.repoName());
        m.put("summary", r.summary());
        m.put("stages", rows);
        m.put("_note", "l2Passed 是主判据：L2（新增组件并补测试）过了才算掌握，"
                + "L0 只是跑通既有脚本。汇报进度时不要把 L0 的通过数说成掌握程度。");
        m.put("_caveats", r.caveats());
        return m;
    }
}
