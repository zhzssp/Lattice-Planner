package org.zhzssp.memorandum.feature.agent.mcp.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF 文档提取器（基于 Apache PDFBox）。
 * 逐页抽取文本，按页序排序保阅读顺序。
 */
public class PdfExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public ExtractedDocument extract(Path path) throws IOException {
        long sizeBytes = Files.size(path);
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                return new ExtractedDocument("", 0, sizeBytes);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            return new ExtractedDocument(cleanText(text), pageCount, sizeBytes);
        }
    }

    /** 剔除控制字符（保留 tab/换行/回车），折叠过多连续空行。 */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                   .replaceAll("\\n{4,}", "\n\n\n")
                   .trim();
    }
}
