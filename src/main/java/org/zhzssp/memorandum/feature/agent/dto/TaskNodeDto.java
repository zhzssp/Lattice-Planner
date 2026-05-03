package org.zhzssp.memorandum.feature.agent.dto;

import java.util.List;

public record TaskNodeDto(
        String id,
        String title,
        String description,
        String parentId,
        List<String> dependsOn,
        String priority,
        Integer estimateHours,
        List<String> acceptanceCriteria
) {
}
