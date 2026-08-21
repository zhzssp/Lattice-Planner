package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbLink;
import org.zhzssp.memorandum.feature.codex.index.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：Codex 索引解析层（front-matter / 标题树 / 链接 / 布局 glob）。
 *
 * <h3>「宽容度」优先于「严格性」</h3>
 * <p>目标仓库现有 61 篇 Markdown <strong>一篇都没有 front-matter</strong>。
 * 如果解析器把「缺元数据」当错误，索引器第一步就会把整个仓库判为不合格。
 * 因此本测试里「无 front-matter 也能正常工作」的用例，比「能解析复杂 YAML」更重要
 * ——这与方案 E「宁松勿严」是同一立场：假阳性比不校验更糟。</p>
 */
class CodexIndexParsersTest {

    private final FrontMatterParser fm = new FrontMatterParser(new ObjectMapper());
    private final MarkdownStructureParser structure = new MarkdownStructureParser();
    private final LinkExtractor links = new LinkExtractor();

    /* ================= front-matter：宽容优先 ================= */

    @Nested
    @DisplayName("front-matter 解析（全部字段可选）")
    class FrontMatter {

        @Test
        @DisplayName("无 front-matter 时返回空结果且无任何 issue")
        void absentIsNotAnError() {
            var r = fm.parse("# 标题\n\n正文。\n");
            assertFalse(r.present());
            assertEquals(0, r.bodyStart());
            assertTrue(r.issues().isEmpty(), "「没有元数据」是合法状态，不是问题");
            assertFalse(r.hasError());
        }

        @Test
        @DisplayName("解析标量、行内数组与嵌套对象")
        void parsesBasicShapes() {
            var r = fm.parse("""
                    ---
                    kind: guide
                    title: MLIR 学习文档
                    priority: P0
                    entities: [dialect-conversion, op-interface]
                    scope:
                      must: [a, b]
                      skip: [pdl-pdll]
                    ---
                    # 正文
                    """);
            assertTrue(r.present());
            assertEquals("guide", r.str("kind"));
            assertEquals("MLIR 学习文档", r.str("title"));
            assertEquals(List.of("dialect-conversion", "op-interface"), r.list("entities"));
            assertFalse(r.map("scope").isEmpty());
            assertTrue(r.bodyStart() > 0);
        }

        @Test
        @DisplayName("解析块数组")
        void parsesBlockList() {
            var r = fm.parse("""
                    ---
                    labs:
                      - mlir-toy-dialect
                      - iree-lab
                    ---
                    body
                    """);
            assertEquals(List.of("mlir-toy-dialect", "iree-lab"), r.list("labs"));
        }

        @Test
        @DisplayName("引号内的 # 不被当注释——否则 anchor 会被吃掉")
        void hashInsideQuotesKept() {
            var r = fm.parse("""
                    ---
                    ref: "docs/x.md#section-4"
                    ---
                    body
                    """);
            assertEquals("docs/x.md#section-4", r.str("ref"));
        }

        @Test
        @DisplayName("有起始无结束分隔符 → 报 ERROR，避免整篇正文被当元数据")
        void unterminatedIsError() {
            var r = fm.parse("---\nkind: guide\n\n# 正文\n没有结束分隔符\n");
            assertTrue(r.hasError());
            assertNotNull(r.firstError());
        }

        @Test
        @DisplayName("非法枚举值报 ERROR 但不影响其他字段")
        void invalidEnumReported() {
            var r = fm.parse("""
                    ---
                    kind: guide
                    maturity: bogus
                    ---
                    body
                    """);
            assertTrue(r.hasError());
            assertEquals("guide", r.str("kind"), "其他字段仍应可用");
        }

        @Test
        @DisplayName("容忍 BOM 与前导空行")
        void tolerantToBomAndBlankLines() {
            var r = fm.parse("\uFEFF---\nkind: note\n---\nbody\n");
            assertTrue(r.present());
            assertEquals("note", r.str("kind"));
        }

        @Test
        @DisplayName("bodyStart 相对原始 content 有效（含 BOM 时不得偏移）")
        void bodyStartIsRelativeToOriginalContent() {
            // 若实现里剥离 BOM 后用剥离后的串算偏移，这里会差 1 字符，
            // 导致后续所有章节区间整体错位——只在带 BOM 的文件上出现，极难定位
            String withBom = "\uFEFF---\nkind: note\n---\n# 标题\n\n正文。\n";
            var r = fm.parse(withBom);
            assertEquals("# 标题", withBom.substring(r.bodyStart()).lines().findFirst().orElse(""),
                    "bodyStart 应精确指向原文中正文的第一个字符");

            String noBom = "---\nkind: note\n---\n# 标题\n\n正文。\n";
            var r2 = fm.parse(noBom);
            assertEquals("# 标题", noBom.substring(r2.bodyStart()).lines().findFirst().orElse(""));
        }
    }

    /* ================= 标题树与 anchor ================= */

    @Nested
    @DisplayName("Markdown 标题解析")
    class Structure {

        @Test
        @DisplayName("代码块内的 # 不被当标题——最常见的误判来源")
        void fencedHashIsNotHeading() {
            String doc = """
                    # 真标题

                    ```bash
                    # 这是 shell 注释，不是标题
                    ### 这也不是
                    ```

                    ## 第二个真标题
                    """;
            var r = structure.parse(doc, 0);
            assertEquals(2, r.sections().size(),
                    "只应识别出 2 个标题，实际：" + r.sections().stream()
                            .map(MarkdownStructureParser.Section::heading).toList());
        }

        @Test
        @DisplayName("#tag 形式不是标题（# 后必须有空格）")
        void hashTagIsNotHeading() {
            var r = structure.parse("#notatitle\n\n# 真标题\n", 0);
            assertEquals(1, r.sections().size());
            assertEquals("真标题", r.sections().get(0).heading());
        }

        @Test
        @DisplayName("重复标题按 GitHub 规则追加 -1 / -2")
        void duplicateHeadingsGetSuffix() {
            var r = structure.parse("## 概述\n\na\n\n## 概述\n\nb\n\n## 概述\n\nc\n", 0);
            assertEquals(3, r.sections().size());
            assertEquals("概述", r.sections().get(0).anchor());
            assertEquals("概述-1", r.sections().get(1).anchor());
            assertEquals("概述-2", r.sections().get(2).anchor());
        }

        @Test
        @DisplayName("CJK 字符保留在 anchor 中（与 GitHub 一致）")
        void cjkPreservedInAnchor() {
            var r = structure.parse("## 4.6 timeline semaphore 语义\n\n正文\n", 0);
            String anchor = r.sections().get(0).anchor();
            assertTrue(anchor.contains("语义"), "CJK 应保留，实际：" + anchor);
            assertTrue(anchor.contains("timeline"));
            assertFalse(anchor.contains("."), "标点应被丢弃");
            assertFalse(anchor.contains(" "), "空格应转为连字符");
        }

        @Test
        @DisplayName("标题里的链接与行内代码被清理")
        void headingFormattingStripped() {
            var r = structure.parse("## 参见 [`foo`](http://x.com) 的说明\n\n正文\n", 0);
            String h = r.sections().get(0).heading();
            assertFalse(h.contains("]("), "链接语法应被清理，实际：" + h);
            assertFalse(h.contains("`"));
            assertTrue(h.contains("foo"));
        }

        @Test
        @DisplayName("headingPath 反映祖先层级")
        void headingPathReflectsHierarchy() {
            var r = structure.parse("""
                    # 顶层

                    ## 第 4 章

                    ### 4.6 小节

                    正文
                    """, 0);
            var last = r.sections().get(r.sections().size() - 1);
            assertTrue(last.headingPath().contains("第 4 章"));
            assertTrue(last.headingPath().contains("4.6 小节"));
            assertTrue(last.headingPath().contains(">"), "祖先链应用 > 连接");
        }

        @Test
        @DisplayName("title 取首个 H1")
        void titleFromFirstH1() {
            var r = structure.parse("## 先出现的 H2\n\n# 真正的 H1\n", 0);
            assertEquals("真正的 H1", r.title());
        }
    }

    /* ================= 链接抽取 ================= */

    @Nested
    @DisplayName("链接抽取")
    class Links {

        @Test
        @DisplayName("区分相对链接、anchor 与外链")
        void classifiesTargets() {
            var out = links.extract("""
                    见 [文档](docs/a.md) 与 [某节](docs/b.md#anchor-x)
                    以及 [外链](https://example.com) 和 [本节](#local)
                    """, 0);

            assertTrue(out.stream().anyMatch(e -> "docs/a.md".equals(e.path())
                    && e.anchor() == null));
            assertTrue(out.stream().anyMatch(e -> "docs/b.md".equals(e.path())
                    && "anchor-x".equals(e.anchor())));
            assertTrue(out.stream().anyMatch(e -> e.kind() == KbLink.LinkKind.EXTERNAL));
            assertTrue(out.stream().anyMatch(e -> e.path() == null && "local".equals(e.anchor())));
        }

        @Test
        @DisplayName("「速记」行的链接归为 BACKREF——方法论硬约束的落点")
        void backrefRecognized() {
            var out = links.extract(
                    "> **速记**：[notes/llvm-phi.md](../notes/llvm-phi.md) —— phi 是选值指令。\n", 0);
            assertTrue(out.stream().anyMatch(e -> e.kind() == KbLink.LinkKind.BACKREF),
                    "速记引用必须能被识别，否则无法做双向性校验");
        }

        @Test
        @DisplayName("指向 notes/ 的链接即便不在速记行也归 BACKREF")
        void notesPathIsBackref() {
            var out = links.extract("参考 [笔记](../notes/x.md)\n", 0);
            assertEquals(KbLink.LinkKind.BACKREF, out.get(0).kind());
        }

        @Test
        @DisplayName("PDF 归 CITATION，脚本归 LAB")
        void citationAndLab() {
            var out = links.extract("""
                    [论文](paper/MLIR.pdf) 与 [脚本](scripts/run.sh)
                    """, 0);
            assertTrue(out.stream().anyMatch(e -> e.kind() == KbLink.LinkKind.CITATION));
            assertTrue(out.stream().anyMatch(e -> e.kind() == KbLink.LinkKind.LAB));
        }

        @Test
        @DisplayName("代码块内的链接被忽略——那是示例文本，校验它只产生噪音")
        void fencedLinksIgnored() {
            var out = links.extract("""
                    真链接 [a](docs/a.md)

                    ```markdown
                    示例 [b](docs/nonexistent.md)
                    ```
                    """, 0);
            assertEquals(1, out.size(), "只应抽出代码块外的链接");
            assertEquals("docs/a.md", out.get(0).path());
        }

        @Test
        @DisplayName("[[双链]] 被识别为 WIKI")
        void wikiLink() {
            var out = links.extract("参见 [[LLVM IR 中的 Phi]] 一文。\n", 0);
            assertEquals(1, out.size());
            assertEquals(KbLink.LinkKind.WIKI, out.get(0).kind());
            assertEquals("LLVM IR 中的 Phi", out.get(0).path());
        }
    }

    /* ================= 路径规范化 ================= */

    @Nested
    @DisplayName("相对路径规范化")
    class PathNormalization {

        @Test
        @DisplayName("解析 ../ 与 ./")
        void resolvesDotSegments() {
            assertEquals("docs/notes/x.md",
                    RepoIndexer.normalizeTarget("docs/learning-guides/g.md", "../notes/x.md"));
            assertEquals("docs/learning-guides/y.md",
                    RepoIndexer.normalizeTarget("docs/learning-guides/g.md", "./y.md"));
            assertEquals("README.md",
                    RepoIndexer.normalizeTarget("docs/README.md", "../README.md"));
        }

        @Test
        @DisplayName("根目录文档的相对链接")
        void fromRootDocument() {
            assertEquals("docs/README.md",
                    RepoIndexer.normalizeTarget("README.md", "docs/README.md"));
        }

        @Test
        @DisplayName("绝对路径去掉前导斜杠")
        void absoluteStripsLeadingSlash() {
            assertEquals("docs/a.md",
                    RepoIndexer.normalizeTarget("x/y.md", "/docs/a.md"));
        }
    }

    /* ================= 布局 glob ================= */

    @Nested
    @DisplayName("布局 glob 匹配（零改动接入的关键）")
    class Layout {

        private final RepoLayout layout = RepoLayout.defaults(400, true);

        @Test
        @DisplayName("内置默认规则正确识别目标仓库的既有结构")
        void defaultsMatchRealRepo() {
            assertEquals(KbDocument.DocKind.GUIDE,
                    layout.resolve("docs/learning-guides/mlir-learning-guide.md").kind());
            assertEquals(KbDocument.DocKind.NOTE,
                    layout.resolve("docs/notes/llvm-phi.md").kind());
            assertEquals(KbDocument.DocKind.CHECKPOINT_SET,
                    layout.resolve("docs/checkpoints/02-mlir.md").kind());
            assertEquals(KbDocument.DocKind.ROADMAP,
                    layout.resolve("README.md").kind());
            assertEquals(KbDocument.DocKind.ROADMAP,
                    layout.resolve("docs/README.md").kind());
            assertEquals(KbDocument.DocKind.SOURCE,
                    layout.resolve("paper/LLVM.pdf").kind());
        }

        @Test
        @DisplayName("paper-notes 带 subkind")
        void paperNoteSubkind() {
            var rule = layout.resolve("docs/paper-notes/03-mlir.md");
            assertEquals(KbDocument.DocKind.GUIDE, rule.kind());
            assertEquals("paper-note", rule.subkind());
        }

        @Test
        @DisplayName("lab README 归 LAB")
        void labReadme() {
            assertEquals(KbDocument.DocKind.LAB, layout.resolve("iree-lab/README.md").kind());
            assertEquals(KbDocument.DocKind.LAB,
                    layout.resolve("mlir-toy-dialect/README.md").kind());
        }

        @Test
        @DisplayName("未匹配精确规则的 md 落到 UNKNOWN 而非被丢弃")
        void unknownFallback() {
            var rule = layout.resolve("some/random/file.md");
            assertNotNull(rule, "兜底规则必须存在，否则文档会被静默跳过");
            assertEquals(KbDocument.DocKind.UNKNOWN, rule.kind());
        }

        @Test
        @DisplayName("排除 lab 产物与虚拟环境目录")
        void excludes() {
            assertTrue(layout.excluded("iree-lab/out/PHASES.md"));
            assertTrue(layout.excluded("x/.venv/lib/a.md"));
            assertTrue(layout.excluded("node_modules/pkg/README.md"));
            assertFalse(layout.excluded("docs/notes/x.md"));
        }

        @Test
        @DisplayName("** 能匹配零级目录")
        void doubleStarMatchesZeroDirs() {
            var p = RepoLayout.globToPattern("docs/**/*.md");
            assertTrue(p.matcher("docs/a.md").matches(), "**/ 应能匹配零级目录");
            assertTrue(p.matcher("docs/x/y/a.md").matches());
        }
    }
}
