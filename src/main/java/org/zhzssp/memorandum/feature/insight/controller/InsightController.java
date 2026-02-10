package org.zhzssp.memorandum.feature.insight.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService;
import org.zhzssp.memorandum.feature.insight.service.InsightScoreService.DailyScore;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * 规划完成度评分接口（Insight 插件）。
 *
 * 提供按日统计的得分曲线数据，用于前端绘制折线图。
 */
@RestController
@RequestMapping("/insight")
public class InsightController {

    @Autowired
    private InsightScoreService insightScoreService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/score")
    public List<DailyScore> score(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            Principal principal
    ) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();

        LocalDate today = LocalDate.now();
        if (end == null) {
            end = today;
        }
        if (start == null) {
            start = end.minusDays(13); // 默认展示近 14 天
        }

        return insightScoreService.calculateScores(user, start, end);
    }
}

