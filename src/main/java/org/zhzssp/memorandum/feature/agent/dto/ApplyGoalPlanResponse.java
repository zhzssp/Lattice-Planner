package org.zhzssp.memorandum.feature.agent.dto;

public record ApplyGoalPlanResponse(
        Long goalId,
        int createdTaskCount,
        String message
) {
}
