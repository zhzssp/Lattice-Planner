package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.entity.EnergyLevel;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.TimeSlot;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务相关工具：所有写操作都走 TaskService，自动触发对应事件 -> GoalEventListener 自动联动。
 */
@Component
public class TaskTools {

    private final TaskService taskService;
    private final TaskRepository taskRepository;

    public TaskTools(TaskService taskService, TaskRepository taskRepository) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
    }

    @AgentTool(name = "task.create", tags = {"task", "write"},
            description = "创建一个待办任务（PENDING）。deadline/energy/preferredSlot 都可空。")
    public TaskView create(
            @ToolParam(value = "title", desc = "任务标题", required = true) String title,
            @ToolParam(value = "description", desc = "任务备注，可空") String description,
            @ToolParam(value = "deadline", desc = "yyyy-MM-dd 截止日期，可空") String deadline,
            @ToolParam(value = "energy", desc = "精力 LOW/MEDIUM/HIGH，可空") String energy,
            @ToolParam(value = "preferredSlot", desc = "MORNING/AFTERNOON/EVENING，可空") String slot
    ) {
        User user = AgentContext.requireUser();
        Task t = new Task();
        t.setTitle(title);
        t.setDescription(description);
        t.setStatus(TaskStatus.PENDING);
        if (deadline != null && !deadline.isBlank()) {
            t.setDeadline(LocalDate.parse(deadline).atStartOfDay());
        }
        if (energy != null && !energy.isBlank()) {
            t.setEnergyRequirement(EnergyLevel.valueOf(energy.trim().toUpperCase()));
        }
        if (slot != null && !slot.isBlank()) {
            t.setPreferredSlot(TimeSlot.valueOf(slot.trim().toUpperCase()));
        }
        return TaskView.of(taskService.saveTask(t, user));
    }

    @AgentTool(name = "task.search", tags = {"task", "read"},
            description = "按关键字 + 截止日期范围查询当前用户任务。任意参数可空。")
    public List<TaskView> search(
            @ToolParam(value = "keyword", desc = "标题关键字，可空") String keyword,
            @ToolParam(value = "from", desc = "yyyy-MM-dd 起始截止日期，可空") String from,
            @ToolParam(value = "to", desc = "yyyy-MM-dd 终止截止日期，可空") String to
    ) {
        User user = AgentContext.requireUser();
        LocalDateTime s = (from == null || from.isBlank()) ? null : LocalDate.parse(from).atStartOfDay();
        LocalDateTime e = (to == null || to.isBlank()) ? null : LocalDate.parse(to).atTime(23, 59, 59);
        return taskService.searchTasks(user.getId(), keyword, s, e).stream()
                .map(TaskView::of).toList();
    }

    @AgentTool(name = "task.today", tags = {"task", "read"},
            description = "查询今日可行动任务（PENDING 且 deadline 是今天或未设置）。无参数。")
    public List<TaskView> today() {
        return taskService.getTodayActionableTasks(AgentContext.requireUser())
                .stream().map(TaskView::of).toList();
    }

    @AgentTool(name = "task.fuzzy_pending", tags = {"task", "read"},
            description = "列出粒度为 FUZZY、状态 PENDING、且创建时间早于 N 天前的任务（建议拆分）。")
    public List<TaskView> fuzzyPending(
            @ToolParam(value = "days", desc = "存在天数下限，缺省 7") Integer days
    ) {
        long d = (days == null || days <= 0) ? 7L : days.longValue();
        return taskService.findFuzzyTasksNeedingSplit(AgentContext.requireUser(), d)
                .stream().map(TaskView::of).toList();
    }

    @AgentTool(name = "task.complete", tags = {"task", "write"}, requiresConfirm = true,
            description = "把指定 id 的任务标记为完成（DONE）。需用户确认。")
    public TaskView complete(
            @ToolParam(value = "id", desc = "任务 id（来自 task.search 等读工具）", required = true) Long id
    ) {
        User user = AgentContext.requireUser();
        Task t = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在 id=" + id));
        if (t.getUser() == null || !t.getUser().getId().equals(user.getId())) {
            throw new SecurityException("非当前用户任务");
        }
        return TaskView.of(taskService.completeTask(t, user));
    }

    @AgentTool(name = "task.archive", tags = {"task", "write"}, requiresConfirm = true,
            description = "归档指定 id 的任务（ARCHIVED）。需用户确认。")
    public TaskView archive(
            @ToolParam(value = "id", desc = "任务 id", required = true) Long id
    ) {
        User user = AgentContext.requireUser();
        Task t = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在 id=" + id));
        if (t.getUser() == null || !t.getUser().getId().equals(user.getId())) {
            throw new SecurityException("非当前用户任务");
        }
        return TaskView.of(taskService.archiveTask(t, user));
    }

    /** 给 LLM 序列化用的瘦视图，避免回传完整 User 等复杂对象 */
    public record TaskView(
            Long id, String title, String description, String status,
            String deadline, String energy, String slot, String createdAt
    ) {
        public static TaskView of(Task t) {
            return new TaskView(
                    t.getId(),
                    t.getTitle(),
                    t.getDescription(),
                    t.getEffectiveStatus().name(),
                    t.getDeadline() == null ? null : t.getDeadline().toLocalDate().toString(),
                    t.getEnergyRequirement() == null ? null : t.getEnergyRequirement().name(),
                    t.getPreferredSlot() == null ? null : t.getPreferredSlot().name(),
                    t.getCreatedAt() == null ? null : t.getCreatedAt().toLocalDate().toString()
            );
        }
    }
}
