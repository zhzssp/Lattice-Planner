package org.zhzssp.memorandum.feature.report.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.report.dto.DailyReport;
import org.zhzssp.memorandum.feature.report.service.DailyReportService;
import org.zhzssp.memorandum.feature.report.service.ProactiveReportService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;

/**
 * 主动式 Agent 晨报 / 晚报接口。
 *
 * <ul>
 *   <li>{@code GET /report/pending} —— 客户端（Electron）轮询入口：按时间窗 + 每日一次
 *       闸门返回应推送的报告，否则 type=none。同时登记该用户「在线活跃」。</li>
 *   <li>{@code GET /report/morning} / {@code GET /report/evening} —— 应用内「立即查看」/
 *       手动触发，不受时间窗与去重限制，便于演示与调试。</li>
 * </ul>
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    private final ProactiveReportService proactiveReportService;
    private final DailyReportService dailyReportService;
    private final UserRepository userRepository;

    public ReportController(ProactiveReportService proactiveReportService,
                            DailyReportService dailyReportService,
                            UserRepository userRepository) {
        this.proactiveReportService = proactiveReportService;
        this.dailyReportService = dailyReportService;
        this.userRepository = userRepository;
    }

    @GetMapping("/pending")
    public DailyReport pending(Principal principal) {
        if (principal == null) {
            return DailyReport.none();
        }
        return userRepository.findByUsername(principal.getName())
                .map(proactiveReportService::poll)
                .orElse(DailyReport.none());
    }

    @GetMapping("/morning")
    public DailyReport morning(Principal principal) {
        User user = currentUser(principal);
        return user == null ? DailyReport.none() : dailyReportService.buildMorning(user);
    }

    @GetMapping("/evening")
    public DailyReport evening(Principal principal) {
        User user = currentUser(principal);
        return user == null ? DailyReport.none() : dailyReportService.buildEvening(user);
    }

    private User currentUser(Principal principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getName()).orElse(null);
    }
}
