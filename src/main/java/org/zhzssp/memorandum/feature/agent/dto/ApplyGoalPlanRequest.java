package org.zhzssp.memorandum.feature.agent.dto;

/**
 * 将 AI 生成的规划草案落地为真实 Goal/Task。
 */
public record ApplyGoalPlanRequest(
        GoalPlanResponse plan
) {
}
