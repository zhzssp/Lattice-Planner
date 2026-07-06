package org.zhzssp.memorandum.feature.agent.mcp.doc;

import org.apache.poi.ss.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;

/**
 * Excel (.xlsx / .xls) 文档提取器（基于 Apache POI）。
 * 每个 Sheet 转为 GFM Markdown 表格；行数超阈值（1000）时截断并标注。
 * 公式取缓存计算结果，合并单元格/图表/宏等复杂特性不予处理（仅取单元格文本值）。
 */
public class ExcelExtractor implements DocumentExtractor {

    /** 单 Sheet 最大展示行数，超出后截断。 */
    private static final int MAX_ROWS_PER_SHEET = 1000;

    private static final DecimalFormat NUM_FMT = new DecimalFormat("#.######");

    @Override
    public boolean supports(String extension) {
        return "xlsx".equalsIgnoreCase(extension) || "xls".equalsIgnoreCase(extension);
    }

    @Override
    public ExtractedDocument extract(Path path) throws IOException {
        long sizeBytes = Files.size(path);
        StringBuilder sb = new StringBuilder();
        int sheetCount;

        try (InputStream is = Files.newInputStream(path);
             Workbook wb = WorkbookFactory.create(is)) {
            sheetCount = wb.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("## ").append(sheet.getSheetName()).append("\n\n");
                sb.append(sheetToMarkdown(sheet));
                sb.append("\n\n");
            }
        }

        return new ExtractedDocument(sb.toString().strip(), sheetCount, sizeBytes);
    }

    /** 将单个 Sheet 转为 Markdown 表格文本。 */
    private String sheetToMarkdown(Sheet sheet) {
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) return "（空工作表）\n";

        // 探测最大有效列数（跳过全空行）
        int maxCols = detectMaxCols(sheet, lastRowNum);
        if (maxCols == 0) return "（空工作表）\n";

        boolean truncated = (lastRowNum + 1) > MAX_ROWS_PER_SHEET;
        int displayRows = Math.min(lastRowNum + 1, MAX_ROWS_PER_SHEET);

        // 第一行作为表头
        Row headerRow = sheet.getRow(0);
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        for (int c = 0; c < maxCols; c++) {
            sb.append(" ").append(safeCellValue(headerRow, c)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < maxCols; c++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // 数据行
        for (int r = 1; r < displayRows; r++) {
            Row row = sheet.getRow(r);
            sb.append("|");
            for (int c = 0; c < maxCols; c++) {
                sb.append(" ").append(safeCellValue(row, c)).append(" |");
            }
            sb.append("\n");
        }

        if (truncated) {
            sb.append("\n*（仅显示前 ").append(MAX_ROWS_PER_SHEET)
              .append(" 行，共 ").append(lastRowNum + 1).append(" 行）*\n");
        }
        return sb.toString();
    }

    /** 扫描各行，确定最大有效列数（忽略末尾连续空列）。 */
    private int detectMaxCols(Sheet sheet, int lastRowNum) {
        int max = 0;
        int checkRows = Math.min(lastRowNum + 1, 50); // 采样前 50 行足够
        for (int r = 0; r < checkRows; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int lastCell = row.getLastCellNum(); // 1-based
            if (lastCell > max) max = lastCell;
        }
        return max;
    }

    /** 安全取单元格文本（滚到管道符、换行符）。 */
    private String safeCellValue(Row row, int colIndex) {
        if (row == null) return "";
        Cell cell = row.getCell(colIndex);
        String value = cellValue(cell);
        return value.replace("|", "\\|").replace("\n", " ");
    }

    /** 按类型取值：字符串 / 数字（去尾零）/ 布尔 / 公式缓存 / 空。 */
    private String cellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                yield NUM_FMT.format(cell.getNumericCellValue());
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    // 优先取公式缓存的计算结果
                    yield cell.getStringCellValue();
                } catch (Exception e1) {
                    try {
                        yield NUM_FMT.format(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        yield cell.getCellFormula();
                    }
                }
            }
            case BLANK, ERROR, _NONE -> "";
        };
    }
}
