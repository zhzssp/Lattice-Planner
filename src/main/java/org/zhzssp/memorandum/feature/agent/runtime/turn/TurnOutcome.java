package org.zhzssp.memorandum.feature.agent.runtime.turn;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单轮收尾上下文（方案 L）。
 *
 * <p>承载本轮结束原因、终态答复、步数与<strong>粘性降级标记</strong>。
 * L1+L2 阶段仅用于轮次归因与降级可观测，尚未引入收尾顾问（L3 起）。</p>
 *
 * <h3>粘性降级标记</h3>
 * <p>{@link #markDegraded(String)} 只能置位、<strong>不能清除</strong>——
 * 本轮一旦发生过信息丢失（截断 / CRAG 降级 / 子代理截断 / 工具封禁），
 * 后续步骤正常收敛<strong>不得</strong>把轮次「洗白」成干净完成。
 * 这正是 DSH「max-tokens 状态粘性」在本项目的等价落地。</p>
 */
public final class TurnOutcome {

    /** 降级原因常量，进入 {@code degradeCauses} 集合。 */
    public static final String CAUSE_TRUNCATED = "TRUNCATED";
    public static final String CAUSE_CRAG_DEGRADED = "CRAG_DEGRADED";
    public static final String CAUSE_SUBAGENT_TRUNCATED = "SUBAGENT_TRUNCATED";
    public static final String CAUSE_TOOL_BANNED = "TOOL_BANNED";

    private final String sessionId;
    private final String mode;
    private final String userInput;

    private TurnEndReason reason;
    private String finalAnswer;
    private int usedSteps;

    /** 粘性：一旦置位不可清除。 */
    private boolean degraded;
    private final Set<String> degradeCauses = new LinkedHashSet<>();

    public TurnOutcome(String sessionId, String mode, String userInput) {
        this.sessionId = sessionId;
        this.mode = mode;
        this.userInput = userInput;
    }

    public String sessionId() {
        return sessionId;
    }

    public String mode() {
        return mode;
    }

    public String userInput() {
        return userInput;
    }

    public TurnEndReason reason() {
        return reason;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public int usedSteps() {
        return usedSteps;
    }

    public boolean degraded() {
        return degraded;
    }

    public Set<String> degradeCauses() {
        return degradeCauses;
    }

    /** 置位结束原因与终态答复。 */
    public void propose(TurnEndReason reason, String finalAnswer, int usedSteps) {
        this.reason = reason;
        this.finalAnswer = finalAnswer;
        this.usedSteps = usedSteps;
    }

    /** 置位粘性降级标记（幂等）。 */
    public void markDegraded(String cause) {
        this.degraded = true;
        if (cause != null && !cause.isBlank()) {
            this.degradeCauses.add(cause);
        }
    }
}
