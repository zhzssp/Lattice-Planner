package org.zhzssp.memorandum.feature.goal.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.repository.GoalRepository;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 目标服务（插件层）。Task↔Goal 多对多弱关联通过 Link 表实现。
 */
@Service
public class GoalService {

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private TaskRepository taskRepository;

    public List<Goal> findActiveGoalsByUser(User user) {
        return goalRepository.findByUserAndArchivedAtIsNull(user);
    }

    public List<Goal> findGoalsByUser(User user) {
        return goalRepository.findByUser(user);
    }

    /** 获取任务关联的目标 ID 列表 */
    public List<Long> findGoalIdsByTaskId(Long taskId) {
        return linkRepository.findBySourceTypeAndSourceId(Link.LinkSourceType.TASK, taskId)
                .stream()
                .filter(l -> l.getTargetType() == Link.LinkTargetType.GOAL)
                .map(Link::getTargetId)
                .collect(Collectors.toList());
    }

    /** 获取任务关联的目标（需校验 goal 属于当前用户） */
    public List<Goal> findGoalsByTaskId(Long taskId, User user) {
        List<Long> goalIds = findGoalIdsByTaskId(taskId);
        if (goalIds.isEmpty()) return List.of();
        return goalRepository.findAllById(goalIds).stream()
                .filter(g -> g.getUser().getId().equals(user.getId()))
                .collect(Collectors.toList());
    }

    /** 绑定任务到目标（替换原有绑定） */
    public void linkTaskToGoals(Long taskId, List<Long> goalIds, User user) {
        // 删除原有 TASK->GOAL 链接
        List<Link> existing = linkRepository.findBySourceTypeAndSourceId(Link.LinkSourceType.TASK, taskId);
        existing.stream()
                .filter(l -> l.getTargetType() == Link.LinkTargetType.GOAL)
                .forEach(linkRepository::delete);

        // 校验 goal 归属后创建新链接
        if (goalIds != null && !goalIds.isEmpty()) {
            List<Goal> goals = goalRepository.findAllById(goalIds);
            for (Goal g : goals) {
                if (g.getUser().getId().equals(user.getId())) {
                    Link link = new Link();
                    link.setSourceType(Link.LinkSourceType.TASK);
                    link.setSourceId(taskId);
                    link.setTargetType(Link.LinkTargetType.GOAL);
                    link.setTargetId(g.getId());
                    linkRepository.save(link);
                }
            }
        }
    }

    public Goal save(Goal goal) {
        return goalRepository.save(goal);
    }

    public void archive(Long goalId, User user) {
        goalRepository.findById(goalId).ifPresent(g -> {
            if (!g.getUser().getId().equals(user.getId())) {
                return;
            }
            // 1) 归档目标本身
            g.setArchivedAt(LocalDateTime.now());
            goalRepository.save(g);

            // 2) 找到所有指向该目标的 TASK→GOAL 链接
            List<Link> links = linkRepository.findByTargetTypeAndTargetId(Link.LinkTargetType.GOAL, goalId)
                    .stream()
                    .filter(l -> l.getSourceType() == Link.LinkSourceType.TASK)
                    .collect(Collectors.toList());

            if (links.isEmpty()) {
                return;
            }

            Set<Long> taskIds = links.stream()
                    .map(Link::getSourceId)
                    .collect(Collectors.toSet());

            // 3) 将这些任务全部设为 ARCHIVED（软归档）
            List<Task> tasks = taskRepository.findAllById(taskIds);
            for (Task t : tasks) {
                if (t.getUser() != null && t.getUser().getId().equals(user.getId())) {
                    t.setStatus(TaskStatus.ARCHIVED);
                }
            }
            taskRepository.saveAll(tasks);
        });
    }

    /**
     * 删除目标及其关联：
     * - mode = "deleteTasks": 删除目标 + 相关链接 + 相关任务
     * - mode = "keepTasks" : 删除目标 + 相关链接，保留任务
     */
    public void deleteGoalWithMode(Long goalId, User user, String mode) {
        goalRepository.findById(goalId).ifPresent(g -> {
            if (!g.getUser().getId().equals(user.getId())) {
                return;
            }

            // 找出所有指向该目标的链接（主要是 TASK→GOAL）
            List<Link> links = linkRepository.findByTargetTypeAndTargetId(Link.LinkTargetType.GOAL, goalId);
            Set<Long> taskIds = links.stream()
                    .filter(l -> l.getSourceType() == Link.LinkSourceType.TASK)
                    .map(Link::getSourceId)
                    .collect(Collectors.toSet());

            if ("deleteTasks".equalsIgnoreCase(mode) && !taskIds.isEmpty()) {
                List<Task> tasks = taskRepository.findAllById(taskIds);
                // 仅删除当前用户的任务
                List<Task> ownedTasks = tasks.stream()
                        .filter(t -> t.getUser() != null && t.getUser().getId().equals(user.getId()))
                        .collect(Collectors.toList());
                taskRepository.deleteAll(ownedTasks);
            }

            // 删除与该目标相关的所有链接（无论是否删除任务）
            linkRepository.deleteAll(links);

            // 最后删除目标本身
            goalRepository.delete(g);
        });
    }
}
