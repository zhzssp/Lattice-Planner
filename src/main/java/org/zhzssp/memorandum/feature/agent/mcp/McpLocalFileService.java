package org.zhzssp.memorandum.feature.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.mcp.doc.DocumentExtractorRegistry;
import org.zhzssp.memorandum.feature.agent.mcp.doc.DocumentSummarizer;
import org.zhzssp.memorandum.feature.agent.mcp.doc.ExtractedDocument;
import org.zhzssp.memorandum.feature.agent.mcp.doc.SummaryResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * S4：MCP 专用的本地文件服务（替代 Electron Bridge 的 LocalBridgeProxy）。
 *
 * <p>在 MCP 场景下，后端直接做磁盘 IO，无需 Electron。
 * 通过 mcp.server.local-files-enabled=true 开启，
 * mcp.server.local-allowed-dirs 配置白名单目录。
 *
 * <p>v3 增强：
 * <ul>
 *   <li>新增统一工具 {@code local.read_document}：按扩展名自动选用提取器（txt/pdf/docx/xlsx），
 *       长文档走 map-reduce 摘要后返回；</li>
 *   <li>{@code local.read_pdf} 内部改为委托 PDFBox 真实解析（替换旧字节粗提取占位）；</li>
 *   <li>移除未实现的写工具 {@code kb.ingest_local_doc} / {@code kb.delete_local_doc}，收紧为纯只读暴露面；</li>
 *   <li>扩展名白名单新增 docx / xlsx。</li>
 * </ul>
 */
@Component
public class McpLocalFileService {

    private static final Logger log = LoggerFactory.getLogger(McpLocalFileService.class);

    @Value("${mcp.server.local-files-enabled:false}")
    private boolean enabled;

    /** 允许访问的目录白名单（逗号分隔），例如 D:/learning,C:/Users/docs。留空则自动使用用户主目录。 */
    @Value("${mcp.server.local-allowed-dirs:}")
    private String allowedDirsConfig;

    private final DocumentExtractorRegistry extractorRegistry;
    private final DocumentSummarizer summarizer;

    private List<Path> allowedDirs;

    public McpLocalFileService(DocumentExtractorRegistry extractorRegistry,
                               DocumentSummarizer summarizer) {
        this.extractorRegistry = extractorRegistry;
        this.summarizer = summarizer;
    }

    private List<Path> getAllowedDirs() {
        if (allowedDirs == null) {
            allowedDirs = new ArrayList<>();
            if (allowedDirsConfig != null && !allowedDirsConfig.isBlank()) {
                for (String d : allowedDirsConfig.split(",")) {
                    String trimmed = d.trim();
                    if (!trimmed.isEmpty()) allowedDirs.add(Path.of(trimmed));
                }
            }
            // 未配置白名单时默认允许整个用户主目录（桌面/文档/下载等均自动可用）
            if (allowedDirs.isEmpty()) {
                String home = System.getProperty("user.home");
                if (home != null && !home.isBlank()) {
                    allowedDirs.add(Path.of(home));
                    log.info("[MCP] 本地文件白名单未配置，自动使用用户主目录：{}", home);
                }
            }
        }
        return allowedDirs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 是否是本地文件工具（loopback MCP Client 通过 handleToolsCall 路由到此）。 */
    public boolean isLocalTool(String name) {
        return Set.of("local.list_dir", "local.read_file", "local.read_pdf", "local.read_document").contains(name);
    }

    /** 导出本地文件工具描述（仅只读工具）。 */
    public List<Map<String, Object>> exportLocalTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("local.list_dir", "列出本地目录下的文件与子目录（路径必须在白名单内）",
                param("path", "绝对路径", "string", true)));
        tools.add(tool("local.read_file",
                "读取本地纯文本/代码文件（UTF-8/GBK 自动探测）",
                param("path", "绝对路径", "string", true)));
        tools.add(tool("local.read_pdf",
                "读取本地 PDF 文件的纯文本内容（逐页抽取）",
                param("path", "绝对路径", "string", true)));
        tools.add(tool("local.read_document",
                "只读读取本地文档（txt/md/pdf/docx/xlsx/jpg/png 等），"
                        + "自动提取文本并在内容过长时生成摘要。"
                        + "路径必须在管理员配置的白名单目录内。",
                param("path", "文档绝对路径", "string", true)));
        return tools;
    }

    /** 执行本地文件工具调用（McpSseEndpoint.handleToolsCall 路由到此）。 */
    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "local.list_dir" -> listDir((String) args.get("path"));
            case "local.read_file" -> readFile((String) args.get("path"));
            case "local.read_pdf" -> readPdf((String) args.get("path"));
            case "local.read_document" -> readDocument((String) args.get("path"));
            default -> errorResult("未实现的本地工具：" + toolName);
        };
    }

    /* ---- 工具实现 ---- */

    private Map<String, Object> listDir(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return errorResult("path 为空");
        Path path = Path.of(pathStr);
        if (!isAllowed(path)) return errorResult("路径不在白名单内：" + pathStr + "。 " + allowedDirsHint());
        if (!Files.isDirectory(path)) return errorResult("不是目录：" + pathStr);
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", p.getFileName().toString());
                    entry.put("isDir", Files.isDirectory(p));
                    try {
                        entry.put("size", Files.size(p));
                    } catch (IOException ignored) {
                        entry.put("size", -1);
                    }
                    entries.add(entry);
                });
            }
            return Map.of("content", List.of(Map.of("type", "text",
                    "text", json(entries))), "isError", false);
        } catch (IOException e) {
            return errorResult("列出目录失败：" + e.getMessage());
        }
    }

    private Map<String, Object> readFile(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return errorResult("path 为空");
        Path path = Path.of(pathStr);
        if (!isAllowed(path)) return errorResult("路径不在白名单内：" + pathStr + "。 " + allowedDirsHint());
        if (!isAllowedExtension(path)) return errorResult("文件扩展名不在白名单内");
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Map.of("content", List.of(Map.of("type", "text",
                    "text", content)), "isError", false);
        } catch (IOException e) {
            return errorResult("读取文件失败：" + e.getMessage());
        }
    }

    /** PDF 真实解析（委托 PdfExtractor）。 */
    private Map<String, Object> readPdf(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return errorResult("path 为空");
        Path path = Path.of(pathStr);
        if (!isAllowed(path)) return errorResult("路径不在白名单内：" + pathStr + "。 " + allowedDirsHint());
        if (!pathStr.toLowerCase().endsWith(".pdf")) return errorResult("仅支持 PDF 文件");
        try {
            ExtractedDocument doc = extractorRegistry.extract(path);
            if (doc.isEmpty()) {
                return errorResult("PDF 无文本内容（可能是扫描件或图片型 PDF，需 OCR 支持）");
            }
            return Map.of("content", List.of(Map.of("type", "text",
                    "text", doc.plainText())), "isError", false);
        } catch (Exception e) {
            log.warn("[MCP] PDF 解析失败：path={}, err={}", pathStr, e.toString());
            return errorResult("PDF 解析失败：" + e.getMessage());
        }
    }

    /** 统一文档读取工具（v3 新增）：自动按扩展名选用提取器，长文档走摘要。 */
    private Map<String, Object> readDocument(String pathStr) {
        // 1. 参数校验
        if (pathStr == null || pathStr.isBlank()) return errorResult("path 为空");
        Path path = Path.of(pathStr);
        if (!isAllowed(path)) return errorResult("路径不在白名单内：" + pathStr + "。 " + allowedDirsHint());
        if (!Files.exists(path)) return errorResult("文件不存在：" + pathStr);
        if (!Files.isRegularFile(path)) return errorResult("不是文件：" + pathStr);

        // 2. 扩展名白名单
        if (!DocumentExtractorRegistry.isAllowedExtension(path)) {
            return errorResult("不支持的文件类型（扩展名不在白名单内），当前支持："
                    + String.join(", ", DocumentExtractorRegistry.getAllowedExtensions()));
        }

        // 3. 提取
        String fileName = path.getFileName().toString();
        ExtractedDocument extracted;
        try {
            extracted = extractorRegistry.extract(path);
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage());
        } catch (IOException e) {
            log.warn("[MCP] 文档提取失败：path={}, err={}", pathStr, e.toString());
            return errorResult("读取文档失败：" + e.getMessage());
        }

        if (extracted.isEmpty()) {
            return result(fileName, "（文档无文本内容）", false, extracted);
        }

        // 4. 摘要（短文直传，长文 map-reduce）
        SummaryResult summary;
        try {
            summary = summarizer.summarize(extracted.plainText(), fileName);
        } catch (Exception e) {
            log.warn("[MCP] 摘要处理异常，降级截断原文：path={}, err={}", pathStr, e.toString());
            // 摘要层已内部兜底，此处为双重保险
            String truncated = extracted.plainText().length() > 4000
                    ? extracted.plainText().substring(0, 4000) + "\n…（原文过长，已截断）"
                    : extracted.plainText();
            return result(fileName, truncated, false, extracted);
        }

        return result(fileName, summary.content(), summary.summarized(), extracted);
    }

    /* ---- 安全校验 ---- */

    boolean isAllowed(Path path) {
        if (!enabled) return false;
        Path abs = path.toAbsolutePath().normalize();
        for (Path allowed : getAllowedDirs()) {
            Path allowedAbs = allowed.toAbsolutePath().normalize();
            if (abs.startsWith(allowedAbs)) return true;
        }
        return false;
    }

    /** 生成被拒时的白名单提示（给 LLM 看，帮助它给用户正确指导）。 */
    private String allowedDirsHint() {
        List<String> dirs = getAllowedDirs().stream()
                .map(p -> p.toAbsolutePath().normalize().toString())
                .toList();
        if (dirs.isEmpty()) return "（未配置白名单目录，请联系管理员设置 mcp.server.local-allowed-dirs）";
        return "当前允许访问的目录：" + String.join("、", dirs)
                + "。请将文件放入这些目录后重试。";
    }

    /** 老式扩展名白名单（legacy read_file 用，保持向后兼容）。 */
    private static final Set<String> ALLOWED_EXTENSIONS_LEGACY = Set.of(
            "md", "txt", "json", "yml", "yaml", "xml", "csv", "html", "css", "js",
            "java", "py", "go", "rs", "c", "cpp", "h", "sh", "bat", "sql", "properties",
            "pdf", "docx", "xlsx"
    );

    private boolean isAllowedExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1);
        return ALLOWED_EXTENSIONS_LEGACY.contains(ext);
    }

    /* ---- 响应组装 ---- */

    private Map<String, Object> result(String fileName, String content, boolean summarized,
                                       ExtractedDocument extracted) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName", fileName);
        meta.put("extension", extensionOf(fileName));
        meta.put("sizeBytes", extracted.sizeBytes());
        meta.put("pageOrSheetCount", extracted.pageOrSheetCount());
        meta.put("charCount", extracted.charCount());
        meta.put("isSummarized", summarized);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("meta", meta);
        r.put("content", List.of(Map.of("type", "text", "text", content)));
        r.put("isError", false);
        return r;
    }

    /* ---- helpers ---- */

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private String json(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private Map<String, Object> tool(String name, String desc, Map<String, Object>... params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> p : params) {
            String pName = (String) p.get("name");
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.get("type"));
            prop.put("description", p.get("desc"));
            props.put(pName, prop);
            if (Boolean.TRUE.equals(p.get("required"))) required.add(pName);
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("description", desc);
        entry.put("inputSchema", schema);
        return entry;
    }

    private Map<String, Object> param(String name, String desc, String type, boolean required) {
        return Map.of("name", name, "desc", desc, "type", type, "required", required);
    }

    private Map<String, Object> errorResult(String msg) {
        return Map.of("content", List.of(Map.of("type", "text", "text", msg)),
                "isError", true);
    }
}
