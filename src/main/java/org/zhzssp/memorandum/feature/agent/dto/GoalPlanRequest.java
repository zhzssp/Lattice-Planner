package org.zhzssp.memorandum.feature.agent.dto;

import java.util.List;

/**
 * Phase 1: 用户提交目标规划请求。
 */
public record GoalPlanRequest(
        String goalStatement,
        List<String> constraints
) {
}
