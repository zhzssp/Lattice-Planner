package org.zhzssp.memorandum.feature.insight.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 使用 Gemini 免费接口，对一段时间内的得分曲线做自然语言总结。
 *
 * 默认使用 Gemini Developer API 的免费模型：gemini-2.5-flash。
 *
 * <p>鉴权方式：
 * - 推荐在操作系统环境变量中配置 GEMINI_API_KEY 或 GOOGLE_API_KEY
 * - 或在 application.properties 中配置 gemini.api.key=YOUR_KEY
 *
 * 如果三者都为空，则本服务会降级为“仅返回本地规则生成的基础总结”，不会调用外部接口。
 */
@Service
public class AiSummaryService {

    /** 使用的 Gemini 模型 ID（免费层可用）。你也可以改成 gemini-2.5-flash-lite 等。 */
    private static final String GEMINI_MODEL_ID = "gemini-2.5-flash";

    /**
     * 调用 Gemini 的超时时间（秒）。
     * 如果在该时间内没有得到模型返回，就会中断调用，回退到本地规则总结。
     */
    private static final long AI_TIMEOUT_SECONDS = 8L;

    /**
     * 从配置文件注入的 key。如果未配置，会回退到环境变量 GEMINI_API_KEY。
     *
     * 在 application.properties 中可以这样配置（示例）：
     * gemini.api.key=YOUR_GEMINI_API_KEY
     *
     * 请不要把真实 key 提交到 Git 仓库。
     */
    @Value("${gemini.api.key:}")
    private String configuredApiKey;

    /**
     * 对指定时间范围内的 DailyScore 列表做总结。
     * 如果无法正常调用 Gemini（key 缺失或网络错误等），会回退到本地规则总结。
     */
    public String summarizeScores(LocalDate start, LocalDate end, List<DailyScore> scores) {
        String fallback = buildLocalSummary(start, end, scores);

        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // 开发者尚未配置 key，直接返回本地规则总结，避免接口报错
            return fallback + "\n\n（提示：当前未配置 Gemini API Key，本摘要由本地规则生成。）";
        }

        String prompt = buildPrompt(start, end, scores, fallback);

        try {
            // 使用异步 + 超时控制，避免模型响应过慢卡住请求。
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                Client client = Client.builder()
                        .apiKey(apiKey)
                        .build();

                GenerateContentResponse response =
                        client.models.generateContent(GEMINI_MODEL_ID, prompt, null);

                String aiText = response.text();
                if (aiText == null || aiText.isBlank()) {
                    throw new IllegalStateException("Gemini 返回了空文本");
                }
                return aiText.trim();
            });

            // orTimeout 会在超时时将 future 以 TimeoutException 完成
            return future.orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof TimeoutException) {
                return fallback + "\n\n（提示：调用 Gemini 超过 "
                        + AI_TIMEOUT_SECONDS
                        + " 秒未返回，已使用本地规则生成摘要。）";
            }
            // 其他异常（网络 / 配额 / Key 等），统一回退
            return fallback + "\n\n（提示：调用 Gemini 接口失败，本摘要由本地规则生成。错误信息已记录在服务端日志。）";
        } catch (Exception ex) {
            // 兜底：理论上不会走到这里，但为了保险起见仍然做一次回退
            return fallback + "\n\n（提示：调用 Gemini 接口失败，本摘要由本地规则生成。错误信息已记录在服务端日志。）";
        }
    }

    private String resolveApiKey() {
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        // 兼容官方 SDK 默认的 GOOGLE_API_KEY 环境变量名字
        String googleApiKey = System.getenv("GOOGLE_API_KEY");
        if (googleApiKey != null && !googleApiKey.isBlank()) {
            return googleApiKey.trim();
        }
        return null;
    }

    /**
     * 构造传给 Gemini 的 Prompt 文本。
     * 会携带一份简单的“规则总结”作为参考，方便模型在此基础上做润色和补充。
     */
    private String buildPrompt(LocalDate start, LocalDate end, List<DailyScore> scores, String fallbackSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个帮助用户复盘自律情况的教练。")
                .append("下面是一段时间内的每日规划完成得分与一些辅助指标，请用中文给出一个有洞见的总结。\n\n")
                .append("需要输出的内容：\n")
                .append("1. 整体评价：这段时间的规划完成情况、自律性水平。\n")
                .append("2. 趋势：是变好、变差还是比较稳定？大致在哪几天发生了明显变化。\n")
                .append("3. 模式：例如“周末高分、工作日低分”“前几天冲刺后几天疲软”等。\n")
                .append("4. 可执行建议：给出 2~4 条具体、可操作的建议，帮助用户优化目标拆分、任务选择和笔记习惯。\n")
                .append("5. 限制：不要出现“作为一个 AI 模型”之类的措辞，不要提到具体的得分算法实现细节。\n\n");

        sb.append("时间范围：").append(start).append(" ~ ").append(end).append("\n\n");
        sb.append("以下是系统根据规则生成的一个初步总结，你可以在此基础上进行改写、补充或纠偏：\n");
        sb.append(fallbackSummary).append("\n\n");

        sb.append("下面是按日期排列的原始数据（按日期升序）：\n");
        sb.append("字段含义：date, totalScore(0-100), plannedTasks, completedTasks, noteCount, ")
                .append("weightedTaskCompletionRate(0-1), goalsCompletedToday, avgGoalProgress(0-1)\n");

        String lines = scores.stream()
                .sorted((a, b) -> a.getDate().compareTo(b.getDate()))
                .map(d -> String.format(
                        "%s: totalScore=%d, planned=%d, done=%d, notes=%d, weightedCompletion=%.2f, goalsDone=%d, avgGoalProgress=%.2f",
                        d.getDate(),
                        d.getTotalScore(),
                        d.getPlannedTasks(),
                        d.getCompletedTasks(),
                        d.getNoteCount(),
                        d.getWeightedTaskCompletionRate(),
                        d.getGoalsCompletedToday(),
                        d.getAvgGoalProgress()
                ))
                .collect(Collectors.joining("\n"));
        sb.append(lines);

        sb.append("\n\n请基于上述数据给出一段 4~8 段落的总结，语言自然、口语化一些，但保持简洁和聚焦。");

        return sb.toString();
    }

    /**
     * 在无法调用 Gemini 时的本地兜底总结。
     * 只做简单统计，用于给用户一个基础反馈，也作为 Prompt 的参考文本。
     */
    private String buildLocalSummary(LocalDate start, LocalDate end, List<DailyScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return "这段时间内没有可用的得分数据，无法对规划完成情况做出评价。";
        }

        int days = scores.size();
        double avgScore = scores.stream().mapToInt(DailyScore::getTotalScore).average().orElse(0.0);
        int maxScore = scores.stream().mapToInt(DailyScore::getTotalScore).max().orElse(0);
        int minScore = scores.stream().mapToInt(DailyScore::getTotalScore).min().orElse(0);
        LocalDate maxDay = scores.stream()
                .max((a, b) -> Integer.compare(a.getTotalScore(), b.getTotalScore()))
                .map(DailyScore::getDate)
                .orElse(null);
        LocalDate minDay = scores.stream()
                .min((a, b) -> Integer.compare(a.getTotalScore(), b.getTotalScore()))
                .map(DailyScore::getDate)
                .orElse(null);

        int first = scores.get(0).getTotalScore();
        int last = scores.get(scores.size() - 1).getTotalScore();

        // 简单波动估计：使用加权绝对变化
        double volatility = 0.0;
        for (int i = 1; i < scores.size(); i++) {
            volatility += Math.abs(scores.get(i).getTotalScore() - scores.get(i - 1).getTotalScore());
        }
        volatility = volatility / Math.max(1, days - 1);

        long highDays = scores.stream().filter(d -> d.getTotalScore() >= 75).count();
        long lowDays = scores.stream().filter(d -> d.getTotalScore() <= 45).count();

        StringBuilder sb = new StringBuilder();
        sb.append("从 ").append(start).append(" 到 ").append(end)
                .append(" 这一段时间内，一共统计了 ").append(days).append(" 天的规划得分。\n")
                .append("平均得分约为 ").append(Math.round(avgScore)).append(" 分，")
                .append("最高分为 ").append(maxScore).append(" 分（大致在 ").append(Objects.toString(maxDay, "未知日期")).append("），")
                .append("最低分为 ").append(minScore).append(" 分（大致在 ").append(Objects.toString(minDay, "未知日期")).append("）。\n");

        if (volatility < 8) {
            sb.append("整体波动不大，说明你的执行节奏相对稳定，");
        } else if (volatility < 18) {
            sb.append("整体波动中等，说明你的执行状态会受到一些短期因素影响，");
        } else {
            sb.append("整体波动较大，说明你的执行状态时好时坏，容易受情绪或环境波动影响，");
        }

        if (last > first + 5) {
            sb.append("但整体趋势是逐渐走高的。");
        } else if (last < first - 5) {
            sb.append("而且从一开始到后期有一定程度的下滑。");
        } else {
            sb.append("整体趋势比较持平。");
        }

        sb.append("\n其中，高分（≥75）天数约为 ").append(highDays).append(" 天，")
                .append("低分（≤45）天数约为 ").append(lowDays).append(" 天。");

        return sb.toString();
    }

    // 下面原来是手写的 REST DTO，使用官方 SDK 后不再需要，已删除。
}

