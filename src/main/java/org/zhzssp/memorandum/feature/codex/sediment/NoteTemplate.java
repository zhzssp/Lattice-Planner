package org.zhzssp.memorandum.feature.codex.sediment;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 笔记与速记引用的排版模板。
 *
 * <h3>为什么模板由代码固定而不交给 LLM 自由发挥</h3>
 * <p>用户仓库里已有 19 篇手写笔记，全部遵循同一结构（标题 → 来源行 → 是什么 → 规则 → 示例）。
 * 若软件产出的笔记结构飘忽，两类笔记混在一个目录里，
 * 「一眼看出这是我写的还是机器写的」就会变成日常摩擦。</p>
 *
 * <p>所以分工是：<strong>结构由代码保证，内容由模型生成</strong>。
 * 模型只负责填正文段落，标题行与来源行由本类拼装。</p>
 *
 * <h3>速记引用格式跟随语料，而非跟随 SKILL.md 的模板</h3>
 * <p>SKILL.md 的模板写的是 {@code [notes/x.md](../notes/x.md)}（链接文字不带 {@code ../}），
 * 但仓库里 23 处真实速记引用<strong>全部</strong>写成
 * {@code [../notes/x.md](../notes/x.md)}（文字与目标一致）。</p>
 *
 * <p>这里选择跟随语料。原因是产出物必须与手写内容<strong>无法区分</strong>：
 * 若软件按文档模板生成、而人按习惯手写，同一篇 guide 里就会出现两种写法，
 * 后续任何基于文本匹配的校验与迁移都要同时兼容两种形态。
 * 语料是事实，文档是意图，冲突时以事实为准。</p>
 */
@Component
public class NoteTemplate {

    /** 速记行前缀，与语料完全一致。 */
    public static final String BACKREF_PREFIX = "> **速记**：";

    /**
     * 一篇笔记的规格。
     *
     * @param title        笔记标题（H1）
     * @param sourcePath   来源文档的仓库内相对路径；null 表示无挂靠点
     * @param sectionLabel 来源章节标注，如 {@code 2.3} 或 {@code 4.6 timeline semaphore}
     * @param body         正文（不含 H1 与来源行），由模型生成
     */
    public record Spec(String title, String notePath, String sourcePath,
                       String sectionLabel, String body) {}

    /**
     * 渲染完整笔记内容。
     *
     * <p>刻意不写 front-matter：现存 19 篇一篇都没有，新产出的若带上，
     * 目录里就会分成「有元数据的机器笔记」与「没元数据的手写笔记」两类。
     * 元数据的补齐应当是一次统一的迁移，而不是从今天起新旧不一致。</p>
     */
    public String render(Spec spec) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("# ").append(spec.title().strip()).append("\n\n");

        if (spec.sourcePath() != null && !spec.sourcePath().isBlank()) {
            sb.append(sourceLine(spec.notePath(), spec.sourcePath(), spec.sectionLabel()))
                    .append("\n\n");
        }

        String body = spec.body() == null ? "" : spec.body().strip();
        // 模型常会把 H1 也写进正文，去掉重复的首行标题
        body = stripLeadingH1(body, spec.title());
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /**
     * 来源行：{@code > 来源：[`llvm-learning-guide.md`](../learning-guides/llvm-learning-guide.md) §2.3}
     */
    public String sourceLine(String notePath, String sourcePath, String sectionLabel) {
        String rel = relative(notePath, sourcePath);
        String fileName = fileNameOf(sourcePath);
        StringBuilder sb = new StringBuilder();
        sb.append("> 来源：[`").append(fileName).append("`](").append(rel).append(")");
        if (sectionLabel != null && !sectionLabel.isBlank()) {
            String s = sectionLabel.strip();
            sb.append(' ').append(s.startsWith("§") ? s : "§" + s);
        }
        return sb.toString();
    }

    /**
     * 速记引用行：{@code > **速记**：[../notes/x.md](../notes/x.md) —— 摘要。}
     *
     * @param guidePath 被插入的知识文档路径（决定相对路径怎么算）
     * @param notePath  笔记路径
     * @param summary   一句话摘要——它的作用是让扫读者决定是否点开，因此不能省
     */
    public String backrefLine(String guidePath, String notePath, String summary) {
        String rel = relative(guidePath, notePath);
        String s = summary == null ? "" : summary.strip();
        // 摘要末尾补句号，但已有中英文终止标点时不重复补
        if (!s.isEmpty() && !endsWithTerminator(s)) s = s + "。";
        return BACKREF_PREFIX + "[" + rel + "](" + rel + ")"
                + (s.isEmpty() ? "" : " —— " + s);
    }

    private static boolean endsWithTerminator(String s) {
        char c = s.charAt(s.length() - 1);
        return c == '。' || c == '？' || c == '！' || c == '.' || c == '?' || c == '!'
                || c == '；' || c == ';' || c == '：' || c == ':';
    }

    /**
     * 从 {@code fromPath} 所在目录指向 {@code toPath} 的相对路径（正斜杠）。
     *
     * <p>用 {@link Path#relativize} 而非手写字符串拼接：{@code docs/notes} 与
     * {@code docs/learning-guides} 互指要产出 {@code ../notes/x.md}，
     * 手写拼接在同级、跨级、同目录三种情形下很容易少或多一层 {@code ../}，
     * 而这类错误产生的是<em>看起来对但点不开</em>的链接。</p>
     */
    public String relative(String fromPath, String toPath) {
        if (fromPath == null || toPath == null) return toPath;
        String from = fromPath.replace('\\', '/');
        String to = toPath.replace('\\', '/');
        int slash = from.lastIndexOf('/');
        if (slash < 0) return to;                       // 源在仓库根，直接用原路径
        String fromDir = from.substring(0, slash);
        try {
            String rel = Path.of(fromDir).relativize(Path.of(to)).toString().replace('\\', '/');
            // 同目录时 relativize 得到裸文件名，Markdown 里习惯写成 ./x.md
            return rel.startsWith(".") ? rel : (rel.contains("/") ? rel : "./" + rel);
        } catch (Exception e) {
            return to;
        }
    }

    /** 标题 → 文件名 slug（短横线、小写）。 */
    public String slug(String title) {
        if (title == null || title.isBlank()) return "note";
        String s = title.strip().toLowerCase(Locale.ROOT);
        // 保留 CJK：中文标题直接转拼音不现实，保留原字比生成 note-1 有意义得多
        s = s.replaceAll("[\\s_/\\\\]+", "-")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (s.isBlank()) return "note";
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-$", "") : s;
    }

    private String fileNameOf(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private String stripLeadingH1(String body, String title) {
        if (body.isEmpty()) return body;
        String[] lines = body.split("\\R", -1);
        if (lines.length == 0) return body;
        String first = lines[0].strip();
        if (!first.startsWith("# ")) return body;
        String h1 = first.substring(2).strip();
        if (title != null && !h1.equalsIgnoreCase(title.strip())) return body;
        // 去掉重复的 H1 及其后的空行
        int idx = 1;
        while (idx < lines.length && lines[idx].isBlank()) idx++;
        return String.join("\n", java.util.Arrays.asList(lines).subList(idx, lines.length));
    }
}
