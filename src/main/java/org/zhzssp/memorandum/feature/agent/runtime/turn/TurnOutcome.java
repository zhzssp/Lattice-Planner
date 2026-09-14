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
    /**
     * 上下文折叠（滚动摘要）：历史被折叠成一条摘要，旧消息不再逐条保留。
     * 与 {@link #CAUSE_TRUNCATED} 区分——截断是纯丢弃，摘要是「折叠成要点」，
     * 有损程度不同，统计口径上必须能分开看。
     */
    public static final String CAUSE_SUMMARIZED = "SUMMARIZED";

    private final String sessionId;
    private final String mode;
    private final String userInput;

    private TurnEndReason reason;
    private String finalAnswer;
    private int usedSteps;

    /** 粘性：一旦置位不可清除。 */
    private boolean degraded;
    private final Set<String> degradeCauses = new LinkedHashSet<>();

    /** 本轮已被 steer 的次数（L3：由 TurnStoppingBus 递增并受硬上限约束）。 */
    private int steerCount;
    /** 最近一次 steer 的顾问名（供埋点归因）。 */
    private String lastAdvisorName;

    /**
     * 本轮是否<b>真的执行过写操作</b>。
     *
     * <h3>为什么单独记这个，而不是让顾问去看工具名</h3>
     * "哪些工具算写"是<b>工具元数据</b>（{@code @AgentTool} 的 tags）说了算的，
     * 而元数据只有编排器在调用现场拿得到。让顾问去维护一份工具名白名单，
     * 等于把同一份知识抄第二遍——新增写工具时必然有人忘记同步，
     * 而忘记的后果是<b>那个工具的漏网行为再也不会被拦住</b>，且没有任何提示。
     *
     * <p>（这类"同一份知识抄两遍"的坑本项目已经栽过：
     * {@code DisclosureInspector} 与生产判定曾各写一套关键词。）
     */
    private boolean writeToolInvoked;

    /** 本轮实际调用成功的工具名，按顺序、去重。仅用于 steer 措辞与排障。 */
    private final Set<String> invokedTools = new LinkedHashSet<>();

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

    public int steerCount() {
        return steerCount;
    }

    public String lastAdvisorName() {
        return lastAdvisorName;
    }

    /** 记录一次 steer（由 TurnStoppingBus 调用）。 */
    public void recordSteer(String advisorName) {
        this.steerCount++;
        this.lastAdvisorName = advisorName;
    }

    /**
     * 记录一次<b>成功执行</b>的工具调用（由编排器在调用现场调用）。
     *
     * @param tool    工具名
     * @param isWrite 该工具是否带 {@code write} 标签。由编排器从工具元数据判定，
     *                顾问侧不再重复维护一份"哪些算写"的名单
     */
    public void recordToolInvoked(String tool, boolean isWrite) {
        if (tool != null && !tool.isBlank()) {
            invokedTools.add(tool);
        }
        if (isWrite) {
            this.writeToolInvoked = true;
        }
    }

    /** 本轮是否真的执行过写操作。 */
    public boolean writeToolInvoked() {
        return writeToolInvoked;
    }

    public Set<String> invokedTools() {
        return invokedTools;
    }
}
