package org.zhzssp.memorandum.feature.codex.index;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 标题树解析：抽出章节 + 生成 GitHub 风格 anchor。
 *
 * <h3>为什么必须有这一层（P0a 的另一半）</h3>
 * <p>目标仓库单文件最大 107K 字符。若把整篇当作一个平铺文本切片，会有两个后果：
 * <ol>
 *   <li>命中只能定位到「某篇 10 万字的文档」，用户还得自己在里面找——等于没定位；</li>
 *   <li>切片边界会随意穿过章节，让一个 chunk 里混着两个无关主题，拉低向量质量。</li>
 * </ol>
 * 按标题先分段，再在段内切片，chunk 天然带 {@code heading_path}，
 * 引用可精确到 {@code iree-learning-guide.md#46-timeline-semaphore}。</p>
 *
 * <h3>anchor 规则为何要对齐 GitHub</h3>
 * <p>仓库里已有 377 条 {@code ](path#anchor)} 形式的链接，它们是按 GitHub 规则写的。
 * 只有用同一套 slug 算法，才能把「文件存在但 anchor 已失效」这类断链检出来——
 * 这类断链在 IDE 里完全不报错，是最隐蔽的一种。</p>
 */
@Component
public class MarkdownStructureParser {

    /**
     * 一个章节。
     *
     * @param anchor      GitHub slug
     * @param heading     标题文本（已去掉 # 与行内格式标记）
     * @param headingPath 祖先链，用 " > " 连接
     * @param level       标题层级 1~6
     * @param ord         文档内序号
     * @param lineStart   标题行本身的起始偏移（前言/上一节的结束边界）
     * @param charStart   本节正文起始偏移（标题行之后）
     * @param charEnd     本节正文结束偏移 = 下一个标题的 lineStart
     */
    public record Section(String anchor, String heading, String headingPath,
                          int level, int ord, int lineStart, int charStart, int charEnd) {}

    /**
     * 解析结果。
     *
     * @param title    文档标题（首个 H1，缺失则退回文件名，由调用方处理）
     * @param sections 章节列表（按出现顺序）
     */
    public record Result(String title, List<Section> sections) {}

    /**
     * 解析标题结构。
     *
     * @param content   完整文件内容
     * @param bodyStart front-matter 之后的起始偏移
     */
    public Result parse(String content, int bodyStart) {
        if (content == null || content.isEmpty()) {
            return new Result(null, List.of());
        }
        int start = Math.max(0, Math.min(bodyStart, content.length()));

        List<Raw> raws = new ArrayList<>();
        Map<String, Integer> slugCount = new HashMap<>();
        boolean inFence = false;
        String fenceMarker = null;

        int pos = start;
        while (pos < content.length()) {
            int nl = content.indexOf('\n', pos);
            int lineEnd = (nl < 0) ? content.length() : nl;
            String line = content.substring(pos, lineEnd);
            String trimmed = line.strip();

            // 代码块内的 # 不是标题——这是最常见的误判来源
            // （shell 注释、Python 注释、diff 的 ### 分隔都会中招）
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String marker = trimmed.startsWith("```") ? "```" : "~~~";
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker;
                } else if (marker.equals(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                pos = lineEnd + 1;
                continue;
            }

            if (!inFence && trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                // ATX 标题要求 # 之后有空格；"#tag" 这类不是标题
                if (level >= 1 && level <= 6
                        && level < trimmed.length()
                        && Character.isWhitespace(trimmed.charAt(level))) {
                    String heading = cleanHeading(trimmed.substring(level).strip());
                    if (!heading.isEmpty()) {
                        String base = slugify(heading);
                        int n = slugCount.merge(base, 1, Integer::sum);
                        // GitHub 对重复标题追加 -1 / -2
                        String anchor = (n == 1) ? base : base + "-" + (n - 1);
                        raws.add(new Raw(anchor, heading, level, pos, lineEnd + 1));
                    }
                }
            }
            if (nl < 0) break;
            pos = nl + 1;
        }

        // 每节正文的结束位置 = 「下一个标题行的起点」，与层级无关。
        //
        // 刻意不用「下一个同级或更高级标题」：那样 H1 节会覆盖其下所有 H2/H3 的正文，
        // 切片时同一段文字会被父节与子节各切一次，导致
        //   ① 索引体积成倍膨胀（10 万字符文档可膨胀到 3 倍）；
        //   ② 检索结果出现大量近重复命中，把真正相关的其他章节挤出 topK。
        // 章节的层级关系由 headingPath 承载，不需要靠区间嵌套表达。
        List<Section> sections = new ArrayList<>(raws.size());
        List<String> ancestors = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            Raw r = raws.get(i);
            int end = (i + 1 < raws.size()) ? raws.get(i + 1).lineStart() : content.length();

            // 维护祖先链
            while (ancestors.size() >= r.level()) ancestors.remove(ancestors.size() - 1);
            while (ancestors.size() < r.level() - 1) ancestors.add("");
            ancestors.add(r.heading());
            String headingPath = String.join(" > ",
                    ancestors.stream().filter(s -> !s.isBlank()).toList());

            sections.add(new Section(r.anchor(), r.heading(), headingPath,
                    r.level(), i, r.lineStart(), r.bodyStart(),
                    Math.max(r.bodyStart(), end)));
        }

        String title = raws.stream().filter(r -> r.level() == 1)
                .map(Raw::heading).findFirst()
                .orElseGet(() -> raws.isEmpty() ? null : raws.get(0).heading());
        return new Result(title, sections);
    }

    /** 解析中间态：lineStart = 标题行起点，bodyStart = 标题行之后。 */
    private record Raw(String anchor, String heading, int level, int lineStart, int bodyStart) {}

    /** 去掉标题中的行内格式标记与尾部 {@code #}，保留可读文本。 */
    static String cleanHeading(String h) {
        String s = h;
        // 尾部闭合的 #（closed ATX）
        s = s.replaceAll("\\s+#+\\s*$", "");
        // 链接 [text](url) → text
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 行内代码、粗体、斜体标记
        s = s.replace("`", "").replace("**", "").replace("__", "");
        return s.strip();
    }

    /**
     * GitHub 风格 slug。
     *
     * <p>规则：转小写 → 去掉标点（保留字母/数字/下划线/连字符/CJK）→ 空格转连字符。
     * CJK 字符<strong>保留</strong>，这与 GitHub 行为一致，
     * 也是仓库里 {@code #24-与工具路线同步的概念学习路线} 这类 anchor 能工作的原因。</p>
     */
    static String slugify(String heading) {
        if (heading == null) return "";
        String s = Normalizer.normalize(heading, Normalizer.Form.NFC)
                .toLowerCase()
                .strip();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append('-');
            } else if (c == '-' || c == '_'
                    || Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
            // 其余标点全部丢弃（与 GitHub 一致）
        }
        // 折叠连续连字符并去掉首尾
        String out = sb.toString().replaceAll("-{2,}", "-");
        out = out.replaceAll("^-+", "").replaceAll("-+$", "");
        return out;
    }
}
