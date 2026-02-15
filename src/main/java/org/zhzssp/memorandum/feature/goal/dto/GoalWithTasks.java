package org.zhzssp.memorandum.feature.goal.dto;

import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.feature.goal.entity.Goal;

import java.util.List;

/** 用于树状展示：一个目标及其关联的任务（仅含有关联关系的） */
public record GoalWithTasks(Goal goal, List<Task> tasks) {}
