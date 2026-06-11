package org.zhzssp.memorandum.feature.report.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.report.dto.DailyReport;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 主动式 Agent「晨报 / 晚报」推送协调器（有状态，进程内）。
 *
 * <p>为什么用拉取 + 时间窗，而不是服务端直推：</p>
 * 现有 Electron 客户端已每 60s 带 Cookie 轮询 {@code /due-dates}、{@code /user-logged-in}。
 * 服务端无法主动连到某个特定客户端，因此沿用「客户端轮询、服务端按时间窗 + 每日一次闸门
 * 返回待推送报告」的模型，零改造即可复用现成的桌面通知链路。
 *
 * <p>闸门规则（{@link #poll(User)}）：</p>
 * <ol>
 *   <li>记录该用户「最近活跃时间」（供 Scheduler 判断要不要为他预生成）；</li>
 *   <li>命中晨报窗口且今日未推送过 → 返回晨报（优先用 Scheduler 预生成的缓存，否则即时生成）并打上已推送标记；</li>
 *   <li>否则命中晚报窗口且今日未推送过 → 同理返回晚报；</li>
 *   <li>否则返回 {@link DailyReport#none()}。</li>
 * </ol>
 *
 * <p>状态仅存内存：个人/小规模应用足够；重启后用户下次轮询会重新登记为活跃，
 * 若仍处窗口且当日未送达则即时补生成，不影响体验。</p>
 */
@Service
public class ProactiveReportService {

    private final DailyReportService dailyReportService;

    private final boolean enabled;
    private final int morningStart;
    private final int morningEnd;
    private final int eveningStart;
    private final int eveningEnd;
    private final long activeWindowMinutes;

    private final ConcurrentHashMap<Long, UserReportState> states = new ConcurrentHashMap<>();

    public ProactiveReportService(
            DailyReportService dailyReportService,
            @Value("${report.proactive.enabled:true}") boolean enabled,
            @Value("${report.proactive.morning.window-start-hour:8}") int morningStart,
            @Value("${report.proactive.morning.window-end-hour:11}") int morningEnd,
            @Value("${report.proactive.evening.window-start-hour:21}") int eveningStart,
            @Value("${report.proactive.evening.window-end-hour:24}") int eveningEnd,
            @Value("${report.proactive.active-window-minutes:15}") long activeWindowMinutes) {
        this.dailyReportService = dailyReportService;
        this.enabled = enabled;
        this.morningStart = morningStart;
        this.morningEnd = morningEnd;
        this.eveningStart = eveningStart;
        this.eveningEnd = eveningEnd;
        this.activeWindowMinutes = activeWindowMinutes;
    }

    /** 进程内每个用户的推送状态。 */
    private static final class UserReportState {
        volatile Instant lastActiveAt = Instant.now();
        volatile LocalDate morningDeliveredDate;
        volatile LocalDate eveningDeliveredDate;
        volatile DailyReport cachedMorning;
        volatile LocalDate cachedMorningDate;
        volatile DailyReport cachedEvening;
        volatile LocalDate cachedEveningDate;
    }

    /**
     * 客户端轮询入口：登记活跃，并按闸门规则返回当前应推送的报告（或 none）。
     */
    public DailyReport poll(User user) {
        if (user == null || user.getId() == null) {
            return DailyReport.none();
        }
        UserReportState st = states.computeIfAbsent(user.getId(), k -> new UserReportState());
        st.lastActiveAt = Instant.now();

        if (!enabled) {
            return DailyReport.none();
        }

        LocalDate today = LocalDate.now();
        int hour = LocalTime.now().getHour();

        if (inWindow(hour, morningStart, morningEnd) && !today.equals(st.morningDeliveredDate)) {
            DailyReport report = consumeCachedOrBuild(
                    st.cachedMorning, st.cachedMorningDate, today,
                    () -> dailyReportService.buildMorning(user));
            st.morningDeliveredDate = today;
            st.cachedMorning = null;
            st.cachedMorningDate = null;
            return report;
        }

        if (inWindow(hour, eveningStart, eveningEnd) && !today.equals(st.eveningDeliveredDate)) {
            DailyReport report = consumeCachedOrBuild(
                    st.cachedEvening, st.cachedEveningDate, today,
                    () -> dailyReportService.buildEvening(user));
            st.eveningDeliveredDate = today;
            st.cachedEvening = null;
            st.cachedEveningDate = null;
            return report;
        }

        return DailyReport.none();
    }

    /**
     * Scheduler 预生成晨报缓存（把可能较慢的生成挪到轮询请求路径之外）。
     */
    public void cacheMorning(User user, DailyReport report) {
        if (user == null || user.getId() == null) return;
        UserReportState st = states.computeIfAbsent(user.getId(), k -> new UserReportState());
        st.cachedMorning = report;
        st.cachedMorningDate = LocalDate.now();
    }

    /** Scheduler 预生成晚报缓存（晚报含 LLM 调用，预生成收益最大）。 */
    public void cacheEvening(User user, DailyReport report) {
        if (user == null || user.getId() == null) return;
        UserReportState st = states.computeIfAbsent(user.getId(), k -> new UserReportState());
        st.cachedEvening = report;
        st.cachedEveningDate = LocalDate.now();
    }

    /** 最近活跃（即客户端在线轮询）的用户 id 集合，供 Scheduler 只为在线用户预生成。 */
    public Set<Long> recentlyActiveUserIds() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(activeWindowMinutes));
        return states.entrySet().stream()
                .filter(e -> e.getValue().lastActiveAt.isAfter(threshold))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============================================================
    private boolean inWindow(int hour, int startHour, int endHour) {
        return hour >= startHour && hour < endHour;
    }

    private DailyReport consumeCachedOrBuild(DailyReport cached, LocalDate cachedDate,
                                             LocalDate today, java.util.function.Supplier<DailyReport> builder) {
        if (cached != null && today.equals(cachedDate)) {
            return cached;
        }
        return builder.get();
    }
}
