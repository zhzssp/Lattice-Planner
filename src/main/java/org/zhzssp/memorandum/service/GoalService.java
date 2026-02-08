package org.zhzssp.memorandum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Goal;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.GoalRepository;
import org.zhzssp.memorandum.repository.LinkRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目标服务。Task↔Goal 多对多弱关联通过 Link 表实现。
 */
@Service
public class GoalService {

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private LinkRepository linkRepository;

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
            if (g.getUser().getId().equals(user.getId())) {
                g.setArchivedAt(LocalDateTime.now());
                goalRepository.save(g);
            }
        });
    }
}
