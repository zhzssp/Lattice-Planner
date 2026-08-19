package org.zhzssp.memorandum.feature.agent.runtime.turn;

import java.util.Optional;

/**
 * 轮次收尾顾问（方案 L，L3 起）。
 *
 * <p>在轮次<strong>真正关闭前</strong>被调用，可返回一条 steer 消息让轮次继续，
 * 从而把「什么时候算结束」从 ReAct 循环内部解耦到可插拔的外部顾问。</p>
 *
 * <p>实现约定（与 {@code AgentTraceListener} 同源）：</p>
 * <ul>
 *   <li>必须非阻塞、不抛异常——异常由 {@code TurnStoppingBus} 吞掉降级为日志；</li>
 *   <li>返回 {@link Optional#empty()} 表示「我没意见，可以结束」；</li>
 *   <li>返回非空表示「还没完」，内容会作为 user 消息回灌，循环继续；</li>
 *   <li><strong>必须自己保证幂等/收敛</strong>——同一轮不要反复 steer 同一件事。
 *       Bus 的单轮 steer 次数硬上限是兜底，不是顾问可以依赖的收敛机制。</li>
 * </ul>
 *
 * <p>Spring 注入所有实现为 {@code List}，无实现时为空列表（零配置可选）。</p>
 */
public interface TurnStoppingAdvisor {

    /** 顾问名，用于埋点与日志归因。 */
    String name();

    /** 越小越先执行；首个返回非空的顾问胜出，其余不再询问。 */
    default int order() {
        return 0;
    }

    /**
     * 收尾前询问。
     *
     * @param outcome 本轮收尾上下文（只读视图 + 可通过 recordSteer 感知当前状态）
     * @return 空 = 可结束；非空 = steer 消息，回灌后轮次继续
     */
    Optional<String> onTurnStopping(TurnOutcome outcome);
}
