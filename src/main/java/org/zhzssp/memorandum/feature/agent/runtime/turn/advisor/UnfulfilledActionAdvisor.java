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
 * 空头承诺顾问：模型<b>宣告了要查</b>、却<b>一次工具都没调</b>就收尾时，把它推回去真干。
 *
 * <h3>★ 由真实录制查出的缺陷</h3>
 * 评测用例 {@code readonly_intent_no_write}（"我这周都有啥安排？"）
 * 在三次真实试验里<b>全部</b>返回：
 * <pre>
 *   "我需要先查看你本周的任务安排。今天是 2026-09-04（周五）……
 *    让我查询一下本周的任务情况。"
 * </pre>
 * 然后<b>轮次就结束了</b>——没有任何工具调用，这段话直接发给了用户。
 *
 * <p>为什么会这样：ReAct 循环判定"该收尾了"的依据是
 * <b>本次响应解析不出工具调用</b>。模型用自然语言描述了它的打算而没输出调用 JSON，
 * 于是这段"打算"被当成了终答。系统还把这轮记作 {@code FINAL_ANSWER}（干净完成），
 * 收敛率指标上看不出任何异常。
 *
 * <p>手写录制盒时代查不出这个：盒子里的响应是人写的，
 * 人不会写出"让我查一下"然后就停笔。<b>只有真实模型会。</b>
 *
 * <h3>判定条件（两个都必须成立）</h3>
 * <ol>
 *   <li>{@code usedSteps == 0}——本轮<b>一次工具都没执行过</b>。
 *       这是硬条件：只要真调过工具，就说明它在干活，不属于空头承诺。</li>
 *   <li>终答里出现前瞻性动作宣告（见 {@link AnnouncedActionInspector}）。</li>
 * </ol>
 *
 * <p>为什么必须叠加条件 1：闲聊类答复（"你好，我能帮你管理任务"）同样
 * {@code usedSteps == 0}，但它没有宣告任何动作，是<b>正当的零工具收尾</b>。
 * 只用条件 1 会把所有闲聊全部误伤。反过来只用条件 2 也不行：
 * 模型完全可能先调了工具、再在答复里说"让我再确认一下"，那不是空头承诺。
 */
@Component
public class UnfulfilledActionAdvisor implements TurnStoppingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UnfulfilledActionAdvisor.class);

    @Value("${agent.chat.turn-stopping.unfulfilled-action.enabled:true}")
    private boolean enabled;

    @Override
    public String name() {
        return "unfulfilled-action";
    }

    /**
     * 排在降级明示（order=10）之前。
     *
     * <p>顺序有实际含义：一次工具都没调的轮次，"降级明示"根本无从谈起
     * （没有信息丢失可言）。先把它推回去真正执行，拿到结果之后，
     * 降级明示才有判断对象。
     */
    @Override
    public int order() {
        return 5;
    }

    @Override
    public Optional<String> onTurnStopping(TurnOutcome outcome) {
        if (!enabled) return Optional.empty();
        // 每轮最多 steer 一次（幂等收敛）
        if (outcome.steerCount() > 0) return Optional.empty();
        // 只管"模型自认为答完了"的情况；步数耗尽/异常中止另有措辞
        if (outcome.reason() != TurnEndReason.FINAL_ANSWER) return Optional.empty();
        // 硬条件：真调过工具就不算空头承诺
        if (outcome.usedSteps() > 0) return Optional.empty();

        String phrase = AnnouncedActionInspector.detect(outcome.finalAnswer());
        if (phrase == null) return Optional.empty();

        log.info("[TurnStopping] 空头承诺「{}」且本轮零工具调用，注入 steer：sid={}",
                phrase, outcome.sessionId());
        return Optional.of("""
                [系统提示] 你上面说了「%s」，但并没有发出工具调用，
                这段话已经要被当作最终答复发给用户了——用户会看到一句空头承诺，拿不到任何结果。
                请立即输出你打算调用的那个工具的调用 JSON（形如 {"tool":"...","arguments":{...}}），
                不要再用自然语言描述你的打算。
                如果这件事其实不需要工具，就直接给出完整答案，不要写"让我查一下"。
                """.formatted(phrase));
    }
}
