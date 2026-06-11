package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.GoalType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.service.GoalService;

import java.util.List;

/**
 * 目标相关工具。
 */
@Component
public class GoalTools {

    private final GoalService goalService;

    public GoalTools(GoalService goalService) {
        this.goalService = goalService;
    }

    @AgentTool(name = "goal.list", tags = {"goal", "read"},
            description = "列出当前用户的活跃（未归档）目标。无参数。")
    public List<GoalView> list() {
        return goalService.findActiveGoalsByUser(AgentContext.requireUser())
                .stream().map(GoalView::of).toList();
    }

    @AgentTool(name = "goal.list_all", tags = {"goal", "read"},
            description = "列出当前用户的全部目标（含已归档）。无参数。")
    public List<GoalView> listAll() {
        return goalService.findGoalsByUser(AgentContext.requireUser())
                .stream().map(GoalView::of).toList();
    }

    @AgentTool(name = "goal.create", tags = {"goal", "write"}, requiresConfirm = true,
            description = "新建目标。goalType ∈ LONG_TERM / SHORT_TERM / TEMPORARY，可空（默认 SHORT_TERM）。")
    public GoalView create(
            @ToolParam(value = "name", desc = "目标名称", required = true) String name,
            @ToolParam(value = "goalType", desc = "LONG_TERM / SHORT_TERM / TEMPORARY，可空") String goalType
    ) {
        User user = AgentContext.requireUser();
        Goal g = new Goal();
        g.setUser(user);
        g.setName(name);
        try {
            g.setGoalType(goalType == null || goalType.isBlank()
                    ? GoalType.SHORT_TERM
                    : GoalType.valueOf(goalType.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            g.setGoalType(GoalType.SHORT_TERM);
        }
        return GoalView.of(goalService.save(g));
    }

    @AgentTool(name = "goal.archive", tags = {"goal", "write"}, requiresConfirm = true,
            description = "归档指定 id 的目标，并把所有挂在该目标下的任务一并设为 ARCHIVED。需用户确认。")
    public String archive(
            @ToolParam(value = "id", desc = "目标 id（来自 goal.list）", required = true) Long id
    ) {
        goalService.archive(id, AgentContext.requireUser());
        return "ok";
    }

    @AgentTool(name = "goal.link_task", tags = {"goal", "write"}, requiresConfirm = true,
            description = "把任务关联到一个或多个目标（替换原有关联）。需用户确认。")
    public String linkTask(
            @ToolParam(value = "taskId", desc = "任务 id", required = true) Long taskId,
            @ToolParam(value = "goalIds", desc = "目标 id 数组，例如 [1,2]", required = true) List<Long> goalIds
    ) {
        goalService.linkTaskToGoals(taskId, goalIds, AgentContext.requireUser());
        return "ok";
    }

    public record GoalView(Long id, String name, String goalType, String archivedAt, String createdAt) {
        static GoalView of(Goal g) {
            return new GoalView(
                    g.getId(), g.getName(),
                    g.getGoalType() == null ? null : g.getGoalType().name(),
                    g.getArchivedAt() == null ? null : g.getArchivedAt().toLocalDate().toString(),
                    g.getCreatedAt() == null ? null : g.getCreatedAt().toLocalDate().toString()
            );
        }
    }
}
