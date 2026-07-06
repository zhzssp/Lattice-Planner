package org.zhzssp.memorandum.feature.agent.mcp.doc;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档提取器接口（策略模式）。
 * 每种文件格式对应一个实现，负责将文件内容解析为纯文本。
 *
 * @see TxtExtractor
 * @see PdfExtractor
 * @see WordExtractor
 * @see ExcelExtractor
 */
public interface DocumentExtractor {

    /** 是否支持指定扩展名（不含点号，如 "pdf"、"docx"）。 */
    boolean supports(String extension);

    /** 解析文件并返回提取后的结构化文档文本。 */
    ExtractedDocument extract(Path path) throws IOException;
}
