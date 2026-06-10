package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.dto.ApplyGoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanRequest;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.service.AgentPlanApplyService;
import org.zhzssp.memorandum.feature.agent.service.PlannerAgentService;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;

import java.util.List;

/**
 * 规划相关工具：把已有的"目标 -> 拆解 -> 落库"链路包装成 Agent 可调用的两个工具。
 *
 * draft_goal_plan：纯只读，给出任务树草稿
 * apply_goal_plan：写库，需用户确认；落库后自动触发 TaskCreatedEvent -> GoalEventListener 联动
 */
@Component
public class PlannerTools {

    private final PlannerAgentService plannerAgentService;
    private final AgentPlanApplyService agentPlanApplyService;

    public PlannerTools(PlannerAgentService plannerAgentService,
                        AgentPlanApplyService agentPlanApplyService) {
        this.plannerAgentService = plannerAgentService;
        this.agentPlanApplyService = agentPlanApplyService;
    }

    @AgentTool(name = "planner.draft_goal_plan", tags = {"planner", "read"},
            description = "把一段目标陈述拆解为里程碑 + 任务树（不落库）。constraints 可空字符串数组。")
    public GoalPlanResponse draft(
            @ToolParam(value = "goalStatement", desc = "目标陈述", required = true) String goalStatement,
            @ToolParam(value = "constraints", desc = "约束数组（每周时间/截止/技术栈等），可空") List<String> constraints
    ) {
        return plannerAgentService.draftPlan(new GoalPlanRequest(goalStatement, constraints));
    }

    @AgentTool(name = "planner.apply_goal_plan", tags = {"planner", "write"}, requiresConfirm = true,
            description = "把 draft_goal_plan 返回的 GoalPlanResponse 落库为目标 + 任务 + 关联。需用户确认。")
    public ApplyGoalPlanResponse apply(
            @ToolParam(value = "plan", desc = "draft_goal_plan 返回的整个 JSON 对象", required = true) GoalPlanResponse plan
    ) {
        User user = AgentContext.requireUser();
        return agentPlanApplyService.apply(user, plan);
    }
}
