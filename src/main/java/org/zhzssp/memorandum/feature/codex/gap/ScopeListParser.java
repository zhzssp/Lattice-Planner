package org.zhzssp.memorandum.feature.codex.gap;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「先跳过」清单解析器。
 *
 * <h3>为什么 P3 必须提前做这件事</h3>
 * <p>skip 召回是本期最有价值的一环，但它依赖 {@code kb_entity} 有数据，
 * 而原计划里概念抽取属于后续的「蒸馏」阶段。若照原顺序，
 * skip 召回上线即是死的——表里一条数据都没有，永远不会触发。</p>
 *
 * <p>所以这里做一个<strong>窄口径</strong>的解析：只抽「先跳过」清单，
 * 不做全量概念抽取。两者的难度差一个数量级——
 * 前者有明确的章节边界与列表结构，后者需要理解全文语义。</p>
 *
 * <h3>精度优先，且是不对称的</h3>
 * <p>误报「这个你标了先跳过」的代价远高于漏报：</p>
 * <ul>
 *   <li>漏一个术语 → 少一次提醒，用户毫无感觉；</li>
 *   <li>误报一次 → 用户立刻发现软件在瞎猜，几天内就会关掉这个功能。</li>
 * </ul>
 * <p>因此规则刻意保守：只在明确的「先跳过」小节里抽，只认
 * 行内代码 / 粗体 / 短的领头短语这三类高置信位置，
 * 并且把整条原文存进 {@code reason} 供用户核对。</p>
 */
@Component
public class ScopeListParser {

    /** 章节标题里表示「先跳过」的措辞。实测语料里的全部变体。 */
    private static final Pattern SKIP_HEADING = Pattern.compile(
            "(可以)?先跳过|可以跳过|可推迟|可以先跳过|先不(学|看|深挖)|以后再(看|学)");

    /**
     * 行内粗体形式的小标题，如 {@code **可以先跳过的内容**：} / {@code **可以先跳过：**}。
     *
     * <p>必须支持：paper-notes 目录下大量用这种写法而非 {@code ###} 标题。</p>
     */
    private static final Pattern SKIP_BOLD_LEAD = Pattern.compile(
            "^\\s*\\*\\*[^*]*((可以)?先跳过|可以跳过|可推迟)[^*]*\\*\\*\\s*[：:]?\\s*$");

    /** 列表项。 */
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+(.*)$");

    /** 行内代码。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    /** 粗体。 */
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /** 标题行（用于判断 bold-lead 段落的结束边界）。 */
    private static final Pattern ANY_HEADING = Pattern.compile("^\\s*#{1,6}\\s+");

    /** 术语里不该出现的收尾虚词——出现说明截取位置不对。 */
    private static final List<String> BAD_TAIL = List.of("的", "与", "和", "或", "及", "在", "把", "被");

    /**
     * 抽出的一条止损线。
     *
     * @param term    可被匹配的术语
     * @param reason  原始列表项全文——<strong>必须保留</strong>，
     *                用户要能核对「软件凭什么说我跳过了它」
     * @param anchor  所在章节 anchor，供跳回原文
     */
    public record SkipItem(String term, String reason, String anchor) {}

    /**
     * 解析一篇文档里的全部「先跳过」条目。
     *
     * @param content   文档全文
     * @param bodyStart front-matter 之后的偏移
     * @param sections  已解析的章节（由 {@link MarkdownStructureParser} 提供）
     */
    public List<SkipItem> parse(String content, int bodyStart,
                                List<MarkdownStructureParser.Section> sections) {
        if (content == null || content.isEmpty()) return List.of();

        // term（小写）→ 首次出现的条目。同一术语在一篇文档里重复出现只留第一条
        Map<String, SkipItem> out = new LinkedHashMap<>();

        // ---- 形式一：独立的「先跳过」小节 ----
        if (sections != null) {
            for (MarkdownStructureParser.Section s : sections) {
                if (!SKIP_HEADING.matcher(s.heading()).find()) continue;
                int from = Math.max(0, Math.min(s.charStart(), content.length()));
                int to = Math.max(from, Math.min(s.charEnd(), content.length()));
                collectFromBlock(content.substring(from, to), s.anchor(), out);
            }
        }

        // ---- 形式二：行内粗体小标题 + 紧随其后的列表 ----
        collectFromBoldLeads(content, bodyStart, sections, out);

        return new ArrayList<>(out.values());
    }

    /* ---------------- 形式二 ---------------- */

    private void collectFromBoldLeads(String content, int bodyStart,
                                      List<MarkdownStructureParser.Section> sections,
                                      Map<String, SkipItem> out) {
        int start = Math.max(0, Math.min(bodyStart, content.length()));
        String[] lines = content.substring(start).split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            if (!SKIP_BOLD_LEAD.matcher(lines[i]).matches()) continue;

            // 收集紧随其后的列表；允许中间有空行，但遇到标题或连续两个非列表行即停止
            StringBuilder block = new StringBuilder();
            int blanks = 0;
            for (int j = i + 1; j < lines.length; j++) {
                String line = lines[j];
                if (ANY_HEADING.matcher(line).find()) break;
                if (line.isBlank()) {
                    if (++blanks >= 2 && block.length() > 0) break;
                    continue;
                }
                if (BULLET.matcher(line).matches()) {
                    blanks = 0;
                    block.append(line).append('\n');
                } else if (block.length() > 0) {
                    // 列表已经开始又出现非列表行 → 段落结束
                    break;
                } else {
                    // 粗体小标题与列表之间可能夹一句说明（「与根 README 一致：」），容忍一行
                    blanks = 0;
                }
            }
            if (block.length() == 0) continue;

            int absolute = start + offsetOfLine(lines, i);
            collectFromBlock(block.toString(), anchorAt(sections, absolute), out);
        }
    }

    private int offsetOfLine(String[] lines, int index) {
        int off = 0;
        for (int i = 0; i < index && i < lines.length; i++) {
            off += lines[i].length() + 1;
        }
        return off;
    }

    /** 找出该偏移落在哪个章节内，供 anchor 回指。 */
    private String anchorAt(List<MarkdownStructureParser.Section> sections, int offset) {
        if (sections == null) return null;
        String anchor = null;
        for (MarkdownStructureParser.Section s : sections) {
            if (s.lineStart() <= offset) anchor = s.anchor();
            else break;
        }
        return anchor;
    }

    /* ---------------- 术语抽取 ---------------- */

    private void collectFromBlock(String block, String anchor, Map<String, SkipItem> out) {
        boolean inFence = false;
        for (String raw : block.split("\\R")) {
            if (raw == null) continue;
            String trimmed = raw.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;

            Matcher b = BULLET.matcher(raw);
            if (!b.matches()) continue;
            String item = b.group(1).strip();
            if (item.isEmpty()) continue;

            for (String term : termsOf(item)) {
                out.putIfAbsent(term.toLowerCase(Locale.ROOT),
                        new SkipItem(term, clip(item, 480), anchor));
            }
        }
    }

    /**
     * 从一条列表项里抽术语。
     *
     * <p>三类来源，置信度依次下降：</p>
     * <ol>
     *   <li>行内代码 —— 作者显式标记的标识符（{@code spirv} / {@code DICompositeType}），置信最高；</li>
     *   <li>粗体 —— 作者显式强调的概念；</li>
     *   <li>领头短语 —— 第一个分隔符（（：。）之前的片段，再按 {@code /}、{@code 、} 切开。
     *       只取前 3 段，且过滤掉像句子的片段。</li>
     * </ol>
     */
    public List<String> termsOf(String item) {
        List<String> terms = new ArrayList<>();

        Matcher code = INLINE_CODE.matcher(item);
        while (code.find()) addIfValid(terms, code.group(1));

        Matcher bold = BOLD.matcher(item);
        while (bold.find()) addIfValid(terms, bold.group(1));

        // 领头短语：先去掉行内标记，再截到第一个分隔符
        String plain = item.replaceAll("`[^`]*`", " ")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("[*_]", "");
        int cut = indexOfAny(plain, "（(：:。.；;，,");
        String lead = (cut > 0 ? plain.substring(0, cut) : plain).strip();
        if (!lead.isEmpty()) {
            String[] parts = lead.split("\\s*[/、]\\s*");
            for (int i = 0; i < parts.length && i < 3; i++) {
                addIfValid(terms, parts[i]);
            }
        }
        return terms;
    }

    private void addIfValid(List<String> sink, String raw) {
        String t = clean(raw);
        if (t == null) return;
        for (String existing : sink) {
            if (existing.equalsIgnoreCase(t)) return;
        }
        sink.add(t);
    }

    /**
     * 术语过滤——这里的每条规则都对应一类真实的误报。
     *
     * @return 合格术语，否则 null
     */
    public String clean(String raw) {
        if (raw == null) return null;
        String t = raw.strip()
                .replaceAll("^[\\-–—•]+", "")
                .replaceAll("[。．.,，、；;：:！!？?]+$", "")
                .strip();
        if (t.isEmpty()) return null;
        if (t.length() > 40) return null;                       // 明显是句子

        boolean hasLatin = t.matches(".*[A-Za-z].*");
        boolean pureLatin = t.matches("[A-Za-z0-9_.+#\\-\\s]+");

        // 纯 latin 的两字母术语（io / os / mm）在提问里几乎必然误报
        if (pureLatin && t.replaceAll("[^A-Za-z0-9]", "").length() < 3) return null;
        // 词数过多 → 是短句不是术语
        if (t.split("\\s+").length > 5) return null;
        // 纯中文且偏长 → 是描述不是术语。含 latin 的可放宽（「Transform dialect 完整用法」）
        if (!hasLatin && t.length() > 12) return null;
        // 以虚词收尾说明截断位置不对（「异常处理的」）
        for (String bad : BAD_TAIL) {
            if (t.endsWith(bad)) return null;
        }
        // 只剩标点/数字
        if (!t.matches(".*[A-Za-z\\u4e00-\\u9fff].*")) return null;
        return t;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static String clip(String s, int max) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
