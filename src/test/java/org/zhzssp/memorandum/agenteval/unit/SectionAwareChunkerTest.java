package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;
import org.zhzssp.memorandum.feature.codex.index.SectionAwareChunker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：P0a 切片修复（{@link SectionAwareChunker}）。
 *
 * <h3>这组测试守的是什么 bug</h3>
 * <p>原笔记切片上限是 64 chunk × 600 字 = 38,400 字符，而真实知识仓库里
 * 六篇主干 guide 全部超过 80,000 字符，最大的 {@code iree-learning-guide.md}
 * 达 107,153 字符——只有前 36% 被索引，且触顶时静默返回。</p>
 *
 * <p>后果比「没有检索」更糟：Agent 会声称「已检索知识库」，用户却据此
 * 误判「我没写过这个」。所以本测试的重点不只是「能切完」，
 * 更是<strong>「切不完时必须说出来」</strong>。</p>
 */
class SectionAwareChunkerTest {

    private final MarkdownStructureParser structure = new MarkdownStructureParser();
    private final SectionAwareChunker chunker = new SectionAwareChunker();

    /** 造一篇 n 节、每节 bodyChars 字符的文档。 */
    private static String buildDoc(int sections, int bodyChars) {
        StringBuilder sb = new StringBuilder("# 测试文档\n\n前言段落。\n\n");
        for (int i = 1; i <= sections; i++) {
            sb.append("## 第 ").append(i).append(" 章 标题\n\n");
            sb.append("正文".repeat(Math.max(1, bodyChars / 2))).append("\n\n");
        }
        return sb.toString();
    }

    /* ================= 核心回归：大文档不再被静默腰斩 ================= */

    @Nested
    @DisplayName("大文档覆盖（P0a 核心）")
    class LargeDocument {

        @Test
        @DisplayName("10 万字符文档在 400 上限下完整覆盖且不标截断")
        void largeDocFullyCovered() {
            // 模拟 iree-learning-guide.md 的量级
            String doc = buildDoc(20, 5000);
            assertTrue(doc.length() > 100_000, "构造的测试文档应达 10 万字符量级");

            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("测试文档", doc, 0, struct.sections(), 400);

            assertFalse(r.truncated(), "400 上限应足够覆盖 10 万字符文档");
            assertEquals(0.0, r.lossRatio(), 1e-9);
            // 旧实现在 64 上限下只能切 64 块；新实现应远超此数
            assertTrue(r.chunks().size() > 64,
                    "应切出远多于旧上限 64 的块数，实际：" + r.chunks().size());
        }

        @Test
        @DisplayName("旧的 64 上限会截断——证明 bug 真实存在")
        void oldLimitWouldTruncate() {
            String doc = buildDoc(20, 5000);
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("测试文档", doc, 0, struct.sections(), 64);

            assertTrue(r.truncated(), "64 上限下 10 万字符文档必然被截断");
            assertTrue(r.lossRatio() > 0.5,
                    "丢失比例应超过一半，实际：" + r.lossRatio());
        }

        @Test
        @DisplayName("触顶时 truncated 与 lossRatio 都必须可读——截断不可静默")
        void truncationIsObservable() {
            String doc = buildDoc(30, 4000);
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("测试文档", doc, 0, struct.sections(), 10);

            assertTrue(r.truncated());
            assertEquals(10, r.chunks().size(), "应恰好停在上限");
            assertTrue(r.charsTotal() > r.charsUsed(), "必须能算出丢了多少");
            assertTrue(r.lossRatio() > 0, "lossRatio 必须 > 0，否则仪表盘无法红标");
        }
    }

    /* ================= 章节定位 ================= */

    @Nested
    @DisplayName("章节感知与引用定位")
    class SectionAwareness {

        @Test
        @DisplayName("每个 chunk 携带 anchor 与 headingPath")
        void chunksCarryLocation() {
            String doc = """
                    # 指南

                    前言。

                    ## 第 4 章 HAL

                    ### 4.6 timeline semaphore

                    64 位单调递增，signal-before-wait 与 wait-before-signal 都支持。
                    """;
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("指南", doc, 0, struct.sections(), 400);

            var located = r.chunks().stream()
                    .filter(c -> c.content().contains("单调递增"))
                    .findFirst();
            assertTrue(located.isPresent(), "应能找到包含正文的 chunk");
            assertNotNull(located.get().anchor(), "命中 chunk 必须带 anchor，否则无法定位引用");
            assertNotNull(located.get().headingPath());
            assertTrue(located.get().headingPath().contains("timeline semaphore"));
        }

        @Test
        @DisplayName("chunk 正文注入文档标题与章节路径，供 LLM 给出准确出处")
        void headerInjected() {
            String doc = "# 我的指南\n\n## 某章\n\n具体内容在这里。\n";
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("我的指南", doc, 0, struct.sections(), 400);

            assertFalse(r.chunks().isEmpty());
            String first = r.chunks().get(0).content();
            assertTrue(first.contains("[文档] 我的指南"));
        }

        @Test
        @DisplayName("首个标题之前的前言不能丢——很多文档的结论就写在那里")
        void preambleKept() {
            String doc = """
                    # 标题

                    这段前言里有最重要的结论：先读这一段。

                    ## 第一章

                    章节内容。
                    """;
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("标题", doc, 0, struct.sections(), 400);

            boolean found = r.chunks().stream()
                    .anyMatch(c -> c.content().contains("最重要的结论"));
            assertTrue(found, "首个标题之前的前言必须被索引");
        }

        @Test
        @DisplayName("无标题结构时退化为平铺切片，仍受上限约束")
        void noSectionsFallsBack() {
            String doc = "没有任何标题的纯文本。\n\n" + "内容".repeat(2000);
            SectionAwareChunker.Result r = chunker.chunk("无标题", doc, 0, List.of(), 400);

            assertFalse(r.chunks().isEmpty());
            assertNull(r.chunks().get(0).anchor(), "无章节时 anchor 应为 null");
        }

        @Test
        @DisplayName("嵌套标题不产生重复切片——章节区间不得互相覆盖")
        void nestedHeadingsDoNotDuplicate() {
            // 若 charEnd 取「下一个同级或更高级标题」，H1 区间会覆盖其下所有 H2/H3，
            // 同一段文字会被父节与子节各切一次，索引体积成倍膨胀且检索出现近重复命中
            String doc = """
                    # 顶层标题

                    ## 第一章

                    ### 1.1 小节

                    这是一段独一无二的标识文本 ZZQQ 用于检测重复。

                    ### 1.2 小节

                    另一段内容。
                    """;
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("顶层标题", doc, 0, struct.sections(), 400);

            long occurrences = r.chunks().stream()
                    .filter(c -> c.content().contains("ZZQQ"))
                    .count();
            assertEquals(1, occurrences,
                    "同一段正文只能出现在一个 chunk 里，实际出现 " + occurrences + " 次");
        }

        @Test
        @DisplayName("章节区间两两不重叠")
        void sectionRangesDoNotOverlap() {
            String doc = buildDoc(6, 800);
            var sections = structure.parse(doc, 0).sections();
            for (int i = 0; i + 1 < sections.size(); i++) {
                assertTrue(sections.get(i).charEnd() <= sections.get(i + 1).lineStart(),
                        "第 " + i + " 节的结束不应越过下一节的标题行");
            }
        }
    }

    /* ================= 边界 ================= */

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空内容返回空结果，不抛异常")
        void emptyContent() {
            assertTrue(chunker.chunk("t", "", 0, List.of(), 400).chunks().isEmpty());
            assertTrue(chunker.chunk("t", null, 0, List.of(), 400).chunks().isEmpty());
            assertTrue(chunker.chunk("t", "   \n\n  ", 0, List.of(), 400).chunks().isEmpty());
        }

        @Test
        @DisplayName("maxChunks<=0 被兜到 1，不会产生空索引")
        void nonPositiveLimit() {
            String doc = "# T\n\n" + "内容".repeat(1000);
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("T", doc, 0, struct.sections(), 0);
            assertEquals(1, r.chunks().size());
            assertTrue(r.truncated());
        }

        @Test
        @DisplayName("bodyStart 跳过 front-matter，元数据不进正文索引")
        void bodyStartSkipsFrontMatter() {
            String doc = "---\nkind: guide\n---\n# 标题\n\n正文内容。\n";
            int bodyStart = doc.indexOf("# 标题");
            var struct = structure.parse(doc, bodyStart);
            SectionAwareChunker.Result r = chunker.chunk("标题", doc, bodyStart,
                    struct.sections(), 400);

            boolean leaked = r.chunks().stream()
                    .anyMatch(c -> c.content().contains("kind: guide"));
            assertFalse(leaked, "front-matter 不应出现在切片正文里");
        }

        @Test
        @DisplayName("chunk 序号连续，便于与 kb_chunk.chunk_idx 对齐")
        void indicesAreSequential() {
            String doc = buildDoc(5, 2000);
            var struct = structure.parse(doc, 0);
            SectionAwareChunker.Result r = chunker.chunk("T", doc, 0, struct.sections(), 400);

            for (int i = 0; i < r.chunks().size(); i++) {
                assertEquals(i, r.chunks().get(i).idx());
            }
        }
    }
}
