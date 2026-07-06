package org.zhzssp.memorandum.feature.agent.mcp.doc;

/**
 * 提取结果。
 *
 * @param plainText        展平后的纯文本内容（Excel 已转为 Markdown 表格，Word 保留段落/标题结构）
 * @param pageOrSheetCount PDF 页数 / Excel sheet 数 / Word 段落数 / 纯文本行数
 * @param sizeBytes        原始文件字节数
 */
public record ExtractedDocument(
        String plainText,
        int pageOrSheetCount,
        long sizeBytes
) {
    public boolean isEmpty() {
        return plainText == null || plainText.isBlank();
    }

    public int charCount() {
        return plainText != null ? plainText.length() : 0;
    }
}
