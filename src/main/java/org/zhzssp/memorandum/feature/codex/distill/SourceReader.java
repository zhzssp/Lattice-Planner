package org.zhzssp.memorandum.feature.codex.distill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.mcp.doc.DocumentExtractorRegistry;
import org.zhzssp.memorandum.feature.agent.mcp.doc.ExtractedDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蒸馏原料读取：把 PDF / docx / md 变成可喂给模型的<strong>原文</strong>分段。
 *
 * <h3>★为什么绝不能走 {@code local.read_document}</h3>
 * <p>MCP 已有的 {@code local.read_document} 看起来正好能用，但它对长文会先经
 * {@code DocumentSummarizer} 做 map-reduce <strong>摘要</strong>再返回。
 * 一篇论文提取后常有 4~6 万字符，摘要后只剩两三千字。</p>
 *
 * <p>基于摘要蒸馏 = <strong>蒸馏二手信息</strong>：论文里的算法伪码、维度标注、
 * 消融对照表会在摘要那一步全部消失，而那恰恰是 guide「必学特性表」与
 * 「核心运行框架」的唯一原料。更糟的是这种损失<strong>无声无息</strong>——
 * 产出的 guide 结构完整、读起来像样，只是里面没有一处能对着原文核对的具体内容。</p>
 *
 * <p>所以这里直接用 {@link DocumentExtractorRegistry} 拿 {@code plainText()}，
 * 自己按结构分段，绝不让原文在进模型之前被压缩过一次。</p>
 *
 * <h3>提取质量必须先判定，而且要拒绝</h3>
 * <p>扫描件 PDF 提取出来是空的或几十个乱码字符。此时正确的做法是<strong>拒绝蒸馏</strong>，
 * 不是"尽力而为"——基于残缺文本产出的 guide 会是一篇看起来专业的空壳，
 * 而用户没有办法从产物本身看出它是空壳。判据用「每页字符数」，可机器判定。</p>
 */
@Component
public class SourceReader {

    private static final Logger log = LoggerFactory.getLogger(SourceReader.class);

    /** 论文类 PDF 正文每页通常 1500~4000 字符；低于这个数基本可判定是图片型。 */
    private static final int MIN_CHARS_PER_PAGE = 250;

    /** 低于此总量无论多少页都不足以蒸馏出一篇 guide。 */
    private static final int MIN_TOTAL_CHARS = 2000;

    /** 论文的典型节标题：{@code 3.2 Method} / {@code IV. EXPERIMENTS} / {@code Abstract}。 */
    private static final Pattern SECTION_HEAD = Pattern.compile(
            "(?m)^\\s{0,6}(?:"
                    + "(\\d{1,2}(?:\\.\\d{1,2}){0,2})\\s+([A-Z][^\\n]{2,70})"      // 3.2 Method
                    + "|([IVXL]{1,5})\\.\\s+([A-Z][A-Z \\-]{2,60})"                 // IV. EXPERIMENTS
                    + "|(Abstract|Introduction|Related Work|Background|Method(?:s|ology)?"
                    + "|Approach|Experiments?|Evaluation|Results?|Ablation[^\\n]{0,20}"
                    + "|Discussion|Conclusions?|Limitations?)"
                    + ")\\s*$");

    /** Markdown 标题（原料本身就是 md 时用）。 */
    private static final Pattern MD_HEAD = Pattern.compile("(?m)^(#{1,4})\\s+(.+?)\\s*$");

    /** 页脚/页码等噪声行。 */
    private static final Pattern NOISE_LINE = Pattern.compile(
            "^\\s*(?:\\d{1,4}|Page\\s+\\d+(?:\\s+of\\s+\\d+)?|arXiv:[^\\s]+)\\s*$");

    /** 读取结果。 */
    public record Source(boolean ok, String code, String message,
                         String fileName, String plainText,
                         int pageCount, int charCount, int charsPerPage,
                         List<Chunk> chunks) {

        static Source fail(String code, String message, String fileName) {
            return new Source(false, code, message, fileName, null, 0, 0, 0, List.of());
        }
    }

    /**
     * 一段原料。
     *
     * @param heading 该段的标题（无法识别时为 {@code 第 N 段}）
     * @param text    段落原文
     */
    public record Chunk(int ord, String heading, String text) {

        public int charCount() {
            return text == null ? 0 : text.length();
        }
    }

    @Value("${codex.distill.max-source-chars:200000}")
    private int maxSourceChars;

    @Value("${codex.distill.chunk-chars:9000}")
    private int chunkChars;

    private final DocumentExtractorRegistry extractors;

    public SourceReader(DocumentExtractorRegistry extractors) {
        this.extractors = extractors;
    }

    /* ==================== 读取 ==================== */

    /**
     * 读取一份原料。
     *
     * @param file 已经过白名单校验的绝对路径（调用方负责沙箱判断）
     */
    public Source read(Path file) {
        String name = file == null ? "?" : String.valueOf(file.getFileName());
        if (file == null || !Files.isRegularFile(file)) {
            return Source.fail("SOURCE_NOT_FOUND", "原料文件不存在：" + name, name);
        }
        if (!DocumentExtractorRegistry.isAllowedExtension(file)) {
            return Source.fail("SOURCE_TYPE_UNSUPPORTED",
                    "不支持的原料类型：" + name + "。当前支持："
                            + String.join("、", DocumentExtractorRegistry.getAllowedExtensions()),
                    name);
        }

        ExtractedDocument doc;
        try {
            doc = extractors.extract(file);
        } catch (Exception e) {
            log.warn("[Codex/Distill] 原料提取失败 {}：{}", name, e.toString());
            return Source.fail("EXTRACT_FAILED", "提取文本失败：" + e.getMessage(), name);
        }

        if (doc.isEmpty()) {
            return Source.fail("SOURCE_NO_TEXT",
                    "该文件提取不到任何文本。若是扫描件或图片型 PDF，需要先做 OCR——"
                            + "本软件不做 OCR，也不会基于残缺文本硬凑一篇 guide。", name);
        }

        String raw = doc.plainText();
        int pages = Math.max(1, doc.pageOrSheetCount());
        int chars = raw.length();
        int perPage = chars / pages;

        // ★质量闸门：宁可拒绝，也不产出看起来专业的空壳
        if (chars < MIN_TOTAL_CHARS) {
            return Source.fail("SOURCE_TOO_THIN",
                    "只提取到 " + chars + " 个字符（共 " + pages + " 页），不足以蒸馏。"
                            + "多半是图片型 PDF 或提取器不适配这份排版。", name);
        }
        if (perPage < MIN_CHARS_PER_PAGE) {
            return Source.fail("SOURCE_LIKELY_SCANNED",
                    "平均每页仅 " + perPage + " 个字符（共 " + pages + " 页、" + chars
                            + " 字符），基本可判定是扫描件或图片型 PDF，提取到的文本残缺。"
                            + "基于残缺文本蒸馏会产出一篇结构完整但内容对不上原文的 guide，"
                            + "而你无法从产物本身看出这一点，因此这里直接拒绝。", name);
        }

        String text = clean(raw);
        if (text.length() > maxSourceChars) {
            // 截断而非拒绝：超长多半是把整本书投进来了，前一部分仍有价值。
            // 但必须自报，否则用户会以为 guide 覆盖了全书
            log.info("[Codex/Distill] 原料 {} 长 {} 字符，截断到 {}", name, text.length(), maxSourceChars);
            text = text.substring(0, maxSourceChars);
        }

        List<Chunk> chunks = split(text, isMarkdown(name));
        return new Source(true, "OK",
                "已提取 " + text.length() + " 字符 / " + pages + " 页，切成 " + chunks.size() + " 段",
                name, text, pages, text.length(), perPage, chunks);
    }

    /* ==================== 清洗 ==================== */

    /**
     * 去掉页码与断行连字符。
     *
     * <p>只做这两件确定安全的清洗。刻意<strong>不</strong>尝试重排双栏、
     * 不猜表格边界——猜错会把两栏文字交错成语义混乱的句子，
     * 那比保留原始换行糟糕得多，而且模型无法从结果里看出发生了什么。</p>
     */
    String clean(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\\R", -1)) {
            if (NOISE_LINE.matcher(line).matches()) continue;
            sb.append(line).append('\n');
        }
        // PDF 断行连字符：optimiza-\ntion → optimization
        return sb.toString().replaceAll("([A-Za-z])-\\n([a-z])", "$1$2");
    }

    private boolean isMarkdown(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt");
    }

    /* ==================== 分段 ==================== */

    /**
     * 按结构分段，段内不再细分到句子。
     *
     * <p>为什么按标题而不是按固定字符数硬切：论文的 Method 与 Experiments 要分别
     * 喂进不同的提取任务（前者产「核心运行框架」，后者产「必学特性表」的依据）。
     * 硬切会把一节劈成两半，让模型在两次调用里各看到半个算法，
     * 于是两次都只能写出模糊的概括。</p>
     *
     * <p>识别不出标题时才退化为按字符数切，并在标题里如实写「第 N 段」。</p>
     */
    List<Chunk> split(String text, boolean markdown) {
        List<int[]> marks = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        Matcher m = (markdown ? MD_HEAD : SECTION_HEAD).matcher(text);
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            titles.add(headingOf(m, markdown));
        }

        List<Chunk> out = new ArrayList<>();
        if (marks.size() < 2) {
            // 结构识别失败：退化为定长切分
            int size = Math.max(2000, chunkChars);
            int ord = 1;
            for (int i = 0; i < text.length(); i += size) {
                String seg = text.substring(i, Math.min(text.length(), i + size));
                out.add(new Chunk(ord, "第 " + ord + " 段（未识别到章节结构）", seg));
                ord++;
            }
            return out;
        }

        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[0];
            int to = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : text.length();
            String seg = text.substring(from, to).strip();
            if (seg.length() < 80) continue;                 // 目录行之类的空壳标题
            out.add(new Chunk(out.size() + 1, titles.get(i), seg));
        }
        // 合并过短的相邻段，避免一次调用只看到两行
        return merge(out);
    }

    private List<Chunk> merge(List<Chunk> in) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String head = null;
        for (Chunk c : in) {
            if (buf.length() > 0 && buf.length() + c.charCount() > chunkChars) {
                out.add(new Chunk(out.size() + 1, head, buf.toString()));
                buf.setLength(0);
                head = null;
            }
            if (head == null) head = c.heading();
            else head = head + " / " + c.heading();
            buf.append(c.text()).append("\n\n");
        }
        if (buf.length() > 0) out.add(new Chunk(out.size() + 1, head, buf.toString()));
        return out;
    }

    private String headingOf(Matcher m, boolean markdown) {
        if (markdown) return m.group(2).strip();
        for (int g = 1; g <= m.groupCount(); g++) {
            String v = m.group(g);
            if (v != null && !v.isBlank()) {
                String next = (g + 1 <= m.groupCount()) ? m.group(g + 1) : null;
                return (next == null || next.isBlank()) ? v.strip() : (v + " " + next).strip();
            }
        }
        return "未命名章节";
    }
}
