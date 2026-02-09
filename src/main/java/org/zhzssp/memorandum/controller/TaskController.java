package org.zhzssp.memorandum.controller;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.*;
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
                            @RequestParam(name = "taskView", required = false, defaultValue = "today") String taskView,
                            jakarta.servlet.http.HttpSession session) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        List<Task> allTasks = taskRepository.findByUser(user);

        // 时间层：三种视图模式
        model.addAttribute("taskView", taskView);

        // 今日视图的基础任务集合（PENDING + deadline=今天或未设置）
        List<Task> todayTasks = taskService.getTodayActionableTasks(user);

        if ("slot".equals(taskView)) {
            // 时间段视图：按上午/下午/晚上分组
            List<Task> morningTasks = todayTasks.stream()
                    .filter(t -> t.getPreferredSlot() == TimeSlot.MORNING)
                    .collect(Collectors.toList());
            List<Task> afternoonTasks = todayTasks.stream()
                    .filter(t -> t.getPreferredSlot() == TimeSlot.AFTERNOON)
                    .collect(Collectors.toList());
            List<Task> eveningTasks = todayTasks.stream()
                    .filter(t -> t.getPreferredSlot() == TimeSlot.EVENING)
                    .collect(Collectors.toList());
            List<Task> otherSlotTasks = todayTasks.stream()
                    .filter(t -> t.getPreferredSlot() == null)
                    .collect(Collectors.toList());
            model.addAttribute("morningTasks", morningTasks);
            model.addAttribute("afternoonTasks", afternoonTasks);
            model.addAttribute("eveningTasks", eveningTasks);
            model.addAttribute("otherSlotTasks", otherSlotTasks);
        } else if ("energy".equals(taskView)) {
            // 精力视图：按精力需求分组
            List<Task> highEnergyTasks = todayTasks.stream()
                    .filter(t -> t.getEnergyRequirement() == EnergyLevel.HIGH)
                    .collect(Collectors.toList());
            List<Task> mediumEnergyTasks = todayTasks.stream()
                    .filter(t -> t.getEnergyRequirement() == EnergyLevel.MEDIUM)
                    .collect(Collectors.toList());
            List<Task> lowEnergyTasks = todayTasks.stream()
                    .filter(t -> t.getEnergyRequirement() == EnergyLevel.LOW)
                    .collect(Collectors.toList());
            List<Task> otherEnergyTasks = todayTasks.stream()
                    .filter(t -> t.getEnergyRequirement() == null)
                    .collect(Collectors.toList());
            model.addAttribute("highEnergyTasks", highEnergyTasks);
            model.addAttribute("mediumEnergyTasks", mediumEnergyTasks);
            model.addAttribute("lowEnergyTasks", lowEnergyTasks);
            model.addAttribute("otherEnergyTasks", otherEnergyTasks);
        } else {
            // 今日视图：单列表展示
            model.addAttribute("tasks", todayTasks);
            model.addAttribute("memos", todayTasks); // 兼容模板中的 memos 引用
        }

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
        for (Task t : todayTasks) {
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
                         @RequestParam(required = false) String energyRequirement,
                         @RequestParam(required = false) String mentalLoad,
                         @RequestParam(required = false) String preferredSlot,
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
        if (energyRequirement != null && !energyRequirement.isEmpty()) {
            try {
                task.setEnergyRequirement(EnergyLevel.valueOf(energyRequirement));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (mentalLoad != null && !mentalLoad.isEmpty()) {
            try {
                task.setMentalLoad(MentalLoad.valueOf(mentalLoad));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (preferredSlot != null && !preferredSlot.isEmpty()) {
            try {
                task.setPreferredSlot(TimeSlot.valueOf(preferredSlot));
            } catch (IllegalArgumentException ignored) {
            }
        }
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
