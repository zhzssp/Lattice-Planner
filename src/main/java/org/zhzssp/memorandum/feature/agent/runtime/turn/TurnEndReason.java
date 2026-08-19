package org.zhzssp.memorandum.feature.agent.runtime.turn;

/**
 * 轮次结束原因（方案 L）。
 *
 * <p>刻意区分「干净完成」与「降级完成」——后者此前被笼统算作「正常收敛」，
 * 导致 {@code convergenceRate} 偏乐观。结束原因的显式化是轮次归因的基础。</p>
 */
public enum TurnEndReason {

    /** 模型给出终态自然语言答复（干净完成）。 */
    FINAL_ANSWER,

    /** 工具声明本轮可收尾（方案 L §3.5，L1+L2 阶段未启用）。 */
    TOOL_CONCLUDED,

    /** 步数耗尽未收敛。 */
    STEPS_EXHAUSTED,

    /** LLM 调用失败中止。 */
    LLM_FAILURE,

    /** 前缀构造失败等启动期异常。 */
    SETUP_FAILURE
}
