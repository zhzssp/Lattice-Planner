package org.zhzssp.memorandum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.*;
import org.zhzssp.memorandum.mapper.TaskMapper;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class TaskService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskRepository taskRepository;

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
