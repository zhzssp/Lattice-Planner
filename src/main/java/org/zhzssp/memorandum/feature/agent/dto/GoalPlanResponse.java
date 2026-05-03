package org.zhzssp.memorandum.feature.agent.dto;

import java.util.List;

/**
 * Phase 1 结构化输出。
 */
public record GoalPlanResponse(
        String goalStatement,
        List<String> assumptions,
        List<MilestoneDto> milestones,
        List<TaskNodeDto> tasks,
        List<String> risks,
        List<String> clarifyQuestions,
        Integer revision,
        String source
) {
}
