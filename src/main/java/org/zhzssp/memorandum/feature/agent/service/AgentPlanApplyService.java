package org.zhzssp.memorandum.feature.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.entity.GoalType;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.dto.ApplyGoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.dto.TaskNodeDto;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.service.GoalService;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentPlanApplyService {

    private final GoalService goalService;
    private final TaskRepository taskRepository;
    private final LinkRepository linkRepository;

    public AgentPlanApplyService(GoalService goalService,
                                 TaskRepository taskRepository,
                                 LinkRepository linkRepository) {
        this.goalService = goalService;
        this.taskRepository = taskRepository;
        this.linkRepository = linkRepository;
    }

    @Transactional
    public ApplyGoalPlanResponse apply(User user, GoalPlanResponse plan) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (plan == null || plan.goalStatement() == null || plan.goalStatement().isBlank()) {
            throw new IllegalArgumentException("plan.goalStatement 不能为空");
        }
        if (plan.tasks() == null || plan.tasks().isEmpty()) {
            throw new IllegalArgumentException("没有可落地的任务，请先生成任务树");
        }

        Goal goal = new Goal();
        goal.setUser(user);
        goal.setName(plan.goalStatement().trim());
        goal.setGoalType(GoalType.SHORT_TERM);
        Goal savedGoal = goalService.save(goal);

        List<Task> createdTasks = new ArrayList<>();
        for (TaskNodeDto node : plan.tasks()) {
            if (node == null || node.title() == null || node.title().isBlank()) {
                continue;
            }
            Task task = new Task();
            task.setUser(user);
            task.setTitle(node.title().trim());
            task.setDescription(node.description());
            task.setStatus(TaskStatus.PENDING);
            Integer estimateHours = node.estimateHours();
            if (estimateHours != null && estimateHours > 0) {
                task.setEstimatedMinutes(estimateHours * 60);
            }
            createdTasks.add(task);
        }

        List<Task> savedTasks = taskRepository.saveAll(createdTasks);

        for (Task task : savedTasks) {
            Link link = new Link();
            link.setSourceType(Link.LinkSourceType.TASK);
            link.setSourceId(task.getId());
            link.setTargetType(Link.LinkTargetType.GOAL);
            link.setTargetId(savedGoal.getId());
            linkRepository.save(link);
        }

        return new ApplyGoalPlanResponse(
                savedGoal.getId(),
                savedTasks.size(),
                "已创建目标与任务，刷新后可在目标与树视图查看"
        );
    }
}
