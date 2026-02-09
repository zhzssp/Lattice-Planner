package org.zhzssp.memorandum.controller;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.Goal;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskGranularity;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.TaskRepository;
import org.zhzssp.memorandum.repository.UserRepository;
import org.zhzssp.memorandum.service.GoalService;
import org.zhzssp.memorandum.service.TaskService;

import java.util.Map;
import java.util.HashMap;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务相关操作。统一使用 Task 概念，URL 保留 /memo/* 以兼容现有书签。
 */
@Controller
public class TaskController {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GoalService goalService;

    @GetMapping("/dashboard")
    public String dashboard(@NotNull Model model,
                            Principal principal,
                            jakarta.servlet.http.HttpSession session) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        List<Task> allTasks = taskRepository.findByUser(user);

        // 默认视图只显示可行动项（PENDING）
        List<Task> actionableTasks = allTasks.stream()
                .filter(t -> t.getEffectiveStatus() == TaskStatus.PENDING)
                .collect(Collectors.toList());
        model.addAttribute("tasks", actionableTasks);
        model.addAttribute("memos", actionableTasks); // 兼容模板中的 memos 引用

        String mode = FeatureSelectionController.getSelectedFeature(session);
        // 兼容旧 session：memos/dueDates 视为 tasks
        if ("memos".equals(mode) || "dueDates".equals(mode)) {
            mode = "tasks";
        }
        model.addAttribute("mode", mode);

        // 即将到期（3 天内）
        List<Task> dueSoon = allTasks.stream()
                .filter(t -> t.getDeadline() != null
                        && java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), t.getDeadline()) <= 3
                        && t.getEffectiveStatus() == TaskStatus.PENDING)
                .collect(Collectors.toList());
        model.addAttribute("dueSoonTasks", dueSoon);
        model.addAttribute("dueSoonMemos", dueSoon); // 兼容模板

        // 任务→目标弱关联（用于展示标签）
        Map<Long, List<Goal>> taskToGoals = new HashMap<>();
        for (Task t : actionableTasks) {
            taskToGoals.put(t.getId(), goalService.findGoalsByTaskId(t.getId(), user));
        }
        model.addAttribute("taskToGoals", taskToGoals);
        model.addAttribute("goals", goalService.findActiveGoalsByUser(user));

        // 模糊任务 N 天存在提示（例如 5 天）
        List<Task> fuzzyNeedSplit = taskService.findFuzzyTasksNeedingSplit(user, 5);
        model.addAttribute("fuzzyNeedSplit", fuzzyNeedSplit);

        // 已完成 / 归档 / 搁置任务，折叠展示
        List<Task> archivedOrShelved = allTasks.stream()
                .filter(t -> t.getEffectiveStatus() != TaskStatus.PENDING)
                .collect(Collectors.toList());
        model.addAttribute("archivedOrShelved", archivedOrShelved);

        return "dashboard";
    }

    @GetMapping("/memo/search")
    public String searchTasks(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false, name = "start") String startDate,
                             @RequestParam(required = false, name = "end") String endDate,
                             Principal principal,
                             Model model,
                             jakarta.servlet.http.HttpSession session) {
        if (!StringUtils.hasText(keyword) && !StringUtils.hasText(startDate) && !StringUtils.hasText(endDate)) {
            return "redirect:/dashboard";
        }

        User user = userRepository.findByUsername(principal.getName()).orElseThrow();

        LocalDateTime start = null;
        LocalDateTime end = null;
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        try {
            if (StringUtils.hasText(startDate)) {
                start = LocalDate.parse(startDate, dateFmt).atStartOfDay();
            }
            if (StringUtils.hasText(endDate)) {
                end = LocalDate.parse(endDate, dateFmt).atTime(23, 59, 59);
            }
        } catch (Exception e) {
            // ignore
        }

        List<Task> tasks = taskService.searchTasks(user.getId(), keyword, start, end);
        model.addAttribute("tasks", tasks);
        model.addAttribute("memos", tasks);
        model.addAttribute("mode", "tasks");

        Map<Long, List<Goal>> taskToGoals = new HashMap<>();
        for (Task t : tasks) {
            taskToGoals.put(t.getId(), goalService.findGoalsByTaskId(t.getId(), user));
        }
        model.addAttribute("taskToGoals", taskToGoals);
        model.addAttribute("goals", goalService.findActiveGoalsByUser(user));

        model.addAttribute("dueSoonTasks", List.of());
        model.addAttribute("dueSoonMemos", List.of());
        return "dashboard";
    }

    @GetMapping("/memo/add")
    public String addTaskForm(Principal principal, Model model) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        model.addAttribute("goals", goalService.findActiveGoalsByUser(user));
        return "addMemo";
    }

    @PostMapping("/memo/add")
    public String addTask(@RequestParam String title,
                         @RequestParam String description,
                         @RequestParam String deadline,
                         @RequestParam(required = false) String granularity,
                         @RequestParam(required = false) Integer estimatedMinutes,
                         @RequestParam(required = false) List<Long> goalIds,
                         Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        Task task = new Task();
        task.setTitle(title);
        task.setDescription(description);
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd'T'HH:mm")
                .optionalStart()
                .appendPattern(":ss")
                .optionalEnd()
                .toFormatter();
        task.setDeadline(LocalDateTime.parse(deadline, formatter));
        if (granularity != null && !granularity.isEmpty()) {
            try {
                task.setGranularity(TaskGranularity.valueOf(granularity));
            } catch (IllegalArgumentException ignored) {
            }
        }
        task.setEstimatedMinutes(estimatedMinutes);
        task.setStatus(TaskStatus.PENDING);
        task.setUser(user);
        taskRepository.save(task);
        goalService.linkTaskToGoals(task.getId(), goalIds != null ? goalIds : List.of(), user);
        return "redirect:/dashboard";
    }

    @GetMapping("/due-dates")
    @ResponseBody
    public List<Task> viewDueDates(Principal principal) {
        if (principal == null) {
            return List.of();
        }
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        return taskRepository.findByUser(user);
    }

    @DeleteMapping("/memo/delete/{id}")
    @ResponseBody
    public String deleteTask(@PathVariable Long id, Principal principal) {
        try {
            User user = userRepository.findByUsername(principal.getName()).orElseThrow();
            Task task = taskRepository.findById(id).orElseThrow();
            if (!task.getUser().getId().equals(user.getId())) {
                return "error:unauthorized";
            }
            taskRepository.delete(task);
            return "success";
        } catch (Exception e) {
            return "error:failed";
        }
    }

    /** 完成任务（静默，不惩罚） */
    @PostMapping("/memo/complete/{id}")
    @ResponseBody
    public String completeTask(@PathVariable Long id, Principal principal) {
        try {
            User user = userRepository.findByUsername(principal.getName()).orElseThrow();
            Task task = taskRepository.findById(id).orElseThrow();
            if (!task.getUser().getId().equals(user.getId())) {
                return "error:unauthorized";
            }
            task.setStatus(TaskStatus.DONE);
            taskRepository.save(task);
            return "success";
        } catch (Exception e) {
            return "error:failed";
        }
    }

    /** 搁置任务（静默搁置，不显示在默认视图） */
    @PostMapping("/memo/shelve/{id}")
    @ResponseBody
    public String shelveTask(@PathVariable Long id, Principal principal) {
        try {
            User user = userRepository.findByUsername(principal.getName()).orElseThrow();
            Task task = taskRepository.findById(id).orElseThrow();
            if (!task.getUser().getId().equals(user.getId())) {
                return "error:unauthorized";
            }
            task.setStatus(TaskStatus.SHELVED);
            task.setShelvedAt(LocalDateTime.now());
            taskRepository.save(task);
            return "success";
        } catch (Exception e) {
            return "error:failed";
        }
    }
}
