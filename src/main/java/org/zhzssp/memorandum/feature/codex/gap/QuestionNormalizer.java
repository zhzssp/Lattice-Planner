package org.zhzssp.memorandum.feature.codex.gap;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提问归一化——用于把「同一个问题的重复提问」聚成一条。
 *
 * <h3>一个必须先讲清的定位：askCount 是排序键，不是统计量</h3>
 * <p>它的用途是回答「我该先补哪个盲区」，而不是「这个问题我精确问过 N 次」。
 * 这个定位决定了全部取舍——一旦把它当统计量，就会掉进
 * 「为了精确而追求语义聚类」的坑里，而那既昂贵又不可靠。</p>
 *
 * <h3>为什么不做 embedding 语义聚类</h3>
 * <ol>
 *   <li><strong>过度聚合是有害的，且危害不对称。</strong>
 *       把两个不同的缺口合成一条，会让 askCount 虚高、且指向一个模糊的问题——
 *       转成学习计划时根本无法执行。反过来，分成两条只是让看板多一行。
 *       所以在拿不准的时候<strong>宁可分开</strong>。</li>
 *   <li>语义阈值需要按语料调参，而这里没有标注集可调。</li>
 *   <li>重复提问在真实场景里措辞高度接近（人会重复自己的说法），
 *       词法归一化已经能覆盖主要场景。</li>
 * </ol>
 *
 * <h3>处理流程</h3>
 * <pre>
 * 原文 → 去 markdown/代码 → 切 token（latin 串 / CJK 串各自成 token）
 *      → 去疑问词与停用词 → 丢弃过短 token → 排序 → 拼接 → 截断 255
 * </pre>
 *
 * <p><strong>排序是刻意的</strong>：「A 和 B 的区别」与「B 和 A 的区别」应当聚成一条，
 * 而它们只差在语序。代价是极小概率的误聚（同词不同序但语义不同的中文问句），
 * 而这类问句在提问里罕见。</p>
 *
 * <p><strong>CJK 串不切成单字</strong>：切成单字后排序等于「字符集合相同即视为同一问题」，
 * 那会把大量无关问句合并——这正是上面说的「有害的过度聚合」。</p>
 */
@Component
public class QuestionNormalizer {

    /** 归一化结果长度上限，与 {@code kb_gap.norm_question} 列宽一致。 */
    public static final int MAX_LEN = 255;

    /** 行内代码与围栏：代码内容不参与归一化（同一段代码可能出现在完全不同的问题里）。 */
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~");

    /** token：latin/数字/常见标识符字符 的连续串，或 CJK 的连续串。 */
    private static final Pattern TOKEN = Pattern.compile(
            "[a-z0-9][a-z0-9_.+#\\-]*|[\\u4e00-\\u9fff]+");

    /**
     * 中文疑问词与虚词。
     *
     * <p>只收<strong>确定无信息量</strong>的词。像「区别」「原理」「实现」这类
     * 刻意<em>不</em>收——它们区分了「X 是什么」和「X 与 Y 的区别」，
     * 而这是两个不同的缺口。</p>
     */
    private static final List<String> CJK_STOP = List.of(
            "为什么", "是什么", "怎么样", "怎么办", "有没有", "能不能", "是不是", "如何",
            "怎么", "什么", "请问", "一下", "可以", "应该", "需要", "我想", "我要",
            "帮我", "告诉我", "解释", "介绍", "讲讲", "说说", "这个", "那个",
            "的", "了", "吗", "呢", "吧", "是", "在", "和", "与", "或", "对",
            "我", "你", "它", "他", "们", "有", "会", "被", "把", "给", "从", "到");

    private static final Set<String> LATIN_STOP = Set.of(
            "what", "why", "how", "when", "where", "which", "who", "whose",
            "is", "are", "was", "were", "be", "been", "am",
            "do", "does", "did", "can", "could", "should", "would", "will",
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "with", "and", "or",
            "it", "its", "this", "that", "these", "those", "i", "me", "my", "you", "your",
            "please", "tell", "explain", "about");

    /**
     * 归一化。
     *
     * @return 归一化串；输入为空或全被过滤时返回 null（<strong>此时不应记录缺口</strong>——
     *         无法归一化的提问也无法去重，会在台账里堆出一串无法聚合的噪声）
     */
    public String normalize(String question) {
        if (question == null || question.isBlank()) return null;

        String s = CODE_FENCE.matcher(question).replaceAll(" ");
        s = s.replaceAll("`[^`]*`", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")   // 链接只留文字
                .toLowerCase(Locale.ROOT);

        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            String raw = m.group();
            if (isCjk(raw)) {
                tokens.addAll(splitCjk(raw));
            } else {
                String t = trimNoise(raw);
                if (acceptLatin(t)) tokens.add(t);
            }
        }
        if (tokens.isEmpty()) return null;

        // 去重后排序：语序不应影响聚合（「A 和 B 的区别」≡「B 和 A 的区别」）
        Set<String> uniq = new LinkedHashSet<>(tokens);
        List<String> sorted = new ArrayList<>(uniq);
        sorted.sort(String::compareTo);

        String out = String.join(" ", sorted);
        return out.length() <= MAX_LEN ? out : out.substring(0, MAX_LEN).strip();
    }

    /** 用于展示的短摘要（原文压一行 + 截断）。 */
    public String summarize(String question, int max) {
        if (question == null) return "";
        String s = question.strip().replaceAll("\\s+", " ");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /* ---------------- 内部 ---------------- */

    private static boolean isCjk(String s) {
        return !s.isEmpty() && s.charAt(0) >= '\u4e00' && s.charAt(0) <= '\u9fff';
    }

    /**
     * 停用词按长度降序，保证长词先匹配。
     *
     * <p>顺序不是细节：若「怎么」先于「怎么样」被替换，「怎么样」会残留一个「样」字，
     * 于是同一个问句在不同措辞下归一化结果不同，聚合失效。</p>
     */
    private static final List<String> CJK_STOP_BY_LEN = CJK_STOP.stream()
            .sorted((a, b) -> b.length() - a.length())
            .toList();

    /** 从 CJK 串里剔除停用词，剩余片段作为 token。 */
    private List<String> splitCjk(String run) {
        String s = run;
        for (String stop : CJK_STOP_BY_LEN) {
            s = s.replace(stop, "\u0001");
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split("\u0001+")) {
            String p = part.strip();
            // 单字 CJK 片段信息量太低（多为残留虚词），丢掉
            if (p.length() >= 2) out.add(p);
        }
        return out;
    }

    private static String trimNoise(String t) {
        String s = t;
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith("-") || s.endsWith("_"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean acceptLatin(String t) {
        if (t.length() < 2) return false;          // 单字母无信息量
        if (LATIN_STOP.contains(t)) return false;
        // 纯数字通常是版本号/行号，不构成缺口主题
        return !t.chars().allMatch(Character::isDigit);
    }

    /** 供测试与诊断：查看某个问题被切成了哪些 token。 */
    public List<String> tokensOf(String question) {
        String n = normalize(question);
        return n == null ? List.of() : Arrays.asList(n.split(" "));
    }
}
