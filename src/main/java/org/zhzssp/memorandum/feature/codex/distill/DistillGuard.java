package org.zhzssp.memorandum.feature.codex.distill;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.gap.ScopeListParser;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 蒸馏产物的结构门禁。
 *
 * <h3>★判据的选法：不是「质量够好」，而是「下游机器真能用」</h3>
 * <p>「这篇 guide 写得好不好」无法机器判定，任何试图判定它的规则最后都会变成
 * 一堆可以被凑数满足的字数与关键词要求。所以这里换一个可判定的问法：
 * <strong>这份产物能不能被系统里已有的机制吃下去？</strong></p>
 *
 * <p>最典型的一条：止损线不是检查「有没有『可以先跳过』这个小节」，
 * 而是把产物直接喂给 P3 的 {@link ScopeListParser}，要求它<strong>真能抽出术语</strong>。
 * 抽不出来意味着这篇 guide 的止损线永远不会触发 skip 召回——
 * 它在系统里是<em>静默失效</em>的，而从文件本身完全看不出来。
 * 用真实解析器做判据，就把「看起来合规」与「实际可用」这条缝焊死了。</p>
 *
 * <h3>为什么止损线缺失要判 ERROR 而不是 WARN</h3>
 * <p>这是用户方法论里唯一被明确写成「不给出止损线的草稿直接判不合格」的一条。
 * 原因不是形式主义：一篇没有止损线的 guide 会让人一路深挖到放弃，
 * 而放弃发生时人通常归因于「我不适合学这个」，不会归因于「这份材料没告诉我哪里可以停」。
 * 这个失效不可观测，所以必须在产出时挡住。</p>
 */
@Component
public class DistillGuard {

    /** Markdown 表格分隔行。 */
    private static final Pattern TABLE_SEP = Pattern.compile(
            "(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");

    private static final Pattern FENCE = Pattern.compile("(?m)^\\s*(```|~~~)");

    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*(?:[-*+]|\\d+\\.)\\s+\\S");

    /** 行内代码标识符——「可对着原文核对的具体内容」的最低证据。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]{2,})`");

    /** 模板留下的占位注释：正文里还剩它，说明这一节模型没填。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("(?s)<!--.*?-->");

    /** 掌握标准至少要有这么多条，少于此说明只是敷衍。 */
    private static final int MIN_MASTERY_ITEMS = 3;

    /** 核心运行框架里至少要有这么多个具体标识符。 */
    private static final int MIN_FRAMEWORK_IDENTIFIERS = 4;

    public record Finding(String code, String message, String hint) {}

    /**
     * 判定结果。
     *
     * @param skipTerms 实际被 {@link ScopeListParser} 抽出的术语——
     *                  回报给用户是必要的：他要能看到「软件认为你跳过了这些」
     */
    public record Verdict(boolean pass, List<Finding> errors, List<Finding> warns,
                          List<String> skipTerms) {

        public String summary() {
            if (pass) {
                return "结构校验通过（止损线抽出 " + skipTerms.size() + " 条术语"
                        + (warns.isEmpty() ? "" : "；" + warns.size() + " 条提醒") + "）";
            }
            return "结构校验未通过：" + errors.size() + " 项不合格";
        }

        public String firstError() {
            return errors.isEmpty() ? null : errors.get(0).message() + " " + errors.get(0).hint();
        }
    }

    private final ScopeListParser scopeParser;
    private final MarkdownStructureParser structure;
    private final FrontMatterParser fm;

    public DistillGuard(ScopeListParser scopeParser,
                        MarkdownStructureParser structure,
                        FrontMatterParser fm) {
        this.scopeParser = scopeParser;
        this.structure = structure;
        this.fm = fm;
    }

    /**
     * 校验一份即将写入的 guide 草稿全文。
     *
     * @param content 完整文件内容（含 front-matter）
     */
    public Verdict check(String content) {
        List<Finding> errors = new ArrayList<>();
        List<Finding> warns = new ArrayList<>();

        if (content == null || content.isBlank()) {
            errors.add(new Finding("EMPTY", "产物为空。", null));
            return new Verdict(false, errors, warns, List.of());
        }

        FrontMatterParser.Result meta = fm.parse(content);
        List<MarkdownStructureParser.Section> sections =
                structure.parse(content, meta.bodyStart()).sections();
        // ---- ① 四个小节都必须在 ----
        String framework = bodyOf(content, sections, GuideTemplate.H_FRAMEWORK);
        String features = bodyOf(content, sections, GuideTemplate.H_FEATURES);
        String mastery = bodyOf(content, sections, GuideTemplate.H_MASTERY);
        String skip = bodyOf(content, sections, GuideTemplate.H_SKIP);

        if (framework == null) {
            errors.add(new Finding("MISSING_FRAMEWORK",
                    "缺少「" + GuideTemplate.H_FRAMEWORK + "」小节。",
                    "这一节是 guide 与「论文摘要」的分界：没有它，产物只是复述而不是教材。"));
        }
        if (features == null) {
            errors.add(new Finding("MISSING_FEATURES",
                    "缺少「" + GuideTemplate.H_FEATURES + "」小节。",
                    "没有必学特性表，读者无法知道哪些内容是主干、哪些是枝节。"));
        }
        if (mastery == null) {
            errors.add(new Finding("MISSING_MASTERY",
                    "缺少「" + GuideTemplate.H_MASTERY + "」小节。",
                    "没有掌握标准就无法出题，这篇 guide 后续接不上验证闭环。"));
        }

        // ---- ② ★止损线：判据是「解析器真能抽出术语」 ----
        List<String> skipTerms = new ArrayList<>();
        for (ScopeListParser.SkipItem item : scopeParser.parse(content, meta.bodyStart(), sections)) {
            skipTerms.add(item.term());
        }
        if (skip == null) {
            errors.add(new Finding("MISSING_SKIP",
                    "缺少「" + GuideTemplate.H_SKIP + "」小节——不给出止损线的草稿直接判不合格。",
                    "没有止损线的 guide 会让人一路深挖到放弃，"
                            + "而放弃时人会归因于「我不适合学这个」，不会归因于材料没说哪里可以停。"));
        } else if (skipTerms.isEmpty()) {
            errors.add(new Finding("SKIP_UNPARSEABLE",
                    "有「" + GuideTemplate.H_SKIP + "」小节，但止损线解析器一条术语都抽不出来。",
                    "这比缺失更隐蔽：文件看起来合规，而 skip 召回对这篇 guide 永远不会触发，"
                            + "它在系统里是静默失效的。"
                            + "把要跳过的东西写成列表项，并用行内反引号或粗体标出具体名字，"
                            + "例如：- `transform dialect` 的完整用法（除非要做自动调优）。"));
        } else if (!hasSpecificSkipItem(skip)) {
            // ★同一个解析器，对既有语料与对新产出用两套严格度。
            //
            // ScopeListParser 刻意宽容——它要能从用户几年写下的各种写法里抽出东西，
            // 所以「实现细节」这样的领头短语它也会收。这对解析既有语料是对的。
            //
            // 但对模型的新产出必须严：它会非常乐意写「实现细节」「其余部分」，
            // 那种条目虽然能被抽成术语，却永远匹配不上任何一次真实提问，
            // 于是这篇 guide 的 skip 召回照样是死的——只不过表面上有术语了，
            // 连 SKIP_UNPARSEABLE 都不会报。这一条就是补这个缝。
            errors.add(new Finding("SKIP_TOO_VAGUE",
                    "止损线条目里没有一条用反引号或粗体标出具体名字，"
                            + "抽出的术语是「" + String.join("、", skipTerms) + "」这类空话。",
                    "止损线要能在你下次问到它时被认出来，所以必须是具体的名字"
                            + "（API / 子模块 / 变体 / 论文里的方法名）。"
                            + "「实现细节」「其余部分」这类条目匹配不上任何一次真实提问，"
                            + "写进去等于没写。"));
        }

        // ---- ③ 必学特性表必须真的是表 ----
        if (features != null && !TABLE_SEP.matcher(features).find()) {
            // 判 WARN 而非 ERROR：偶尔用列表表达也说得通，但要提醒
            warns.add(new Finding("FEATURES_NOT_TABLE",
                    "「" + GuideTemplate.H_FEATURES + "」不是 Markdown 表格。",
                    "并排对照本身就是知识：三列（特性 / 为什么必学 / 在原文哪一节）"
                            + "写成散文后，读者要自己在脑子里重建这张表。"));
        }

        // ---- ④ 核心运行框架要有可核对的具体内容 ----
        if (framework != null) {
            int identifiers = countMatches(INLINE_CODE, framework);
            boolean hasBlock = FENCE.matcher(framework).find() || TABLE_SEP.matcher(framework).find();
            if (!hasBlock && identifiers < MIN_FRAMEWORK_IDENTIFIERS) {
                errors.add(new Finding("FRAMEWORK_TOO_ABSTRACT",
                        "「" + GuideTemplate.H_FRAMEWORK + "」里没有代码块、也几乎没有具体标识符"
                                + "（只有 " + identifiers + " 个），无法对着原文核对。",
                        "蒸馏最典型的失败不是写错，是写得<空>——"
                                + "全是「该模块负责处理相关逻辑」这类无法验证也无法反驳的句子。"
                                + "请写出真实的对象名 / 函数名 / 张量维度 / 一次调用的站点顺序。"));
            }
        }

        // ---- ⑤ 掌握标准要成条目且足够多 ----
        if (mastery != null) {
            int items = countMatches(BULLET, mastery);
            if (items < MIN_MASTERY_ITEMS) {
                warns.add(new Finding("MASTERY_TOO_FEW",
                        "「" + GuideTemplate.H_MASTERY + "」只有 " + items + " 条（建议 ≥ "
                                + MIN_MASTERY_ITEMS + "）。",
                        "掌握标准条数太少时，它退化成一句「理解本文内容」，无法出题。"));
            }
        }

        // ---- ⑥ 占位注释残留说明某节没填 ----
        if (PLACEHOLDER.matcher(stripFrontMatter(content, meta.bodyStart())).find()) {
            warns.add(new Finding("PLACEHOLDER_LEFT",
                    "正文里仍有模板占位注释（<!-- ... -->），说明至少有一节模型没填。",
                    "占位注释在渲染后不可见，用户很可能不会注意到那一节是空的。"));
        }

        // ---- ⑦ 草稿标记不可缺 ----
        String maturity = meta.str("maturity");
        if (maturity == null || !"draft".equalsIgnoreCase(maturity)) {
            errors.add(new Finding("NOT_MARKED_DRAFT",
                    "front-matter 里 maturity 不是 draft（实际：" + maturity + "）。",
                    "未经核对的内容一旦看起来与手写 guide 无异，"
                            + "半年后就会被当作可信来源引用，而里面可能有一处模型编的参数。"));
        }
        if (!content.contains("尚未经人工核对")) {
            errors.add(new Finding("NO_VISIBLE_BANNER",
                    "正文顶部缺少渲染后可见的草稿警示。",
                    "front-matter 在多数渲染器里不显示，只靠它标注等于没标注。"));
        }

        return new Verdict(errors.isEmpty(), errors, warns, skipTerms);
    }

    /* ---------------- 内部 ---------------- */

    /** 粗体（判断止损线条目是否标出了具体名字）。 */
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]{2,})\\*\\*");

    /**
     * 止损线里是否至少有一条<strong>标出了具体名字</strong>的条目。
     *
     * <p>判据是「该行含行内反引号或粗体」——这正是 {@code ScopeListParser}
     * 视为高置信来源的两个位置。要求与下游解析器的高置信规则对齐，
     * 而不是另定一套「看起来具体」的标准。</p>
     */
    private boolean hasSpecificSkipItem(String skipBody) {
        boolean inFence = false;
        for (String raw : skipBody.split("\\R")) {
            String t = raw.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            if (!BULLET.matcher(raw).find()) continue;
            if (INLINE_CODE.matcher(raw).find() || BOLD.matcher(raw).find()) return true;
        }
        return false;
    }

    /**
     * 取标题里含给定关键词的小节正文；找不到返回 null。
     *
     * <p>{@code charStart} 已经是标题行<em>之后</em>的偏移
     * （见 {@link MarkdownStructureParser.Section}），所以这里直接截取即可。
     * 若再自行去掉首行，会把正文第一行当成标题删掉——
     * 而正文第一行恰恰常是表头或代码块起始，删掉后判据全部误判。</p>
     */
    private String bodyOf(String content, List<MarkdownStructureParser.Section> sections,
                          String headingKeyword) {
        for (MarkdownStructureParser.Section s : sections) {
            if (s.heading() == null || !s.heading().contains(headingKeyword)) continue;
            int from = Math.max(0, Math.min(s.charStart(), content.length()));
            int to = Math.max(from, Math.min(s.charEnd(), content.length()));
            return content.substring(from, to);
        }
        return null;
    }

    private String stripFrontMatter(String content, int bodyStart) {
        int s = Math.max(0, Math.min(bodyStart, content.length()));
        return content.substring(s);
    }

    private int countMatches(Pattern p, String s) {
        int n = 0;
        var m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }
}
