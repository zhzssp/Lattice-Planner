package org.zhzssp.memorandum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.Goal;
import org.zhzssp.memorandum.entity.GoalType;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.UserRepository;
import org.zhzssp.memorandum.service.GoalService;

import java.security.Principal;
import java.util.List;

@Controller
@RequestMapping("/goal")
public class GoalController {

    @Autowired
    private GoalService goalService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/list")
    @ResponseBody
    public List<Goal> listGoals(Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        return goalService.findActiveGoalsByUser(user);
    }

    @PostMapping("/add")
    @ResponseBody
    public Long addGoal(@RequestParam String name,
                       @RequestParam(required = false) String goalType,
                       Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        Goal goal = new Goal();
        goal.setName(name);
        if (goalType != null && !goalType.isEmpty()) {
            try {
                goal.setGoalType(GoalType.valueOf(goalType));
            } catch (IllegalArgumentException ignored) {}
        }
        goal.setUser(user);
        return goalService.save(goal).getId();
    }

    @PostMapping("/archive/{id}")
    @ResponseBody
    public String archiveGoal(@PathVariable Long id, Principal principal) {
        try {
            User user = userRepository.findByUsername(principal.getName()).orElseThrow();
            goalService.archive(id, user);
            return "success";
        } catch (Exception e) {
            return "error:failed";
        }
    }
}
