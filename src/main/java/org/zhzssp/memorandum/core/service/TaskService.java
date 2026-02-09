package org.zhzssp.memorandum.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.core.event.TaskArchivedEvent;
import org.zhzssp.memorandum.core.event.TaskCompletedEvent;
import org.zhzssp.memorandum.core.event.TaskCreatedEvent;
import org.zhzssp.memorandum.entity.*;
import org.zhzssp.memorandum.mapper.TaskMapper;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 核心任务服务。负责核心业务逻辑，通过事件机制与插件解耦。
 */
@Service
public class TaskService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public List<Task> searchTasks(Long userId, String keyword, LocalDateTime start, LocalDateTime end) {
        return taskMapper.searchTasks(userId, keyword, start, end);
    }

    /**
     * 查找「需要拆分提示」的任务：模糊 + 待办 + 创建时间早于 now - days。
     */
    public List<Task> findFuzzyTasksNeedingSplit(User user, long days) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        return taskRepository.findByUserAndGranularityAndStatusAndCreatedAtBefore(
                user,
                TaskGranularity.FUZZY,
                TaskStatus.PENDING,
                threshold
        );
    }

    /**
     * 今日可行动任务：状态 PENDING，且 deadline 为今天或未设置。
     */
    public List<Task> getTodayActionableTasks(User user) {
        List<Task> all = taskRepository.findByUser(user);
        LocalDate today = LocalDate.now();
        return all.stream()
                .filter(t -> t.getEffectiveStatus() == TaskStatus.PENDING)
                .filter(t -> t.getDeadline() == null || t.getDeadline().toLocalDate().isEqual(today))
                .sorted(taskComparatorBySlotAndEnergy())
                .toList();
    }

    /**
     * 保存任务并发布创建事件。
     */
    public Task saveTask(Task task, User user) {
        task.setUser(user);
        Task saved = taskRepository.save(task);
        eventPublisher.publishEvent(new TaskCreatedEvent(saved, user));
        return saved;
    }

    /**
     * 完成任务并发布完成事件。
     */
    public Task completeTask(Task task, User user) {
        task.setStatus(TaskStatus.DONE);
        Task saved = taskRepository.save(task);
        eventPublisher.publishEvent(new TaskCompletedEvent(saved, user));
        return saved;
    }

    /**
     * 归档任务并发布归档事件。
     */
    public Task archiveTask(Task task, User user) {
        task.setStatus(TaskStatus.ARCHIVED);
        Task saved = taskRepository.save(task);
        eventPublisher.publishEvent(new TaskArchivedEvent(saved, user));
        return saved;
    }

    /**
     * 用于今日视图排序：先按时间段（上午/下午/晚上/未指定），再按精力需求（HIGH/MEDIUM/LOW）。
     */
    private Comparator<Task> taskComparatorBySlotAndEnergy() {
        return Comparator
                .comparing((Task t) -> slotOrder(t.getPreferredSlot()))
                .thenComparing(t -> energyOrder(t.getEnergyRequirement()));
    }

    private int slotOrder(TimeSlot slot) {
        if (slot == null) return 3;
        return switch (slot) {
            case MORNING -> 0;
            case AFTERNOON -> 1;
            case EVENING -> 2;
        };
    }

    private int energyOrder(EnergyLevel level) {
        if (level == null) return 3;
        return switch (level) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }
}
