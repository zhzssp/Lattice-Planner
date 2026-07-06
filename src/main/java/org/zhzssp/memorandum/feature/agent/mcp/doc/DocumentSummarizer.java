package org.zhzssp.memorandum.feature.agent.mcp.doc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档摘要层。
 * <ul>
 * <li>短文档（≤4000 字符）原文直传，不走 LLM，省 token 且更快。</li>
 * <li>长文档走 map-reduce 摘要：按段落边界分块 → 每块独立摘要 → 合并，若合并后仍过长再做一轮 reduce。</li>
 * <li>任意步骤异常时降级为「截断原文 + 标注」，绝不抛异常阻断 Agent 工具调用。</li>
 * </ul>
 */
@Component
public class DocumentSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentSummarizer.class);

    /** 短文档阈值（字符数）：低于此值原文直传。 */
    private static final int DIRECT_THRESHOLD = 4000;

    /** 单块摘要粒度（字符数）：尽量在段落边界切分。 */
    private static final int CHUNK_SIZE = 3000;

    /** 降级截断长度。 */
    private static final int TRUNCATE_LENGTH = DIRECT_THRESHOLD;

    private static final String SUMMARIZE_PROMPT =
            "以下是文档「%s」的第 %d/%d 部分内容，请用中文提炼要点（保留关键数据/结论，不要展开背景），150 字以内：\n\n%s";

    private static final String REDUCE_PROMPT =
            "以下是文档「%s」的多个分块摘要，请整合为一份完整摘要（保留关键数据/结论），300 字以内：\n\n%s";

    private final LlmGateway llmGateway;

    public DocumentSummarizer(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    /**
     * 对提取后的文档文本做摘要（或直传）。
     *
     * @param plainText 提取器产出的纯文本
     * @param fileName  原始文件名（仅用于日志与 Prompt 中标识来源）
     * @return 摘要结果（绝不会为 null）
     */
    public SummaryResult summarize(String plainText, String fileName) {
        if (plainText == null || plainText.isBlank()) {
            return SummaryResult.direct("");
        }
        if (plainText.length() <= DIRECT_THRESHOLD) {
            return SummaryResult.direct(plainText);
        }

        try {
            return doMapReduce(plainText, fileName);
        } catch (Exception e) {
            log.warn("[DocSummarizer] map-reduce 摘要失败，降级截断原文：file={}, err={}", fileName, e.toString());
            return fallbackTruncate(plainText, fileName);
        }
    }

    /** map-reduce 主流程。 */
    private SummaryResult doMapReduce(String text, String fileName) {
        List<String> chunks = splitByParagraph(text, CHUNK_SIZE);
        if (chunks.size() == 1) {
            // 单块但超阈值（段落核爆），直接压缩
            return SummaryResult.truncated(truncate(text, TRUNCATE_LENGTH), fileName);
        }

        // Map：各块独立摘要
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String summary = summarizeChunk(chunk, fileName, i + 1, chunks.size());
            if (summary != null) {
                chunkSummaries.add(summary);
            }
        }

        if (chunkSummaries.isEmpty()) {
            return fallbackTruncate(text, fileName);
        }

        // Reduce
        String combined = String.join("\n\n", chunkSummaries);
        if (combined.length() > DIRECT_THRESHOLD) {
            // 合并后仍过长 → 二次 reduce
            String reduced = reduceSummaries(combined, fileName);
            if (reduced != null) {
                return SummaryResult.summarized(reduced, chunks.size());
            }
            // reduce 也失败 → 用一次摘要结果截断
            return SummaryResult.summarized(truncate(combined, TRUNCATE_LENGTH), chunks.size());
        }

        return SummaryResult.summarized(combined, chunks.size());
    }

    /** 对单个分块调用 LLM 做摘要，失败返回 null。 */
    private String summarizeChunk(String chunk, String fileName, int idx, int total) {
        try {
            String prompt = String.format(SUMMARIZE_PROMPT, fileName, idx, total, chunk);
            String result = llmGateway.generateText(prompt);
            if (result != null && !result.isBlank()) {
                return result.strip();
            }
        } catch (Exception e) {
            log.warn("[DocSummarizer] 分块摘要失败：file={}, chunk={}/{}", fileName, idx, total);
        }
        return null;
    }

    /** 对多个分块摘要做二次 reduce，失败返回 null。 */
    private String reduceSummaries(String combined, String fileName) {
        try {
            String prompt = String.format(REDUCE_PROMPT, fileName, combined);
            String result = llmGateway.generateText(prompt);
            if (result != null && !result.isBlank()) {
                return result.strip();
            }
        } catch (Exception e) {
            log.warn("[DocSummarizer] reduce 摘要失败：file={}", fileName);
        }
        return null;
    }

    /** 终极降级。 */
    private SummaryResult fallbackTruncate(String text, String fileName) {
        return SummaryResult.truncated(truncate(text, TRUNCATE_LENGTH), fileName);
    }

    /* ---- 分块策略 ---- */

    /**
     * 按段落边界切分长文本。
     * 以双换行（空行）作为自然段落分隔，尽量保持语义完整；
     * 若段落累积超过 CHUNK_SIZE 则形成一块；单独一个段落超过 CHUNK_SIZE 则按句号二次切分。
     */
    static List<String> splitByParagraph(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n", -1);

        StringBuilder buf = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            // 单个段落超过 chunkSize：按句号强制切分
            if (trimmed.length() > chunkSize) {
                // 先 flush 当前缓冲区
                flushBuffer(buf, chunks);
                // 对超长段落按句子切分
                splitLongParagraph(trimmed, chunkSize, chunks);
                continue;
            }

            // 当前缓冲区 + 新段落超过阈值 → flush
            if (!buf.isEmpty() && buf.length() + trimmed.length() > chunkSize) {
                flushBuffer(buf, chunks);
            }

            if (!buf.isEmpty()) buf.append("\n\n");
            buf.append(trimmed);
        }
        flushBuffer(buf, chunks);

        return chunks;
    }

    private static void flushBuffer(StringBuilder buf, List<String> chunks) {
        if (!buf.isEmpty()) {
            chunks.add(buf.toString());
            buf.setLength(0);
        }
    }

    /** 对超长段落按句号/分号/逗号边界切分。 */
    private static void splitLongParagraph(String text, int chunkSize, List<String> chunks) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                // 回退到最近的句子边界
                int boundary = findSentenceBoundary(text, start, end);
                if (boundary > start) end = boundary;
            }
            chunks.add(text.substring(start, end).strip());
            start = end;
        }
    }

    /** 在 [start, end] 范围内寻找最近的句子结尾（。！？；\n）。 */
    private static int findSentenceBoundary(String text, int start, int end) {
        int pos = end;
        // 往前找最近的边界符，最多回退到目标长度的 50%
        int minPos = start + CHUNK_SIZE / 2;
        if (minPos >= end) return end;
        while (pos > minPos) {
            pos--;
            char c = text.charAt(pos);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                return pos + 1; // 包含边界符
            }
        }
        // 找不到合适边界 → 原样截断
        return end;
    }

    static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        // 尽量在句子边界截断
        int cut = findSentenceBoundary(text, 0, maxLen);
        return text.substring(0, cut).strip() + "\n\n…（原文过长，已截断）";
    }
}
