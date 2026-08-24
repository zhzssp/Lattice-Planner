package org.zhzssp.memorandum.feature.codex.distill;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 检验册（checkpoint set）的排版模板。
 *
 * <h3>★格式必须与 {@code CheckpointParser} 逐字对齐，而不是"差不多"</h3>
 * <p>本模板产出的 Markdown 会被<strong>既有的</strong>解析器读回数据库。
 * 若这里写 {@code **验收命令:**} 而解析器认的是 {@code **验收命令**：}，
 * 结果不是报错，而是<em>那一节被静默忽略</em>——题目落库了、但没有判据，
 * 于是它永远无法被运行，而从数据库里看它是一条正常的 checkpoint。</p>
 *
 * <p>所以下面每个标记的形状都直接对应解析器里的一条正则：
 * 条目标题用全角 {@code ｜}、元信息用 {@code - **字段**：}、
 * 段落标题独占一行以 {@code ：} 结尾、验收命令放在 fenced 代码块里。
 * 改动其中任何一处，都必须同步改解析器并跑
 * {@code ExamRoundTripTest}——那个测试的全部意义就是守住这条对齐。</p>
 *
 * <h3>为什么不引入一套新的声明式格式</h3>
 * <p>声明式 front-matter 判据更精确（{@code VerifySource.DECLARED}），
 * 但那会让机器出的题与用户手写的 86 条题<strong>形态不同</strong>。
 * 用户以后想手改一条机器出的题时，得先学一套只有机器在用的 schema。
 * 产出物与手写内容同构，是这套设计里反复出现的取舍方向。</p>
 */
@Component
public class CheckpointTemplate {

    /** 机器出题标记——它跟着条目内容走，剪贴到别处也不会丢。 */
    public static final String AGENT_MARK = "本条由 Lattice Agent 起草，判据未经人工验证";

    /**
     * 一道题。
     *
     * @param level      L0~L3
     * @param ord        册内序号
     * @param title      标题
     * @param checksWhat 这条通过意味着掌握了什么
     * @param task       要做什么
     * @param prediction 「先预测再动手」要回答的问题
     * @param command    验收命令（多行，原样放进代码块）
     * @param criteria   通过标准（应机器可判定）
     * @param blindSpots 常见失败 → 盲点
     * @param resource   local / local+toolchain / gpu1 / gpuN / multinode
     */
    public record Item(String level, int ord, String title, String checksWhat,
                       String prerequisite, String task, String prediction,
                       String command, String criteria, String blindSpots,
                       String resource, String estHours) {}

    /**
     * 一册。
     *
     * @param topicCode  代码里的主题段，形如 {@code FLASHATTN}（只允许字母数字）
     * @param guidePath  对应的知识文档仓库内相对路径
     * @param labDir     对应动手项目目录；null 表示没有
     */
    public record Book(String title, String topicCode, String guidePath,
                       String labDir, List<Item> items) {}

    /** 渲染整册。 */
    public String render(Book book) {
        StringBuilder sb = new StringBuilder(4096);

        sb.append("---\n");
        sb.append("schema: 1\n");
        sb.append("kind: checkpoint-set\n");
        sb.append("maturity: draft\n");
        // ★这一行让整册被解析成 AGENT_DRAFT
        sb.append("authored_by: lattice-agent\n");
        sb.append("authored_at: ").append(LocalDate.now()).append('\n');
        if (book.guidePath() != null && !book.guidePath().isBlank()) {
            sb.append("covers: ").append(book.guidePath().strip()).append('\n');
        }
        sb.append("---\n\n");

        sb.append("# ").append(book.title().strip()).append("\n\n");
        sb.append("> ⚠ **这些题目由 AI 起草，判据未经人工验证。**\n");
        sb.append("> 跑之前请先读一遍验收命令：命令写错时失败原因是「环境不对」而不是「你没掌握」，\n");
        sb.append("> 那种失败会让通过率这个指标失去意义。确认无误后再把 `maturity` 改为 `reviewed`。\n\n");

        if (book.guidePath() != null && !book.guidePath().isBlank()) {
            sb.append("- **对应知识文档**：[`").append(fileName(book.guidePath()))
                    .append("`](").append(relative(book.guidePath())).append(")\n");
        }
        if (book.labDir() != null && !book.labDir().isBlank()) {
            // 这一行的格式对应 CheckpointParser.detectLab，用于推断验收命令的 cwd
            sb.append("- **对应动手项目**：[`").append(trimSlash(book.labDir()))
                    .append("/`](").append(relative(book.labDir())).append(")\n");
        }
        sb.append('\n');

        for (Item it : book.items()) {
            sb.append(renderItem(book.topicCode(), it));
        }
        return sb.toString();
    }

    /** 渲染单条——公开是为了让出题服务能逐条校验后再拼册。 */
    public String renderItem(String topicCode, Item it) {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("### ").append(it.level()).append('-')
                .append(topicCode).append('-')
                .append(String.format("%02d", it.ord()))
                .append('｜').append(oneLine(it.title())).append("\n\n");

        // 元信息：字段名与冒号形状必须与 META 正则一致
        metaLine(sb, "检验什么", it.checksWhat());
        metaLine(sb, "前置", it.prerequisite());
        metaLine(sb, "资源", "`" + nvl(it.resource(), "local") + "`");
        metaLine(sb, "预计耗时", nvl(it.estHours(), "1h"));
        sb.append('\n');

        block(sb, "任务", it.task());
        block(sb, "先预测再动手", it.prediction());

        sb.append("**验收命令**：\n\n```bash\n")
                .append(it.command() == null ? "" : it.command().strip())
                .append("\n```\n\n");

        block(sb, "通过标准", it.criteria());
        block(sb, "常见失败 → 盲点", it.blindSpots());

        sb.append("- _").append(AGENT_MARK).append("_\n\n---\n\n");
        return sb.toString();
    }

    /* ---------------- 内部 ---------------- */

    private void metaLine(StringBuilder sb, String field, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("- **").append(field).append("**：").append(oneLine(value)).append('\n');
    }

    private void block(StringBuilder sb, String name, String body) {
        if (body == null || body.isBlank()) return;
        sb.append("**").append(name).append("**：\n\n").append(body.strip()).append("\n\n");
    }

    private String oneLine(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    private String fileName(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /**
     * 检验册固定放在 {@code docs/checkpoints/} 下，指向 {@code docs/} 里其他文档
     * 或仓库根下的 lab 都要退一级。
     */
    private String relative(String repoPath) {
        String p = repoPath.replace('\\', '/');
        if (p.startsWith("docs/")) return "../" + p.substring("docs/".length());
        return "../../" + p;
    }

    private String trimSlash(String s) {
        String t = s.replace('\\', '/');
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.strip();
    }
}
