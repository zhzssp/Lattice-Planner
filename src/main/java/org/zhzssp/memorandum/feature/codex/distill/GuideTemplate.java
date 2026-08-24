package org.zhzssp.memorandum.feature.codex.distill;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 蒸馏产物（Guide 草稿）的排版模板。
 *
 * <h3>结构由代码固定，内容由模型填</h3>
 * <p>沿用 {@code NoteTemplate} 的分工。但这里的固定结构不只是为了好看：
 * 用户方法论对一篇 guide 的要求是明确的四件东西——
 * <strong>核心运行框架 / 必学特性表 / 掌握标准 / 先跳过</strong>。
 * 由代码把这四个小节钉死，模型就只能在里面填，不能"发挥"成一篇散文。
 * 而这四个小节全部存在，正是下游门禁能够机器判定的前提。</p>
 *
 * <h3>★为什么这里要写 front-matter，而笔记刻意不写</h3>
 * <p>看似不一致，实则相反的两个诉求：</p>
 * <ul>
 *   <li><strong>沉淀笔记</strong>的内容是用户在对话里<em>已经认可过</em>的，
 *       所以产物要与手写笔记<strong>无法区分</strong>——加 front-matter 反而会把
 *       目录劈成「机器笔记」与「手写笔记」两类。</li>
 *   <li><strong>蒸馏草稿</strong>的内容<em>没有任何人看过</em>。
 *       它必须<strong>一眼可辨</strong>，否则半年后它会和手写 guide 一样被当作可信来源引用，
 *       而里面可能有一处模型编的维度或参数——这是最坏的失效，
 *       因为错误已经通过引用扩散出去了。</li>
 * </ul>
 * <p>所以这里既写 {@code maturity: draft}，又在正文顶部放一个<strong>渲染后可见</strong>的
 * 待核对块（front-matter 在多数 Markdown 渲染器里是不显示的，不能只靠它）。</p>
 */
@Component
public class GuideTemplate {

    /** 小节标题——下游门禁按这些标题判定结构完整性，改动时必须同步 DistillGuard。 */
    public static final String H_FRAMEWORK = "核心运行框架";
    public static final String H_FEATURES = "必学特性表";
    public static final String H_MASTERY = "掌握标准";
    public static final String H_SKIP = "可以先跳过";
    public static final String H_REVIEW = "待人工核对";

    /** 正文顶部的可见警示块。 */
    public static final String DRAFT_BANNER =
            "> ⚠ **这是 AI 蒸馏草稿，尚未经人工核对。**\n"
                    + "> 其中的接口名、维度、参数与结论都可能与原文不符。\n"
                    + "> 核对完成后请删除本段并把 front-matter 的 `maturity` 改为 `reviewed`。";

    /**
     * 一篇草稿的规格。
     *
     * @param title      guide 标题
     * @param sourceRef  原料标识（论文文件名 / 官方文档 URL）
     * @param sourcePath 原料在仓库内的相对路径；原料在仓库外时为 null
     * @param domain     所属域（用于 front-matter，可空）
     * @param oneLiner   一句话结论：这篇讲什么、为什么值得学
     * @param framework  核心运行框架正文（应含流程/代码/IR）
     * @param features   必学特性表正文（应为 Markdown 表格）
     * @param mastery    掌握标准正文（应为可判定的条目）
     * @param skipItems  先跳过清单（★不可为空）
     * @param openIssues 模型自报的不确定处——它比正文更该被人先看
     */
    public record Spec(String title, String sourceRef, String sourcePath, String domain,
                       String oneLiner, String framework, String features,
                       String mastery, List<String> skipItems, List<String> openIssues,
                       int sourceChars, int sourcePages) {}

    /** 渲染完整草稿。 */
    public String render(Spec spec) {
        StringBuilder sb = new StringBuilder(8192);

        sb.append("---\n");
        sb.append("schema: 1\n");
        sb.append("kind: guide\n");
        sb.append("subkind: paper-note\n");
        // ★draft 是本模板最重要的一个字段：它让「未经核对」成为机器可读的事实，
        // 从而能被知识 CI 与检索排序区别对待，而不只是一句免责声明
        sb.append("maturity: draft\n");
        if (spec.domain() != null && !spec.domain().isBlank()) {
            sb.append("domain: ").append(spec.domain().strip()).append('\n');
        }
        sb.append("distilled_by: lattice-agent\n");
        sb.append("distilled_at: ").append(LocalDate.now()).append('\n');
        sb.append("source:\n");
        sb.append("  kind: ").append(spec.sourcePath() != null ? "file" : "external").append('\n');
        sb.append("  ref: ").append(yamlScalar(spec.sourceRef())).append('\n');
        if (spec.sourcePath() != null && !spec.sourcePath().isBlank()) {
            sb.append("  path: ").append(yamlScalar(spec.sourcePath())).append('\n');
        }
        // 原料规模写进 front-matter：将来原料被替换/更新时，靠它能判断草稿是否已过期
        sb.append("  chars: ").append(spec.sourceChars()).append('\n');
        sb.append("  pages: ").append(spec.sourcePages()).append('\n');
        sb.append("---\n\n");

        sb.append("# ").append(spec.title().strip()).append("\n\n");
        sb.append(DRAFT_BANNER).append("\n\n");

        if (notBlank(spec.oneLiner())) {
            sb.append("> **一句话**：").append(oneLine(spec.oneLiner())).append("\n\n");
        }
        sb.append("> **原料**：").append(spec.sourceRef() == null ? "（未标注）" : spec.sourceRef())
                .append("（").append(spec.sourcePages()).append(" 页 / ")
                .append(spec.sourceChars()).append(" 字符）\n\n");

        section(sb, 2, "1 " + H_FRAMEWORK, spec.framework(),
                "<!-- 这一节该回答：数据/控制流怎么走，关键对象是什么，"
                        + "一次典型调用经过哪些站点。必须有可对着原文核对的具体内容。 -->");

        section(sb, 2, "2 " + H_FEATURES, spec.features(),
                "<!-- 表格三列：特性 | 为什么必学 | 在哪一节/哪个图表能看到 -->");

        section(sb, 2, "3 " + H_MASTERY, spec.mastery(),
                "<!-- 每条都要可判定：能画出 X、能说清 Y 与 Z 的区别、能改一处并预测结果 -->");

        // ---- 先跳过（★门禁小节，标题层级与措辞必须让 ScopeListParser 能认出来）----
        sb.append("## 4 ").append(H_SKIP).append("\n\n");
        List<String> skips = spec.skipItems() == null ? List.of() : spec.skipItems();
        if (skips.isEmpty()) {
            // 正常路径不会走到这里（门禁会先拒），留着是为了让人工构造的 Spec 也不产出假合规文件
            sb.append("<!-- 缺失：没有止损线的 guide 会让人一路深挖到放弃 -->\n\n");
        } else {
            for (String s : skips) {
                sb.append("- ").append(oneLine(s)).append('\n');
            }
            sb.append('\n');
        }

        // ---- 待人工核对 ----
        sb.append("## 5 ").append(H_REVIEW).append("\n\n");
        List<String> issues = spec.openIssues() == null
                ? new ArrayList<>() : new ArrayList<>(spec.openIssues());
        // 固定三条：这三处是 LLM 从 PDF 蒸馏时最常出错的地方，与具体论文无关
        issues.add("公式与图表：PDF 提取会丢公式排版与全部图，本草稿里凡涉及公式/图的表述都需回原文核对。");
        issues.add("接口名与维度：模型可能把相近的 API 名或张量维度写错，这类错误读起来完全通顺。");
        issues.add("止损线是否合理：上面「可以先跳过」的判断来自模型，须由你确认——"
                + "跳错了会在几周后变成挡路的盲区。");
        for (String s : issues) {
            sb.append("- [ ] ").append(oneLine(s)).append('\n');
        }
        sb.append('\n');

        return sb.toString();
    }

    /** 检验册草稿的文件名 slug（与 NoteTemplate.slug 同规则，此处独立以免相互牵连）。 */
    public String slug(String title) {
        if (title == null || title.isBlank()) return "guide";
        String s = title.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s_/\\\\]+", "-")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (s.isBlank()) return "guide";
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-$", "") : s;
    }

    /* ---------------- 内部 ---------------- */

    private void section(StringBuilder sb, int level, String heading,
                         String body, String hintWhenEmpty) {
        sb.append("#".repeat(level)).append(' ').append(heading).append("\n\n");
        if (notBlank(body)) {
            sb.append(stripOwnHeading(body, heading).strip()).append("\n\n");
        } else {
            sb.append(hintWhenEmpty).append("\n\n");
        }
    }

    /** 模型常把小节标题也写进内容里，去掉重复的那一行。 */
    private String stripOwnHeading(String body, String heading) {
        String[] lines = body.strip().split("\\R", -1);
        if (lines.length == 0) return body;
        String first = lines[0].strip();
        if (!first.startsWith("#")) return body;
        String text = first.replaceFirst("^#+\\s*", "");
        String bare = heading.replaceFirst("^\\d+\\s+", "");
        if (!text.contains(bare)) return body;
        int i = 1;
        while (i < lines.length && lines[i].isBlank()) i++;
        return String.join("\n", java.util.Arrays.asList(lines).subList(i, lines.length));
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String oneLine(String s) {
        return s.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    /** YAML 标量：含特殊字符时加引号，避免产出的 front-matter 自己解析不了。 */
    private String yamlScalar(String s) {
        if (s == null || s.isBlank()) return "\"\"";
        String t = s.strip().replace("\"", "'");
        boolean needQuote = t.contains(":") || t.contains("#") || t.contains("[")
                || t.contains("]") || t.contains("{") || t.contains("}") || t.contains(",");
        return needQuote ? "\"" + t + "\"" : t;
    }
}
