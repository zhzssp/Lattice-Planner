package org.zhzssp.memorandum.feature.report.service;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.entity.EnergyLevel;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TimeSlot;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.insight.service.AiSummaryService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;
import org.zhzssp.memorandum.feature.report.dto.DailyReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 晨报 / 晚报「内容生成器」（无状态）。
 *
 * <p>设计：完全复用现有能力，不引入任何新数据模型——</p>
 * <ul>
 *   <li>晨报：{@link TaskService#getTodayActionableTasks(User)} 今日可行动任务清单</li>
 *   <li>晚报：{@link InsightScoreService#calculateScores} 当日得分
 *            + {@link AiSummaryService#summarizeScores} 结合 RAG 的 AI 复盘（自带超时降级）</li>
 * </ul>
 *
 * <p>本类只负责「把现成数据拼成一份报告文本」，何时推送、是否已推送由
 * {@code ProactiveReportService} 决定。</p>
 */
@Service
public class DailyReportService {

    private final TaskService taskService;
    private final InsightScoreService insightScoreService;
    private final AiSummaryService aiSummaryService;

    public DailyReportService(TaskService taskService,
                              InsightScoreService insightScoreService,
                              AiSummaryService aiSummaryService) {
        this.taskService = taskService;
        this.insightScoreService = insightScoreService;
        this.aiSummaryService = aiSummaryService;
    }

    // ============================================================
    // 晨报：今日可推进任务 + 一句激励
    // ============================================================
    public DailyReport buildMorning(User user) {
        List<Task> tasks = safeTodayTasks(user);
        int n = tasks.size();
        String name = displayName(user);

        String title = (n == 0)
                ? "早安，今天还是一张白纸"
                : "早安 · 今天有 " + n + " 件事可推进";

        String body;
        if (n == 0) {
            body = "暂无待办，先给自己定一个今天最想完成的小目标吧。";
        } else {
            Task top = tasks.get(0);
            body = "建议优先：" + nullSafe(top.getTitle(), "(未命名任务)")
                    + (n > 1 ? "，等 " + n + " 件待推进" : "");
        }

        StringBuilder d = new StringBuilder();
        d.append("## 今日晨报 · ").append(LocalDate.now()).append("\n\n");
        d.append(name).append("，新的一天开始了。\n\n");
        if (n == 0) {
            d.append("今天还没有安排可行动的任务。\n\n")
                    .append("不妨花两分钟，挑一件最想推进的事，把它拆成一个 25 分钟就能开始的小步骤。\n");
        } else {
            d.append("**今天可推进 ").append(n).append(" 件事：**\n\n");
            int limit = Math.min(n, 8);
            for (int i = 0; i < limit; i++) {
                Task t = tasks.get(i);
                d.append(i + 1).append(". ")
                        .append(nullSafe(t.getTitle(), "(未命名任务)"))
                        .append(slotTag(t.getPreferredSlot()))
                        .append(energyTag(t.getEnergyRequirement()))
                        .append("\n");
            }
            if (n > limit) {
                d.append("\n…… 还有 ").append(n - limit).append(" 件，登录应用查看完整清单。\n");
            }
            d.append("\n").append(motivation()).append("\n");
        }

        return new DailyReport("morning", title, body, d.toString(), LocalDateTime.now().toString());
    }

    // ============================================================
    // 晚报：当日得分 + AI 复盘（近 7 天上下文）+ 明日建议
    // ============================================================
    public DailyReport buildEvening(User user) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6); // 给 AI 复盘一点趋势上下文，但只展示今日得分

        List<DailyScore> week = safeScores(user, start, today);
        DailyScore todayScore = week.stream()
                .filter(s -> today.equals(s.getDate()))
                .findFirst()
                .orElse(week.isEmpty() ? null : week.get(week.size() - 1));

        int score = todayScore == null ? 0 : todayScore.getTotalScore();
        int planned = todayScore == null ? 0 : todayScore.getPlannedTasks();
        int done = todayScore == null ? 0 : todayScore.getCompletedTasks();
        int notes = todayScore == null ? 0 : todayScore.getNoteCount();

        // AI 复盘（结合 RAG 的个人笔记），AiSummaryService 内部已含超时 + 本地兜底
        String aiSummary;
        try {
            aiSummary = aiSummaryService.summarizeScores(start, today, week, user);
        } catch (Exception e) {
            aiSummary = "今天也辛苦了，明天继续保持节奏。";
        }

        List<Task> remaining = safeTodayTasks(user);

        String title = "晚间复盘 · 今日 " + score + " 分";
        String body = eveningOneLiner(score, done, planned);

        StringBuilder d = new StringBuilder();
        d.append("## 今日晚报 · ").append(today).append("\n\n");
        d.append("**今日得分：").append(score).append(" / 100**\n\n");
        d.append("- 计划任务：").append(planned).append(" 件，已完成：").append(done).append(" 件\n");
        d.append("- 新增笔记：").append(notes).append(" 条\n\n");
        d.append("### AI 复盘\n\n").append(aiSummary == null ? "" : aiSummary.strip()).append("\n\n");

        d.append("### 明日建议\n\n");
        if (remaining.isEmpty()) {
            d.append("当前没有待推进的任务。睡前可以花一分钟，为明天写下一件最重要的事。\n");
        } else {
            d.append("还有 ").append(remaining.size()).append(" 件待推进，明天建议优先：**")
                    .append(nullSafe(remaining.get(0).getTitle(), "(未命名任务)")).append("**。\n");
        }

        return new DailyReport("evening", title, body, d.toString(), LocalDateTime.now().toString());
    }

    // ============================================================
    // 容错包装：报告生成绝不能因数据异常中断推送链路
    // ============================================================
    private List<Task> safeTodayTasks(User user) {
        try {
            List<Task> t = taskService.getTodayActionableTasks(user);
            return t == null ? List.of() : t;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<DailyScore> safeScores(User user, LocalDate start, LocalDate end) {
        try {
            List<DailyScore> s = insightScoreService.calculateScores(user, start, end);
            return s == null ? List.of() : s;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ============================================================
    // 文案辅助
    // ============================================================
    private String displayName(User user) {
        if (user == null) return "你好";
        try {
            String name = user.getUsername();
            return (name == null || name.isBlank()) ? "你好" : name;
        } catch (Exception e) {
            return "你好";
        }
    }

    private String slotTag(TimeSlot slot) {
        if (slot == null) return "";
        return switch (slot) {
            case MORNING -> "（上午）";
            case AFTERNOON -> "（下午）";
            case EVENING -> "（晚上）";
        };
    }

    private String energyTag(EnergyLevel level) {
        if (level == null) return "";
        return switch (level) {
            case HIGH -> " [高精力]";
            case MEDIUM -> " [中精力]";
            case LOW -> " [低精力]";
        };
    }

    private String eveningOneLiner(int score, int done, int planned) {
        if (score >= 80) return "今天状态很好，完成了 " + done + "/" + planned + " 件，给自己点个赞。";
        if (score >= 60) return "今天稳步推进，完成了 " + done + "/" + planned + " 件，继续保持。";
        if (score >= 40) return "今天节奏一般，明天可以挑一件最重要的先做。";
        return "今天进展不多也没关系，看看复盘，明天换个方式开始。";
    }

    /** 按"年内第几天"轮换激励语，避免每天一样。 */
    private String motivation() {
        String[] lines = {
                "> 先完成，再完美。开始本身就是进度。",
                "> 不必一次做完，先做 25 分钟。",
                "> 把大事拆小，把小事做完。",
                "> 今天的每一步，都在为目标累积复利。",
                "> 状态来自行动，而不是行动等待状态。",
                "> 选一件最重要的事，先把它推进一格。",
                "> 进度大于完美，记录大于记忆。"
        };
        int idx = LocalDate.now().getDayOfYear() % lines.length;
        return lines[idx];
    }

    private String nullSafe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
