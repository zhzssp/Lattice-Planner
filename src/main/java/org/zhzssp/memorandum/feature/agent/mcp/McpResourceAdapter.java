package org.zhzssp.memorandum.feature.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.goal.service.GoalService;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;

import java.time.LocalDate;
import java.util.*;

/**
 * MCP Resources 适配层：暴露只读数据端点。
 */
@Component
public class McpResourceAdapter {

    private final GoalService goalService;
    private final TaskService taskService;
    private final NoteService noteService;
    private final RagSearchService ragService;
    private final InsightScoreService insightService;
    private final ObjectMapper om;

    public McpResourceAdapter(GoalService goalService,
                              TaskService taskService,
                              NoteService noteService,
                              RagSearchService ragService,
                              InsightScoreService insightService,
                              ObjectMapper om) {
        this.goalService = goalService;
        this.taskService = taskService;
        this.noteService = noteService;
        this.ragService = ragService;
        this.insightService = insightService;
        this.om = om;
    }

    /** 列出可用的 MCP Resources。 */
    public List<Map<String, Object>> listResources() {
        return List.of(
                resource("lattice://goals", "活跃目标列表", "application/json"),
                resource("lattice://goals/all", "全部目标（含归档）", "application/json"),
                resource("lattice://tasks/today", "今日可行动任务", "application/json"),
                resource("lattice://notes", "笔记列表", "application/json"),
                resource("lattice://insight/recent", "最近 7 天得分", "application/json")
        );
    }

    /** 读取指定 URI 的资源。 */
    public Map<String, Object> readResource(String uri) throws Exception {
        User u = AgentContext.requireUser();
        Object data;
        switch (uri) {
            case "lattice://goals" -> data = goalService.findActiveGoalsByUser(u);
            case "lattice://goals/all" -> data = goalService.findGoalsByUser(u);
            case "lattice://tasks/today" -> data = taskService.getTodayActionableTasks(u);
            case "lattice://notes" -> data = noteService.listVisibleByUser(u);
            case "lattice://insight/recent" -> {
                LocalDate to = LocalDate.now();
                LocalDate from = to.minusDays(7);
                data = insightService.calculateScores(u, from, to);
            }
            default -> {
                return Map.of("contents", List.of(Map.of(
                        "uri", uri, "mimeType", "text/plain",
                        "text", "未知资源：" + uri)));
            }
        }
        String json = om.writeValueAsString(data);
        return Map.of("contents", List.of(Map.of(
                "uri", uri, "mimeType", "application/json", "text", json)));
    }

    private Map<String, Object> resource(String uri, String name, String mime) {
        return Map.of("uri", uri, "name", name, "mimeType", mime);
    }
}
