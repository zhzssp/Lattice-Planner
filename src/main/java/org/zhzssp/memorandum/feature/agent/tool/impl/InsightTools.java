package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.insight.service.AiSummaryService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;

import java.time.LocalDate;
import java.util.List;

/**
 * 复盘相关工具：调用现有 InsightScoreService + AiSummaryService。
 */
@Component
public class InsightTools {

    private final InsightScoreService insightScoreService;
    private final AiSummaryService aiSummaryService;

    public InsightTools(InsightScoreService insightScoreService, AiSummaryService aiSummaryService) {
        this.insightScoreService = insightScoreService;
        this.aiSummaryService = aiSummaryService;
    }

    @AgentTool(name = "insight.daily_scores", tags = {"insight", "read"},
            description = "查询当前用户在 [from, to] 区间内每天的规划完成得分（0~100）。日期格式 yyyy-MM-dd。")
    public List<ScoreView> dailyScores(
            @ToolParam(value = "from", desc = "起始日期 yyyy-MM-dd", required = true) String from,
            @ToolParam(value = "to", desc = "终止日期 yyyy-MM-dd", required = true) String to
    ) {
        User user = AgentContext.requireUser();
        LocalDate s = LocalDate.parse(from);
        LocalDate e = LocalDate.parse(to);
        List<DailyScore> raw = insightScoreService.calculateScores(user, s, e);
        return raw.stream().map(ScoreView::of).toList();
    }

    @AgentTool(name = "insight.summarize_period", tags = {"insight", "read"},
            description = "对 [from, to] 区间内的得分曲线给出自然语言总结（含建议）。日期格式 yyyy-MM-dd。")
    public String summarizePeriod(
            @ToolParam(value = "from", desc = "起始日期 yyyy-MM-dd", required = true) String from,
            @ToolParam(value = "to", desc = "终止日期 yyyy-MM-dd", required = true) String to
    ) {
        User user = AgentContext.requireUser();
        LocalDate s = LocalDate.parse(from);
        LocalDate e = LocalDate.parse(to);
        List<DailyScore> raw = insightScoreService.calculateScores(user, s, e);
        return aiSummaryService.summarizeScores(s, e, raw, user);
    }

    public record ScoreView(
            String date, int totalScore, int planned, int completed, int notes,
            double weightedCompletionRate, int goalsCompletedToday, double avgGoalProgress
    ) {
        static ScoreView of(DailyScore d) {
            return new ScoreView(
                    d.getDate().toString(),
                    d.getTotalScore(),
                    d.getPlannedTasks(),
                    d.getCompletedTasks(),
                    d.getNoteCount(),
                    d.getWeightedTaskCompletionRate(),
                    d.getGoalsCompletedToday(),
                    d.getAvgGoalProgress()
            );
        }
    }
}
