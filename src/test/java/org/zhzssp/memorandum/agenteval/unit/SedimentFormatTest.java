package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;
import org.zhzssp.memorandum.feature.codex.sediment.BackrefInserter;
import org.zhzssp.memorandum.feature.codex.sediment.NoteTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：笔记排版与速记引用插入。
 *
 * <h3>为什么格式要有测试守着</h3>
 * <p>产出物必须与用户手写的内容<strong>无法区分</strong>。若软件按一种写法生成、
 * 用户按另一种习惯手写，同一篇 guide 里就会出现两种格式，
 * 之后任何基于文本匹配的校验（尤其双向性检查）都要同时兼容两种形态。</p>
 *
 * <p>目标语料里 23 处真实速记引用<strong>全部</strong>是
 * {@code [../notes/x.md](../notes/x.md)}（链接文字与目标一致），
 * 而 SKILL.md 的模板写的是 {@code [notes/x.md](../notes/x.md)}。
 * 这里跟随语料——语料是事实，文档是意图，冲突时以事实为准。</p>
 */
class SedimentFormatTest {

    private final NoteTemplate template = new NoteTemplate();
    private final MarkdownStructureParser structure = new MarkdownStructureParser();
    private final BackrefInserter inserter = new BackrefInserter(structure);

    /* ================= 相对路径 ================= */

    @Nested
    @DisplayName("相对路径计算")
    class RelativePath {

        @Test
        @DisplayName("guide → note 得到 ../notes/x.md（与语料一致）")
        void guideToNote() {
            assertEquals("../notes/llvm-phi.md",
                    template.relative("docs/learning-guides/llvm-learning-guide.md",
                            "docs/notes/llvm-phi.md"));
        }

        @Test
        @DisplayName("note → guide 得到 ../learning-guides/x.md")
        void noteToGuide() {
            assertEquals("../learning-guides/llvm-learning-guide.md",
                    template.relative("docs/notes/llvm-phi.md",
                            "docs/learning-guides/llvm-learning-guide.md"));
        }

        @Test
        @DisplayName("同目录得到 ./x.md（语料里 notes 互引就是这个写法）")
        void sameDirectory() {
            assertEquals("./mlir-block-arg-ssa.md",
                    template.relative("docs/notes/llvm-phi.md",
                            "docs/notes/mlir-block-arg-ssa.md"));
        }

        @Test
        @DisplayName("源在仓库根时直接用原路径")
        void fromRoot() {
            assertEquals("docs/notes/x.md",
                    template.relative("README.md", "docs/notes/x.md"));
        }
    }

    /* ================= 速记引用行 ================= */

    @Nested
    @DisplayName("速记引用行格式")
    class BackrefLine {

        @Test
        @DisplayName("★与语料逐字符一致：链接文字等于相对目标")
        void matchesCorpusExactly() {
            String line = template.backrefLine(
                    "docs/learning-guides/llvm-learning-guide.md",
                    "docs/notes/llvm-phi.md",
                    "汇合选值、两条规则、与 MLIR block argument 的对应");
            assertEquals("> **速记**：[../notes/llvm-phi.md](../notes/llvm-phi.md)"
                            + " —— 汇合选值、两条规则、与 MLIR block argument 的对应。",
                    line);
        }

        @Test
        @DisplayName("摘要已有终止标点时不重复补句号")
        void doesNotDoublePunctuate() {
            String line = template.backrefLine("docs/learning-guides/g.md",
                    "docs/notes/x.md", "为什么 ONNX 用 Protobuf？");
            assertTrue(line.endsWith("？"), "实际：" + line);
            assertFalse(line.endsWith("？。"));
        }

        @Test
        @DisplayName("空摘要时不留下悬空的破折号")
        void handlesEmptySummary() {
            String line = template.backrefLine("docs/learning-guides/g.md",
                    "docs/notes/x.md", "  ");
            assertFalse(line.contains("——"));
        }
    }

    /* ================= 笔记渲染 ================= */

    @Nested
    @DisplayName("笔记渲染")
    class Render {

        @Test
        @DisplayName("结构与 docs/notes/llvm-phi.md 一致：H1 + 来源行 + 正文")
        void structureMatchesCorpus() {
            String out = template.render(new NoteTemplate.Spec(
                    "LLVM IR 中的 Phi", "docs/notes/llvm-phi.md",
                    "docs/learning-guides/llvm-learning-guide.md", "2.3",
                    "## 是什么\n\nphi 在汇合点选值。\n"));

            String[] lines = out.split("\n");
            assertEquals("# LLVM IR 中的 Phi", lines[0]);
            assertEquals("", lines[1]);
            assertEquals("> 来源：[`llvm-learning-guide.md`]"
                    + "(../learning-guides/llvm-learning-guide.md) §2.3", lines[2]);
            assertTrue(out.contains("## 是什么"));
        }

        @Test
        @DisplayName("章节号已带 § 时不重复添加")
        void sectionLabelIdempotent() {
            String out = template.render(new NoteTemplate.Spec(
                    "T", "docs/notes/t.md", "docs/learning-guides/g.md", "§4.6", "正文"));
            assertTrue(out.contains("§4.6"));
            assertFalse(out.contains("§§"));
        }

        @Test
        @DisplayName("模型把 H1 重复写进正文时去重")
        void stripsDuplicateH1() {
            String out = template.render(new NoteTemplate.Spec(
                    "标题 A", "docs/notes/a.md", null, null,
                    "# 标题 A\n\n## 是什么\n\n内容\n"));
            assertEquals(1, out.split("(?m)^# ").length - 1,
                    "正文里重复的 H1 应被去掉，实际内容：\n" + out);
        }

        @Test
        @DisplayName("无挂靠点时不产生空的来源行")
        void noSourceLineWhenUnanchored() {
            String out = template.render(new NoteTemplate.Spec(
                    "T", "docs/notes/t.md", null, null, "## 是什么\n\n内容\n"));
            assertFalse(out.contains("来源"));
        }
    }

    /* ================= slug ================= */

    @Nested
    @DisplayName("文件名 slug")
    class Slug {

        @Test
        @DisplayName("英文标题转短横线小写")
        void english() {
            assertEquals("llvm-phi-node", template.slug("LLVM Phi Node"));
        }

        @Test
        @DisplayName("中文保留原字而非退化成 note-1")
        void keepsCjk() {
            String s = template.slug("MLIR 的 Region 与 Block");
            assertTrue(s.contains("的"), "实际：" + s);
            assertFalse(s.isBlank());
        }

        @Test
        @DisplayName("标点被剔除，不产生连续或首尾短横线")
        void stripsPunctuation() {
            String s = template.slug("A / B: C?  D");
            assertFalse(s.contains("--"), "实际：" + s);
            assertFalse(s.startsWith("-"));
            assertFalse(s.endsWith("-"));
        }
    }

    /* ================= 插入 ================= */

    @Nested
    @DisplayName("速记引用插入（外科式，不改写既有内容）")
    class Insert {

        private static final String GUIDE = """
                # 学习指南

                ## 2 中端

                ### 2.3 SSA 与 phi

                LLVM IR 是 SSA 形式，汇合点用 phi 选值。

                ### 2.4 别的

                另一段内容。
                """;

        @Test
        @DisplayName("★只插入一行，既有内容一个字符都不改")
        void onlyAppendsOneLine() {
            String line = template.backrefLine("docs/learning-guides/g.md",
                    "docs/notes/llvm-phi.md", "汇合选值");
            BackrefInserter.Result r = inserter.insert(
                    GUIDE, 0, "23-ssa-与-phi", line, "docs/notes/llvm-phi.md");

            assertEquals(BackrefInserter.Outcome.INSERTED, r.outcome(),
                    "message=" + r.message() + " anchors=" + r.availableAnchors());
            // 把插入的内容原样删掉后，必须与原文逐字符相同——
            // 这是「Agent 不可能弄坏语料」这条结构性保证的直接验证
            assertEquals(GUIDE, r.newContent().replace("\n\n" + line, ""),
                    "插入不得改动任何既有字符");
            assertEquals(GUIDE.length() + line.length() + 2, r.newContent().length(),
                    "长度增量应恰好等于一行加两个换行");
        }

        @Test
        @DisplayName("插在指定章节末尾，不越到下一节")
        void insertsAtSectionEnd() {
            String line = "> **速记**：[../notes/x.md](../notes/x.md) —— 摘要。";
            BackrefInserter.Result r = inserter.insert(
                    GUIDE, 0, "23-ssa-与-phi", line, "docs/notes/x.md");
            int inserted = r.newContent().indexOf(line);
            int nextHeading = r.newContent().indexOf("### 2.4");
            assertTrue(inserted > 0 && inserted < nextHeading,
                    "速记行必须落在 2.3 与 2.4 之间");
        }

        @Test
        @DisplayName("★幂等：同一章节已引用该笔记则不重复插入")
        void idempotent() {
            String line = template.backrefLine("docs/learning-guides/g.md",
                    "docs/notes/llvm-phi.md", "摘要");
            BackrefInserter.Result first = inserter.insert(
                    GUIDE, 0, "23-ssa-与-phi", line, "docs/notes/llvm-phi.md");
            BackrefInserter.Result second = inserter.insert(
                    first.newContent(), 0, "23-ssa-与-phi", line, "docs/notes/llvm-phi.md");
            assertEquals(BackrefInserter.Outcome.ALREADY_PRESENT, second.outcome());
            assertNull(second.newContent());
        }

        @Test
        @DisplayName("幂等判定用文件名：不同 guide 里相对路径不同，比全路径会漏判")
        void idempotentByFileName() {
            String existing = """
                    ## A

                    正文。

                    > **速记**：[./llvm-phi.md](./llvm-phi.md) —— 旧摘要。
                    """;
            BackrefInserter.Result r = inserter.insert(existing, 0, "a",
                    "> **速记**：[../notes/llvm-phi.md](../notes/llvm-phi.md) —— 新摘要。",
                    "docs/notes/llvm-phi.md");
            assertEquals(BackrefInserter.Outcome.ALREADY_PRESENT, r.outcome());
        }

        @Test
        @DisplayName("连续速记行紧邻排布（与语料一致，不夹空行）")
        void groupsConsecutiveBackrefs() {
            String existing = """
                    ## A

                    正文。

                    > **速记**：[../notes/a.md](../notes/a.md) —— 甲。
                    """;
            String line = "> **速记**：[../notes/b.md](../notes/b.md) —— 乙。";
            BackrefInserter.Result r = inserter.insert(existing, 0, "a", line,
                    "docs/notes/b.md");
            assertEquals(BackrefInserter.Outcome.INSERTED, r.outcome());
            assertTrue(r.newContent().contains("—— 甲。\n" + line),
                    "两条速记应紧邻，实际：\n" + r.newContent());
        }

        @Test
        @DisplayName("anchor 不存在时拒绝并给出可用列表，绝不猜")
        void refusesUnknownAnchor() {
            BackrefInserter.Result r = inserter.insert(GUIDE, 0, "不存在的锚点",
                    "> **速记**：x", "docs/notes/x.md");
            assertEquals(BackrefInserter.Outcome.ANCHOR_NOT_FOUND, r.outcome());
            assertFalse(r.availableAnchors().isEmpty(),
                    "必须回报可用 anchor，否则调用方只能反复试错");
            assertNull(r.newContent());
        }

        @Test
        @DisplayName("章节正文为空时插在标题之后，不越界到下一节")
        void handlesEmptySection() {
            String doc = "## A\n\n## B\n\n内容\n";
            BackrefInserter.Result r = inserter.insert(doc, 0, "a",
                    "> **速记**：[../notes/x.md](../notes/x.md) —— 摘要。", "docs/notes/x.md");
            assertEquals(BackrefInserter.Outcome.INSERTED, r.outcome());
            int inserted = r.newContent().indexOf("速记");
            assertTrue(inserted < r.newContent().indexOf("## B"));
        }
    }
}
