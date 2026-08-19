package org.zhzssp.memorandum.feature.agent.runtime.turn.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.service.TaskService;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnEndReason;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnStoppingAdvisor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 主动衔接顾问（方案 L，L5）。
 *
 * <p>把「晨报/晚报」的信息供给接进对话收尾时刻：复盘类对话收尾前，
 * 若明日有多个任务到期，主动追问用户是否要帮忙排期。</p>
 *
 * <p>解决「主动式能力与对话割裂」：此前晨报/晚报走独立轮询通道，
 * 用户刚做完复盘，系统明知「明天有 3 个任务到期」却无法在对话里自然接一句。</p>
 *
 * <p><strong>复用而非新建</strong>：数据来自既有的 {@link TaskService#searchTasks}，
 * 本顾问只负责「在什么时机、以什么措辞」接入。</p>
 *
 * <h3>触发条件为什么这么严</h3>
 * <p>主动性一旦过度就是骚扰。六道闸门缺一不可，与晨报/晚报的
 * 「时间窗 + 每日一次」是同一种设计克制。</p>
 */
@Component
public class ProactiveFollowUpAdvisor implements TurnStoppingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProactiveFollowUpAdvisor.class);

    private final TaskService taskService;

    @Value("${agent.chat.turn-stopping.proactive-follow-up.enabled:true}")
    private boolean enabled;

    /** 明日到期任务达到此阈值才追问（默认 3）。 */
    @Value("${agent.chat.turn-stopping.proactive-follow-up.due-threshold:3}")
    private int dueThreshold;

    // @Lazy：TaskService 属核心层，本顾问属 agent 层，避免潜在装配顺序问题
    public ProactiveFollowUpAdvisor(@Lazy TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String name() {
        return "proactive-follow-up";
    }

    @Override
    public int order() {
        return 100;   // 让降级明示（order=10）先行
    }

    @Override
    public Optional<String> onTurnStopping(TurnOutcome outcome) {
        if (!enabled) return Optional.empty();

        // ① 每轮最多接一次（幂等收敛）
        if (outcome.steerCount() > 0) return Optional.empty();
        // ② 仅正常收敛的答复
        if (outcome.reason() != TurnEndReason.FINAL_ANSWER) return Optional.empty();
        // ③ 本轮已降级，别再加负担（降级明示顾问已介入）
        if (outcome.degraded()) return Optional.empty();
        // ④ 仅复盘类语境
        if (!isReflectiveTurn(outcome)) return Optional.empty();
        // ⑤ 答复已经提到了追问/明日，放行
        if (alreadyMentionsFollowUp(outcome.finalAnswer())) return Optional.empty();

        // ⑥ 明日到期任务数是否达阈值（全链路容错，异常返回 0 → 不追问）
        int dueCount = safeCountTomorrowDue();
        if (dueCount < Math.max(1, dueThreshold)) return Optional.empty();

        log.info("[TurnStopping] 主动衔接：明日到期 {} 个任务，注入追问", dueCount);
        return Optional.of("""
                [系统补充信息] 明天有 %d 个任务即将到期。
                请在你上面的答复末尾，用一句自然的中文主动询问用户是否需要你帮忙排期，
                不要重复已经说过的内容，不要调用任何工具，直接给出完整的最终答复。
                """.formatted(dueCount));
    }

    /** 复盘类语境：mode == reflect。其余模式暂不触发（避免骚扰）。 */
    private boolean isReflectiveTurn(TurnOutcome outcome) {
        return "reflect".equalsIgnoreCase(outcome.mode());
    }

    private boolean alreadyMentionsFollowUp(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String s = answer;
        return s.contains("排期") || s.contains("明天") || s.contains("明日")
                || s.contains("需要我") || s.contains("要不要");
    }

    /** 统计明日到期任务数；任何异常返回 0（全链路容错，绝不因追问阻塞主链路）。 */
    private int safeCountTomorrowDue() {
        try {
            User user = AgentContext.requireUser();
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            LocalDateTime start = tomorrow.atStartOfDay();
            LocalDateTime end = tomorrow.plusDays(1).atStartOfDay();
            List<Task> tasks = taskService.searchTasks(user.getId(), null, start, end);
            return tasks == null ? 0 : tasks.size();
        } catch (Exception e) {
            // 非 Agent 线程（无 AgentContext）或查询异常，静默降级
            log.debug("[TurnStopping] 统计明日到期失败：{}", e.getMessage());
            return 0;
        }
    }
}
