package org.zhzssp.memorandum.feature.agent.runtime.turn.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnEndReason;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnStoppingAdvisor;

import java.util.Optional;

/**
 * 降级明示顾问（方案 L，L4）。
 *
 * <p>本轮发生过信息丢失（粘性 degraded 置位），但终态答复里<strong>没有任何提示</strong>时，
 * 强制注入一条 steer，让模型重新给出<strong>诚实声明</strong>的答复。</p>
 *
 * <p>这是 CRAG「硬闭环」思路在轮次层面的延伸：此前降级明示靠 CRAG 的 {@code message}
 * 字段和 system prompt 的软约束，模型可能不遵守。本顾问在收尾前做一次<strong>确定性检查</strong>
 * ——不赌模型自觉。</p>
 *
 * <p>价值判断：在还没能力「不丢信息」之前，先做到「不隐瞒信息丢失」。</p>
 */
@Component
public class DegradeDisclosureAdvisor implements TurnStoppingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(DegradeDisclosureAdvisor.class);

    @Value("${agent.chat.turn-stopping.degrade-disclosure.enabled:true}")
    private boolean enabled;

    @Override
    public String name() {
        return "degrade-disclosure";
    }

    @Override
    public int order() {
        return 10;   // 让降级明示优先于主动衔接（L5）
    }

    @Override
    public Optional<String> onTurnStopping(TurnOutcome outcome) {
        if (!enabled) return Optional.empty();

        // 仅当本轮真发生过降级
        if (!outcome.degraded()) return Optional.empty();
        // 每轮最多 steer 一次（幂等收敛）
        if (outcome.steerCount() > 0) return Optional.empty();
        // 仅对「正常收敛」的答复做明示；步数耗尽/异常中止的措辞已自带说明
        if (outcome.reason() != TurnEndReason.FINAL_ANSWER) return Optional.empty();
        // 答复已诚实交代，放行
        if (DisclosureInspector.adequatelyDisclosed(outcome.finalAnswer())) return Optional.empty();

        String causes = String.join("、", outcome.degradeCauses());
        String fabricated = DisclosureInspector.detectFabricatedAttribution(outcome.finalAnswer());

        if (fabricated != null) {
            log.info("[TurnStopping] 伪造归属「{}」，注入 steer：causes={}", fabricated, causes);
            return Optional.of("""
                    [系统提示] 本轮检索到的内容并不可靠（原因：%s），但你上面的答复用
                    「%s」把这些内容说成了出自用户自己的笔记。这会让用户误以为是自己记过的结论，
                    从而放弃核实。请重新给出最终答复：明确说明未能从用户笔记中找到相关内容、
                    以下属于通用知识，并去掉一切把内容归到用户笔记名下的表述。
                    不要调用任何工具，直接输出自然语言中文。
                    """.formatted(causes, fabricated));
        }

        log.info("[TurnStopping] 降级未明示，注入 steer：causes={}", causes);
        return Optional.of("""
                [系统提示] 本轮执行过程中存在信息不完整的情况（原因：%s）。
                你上面的答复没有向用户说明这一点。请重新给出最终答复：
                在开头简短说明信息可能不完整（例如"部分内容因过长被截断，以下基于已有信息"），
                然后再给出你的结论。不要调用任何工具，直接输出自然语言中文。
                """.formatted(causes));
    }
}
