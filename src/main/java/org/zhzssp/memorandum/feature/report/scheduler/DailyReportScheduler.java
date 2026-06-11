package org.zhzssp.memorandum.feature.report.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.report.dto.DailyReport;
import org.zhzssp.memorandum.feature.report.service.DailyReportService;
import org.zhzssp.memorandum.feature.report.service.ProactiveReportService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

/**
 * 晨报 / 晚报定时预生成器。
 *
 * <p>职责：到点（窗口开启时刻）为「最近在线轮询」的用户提前把报告生成好并写入
 * {@link ProactiveReportService} 的缓存，使随后客户端 60s 轮询能瞬时拿到结果——
 * 尤其晚报含 LLM 复盘调用（可能数秒～数十秒），预生成可避免阻塞轮询请求线程。</p>
 *
 * <p>仅为「最近活跃用户」预生成：未运行客户端的用户不浪费 LLM 调用；
 * 客户端持续在线轮询，到点时必然已落在活跃集合内。即使 Scheduler 未跑（如刚重启），
 * 轮询闸门仍会即时补生成，预生成只是性能优化而非正确性前提。</p>
 */
@Component
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final ProactiveReportService proactiveReportService;
    private final DailyReportService dailyReportService;
    private final UserRepository userRepository;

    public DailyReportScheduler(ProactiveReportService proactiveReportService,
                                DailyReportService dailyReportService,
                                UserRepository userRepository) {
        this.proactiveReportService = proactiveReportService;
        this.dailyReportService = dailyReportService;
        this.userRepository = userRepository;
    }

    /** 晨报预生成：默认每天 08:00。 */
    @Async
    @Scheduled(cron = "${report.proactive.morning.cron:0 0 8 * * *}")
    public void preGenerateMorning() {
        if (!proactiveReportService.isEnabled()) return;
        Set<Long> ids = proactiveReportService.recentlyActiveUserIds();
        log.info("[DailyReport] 预生成晨报，活跃用户数={}", ids.size());
        for (Long uid : ids) {
            Optional<User> user = userRepository.findById(uid);
            if (user.isEmpty()) continue;
            try {
                DailyReport report = dailyReportService.buildMorning(user.get());
                proactiveReportService.cacheMorning(user.get(), report);
            } catch (Exception e) {
                log.warn("[DailyReport] 晨报预生成失败 userId={}", uid, e);
            }
        }
    }

    /** 晚报预生成：默认每天 21:00。 */
    @Async
    @Scheduled(cron = "${report.proactive.evening.cron:0 0 21 * * *}")
    public void preGenerateEvening() {
        if (!proactiveReportService.isEnabled()) return;
        Set<Long> ids = proactiveReportService.recentlyActiveUserIds();
        log.info("[DailyReport] 预生成晚报，活跃用户数={}", ids.size());
        for (Long uid : ids) {
            Optional<User> user = userRepository.findById(uid);
            if (user.isEmpty()) continue;
            try {
                DailyReport report = dailyReportService.buildEvening(user.get());
                proactiveReportService.cacheEvening(user.get(), report);
            } catch (Exception e) {
                log.warn("[DailyReport] 晚报预生成失败 userId={}", uid, e);
            }
        }
    }
}
