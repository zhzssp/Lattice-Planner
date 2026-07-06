package org.zhzssp.memorandum.feature.agent.mcp.doc;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文档提取器注册中心，按扩展名分发到对应提取器。
 * 同时维护一份全局允许的扩展名白名单（与 McpLocalFileService 共享）。
 */
@Component
public class DocumentExtractorRegistry {

    /** 所有当前支持的扩展名（不含点号）。 */
    @SuppressWarnings("SpellCheckingInspection")
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 纯文本
            "txt", "md", "log", "csv", "json", "yml", "yaml", "xml", "properties",
            "ini", "cfg", "conf", "toml", "gradle", "cmake",
            // 代码
            "java", "py", "js", "ts", "go", "c", "cpp", "h", "hpp", "rs", "rb",
            "php", "swift", "kt", "scala", "r", "m", "sql", "sh", "bat", "ps1",
            "html", "css", "scss", "less",
            // Office
            "pdf", "docx", "xlsx", "xls"
    );

    private final List<DocumentExtractor> extractors;

    public DocumentExtractorRegistry() {
        this.extractors = new ArrayList<>();
        this.extractors.add(new TxtExtractor());
        this.extractors.add(new PdfExtractor());
        this.extractors.add(new WordExtractor());
        this.extractors.add(new ExcelExtractor());
    }

    /** 判断文件扩展名是否在白名单内。 */
    public static boolean isAllowedExtension(Path path) {
        String ext = extensionOf(path);
        return ext != null && ALLOWED_EXTENSIONS.contains(ext);
    }

    /** 按文件选定提取器。 */
    public DocumentExtractor getExtractor(Path path) {
        String ext = extensionOf(path);
        if (ext == null) {
            throw new IllegalArgumentException("无法识别文件类型（无扩展名）：" + path);
        }
        for (DocumentExtractor ex : extractors) {
            if (ex.supports(ext)) return ex;
        }
        throw new IllegalArgumentException("不支持的文件类型：" + ext);
    }

    /** 提取文件并返回结构化文本。 */
    public ExtractedDocument extract(Path path) throws IOException {
        return getExtractor(path).extract(path);
    }

    public static Set<String> getAllowedExtensions() {
        return ALLOWED_EXTENSIONS;
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1);
    }
}
