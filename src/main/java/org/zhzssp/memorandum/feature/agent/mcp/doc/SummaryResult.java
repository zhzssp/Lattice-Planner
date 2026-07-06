package org.zhzssp.memorandum.feature.agent.mcp.doc;

/**
 * 摘要结果。
 *
 * @param content      摘要文本（或原文截断）
 * @param summarized   是否确实经历了 LLM 摘要（false 表示原文直传或降级截断）
 * @param chunkCount   原文被切为几块（直传时为 0）
 */
public record SummaryResult(
        String content,
        boolean summarized,
        int chunkCount
) {
    /** 短文档原文直传。 */
    public static SummaryResult direct(String content) {
        return new SummaryResult(content, false, 0);
    }

    /** 正常摘要产出。 */
    public static SummaryResult summarized(String content, int chunkCount) {
        return new SummaryResult(content, true, chunkCount);
    }

    /** 降级：截断原文 + 标注。 */
    public static SummaryResult truncated(String content, String fileName) {
        String marker = "\n\n---\n⚠ 原文过长且摘要失败，以下为截断后的原文前段（来自：" + fileName + "）\n---\n\n";
        return new SummaryResult(marker + content, false, 0);
    }
}
