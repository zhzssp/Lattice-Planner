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
 * 讨许可顾问：模型查清楚了、目标也定了，却在文字里问一句"确认吗"就收尾。
 *
 * <h3>★ 由真实录制查出的缺陷（与 {@link UnfulfilledActionAdvisor} 是<b>两种</b>形态）</h3>
 * <p>评测用例 {@code batch_complete_overdue_only}（"把已经过期的任务都标记完成"）
 * 三次真实试验里<b>两次直接做了、一次停下来问</b>：</p>
 * <pre>
 *   trial 0 ✅ "已把这 3 个已过期的任务标记为完成：……"
 *   trial 1 ❌ "已过期的任务有：90203…、90202…、90201…。确认把这些标记为完成吗？"
 *   trial 2 ✅ "已把这 3 个已过期的任务标记为完成：……"
 * </pre>
 * <p>同一句祈使、同一批数据，三次跑出两种行为。用户拿到哪种<b>纯看运气</b>。</p>
 *
 * <h3>为什么 {@link UnfulfilledActionAdvisor} 拦不住它</h3>
 * <p>那个顾问有一条硬条件 {@code usedSteps == 0}（一次工具都没调）。
 * 而这里模型<b>确实调了工具</b>——它调了 {@code task.today} 把三条过期任务查得清清楚楚、
 * 连 ID 都列出来了，只是没调 {@code task.complete}。{@code usedSteps > 0}，直接被放行。</p>
 *
 * <p>所以判据不能是"有没有调工具"，而必须是
 * <b>"有没有调<i>写</i>工具"</b>（见 {@link TurnOutcome#writeToolInvoked()}）。
 * 查完了不写，正是"只说不做"最典型的样子。</p>
 *
 * <h3>判定条件（三个都必须成立）</h3>
 * <ol>
 *   <li>终答里有许可式反问（见 {@link PermissionSeekingInspector}，
 *       刻意只认"确认/确定"，不认"要我……吗"）；</li>
 *   <li>{@code writeToolInvoked == false}——本轮<b>一次写都没落地</b>。
 *       已经写完了再问"还需要处理另一条吗"是<b>正当的后续提议</b>，不该拦；</li>
 *   <li>{@code FINAL_ANSWER}——模型自认为答完了。步数耗尽另有措辞。</li>
 * </ol>
 *
 * <h3>这不是在禁止模型提问</h3>
 * <p>真缺信息时该问还得问：{@code ambiguous_asks_clarification}（"帮我安排一下"）
 * 期望的正确行为就是反问"你是想安排什么呢"。那句话里没有"确认/确定"，
 * 因为它问的是<b>缺失的信息</b>而不是<b>已定动作的许可</b>。
 * 这条线是本顾问全部精度的来源。</p>
 */
@Component
public class UnconfirmedWriteAdvisor implements TurnStoppingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedWriteAdvisor.class);

    @Value("${agent.chat.turn-stopping.unconfirmed-write.enabled:true}")
    private boolean enabled;

    @Override
    public String name() {
        return "unconfirmed-write";
    }

    /**
     * 排在空头承诺（order=5）之后、降级明示（order=10）之前。
     *
     * <p>与 5 的先后有实际含义：{@code usedSteps == 0} 且宣告了动作时两者都会命中，
     * 此时该由 5 处理——它的措辞是"你还什么都没做"，比本顾问的
     * "你查完了但没写"更贴合那个场景。总线的单次 steer 上限保证不会连开两枪。
     */
    @Override
    public int order() {
        return 6;
    }

    @Override
    public Optional<String> onTurnStopping(TurnOutcome outcome) {
        if (!enabled) return Optional.empty();
        if (outcome.steerCount() > 0) return Optional.empty();
        if (outcome.reason() != TurnEndReason.FINAL_ANSWER) return Optional.empty();
        // 硬条件：真写过就不算扣着不做（写完再提议下一步是正当行为）
        if (outcome.writeToolInvoked()) return Optional.empty();

        String question = PermissionSeekingInspector.detect(outcome.finalAnswer());
        if (question == null) return Optional.empty();

        log.info("[TurnStopping] 讨许可「{}」且本轮零写操作，注入 steer：sid={} 已调工具={}",
                question, outcome.sessionId(), outcome.invokedTools());
        return Optional.of("""
                [系统提示] 你上面问了「%s」，然后就收尾了——本轮没有执行任何写操作。

                这个系统里，需要用户点头的操作由**系统弹窗**负责拦截，不需要你在文字里先问一遍。
                你直接发出工具调用即可：真正危险的操作会自动弹窗让用户确认，
                安全的操作则直接执行。你在文字里问一句，用户只会多等一轮。

                如果你已经知道该做什么、对象也确定了，请立即输出工具调用 JSON
                （形如 {"tool":"...","arguments":{...}}）。
                只有在**确实缺少必要信息**、不问就无法动手时，才应该反问用户——
                那种情况下请直接问缺的那项信息，不要问"要不要执行"。
                """.formatted(question));
    }
}
