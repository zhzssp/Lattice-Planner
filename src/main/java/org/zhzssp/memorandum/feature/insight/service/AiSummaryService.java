package org.zhzssp.memorandum.feature.insight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 使用统一 LLM 网关，对一段时间内的得分曲线做自然语言总结。
 *
 * 说明：
 * - 通过 LlmGateway 调用当前项目配置的模型（当前为 DeepSeek）
 * - 如果调用失败，会自动回退到本地规则总结
 * - Stage 3 起：当传入 user 时，会先调用 RagSearchService 拉取该用户最近的相关
 *   笔记片段（用户笔记 + 已摄取本地文档）注入 prompt，让复盘从"通用建议"变成
 *   "结合你最近写过的内容给的针对性建议"。
 */
@Service
public class AiSummaryService {

    private final LlmGateway llmGateway;
    private final RagSearchService ragSearchService;
    private final long summaryTimeoutSeconds;

    public AiSummaryService(LlmGateway llmGateway,
                            RagSearchService ragSearchService,
                            @Value("${agent.llm.summary-timeout-seconds:30}") long summaryTimeoutSeconds) {
        this.llmGateway = llmGateway;
        this.ragSearchService = ragSearchService;
        this.summaryTimeoutSeconds = summaryTimeoutSeconds;
    }

    /**
     * 旧签名兼容：不带 user 时不附 RAG 上下文。
     */
    public String summarizeScores(LocalDate start, LocalDate end, List<DailyScore> scores) {
        return summarizeScores(start, end, scores, null);
    }

    /**
     * 对指定时间范围内的 DailyScore 列表做总结。
     * 如果无法正常调用 LLM（key 缺失或网络错误等），会回退到本地规则总结。
     *
     * @param user 当前用户；非 null 时启用 RAG 检索个人笔记 / 本地文档作为上下文。
     */
    public String summarizeScores(LocalDate start, LocalDate end, List<DailyScore> scores, User user) {
        String fallback = buildLocalSummary(start, end, scores);
        String ragContext = (user == null) ? "" : buildRagContext(user, start, end);
        String prompt = buildPrompt(start, end, scores, fallback, ragContext);

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> llmGateway.generateText(prompt));
            return future.orTimeout(summaryTimeoutSeconds, TimeUnit.SECONDS).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof TimeoutException) {
                return fallback + "\n\n（提示：调用模型超过 "
                        + summaryTimeoutSeconds
                        + " 秒未返回，已使用本地规则生成摘要。）";
            }
            return fallback + "\n\n（提示：调用模型接口失败，本摘要由本地规则生成。错误信息已记录在服务端日志。）";
        } catch (Exception ex) {
            return fallback + "\n\n（提示：调用模型接口失败，本摘要由本地规则生成。错误信息已记录在服务端日志。）";
        }
    }

    /**
     * 构造传给模型的 Prompt 文本。
     * 会携带一份简单的“规则总结”作为参考，方便模型在此基础上做润色和补充。
     */
    private String buildPrompt(LocalDate start, LocalDate end, List<DailyScore> scores,
                               String fallbackSummary, String ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个帮助用户复盘自律情况的教练。")
                .append("下面是一段时间内的每日规划完成得分与一些辅助指标，请用中文给出一个有洞见的总结。\n\n")
                .append("需要输出的内容：\n")
                .append("1. 整体评价：这段时间的规划完成情况、自律性水平。\n")
                .append("2. 趋势：是变好、变差还是比较稳定？大致在哪几天发生了明显变化。\n")
                .append("3. 模式：例如“周末高分、工作日低分”“前几天冲刺后几天疲软”等。\n")
                .append("4. 可执行建议：给出 2~4 条具体、可操作的建议，帮助用户优化目标拆分、任务选择和笔记习惯。\n")
                .append("5. 限制：不要出现“作为一个 AI 模型”之类的措辞，不要提到具体的得分算法实现细节。\n");

        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("6. 结合用户笔记：下面会给你几段【用户的相关笔记/资料片段】。如果它们与本段时间")
                    .append("的复盘主题相关（比如出现了具体目标、习惯、卡点），请在建议里有针对性地引用，")
                    .append("引用时使用 [[标题]] 写法（仅限来自用户笔记的条目，本地文档不要 [[]]）。")
                    .append("若片段与复盘无关，可忽略。\n");
        }
        sb.append('\n');

        sb.append("时间范围：").append(start).append(" ~ ").append(end).append("\n\n");
        sb.append("以下是系统根据规则生成的一个初步总结，你可以在此基础上进行改写、补充或纠偏：\n");
        sb.append(fallbackSummary).append("\n\n");

        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("【用户的相关笔记/资料片段（按相关度倒排）】\n")
                    .append(ragContext).append("\n\n");
        }

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
     * Stage 3：从用户的个人知识库（笔记 + 已摄取本地文档）抽取 Top-N 与"复盘 / 学习 /
     * 习惯 / 自律"相关的 chunk 注入 prompt。
     *
     * 设计取舍：
     *  - 查询语用复盘语义而非时间窗：embedding 不擅长按日期过滤，而 RAG 命中近期常被
     *    Note 的 [标题] + tag 体现，已经能 surface 出"最近写过的复盘/学习反思"；
     *  - 任一通路异常（embedding 没配 / FULLTEXT 未启用）都会被 RagSearchService 内部
     *    swallow，这里 catch 掉外层异常再给空串，绝不影响复盘主流程；
     *  - 单 chunk 截断 300 字，最多 5 条 → 总 prompt 增量约 1.5KB，可控。
     */
    private String buildRagContext(User user, LocalDate start, LocalDate end) {
        try {
            String query = String.format(
                    "复盘 学习 习惯 自律 卡点 反思 目标 任务 %s ~ %s", start, end);
            List<RagSearchService.Hit> hits = ragSearchService.search(user, query, 5);
            if (hits == null || hits.isEmpty()) return "";
            StringBuilder b = new StringBuilder();
            int i = 1;
            for (RagSearchService.Hit h : hits) {
                String head;
                if ("NOTE".equals(h.source())) {
                    head = "笔记 noteId=" + h.noteId();
                } else {
                    head = "本地文档 " + (h.sourcePath() == null ? "" : h.sourcePath());
                }
                String body = h.content() == null ? "" : h.content().strip();
                if (body.length() > 300) body = body.substring(0, 300) + "...";
                b.append(i++).append(". [").append(head)
                        .append("  score=").append(String.format("%.3f", h.score()))
                        .append("] ").append(body).append("\n");
            }
            return b.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    /**
     * 在无法调用模型时的本地兜底总结。
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
}
