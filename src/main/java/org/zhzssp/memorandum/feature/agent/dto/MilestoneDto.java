package org.zhzssp.memorandum.feature.agent.dto;

import java.util.List;

public record MilestoneDto(
        String id,
        String name,
        String dueDate,
        List<String> taskIds
) {
}
