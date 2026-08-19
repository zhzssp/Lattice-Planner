package org.zhzssp.memorandum.feature.agent.runtime.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 轮次收尾顾问分发器（方案 L，L3）。
 *
 * <p>职责：按 {@code order} 依次询问顾问，首个返回非空的胜出；同时执行
 * <strong>失控防护</strong>——steer 会让循环 {@code continue}，若顾问实现有 bug
 * （每次都返回非空），就是一个无限循环 + 无限 LLM 调用 + 无限烧钱。
 * 因此单轮 steer 次数上限由 Bus 强制，顾问<strong>无法绕过</strong>。</p>
 *
 * <p>这与方案 D 的「执行层强制」是同一立场：<strong>不能把正确性押在实现者的自觉上</strong>。
 * 第二道防护是「剩余步数检查」：steer 会消耗至少一步，不能在最后一步 steer
 * 把轮次推进「步数耗尽」死路，让用户什么都拿不到。</p>
 */
@Component
public class TurnStoppingBus {

    private static final Logger log = LoggerFactory.getLogger(TurnStoppingBus.class);

    private final List<TurnStoppingAdvisor> advisors;

    @Value("${agent.chat.turn-stopping.enabled:false}")
    private boolean enabled;

    @Value("${agent.chat.turn-stopping.max-steer-per-turn:1}")
    private int maxSteer;

    public TurnStoppingBus(List<TurnStoppingAdvisor> advisors) {
        this.advisors = advisors == null ? List.of() :
                advisors.stream()
                        .sorted(Comparator.comparingInt(TurnStoppingAdvisor::order))
                        .toList();
        log.info("[TurnStopping] 已装载 {} 个收尾顾问：{}",
                this.advisors.size(),
                this.advisors.stream().map(TurnStoppingAdvisor::name).toList());
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxSteer() {
        return maxSteer;
    }

    /**
     * 询问所有顾问（按 order 升序）。返回首个非空 steer。
     *
     * @param outcome  本轮收尾上下文
     * @param maxSteps 主循环步数上限（用于剩余步数防护）
     * @return 空 = 可结束；非空 = steer 消息
     */
    public Optional<String> consult(TurnOutcome outcome, int maxSteps) {
        if (!enabled) return Optional.empty();

        // ① 单轮 steer 硬上限（顾问无法绕过）
        if (outcome.steerCount() >= Math.max(0, maxSteer)) {
            log.debug("[TurnStopping] steer 已达上限 {}/{}，强制收尾", outcome.steerCount(), maxSteer);
            return Optional.empty();
        }
        // ② 剩余步数保护：steer 消耗至少一步，不能在最后一步 steer
        if (outcome.usedSteps() >= maxSteps - 1) {
            log.debug("[TurnStopping] 剩余步数不足（usedSteps={}），不再 steer", outcome.usedSteps());
            return Optional.empty();
        }

        for (TurnStoppingAdvisor advisor : advisors) {
            try {
                Optional<String> steer = advisor.onTurnStopping(outcome);
                if (steer.isPresent() && !steer.get().isBlank()) {
                    outcome.recordSteer(advisor.name());
                    return steer;
                }
            } catch (Exception e) {
                log.debug("[TurnStopping] 顾问 {} 处理异常（已忽略）：{}", advisor.name(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
