package org.zhzssp.memorandum.feature.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanRequest;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanResponse;

import java.util.List;

@Service
public class PlannerAgentService {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public PlannerAgentService(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public GoalPlanResponse draftPlan(GoalPlanRequest request) {
        validateRequest(request);

        if (needsClarification(request.goalStatement())) {
            return new GoalPlanResponse(
                    request.goalStatement(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                            "你的目标最终交付物是什么？（例如：论文、产品原型、上线版本）",
                            "希望在多长时间内完成？",
                            "可投入的每周时间大约是多少小时？",
                            "是否有不可变约束（预算/技术栈/协作者）？"
                    ),
                    1,
                    "local-clarify"
            );
        }

        try {
            String prompt = buildPlannerPrompt(request);
            String json = llmGateway.generateText(prompt);
            GoalPlanResponse parsed = objectMapper.readValue(extractJson(json), GoalPlanResponse.class);
            return new GoalPlanResponse(
                    parsed.goalStatement() != null ? parsed.goalStatement() : request.goalStatement(),
                    parsed.assumptions() != null ? parsed.assumptions() : List.of(),
                    parsed.milestones() != null ? parsed.milestones() : List.of(),
                    parsed.tasks() != null ? parsed.tasks() : List.of(),
                    parsed.risks() != null ? parsed.risks() : List.of(),
                    List.of(),
                    parsed.revision() != null ? parsed.revision() : 1,
                    "llm"
            );
        } catch (Exception ex) {
            return new GoalPlanResponse(
                    request.goalStatement(),
                    List.of("LLM不可用，已返回基础规划模板"),
                    List.of(),
                    List.of(),
                    List.of("请配置 agent.llm.api-key 后重试以获得完整任务树"),
                    List.of(),
                    1,
                    "fallback"
            );
        }
    }

    private void validateRequest(GoalPlanRequest request) {
        if (request == null || request.goalStatement() == null || request.goalStatement().isBlank()) {
            throw new IllegalArgumentException("goalStatement 不能为空");
        }
    }

    private boolean needsClarification(String goalStatement) {
        String trimmed = goalStatement == null ? "" : goalStatement.trim();
        return trimmed.length() < 12;
    }

    private String buildPlannerPrompt(GoalPlanRequest request) {
        return """
你是一个任务分解规划专家。请把用户目标拆解成可执行的里程碑和任务树。

要求：
1) 只输出 JSON，不要输出额外说明。
2) 任务粒度以 1~8 小时为主。
3) 任务必须包含 id/title/description/parentId/dependsOn/priority/estimateHours/acceptanceCriteria。
4) priority 仅允许 P0/P1/P2。
5) 如果有不确定信息，写入 assumptions 或 risks。

输出 JSON 结构：
{
  "goalStatement": "",
  "assumptions": [""],
  "milestones": [{"id":"M1","name":"","dueDate":"YYYY-MM-DD","taskIds":["T1"]}],
  "tasks": [{"id":"T1","title":"","description":"","parentId":null,"dependsOn":[],"priority":"P1","estimateHours":4,"acceptanceCriteria":[""]}],
  "risks": [""],
  "revision": 1
}

用户目标：%s
约束：%s
""".formatted(
                request.goalStatement(),
                request.constraints() == null ? "[]" : request.constraints().toString()
        );
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }
}
