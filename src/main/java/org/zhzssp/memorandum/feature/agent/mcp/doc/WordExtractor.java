package org.zhzssp.memorandum.feature.agent.mcp.doc;

import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Word (.docx) 文档提取器（基于 Apache POI XWPF）。
 * 遍历段落与表格：段落标题按 style 名推断层级（Heading1→#, Heading2→##），
 * 表格转 Markdown 表格格式。
 */
public class WordExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public ExtractedDocument extract(Path path) throws IOException {
        long sizeBytes = Files.size(path);
        StringBuilder sb = new StringBuilder();
        int paragraphCount = 0;

        try (InputStream is = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(is)) {

            List<IBodyElement> elements = doc.getBodyElements();
            for (IBodyElement element : elements) {
                if (element instanceof XWPFParagraph para) {
                    String style = para.getStyle();
                    String text = para.getText();
                    if (text != null && !text.isBlank()) {
                        appendHeadingPrefix(sb, style);
                        sb.append(text.strip()).append("\n");
                        paragraphCount++;
                    }
                } else if (element instanceof XWPFTable table) {
                    sb.append(tableToMarkdown(table)).append("\n");
                }
            }
        }

        return new ExtractedDocument(sb.toString().strip(), paragraphCount, sizeBytes);
    }

    /** 根据段落样式名推断标题层级，追加 Markdown # 前缀。 */
    private void appendHeadingPrefix(StringBuilder sb, String styleId) {
        if (styleId == null) return;
        String lower = styleId.toLowerCase();
        if (lower.startsWith("heading") || lower.startsWith("标题")) {
            int level = extractLevel(lower);
            if (level >= 1 && level <= 6) {
                sb.append("#".repeat(level)).append(" ");
            }
        }
    }

    private int extractLevel(String styleId) {
        // "Heading 1", "Heading2", "标题 1", "标题2" 等
        String digits = styleId.replaceAll("\\D+", "");
        if (digits.isEmpty()) return 1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 将 POI 表格转为 GFM Markdown 表格。 */
    private String tableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";

        int cols = maxColumnCount(rows);

        StringBuilder sb = new StringBuilder();
        // 表头
        XWPFTableRow headerRow = rows.get(0);
        sb.append("|");
        for (int c = 0; c < cols; c++) {
            sb.append(" ").append(cellText(headerRow, c)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // 数据行
        for (int r = 1; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            sb.append("|");
            for (int c = 0; c < cols; c++) {
                sb.append(" ").append(cellText(row, c)).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private int maxColumnCount(List<XWPFTableRow> rows) {
        int max = 0;
        for (XWPFTableRow row : rows) {
            if (row.getTableCells().size() > max) {
                max = row.getTableCells().size();
            }
        }
        return max;
    }

    private String cellText(XWPFTableRow row, int colIndex) {
        List<XWPFTableCell> cells = row.getTableCells();
        if (colIndex >= cells.size()) return "";
        return cells.get(colIndex).getText().strip().replace("|", "\\|").replace("\n", " ");
    }
}
