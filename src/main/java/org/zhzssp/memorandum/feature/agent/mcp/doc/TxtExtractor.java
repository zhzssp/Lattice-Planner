package org.zhzssp.memorandum.feature.agent.mcp.doc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 纯文本 / 代码文本提取器。
 * 覆盖 txt、md、log、json、yaml、xml、csv 及常规编程语言源文件。
 * 优先 UTF-8 解码，含大量替换字符时回退 GBK。
 */
public class TxtExtractor implements DocumentExtractor {

    private static final Set<String> SUPPORTED = Set.of(
            "txt", "md", "log", "csv", "json", "yml", "yaml", "xml", "properties",
            "java", "py", "js", "ts", "go", "c", "cpp", "h", "sql", "sh", "bat",
            "html", "css", "rs", "rb", "php", "swift", "kt", "scala", "r", "m",
            "ini", "cfg", "conf", "toml", "gradle", "cmake"
    );

    @Override
    public boolean supports(String extension) {
        return SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public ExtractedDocument extract(Path path) throws IOException {
        long sizeBytes = Files.size(path);
        String text = readWithEncodingFallback(path);
        return new ExtractedDocument(text, countLines(text), sizeBytes);
    }

    /** 优先 UTF-8，含大量替换字符（\uFFFD）时回退 GBK。 */
    private String readWithEncodingFallback(Path path) throws IOException {
        String utf8Text = Files.readString(path, StandardCharsets.UTF_8);
        if (highReplacementRate(utf8Text)) {
            try {
                // GBK → UTF-8 内码转换，避免后续处理依赖 GBK 编码字符串
                byte[] raw = Files.readAllBytes(path);
                return new String(raw, Charset.forName("GBK"));
            } catch (Exception e) {
                // GBK 也失败则保留 UTF-8 原文（最少破坏性）
                return utf8Text;
            }
        }
        return utf8Text;
    }

    /** 采样前 2000 个字符，若 \uFFFD 比例 > 2%，认定为编码不匹配。 */
    private boolean highReplacementRate(String text) {
        if (text.isEmpty()) return false;
        int sampleSize = Math.min(text.length(), 2000);
        int count = 0;
        for (int i = 0; i < sampleSize; i++) {
            if (text.charAt(i) == '\uFFFD') count++;
        }
        return (double) count / sampleSize > 0.02;
    }

    private int countLines(String text) {
        if (text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }
}
