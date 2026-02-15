package org.zhzssp.memorandum.feature.goal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** D3 树状图节点：目标与任务层级，用于 JSON API */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreeNodeDto(
    String name,
    String type,
    Long id,
    String goalType,
    String deadline,
    List<TreeNodeDto> children
) {}
