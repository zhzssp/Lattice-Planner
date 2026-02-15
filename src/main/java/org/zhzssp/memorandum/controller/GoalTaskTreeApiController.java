package org.zhzssp.memorandum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.goal.dto.GoalWithTasks;
import org.zhzssp.memorandum.feature.goal.dto.TreeNodeDto;
import org.zhzssp.memorandum.feature.goal.service.GoalService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目标与任务树状图 API：返回层级 JSON 供前端 D3 绘制节点与边。
 */
@RestController
@RequestMapping("/api")
public class GoalTaskTreeApiController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private GoalService goalService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/goal-task-tree")
    public TreeNodeDto getGoalTaskTree(Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        List<GoalWithTasks> list = goalService.findGoalTaskTree(user);
        List<TreeNodeDto> goalNodes = list.stream()
                .map(item -> new TreeNodeDto(
                        item.goal().getName(),
                        "goal",
                        item.goal().getId(),
                        item.goal().getGoalType() != null ? item.goal().getGoalType().name() : null,
                        null,
                        item.tasks().stream()
                                .map(t -> new TreeNodeDto(
                                        t.getTitle(),
                                        "task",
                                        t.getId(),
                                        null,
                                        t.getDeadline() != null ? t.getDeadline().format(DATE_FMT) : null,
                                        null
                                ))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
        return new TreeNodeDto("根", "root", null, null, null, goalNodes);
    }
}
