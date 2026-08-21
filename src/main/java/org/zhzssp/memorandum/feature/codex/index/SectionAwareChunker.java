package org.zhzssp.memorandum.feature.codex.index;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节感知切片器（P0a 修复的核心）。
 *
 * <h3>为什么不能沿用笔记的平铺切片</h3>
 * <p>{@code NoteIndexService.chunk} 的上限是 64 chunk × 600 字 = 38,400 字符。
 * 目标仓库最重要的六篇 guide 全部超过 80,000 字符，最大的
 * {@code iree-learning-guide.md} 有 107,153 字符——<strong>只有前 36% 会被索引</strong>，
 * 且旧实现触顶时静默返回，不记日志、不报警。</p>
 *
 * <p>后果比「没有检索」更糟：Agent 会声称「已检索你的知识库」，
 * 但 HAL 对象模型、timeline semaphore 这些核心内容根本不在索引里，
 * 用户会据此误判「我的笔记里没写过」。</p>
 *
 * <h3>三层修法</h3>
 * <ol>
 *   <li><strong>按章节先分段</strong>：107K 的文档按 {@code ##} 切成十几段，
 *       每段独立切片，chunk 天然携带 {@code headingPath} 与 {@code anchor}；</li>
 *   <li><strong>上限提到文档级</strong>（默认 400）：不动笔记的 64，互不影响；</li>
 *   <li><strong>触顶必须可观测</strong>：返回 {@code truncated} 标记，落库 + 仪表盘红标。
 *       这延续项目既有的「截断不可静默」原则（同方案 L 的粘性降级立场）。</li>
 * </ol>
 */
@Component
public class SectionAwareChunker {

    /** 单 chunk 目标字符数。与笔记侧保持一致，便于两路检索的分数可比。 */
    static final int CHUNK_SIZE = 600;
    /** 滑窗 overlap，避免长段落在边界处语义被切断。 */
    static final int CHUNK_OVERLAP = 100;

    /**
     * 一个切片。
     *
     * @param idx         文档内序号
     * @param content     正文（已注入标题上下文）
     * @param sectionOrd  所属章节序号；无章节时为 null
     * @param headingPath 章节祖先链
     * @param anchor      章节 anchor
     */
    public record Chunk(int idx, String content, Integer sectionOrd,
                        String headingPath, String anchor) {}

    /**
     * 切片结果。
     *
     * @param chunks     切片列表
     * @param truncated  是否因触达上限而丢弃了后续内容
     * @param charsTotal 输入总字符数
     * @param charsUsed  实际被覆盖的字符数
     */
    public record Result(List<Chunk> chunks, boolean truncated, int charsTotal, int charsUsed) {

        public double lossRatio() {
            if (charsTotal <= 0) return 0.0;
            return Math.max(0.0, 1.0 - (double) charsUsed / charsTotal);
        }
    }

    /**
     * 按章节切片。
     *
     * @param docTitle  文档标题，注入每个 chunk 头部提升向量主题相关性
     * @param content   完整内容
     * @param bodyStart front-matter 之后的偏移
     * @param sections  章节列表；为空则退化为平铺切片
     * @param maxChunks 上限
     */
    public Result chunk(String docTitle,
                        String content,
                        int bodyStart,
                        List<MarkdownStructureParser.Section> sections,
                        int maxChunks) {
        if (content == null || content.isBlank()) {
            return new Result(List.of(), false, 0, 0);
        }
        int limit = Math.max(1, maxChunks);
        int start = Math.max(0, Math.min(bodyStart, content.length()));
        String body = content.substring(start);
        int charsTotal = body.length();

        List<Chunk> out = new ArrayList<>();
        int used = 0;

        if (sections == null || sections.isEmpty()) {
            // 无标题结构（如纯列表文件）：退化为平铺切片，仍受 limit 约束
            for (String piece : splitText(body)) {
                if (out.size() >= limit) {
                    return new Result(out, true, charsTotal, used);
                }
                out.add(new Chunk(out.size(), withHeader(docTitle, null, piece), null, null, null));
                used += piece.length();
            }
            return new Result(out, false, charsTotal, charsTotal);
        }

        // 首个标题之前的前言（很多文档的核心结论就在这里，不能丢）。
        // 边界用 lineStart（标题行起点）而非 charStart（标题行之后），
        // 否则首个标题行本身会被并入前言。
        int firstSectionStart = sections.get(0).lineStart();
        if (firstSectionStart > start) {
            String preamble = content.substring(start, firstSectionStart).strip();
            if (!preamble.isBlank()) {
                for (String piece : splitText(preamble)) {
                    if (out.size() >= limit) {
                        return new Result(out, true, charsTotal, used);
                    }
                    out.add(new Chunk(out.size(), withHeader(docTitle, null, piece),
                            null, null, null));
                    used += piece.length();
                }
            }
        }

        for (MarkdownStructureParser.Section s : sections) {
            int segStart = Math.min(s.charStart(), content.length());
            int segEnd = Math.min(s.charEnd(), content.length());
            if (segEnd <= segStart) continue;
            String segment = content.substring(segStart, segEnd).strip();
            if (segment.isBlank()) continue;

            for (String piece : splitText(segment)) {
                if (out.size() >= limit) {
                    return new Result(out, true, charsTotal, used);
                }
                out.add(new Chunk(out.size(),
                        withHeader(docTitle, s.headingPath(), piece),
                        s.ord(), s.headingPath(), s.anchor()));
                used += piece.length();
            }
        }
        return new Result(out, false, charsTotal, charsTotal);
    }

    /**
     * 段落优先 + 超长段落滑窗，与 {@code NoteIndexService.chunk} 的策略一致
     * （保持两路 chunk 粒度可比，融合分数才有意义）。
     */
    private List<String> splitText(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.strip();
        if (t.isEmpty()) return out;

        String[] paras = t.split("\\n{2,}");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            if (p == null) continue;
            String para = p.strip();
            if (para.isEmpty()) continue;

            if (buf.length() + para.length() + 2 <= CHUNK_SIZE) {
                if (buf.length() > 0) buf.append("\n\n");
                buf.append(para);
            } else {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                if (para.length() <= CHUNK_SIZE) {
                    buf.append(para);
                } else {
                    int step = CHUNK_SIZE - CHUNK_OVERLAP;
                    for (int i = 0; i < para.length(); i += step) {
                        out.add(para.substring(i, Math.min(para.length(), i + CHUNK_SIZE)));
                    }
                }
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /**
     * 注入标题上下文。
     *
     * <p>不只是为了向量质量：LLM 拿到 chunk 时能直接看到它出自哪篇的哪一节，
     * 从而在回答里给出可点击的准确引用，而不是含糊地说「你的笔记里提到过」。</p>
     */
    private String withHeader(String docTitle, String headingPath, String piece) {
        StringBuilder sb = new StringBuilder();
        if (docTitle != null && !docTitle.isBlank()) {
            sb.append("[文档] ").append(docTitle).append('\n');
        }
        if (headingPath != null && !headingPath.isBlank()) {
            sb.append("[章节] ").append(headingPath).append('\n');
        }
        sb.append(piece);
        return sb.toString();
    }
}
