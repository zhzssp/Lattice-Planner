package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.gap.ScopeListParser;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：「先跳过」清单解析与止损线召回匹配。
 *
 * <h3>为什么这组测试的重点是「不要误报」</h3>
 * <p>止损线召回的误报代价是<strong>不对称</strong>的：</p>
 * <ul>
 *   <li>漏一个术语 → 少一次提醒，用户毫无感觉；</li>
 *   <li>误报一次 → 用户立刻发现软件在瞎猜，几天内就会关掉这个功能。</li>
 * </ul>
 * <p>所以下面大半断言是「这些不该被识别为术语」与「这些不该被匹配到」。</p>
 */
class ScopeRecallTest {

    private final ScopeListParser parser = new ScopeListParser();
    private final MarkdownStructureParser structure = new MarkdownStructureParser();

    /** 取自目标仓库 llvm-learning-guide.md §9.2 的真实片段。 */
    private static final String REAL_GUIDE = """
            # LLVM 学习指南

            ## 9 收束

            ### 9.1 必须掌握

            1. **SSA 与 phi** 的关系。

            ### 9.2 可以先跳过

            - LangRef 里 intrinsic 的逐条细节（当字典查）。
            - 调试信息（DWARF、`DICompositeType` 那一整套 metadata）。
            - 异常处理的底层实现（landingpad / personality / compact unwind）。
            - 内联汇编约束字符串。
            - `llvm-mca` 的调度模型细节（除非要做微架构级调优）。
            - Legacy Pass Manager 的具体 API（除非要改 codegen pipeline）。

            如果确实要看，命令是：

            ```bash
            - 这行在代码块里，不该被当成列表项
            ```

            ### 9.3 动手清单

            **第一步：看穿中端**
            """;

    /** 取自 mlir-learning-guide.md §11.2 的真实片段（含外围 dialect 的 code span 列表）。 */
    private static final String MLIR_GUIDE = """
            ## 11 收束

            ### 11.2 可以先跳过

            与根 README §3.2「先跳过」一致：

            - PDL / PDLL 声明式重写（先写 C++ pattern）。
            - Transform dialect 完整用法（知道是"IR 描述调度"即可）。
            - 外围 dialect 细节：`spirv` / `emitc` / `async` / `pdl` / `acc` / `omp`。
            - MLIR C API、ExecutionEngine 内部实现。
            """;

    /** paper-notes 目录的写法：行内粗体小标题而非 ### 标题。 */
    private static final String PAPER_NOTE = """
            ## 7 最小必要集

            **必须掌握的点**：

            - Tiling 与 Fusion 的关系。

            **可以先跳过的内容**：

            - `autoTVM` 的 XGBoost 特征工程细节。
            - Ansor 的任务调度器实现。

            ## 8 下一步
            """;

    /* ================= 解析 ================= */

    @Nested
    @DisplayName("识别「先跳过」小节")
    class SectionDetection {

        @Test
        @DisplayName("### 标题形式：抽出条目且不越到相邻小节")
        void headingForm() {
            var sections = structure.parse(REAL_GUIDE, 0).sections();
            List<ScopeListParser.SkipItem> items = parser.parse(REAL_GUIDE, 0, sections);

            List<String> terms = items.stream()
                    .map(i -> i.term().toLowerCase(Locale.ROOT)).toList();
            assertTrue(terms.contains("dicompositetype"), "行内代码应被抽出，实际：" + terms);
            assertTrue(terms.contains("llvm-mca"), "含连字符的标识符应被抽出，实际：" + terms);
            assertTrue(terms.stream().anyMatch(t -> t.contains("内联汇编")),
                    "中文短语应被抽出，实际：" + terms);

            // 9.1「必须掌握」里的 SSA 绝不能被当成跳过项
            assertFalse(terms.contains("ssa 与 phi"),
                    "「必须掌握」小节的内容被当成跳过项，是最严重的一类误报");
        }

        @Test
        @DisplayName("★「先跳过」小节内部的代码块里的「- 行」不被当成列表项")
        void ignoresFencedContent() {
            var sections = structure.parse(REAL_GUIDE, 0).sections();
            List<ScopeListParser.SkipItem> items = parser.parse(REAL_GUIDE, 0, sections);
            // 代码块就在 9.2 小节内部，因此这条断言真正验证了围栏处理
            assertTrue(items.stream().noneMatch(i -> i.term().contains("这行在代码块里")),
                    "代码块内容被解析成术语；示例命令里的 - 行会污染整个术语表");
        }

        @Test
        @DisplayName("code span 列表：六个外围 dialect 全部抽出")
        void codeSpanList() {
            var sections = structure.parse(MLIR_GUIDE, 0).sections();
            List<String> terms = parser.parse(MLIR_GUIDE, 0, sections).stream()
                    .map(i -> i.term().toLowerCase(Locale.ROOT)).toList();
            for (String t : List.of("spirv", "emitc", "async", "pdl", "acc", "omp")) {
                assertTrue(terms.contains(t), "缺少术语 " + t + "，实际：" + terms);
            }
            assertTrue(terms.stream().anyMatch(t -> t.startsWith("transform dialect")),
                    "领头短语「Transform dialect …」应被抽出，实际：" + terms);
        }

        @Test
        @DisplayName("★行内粗体小标题形式（paper-notes 的写法）同样支持")
        void boldLeadForm() {
            var sections = structure.parse(PAPER_NOTE, 0).sections();
            List<String> terms = parser.parse(PAPER_NOTE, 0, sections).stream()
                    .map(i -> i.term().toLowerCase(Locale.ROOT)).toList();
            assertTrue(terms.contains("autotvm"), "实际：" + terms);
            // 「必须掌握的点」下面的条目绝不能混进来
            assertFalse(terms.stream().anyMatch(t -> t.contains("tiling")),
                    "「必须掌握」的条目被当成跳过项");
        }

        @Test
        @DisplayName("保留原文作为 reason，用户能核对软件凭什么这么说")
        void keepsReason() {
            var sections = structure.parse(MLIR_GUIDE, 0).sections();
            var item = parser.parse(MLIR_GUIDE, 0, sections).stream()
                    .filter(i -> i.term().equalsIgnoreCase("spirv"))
                    .findFirst().orElseThrow();
            assertTrue(item.reason().contains("外围 dialect"),
                    "reason 必须是原始列表项，否则用户无法判断这条识别是否正确");
        }

        @Test
        @DisplayName("没有「先跳过」小节时返回空——不猜")
        void noSkipSection() {
            String doc = "# 标题\n\n## 正文\n\n- 一条普通列表。\n";
            assertTrue(parser.parse(doc, 0, structure.parse(doc, 0).sections()).isEmpty());
        }
    }

    /* ================= 术语过滤（防误报） ================= */

    @Nested
    @DisplayName("术语过滤：这些都不该成为术语")
    class TermFilter {

        @Test
        @DisplayName("两字母 latin 术语被拒（io / os 在提问里必然误报）")
        void rejectsShortLatin() {
            assertNull(parser.clean("io"));
            assertNull(parser.clean("os"));
            assertNotNull(parser.clean("pdl"), "三字母是合法术语，靠词边界避免误报");
        }

        @Test
        @DisplayName("长中文描述被拒——那是句子不是术语")
        void rejectsLongCjk() {
            assertNull(parser.clean("这一整套机制在实际工程中通常不需要关心"));
            assertNotNull(parser.clean("内联汇编约束字符串"));
        }

        @Test
        @DisplayName("以虚词收尾被拒——说明截断位置不对")
        void rejectsDanglingParticle() {
            assertNull(parser.clean("异常处理的"));
            assertNull(parser.clean("调试信息与"));
        }

        @Test
        @DisplayName("词数过多被拒")
        void rejectsTooManyWords() {
            assertNull(parser.clean("this is a fairly long english phrase"));
        }

        @Test
        @DisplayName("纯标点数字被拒；尾部标点被剥离")
        void rejectsPunctuationOnly() {
            assertNull(parser.clean("（）。"));
            assertNull(parser.clean("123"));
            assertEquals("spirv", parser.clean("spirv。"));
        }
    }

    /* ================= ★匹配：词边界 ================= */

    @Nested
    @DisplayName("匹配必须做词边界，否则三字母术语会疯狂误报")
    class WordBoundary {

        private boolean hit(String term, String question) {
            var t = org.zhzssp.memorandum.feature.codex.gap.ScopeRecallService
                    .compile(1L, term);
            return t.matches(question.toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("★omp 不该命中 compiler / omp 该命中 omp dialect")
        void ompNotInCompiler() {
            assertFalse(hit("omp", "compiler 是怎么工作的"),
                    "若用 contains 匹配，omp 会命中 compiler——一句话能误触发好几条");
            assertFalse(hit("omp", "这个 component 怎么用"));
            assertTrue(hit("omp", "omp dialect 怎么用"));
            assertTrue(hit("omp", "MLIR 里的 omp 是什么"));
        }

        @Test
        @DisplayName("★acc 不该命中 accuracy / accelerator")
        void accNotInAccuracy() {
            assertFalse(hit("acc", "量化后 accuracy 掉了怎么办"));
            assertFalse(hit("acc", "accelerator 后端怎么接"));
            assertTrue(hit("acc", "acc dialect 有什么用"));
        }

        @Test
        @DisplayName("pdl 不该命中 pdll（更长的相邻术语）")
        void pdlNotInPdll() {
            assertFalse(hit("pdl", "pdll 声明式重写怎么写"));
            assertTrue(hit("pdl", "pdl 和手写 pattern 的区别"));
        }

        @Test
        @DisplayName("含连字符与加号的术语能正确匹配（\\\\b 在这些字符处行为反直觉）")
        void handlesPunctuationInTerm() {
            assertTrue(hit("llvm-mca", "llvm-mca 的调度模型怎么看"));
            assertTrue(hit("c++", "这段 c++ 代码什么意思"));
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertTrue(hit("SPIRV", "spirv dialect 怎么用"));
            assertTrue(hit("spirv", "SPIRV 是什么".toLowerCase(Locale.ROOT)));
        }

        @Test
        @DisplayName("中文术语走 contains（中文没有词边界概念）")
        void cjkUsesContains() {
            assertTrue(hit("内联汇编约束字符串", "内联汇编约束字符串怎么写"));
            assertFalse(hit("内联汇编约束字符串", "内联汇编怎么写"));
        }

        @Test
        @DisplayName("混合中英术语按 CJK 规则处理")
        void mixedTerm() {
            assertTrue(hit("Transform dialect 完整用法",
                    "transform dialect 完整用法有文档吗".toLowerCase(Locale.ROOT)));
        }
    }
}
