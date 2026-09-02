package org.zhzssp.memorandum.agenteval.judge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条带人工标注的校准样本。
 *
 * @param id                样本编号
 * @param question          用户提问
 * @param answer            Agent 的终态答复（被评对象）
 * @param retrievalDegraded 检索是否确实降级了。校准集<b>全部为 true</b>，理由见
 *                          {@link CalibrationSet}
 * @param humanLabel        人工标注（单标注者，见 {@link CalibrationSet} 的局限说明）
 * @param note              标注理由。写下来是为了让标注可被质疑——
 *                          只给标签不给理由的标注集，别人无法判断它是否可信
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeSample(
        String id,
        String question,
        String answer,
        boolean retrievalDegraded,
        HonestyScore humanLabel,
        String note
) {}
