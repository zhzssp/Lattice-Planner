package org.zhzssp.memorandum.feature.insight.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.EnergyLevel;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.MentalLoad;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.repository.GoalRepository;
import org.zhzssp.memorandum.repository.LinkRepository;
import org.zhzssp.memorandum.repository.NoteRepository;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * 规划完成度评分服务（Insight 插件）。
 *
 * 按天计算用户的「规划完成得分」，用于绘制一段时间内的得分曲线：
 * - 任务维度（0~70）：不仅看完成率，也看完成数量（吞吐）并对「心理负担/精力需求/周期长度」做加权
 * - 目标维度（0~20）：考虑目标整体进度、当天是否完整完成了目标、以及当天对目标的推进覆盖面
 * - 笔记维度（0~10）：保留记录激励，但采用饱和曲线避免“刷分”
 *
 * 总分范围：0 ~ 100。
 */
@Service
public class InsightScoreService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private GoalRepository goalRepository;

    public static class DailyScore {
        private LocalDate date;
        private int plannedTasks;
        private int completedTasks;
        private int noteCount;
        private double taskCompletionRate;
        private double noteFactor;
        private int totalScore;

        // 额外指标（用于未来扩展/调试；前端不依赖这些字段）
        private double weightedTaskCompletionRate;
        private int activeGoalCount;
        private int goalsCompletedToday;
        private double avgGoalProgress;

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public int getPlannedTasks() {
            return plannedTasks;
        }

        public void setPlannedTasks(int plannedTasks) {
            this.plannedTasks = plannedTasks;
        }

        public int getCompletedTasks() {
            return completedTasks;
        }

        public void setCompletedTasks(int completedTasks) {
            this.completedTasks = completedTasks;
        }

        public int getNoteCount() {
            return noteCount;
        }

        public void setNoteCount(int noteCount) {
            this.noteCount = noteCount;
        }

        public double getTaskCompletionRate() {
            return taskCompletionRate;
        }

        public void setTaskCompletionRate(double taskCompletionRate) {
            this.taskCompletionRate = taskCompletionRate;
        }

        public double getNoteFactor() {
            return noteFactor;
        }

        public void setNoteFactor(double noteFactor) {
            this.noteFactor = noteFactor;
        }

        public int getTotalScore() {
            return totalScore;
        }

        public void setTotalScore(int totalScore) {
            this.totalScore = totalScore;
        }

        public double getWeightedTaskCompletionRate() {
            return weightedTaskCompletionRate;
        }

        public void setWeightedTaskCompletionRate(double weightedTaskCompletionRate) {
            this.weightedTaskCompletionRate = weightedTaskCompletionRate;
        }

        public int getActiveGoalCount() {
            return activeGoalCount;
        }

        public void setActiveGoalCount(int activeGoalCount) {
            this.activeGoalCount = activeGoalCount;
        }

        public int getGoalsCompletedToday() {
            return goalsCompletedToday;
        }

        public void setGoalsCompletedToday(int goalsCompletedToday) {
            this.goalsCompletedToday = goalsCompletedToday;
        }

        public double getAvgGoalProgress() {
            return avgGoalProgress;
        }

        public void setAvgGoalProgress(double avgGoalProgress) {
            this.avgGoalProgress = avgGoalProgress;
        }
    }

    /**
     * 计算指定时间范围内每天的得分。
     */
    public List<DailyScore> calculateScores(User user, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        // 取出用户所有任务和笔记，在内存中按日期聚合（个人应用数据量可接受）
        List<Task> allTasks = taskRepository.findByUser(user);
        List<Note> allNotes = noteRepository.findByUser(user);
        List<Goal> allGoals;
        List<Link> allLinks;
        try {
            // 目标/关联表属于可选增强能力，如果数据库还没对应表或数据异常，
            // 不应该影响基础“任务 + 笔记”得分的计算。
            allGoals = goalRepository.findByUser(user);
            allLinks = linkRepository.findAll();
        } catch (Exception e) {
            // 容错：记录日志由全局异常/日志框架处理，这里只在评分层面“降级”为不考虑目标因素
            allGoals = List.of();
            allLinks = List.of();
        }

        // 预构建 taskId -> task 便于关联过滤
        Map<Long, Task> taskById = allTasks.stream()
                .filter(t -> t.getId() != null)
                .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));

        // 目标只统计当前用户的
        Map<Long, Goal> goalById = allGoals.stream()
                .filter(g -> g.getId() != null)
                .collect(Collectors.toMap(Goal::getId, g -> g, (a, b) -> a));

        // Task ↔ Goal 关联（弱关联，来自 link 表）
        // 个人应用：直接全表取出再过滤（后续若数据量大，可加 repository 查询方法优化）
        Map<Long, Set<Long>> taskToGoalIds = new HashMap<>();
        Map<Long, Set<Long>> goalToTaskIds = new HashMap<>();
        for (Link l : allLinks) {
            if (l.getSourceType() != Link.LinkSourceType.TASK) continue;
            if (l.getTargetType() != Link.LinkTargetType.GOAL) continue;
            Long taskId = l.getSourceId();
            Long goalId = l.getTargetId();
            if (taskId == null || goalId == null) continue;
            if (!taskById.containsKey(taskId)) continue;  // 仅统计当前用户任务
            if (!goalById.containsKey(goalId)) continue;  // 仅统计当前用户目标
            taskToGoalIds.computeIfAbsent(taskId, k -> new HashSet<>()).add(goalId);
            goalToTaskIds.computeIfAbsent(goalId, k -> new HashSet<>()).add(taskId);
        }

        Map<LocalDate, List<Task>> tasksByDeadline = allTasks.stream()
                .filter(t -> t.getDeadline() != null)
                .collect(Collectors.groupingBy(t -> t.getDeadline().toLocalDate()));

        Map<LocalDate, List<Note>> notesByCreated = allNotes.stream()
                .filter(n -> n.getCreatedAt() != null)
                .collect(Collectors.groupingBy(n -> n.getCreatedAt().toLocalDate()));

        // 当天完成目标：以目标 archivedAt 作为“目标完整完成”的事件信号
        Map<LocalDate, Long> goalsCompletedByDay = allGoals.stream()
                .filter(g -> g.getArchivedAt() != null)
                .collect(Collectors.groupingBy(g -> g.getArchivedAt().toLocalDate(), Collectors.counting()));

        List<Goal> activeGoals = allGoals.stream()
                .filter(g -> g.getArchivedAt() == null)
                .collect(Collectors.toList());

        List<DailyScore> result = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            List<Task> tasksOfDay = tasksByDeadline.getOrDefault(cursor, List.of());
            List<Note> notesOfDay = notesByCreated.getOrDefault(cursor, List.of());

            int planned = tasksOfDay.size();
            int completed = (int) tasksOfDay.stream()
                    .filter(t -> {
                        TaskStatus s = t.getEffectiveStatus();
                        return s == TaskStatus.DONE || s == TaskStatus.ARCHIVED;
                    })
                    .count();

            double completionRate = planned == 0 ? 0.0 : (completed * 1.0 / planned);

            // --- 新版任务评分：加权完成率 + 加权吞吐（考虑周期长度、心理负担、精力需求） ---
            double plannedWeight = 0.0;
            double completedWeight = 0.0;
            for (Task t : tasksOfDay) {
                double w = taskWeight(t, cursor);
                plannedWeight += w;
                TaskStatus s = t.getEffectiveStatus();
                if (s == TaskStatus.DONE || s == TaskStatus.ARCHIVED) {
                    completedWeight += w;
                }
            }
            double weightedCompletionRate = plannedWeight <= 0.0 ? 0.0 : (completedWeight / plannedWeight);
            // 吞吐因子：完成越多越高，但快速饱和，避免“堆很多小任务刷分”
            double throughputFactor = 1.0 - Math.exp(-completedWeight / 3.0); // 0~1

            int notes = notesOfDay.size();
            // 笔记因子：0~1，饱和曲线（2~4 条就接近满分，超过仍会增加但非常慢）
            double noteFactor = 1.0 - Math.exp(-notes / 2.0);

            // --- 新版目标评分：整体进度 + 当天完成目标数 + 当天推进覆盖面 ---
            double avgGoalProgress = 0.0;
            if (!activeGoals.isEmpty()) {
                double sum = 0.0;
                for (Goal g : activeGoals) {
                    sum += goalProgressPow(g, goalToTaskIds, taskById);
                }
                avgGoalProgress = sum / activeGoals.size(); // 0~1（已做 pow 变换）
            }
            int goalsCompletedToday = goalsCompletedByDay.getOrDefault(cursor, 0L).intValue();

            // 当天“推进了多少个目标”：当天已完成的任务中，涉及到的目标覆盖数（鼓励目标对齐）
            Set<Long> touchedGoals = new HashSet<>();
            for (Task t : tasksOfDay) {
                TaskStatus s = t.getEffectiveStatus();
                if (s != TaskStatus.DONE && s != TaskStatus.ARCHIVED) continue;
                Set<Long> gids = taskToGoalIds.get(t.getId());
                if (gids != null) touchedGoals.addAll(gids);
            }
            double touchedFactor = touchedGoals.isEmpty() ? 0.0 : (1.0 - Math.exp(-touchedGoals.size() / 2.0)); // 0~1

            // --- 总分拆分（0~100） ---
            double taskScore = 50.0 * weightedCompletionRate + 20.0 * throughputFactor; // 0~70
            double goalScore = 8.0 * avgGoalProgress
                    + 8.0 * (1.0 - Math.exp(-goalsCompletedToday)) // 目标完成事件强信号
                    + 4.0 * touchedFactor; // 0~20
            double noteScore = 10.0 * noteFactor; // 0~10
            int total = (int) Math.round(clamp(taskScore + goalScore + noteScore, 0.0, 100.0));

            DailyScore ds = new DailyScore();
            ds.setDate(cursor);
            ds.setPlannedTasks(planned);
            ds.setCompletedTasks(completed);
            ds.setNoteCount(notes);
            ds.setTaskCompletionRate(round1(completionRate));
            ds.setNoteFactor(round1(noteFactor));
            ds.setTotalScore(total);
            ds.setWeightedTaskCompletionRate(round1(weightedCompletionRate));
            ds.setActiveGoalCount(activeGoals.size());
            ds.setGoalsCompletedToday(goalsCompletedToday);
            ds.setAvgGoalProgress(round1(avgGoalProgress));

            result.add(ds);
            cursor = cursor.plusDays(1);
        }

        return result;
    }

    /**
     * 单个任务的权重：综合“精力需求（难度 proxy）”“心理负担”“任务周期长度”。
     *
     * 目标：
     * - 同样完成 1 个任务，难度/负担更高、周期更长的任务贡献更大
     * - 在心理负担较重的任务上，即使精力需求较低，也能拿到不错的权重（符合“顶着压力做简单事也很难”）
     */
    private double taskWeight(Task t, LocalDate day) {
        // 兼容旧数据：energyRequirement / mentalLoad 可能为 null，这里给出温和默认值
        EnergyLevel energy = t.getEnergyRequirement();
        if (energy == null) {
            energy = EnergyLevel.MEDIUM;
        }
        MentalLoad mental = t.getMentalLoad();
        if (mental == null) {
            mental = MentalLoad.LIGHT;
        }

        double difficulty = switch (energy) {
            case HIGH -> 1.00;
            case MEDIUM -> 0.72;
            case LOW -> 0.48;
        };

        double burden = switch (mental) {
            case HEAVY -> 1.00;
            case LIGHT -> 0.70;
        };

        // 交互项：心理负担高时，哪怕难度低也不至于太“便宜”
        double base = 0.55 * difficulty + 0.45 * burden;
        double interaction = 0.20 * Math.min(difficulty, burden);
        double w = base + interaction; // 大约 0.4~1.2

        // 周期长度：createdAt -> deadline 的跨度更长，权重上调（对“长周期目标推进”更公平）
        double cycleMult = 1.0;
        if (t.getCreatedAt() != null && t.getDeadline() != null) {
            LocalDate created = t.getCreatedAt().toLocalDate();
            LocalDate ddl = t.getDeadline().toLocalDate();
            long cycleDays = Math.max(0, ChronoUnit.DAYS.between(created, ddl));
            cycleMult = 1.0 + 0.15 * Math.log1p(Math.min(cycleDays, 90)); // 上限防爆
        } else if (t.getCreatedAt() != null) {
            // 没有 deadline 的任务（但在此统计里一般不会出现），用“存在天数”近似
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(t.getCreatedAt().toLocalDate(), day));
            cycleMult = 1.0 + 0.10 * Math.log1p(Math.min(ageDays, 90));
        }

        return clamp(w * cycleMult, 0.30, 1.60);
    }

    /**
     * 目标进度（0~1）做一个幂变换：progress^0.7
     * - 低进度也能得到一点反馈（避免用户早期完全没正反馈）
     * - 高进度仍然更值钱（接近完成更难）
     */
    private double goalProgressPow(
            Goal g,
            Map<Long, Set<Long>> goalToTaskIds,
            Map<Long, Task> taskById
    ) {
        Set<Long> taskIds = goalToTaskIds.get(g.getId());
        if (taskIds == null || taskIds.isEmpty()) return 0.0;

        int total = 0;
        int done = 0;
        for (Long tid : taskIds) {
            Task t = taskById.get(tid);
            if (t == null) continue;
            total++;
            TaskStatus s = t.getEffectiveStatus();
            if (s == TaskStatus.DONE || s == TaskStatus.ARCHIVED) done++;
        }
        if (total == 0) return 0.0;
        double progress = done * 1.0 / total;
        return Math.pow(progress, 0.7);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

