package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.dto.ApplyGoalPlanRequest;
import org.zhzssp.memorandum.feature.agent.dto.ApplyGoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanRequest;
import org.zhzssp.memorandum.feature.agent.dto.GoalPlanResponse;
import org.zhzssp.memorandum.feature.agent.service.AgentPlanApplyService;
import org.zhzssp.memorandum.feature.agent.service.PlannerAgentService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;

/**
 * Phase 1 Agent API。独立前缀，避免影响现有功能。
 */
@RestController
@RequestMapping("/api/agent/planning")
public class AgentPlanningController {

    private final PlannerAgentService plannerAgentService;
    private final AgentPlanApplyService agentPlanApplyService;
    private final UserRepository userRepository;

    public AgentPlanningController(PlannerAgentService plannerAgentService,
                                   AgentPlanApplyService agentPlanApplyService,
                                   UserRepository userRepository) {
        this.plannerAgentService = plannerAgentService;
        this.agentPlanApplyService = agentPlanApplyService;
        this.userRepository = userRepository;
    }

    @PostMapping("/draft")
    public ResponseEntity<GoalPlanResponse> draft(@RequestBody GoalPlanRequest request) {
        GoalPlanResponse response = plannerAgentService.draftPlan(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/apply")
    public ResponseEntity<ApplyGoalPlanResponse> apply(@RequestBody ApplyGoalPlanRequest request,
                                                       Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        ApplyGoalPlanResponse response = agentPlanApplyService.apply(user, request.plan());
        return ResponseEntity.ok(response);
    }
}
