package org.zhzssp.memorandum.feature.agent.subagent;

import java.util.List;

/**
 * 子代理一次运行的压缩结论。只有 {@link #finalText} 会回灌主 Agent 上下文，
 * 子代理内部的素材全文 / 中间工具 JSON 全部留在子上下文并随其结束被丢弃。
 *
 * @param role      角色名（PLANNER / REFLECTION / RESEARCH）
 * @param finalText 给主 Agent 的最终自然语言结论
 * @param steps     子代理实际推理步数
 * @param toolsUsed 子代理用到的工具名列表（用于可视化叙事）
 * @param truncated 是否因达最大步数 / 异常而提前收尾
 */
public record SubAgentResult(
        String role,
        String finalText,
        int steps,
        List<String> toolsUsed,
        boolean truncated) {
}
