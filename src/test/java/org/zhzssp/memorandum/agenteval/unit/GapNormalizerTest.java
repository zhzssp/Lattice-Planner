package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.gap.QuestionNormalizer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：提问归一化。
 *
 * <h3>这组测试守的是一个容易被「优化」错方向的取舍</h3>
 * <p>{@code askCount} 是<strong>排序键</strong>而非统计量——它回答「我该先补哪个盲区」，
 * 不回答「这个问题我精确问过 N 次」。</p>
 *
 * <p>由此得出的关键判断：<strong>过度聚合的危害远大于聚合不足</strong>。
 * 把两个不同的缺口合成一条，会让 askCount 虚高且指向一个模糊的问题，
 * 转成学习计划时根本无法执行；而分成两条只是让看板多一行。
 * 所以下面既有「该合的要合」的断言，也有<strong>「不该合的绝不能合」</strong>的断言，
 * 后者更重要。</p>
 */
class GapNormalizerTest {

    private final QuestionNormalizer n = new QuestionNormalizer();

    /* ================= 该合的要合 ================= */

    @Nested
    @DisplayName("同一问题的不同措辞应聚成一条")
    class ShouldMerge {

        @Test
        @DisplayName("语序不同不影响聚合")
        void wordOrderIrrelevant() {
            assertEquals(n.normalize("MLIR 的 transform dialect 怎么用"),
                    n.normalize("怎么用 MLIR 的 transform dialect"));
        }

        @Test
        @DisplayName("疑问词与虚词不影响聚合")
        void interrogativesStripped() {
            assertEquals(n.normalize("什么是 transform dialect"),
                    n.normalize("transform dialect 是什么？"));
            assertEquals(n.normalize("请问一下 phi 节点的作用"),
                    n.normalize("phi 节点的作用"));
        }

        @Test
        @DisplayName("大小写与尾部标点不影响聚合")
        void caseAndPunctuation() {
            assertEquals(n.normalize("PDL 是什么"), n.normalize("pdl 是什么？？"));
        }

        @Test
        @DisplayName("代码块内容不参与归一化——同一段代码可能出现在完全不同的问题里")
        void codeIgnored() {
            String a = n.normalize("这段 IR 什么意思\n```llvm\n%r = phi i32\n```");
            String b = n.normalize("这段 IR 什么意思");
            assertEquals(b, a);
        }
    }

    /* ================= ★不该合的绝不能合 ================= */

    @Nested
    @DisplayName("语义不同的问题必须分开（过度聚合的危害更大）")
    class MustNotMerge {

        @Test
        @DisplayName("「X 是什么」与「X 与 Y 的区别」是两个缺口")
        void definitionVsComparison() {
            assertNotEquals(n.normalize("transform dialect 是什么"),
                    n.normalize("transform dialect 和 pdl 的区别"));
        }

        @Test
        @DisplayName("同一主题的不同侧面不合并（「区别」「原理」等实词刻意不列为停用词）")
        void differentAspects() {
            assertNotEquals(n.normalize("linalg 的原理"),
                    n.normalize("linalg 的用法"));
        }

        @Test
        @DisplayName("★CJK 串不切成单字：否则「字符集合相同」就会被当成同一问题")
        void cjkNotSplitIntoChars() {
            // 这两句字符集合高度重叠，若按单字切分并排序极易被误判为同一问题
            String a = n.normalize("编译器怎么做算子融合");
            String b = n.normalize("算子融合怎么让编译器做");
            // 允许它们相同（词序无关是设计目标），但绝不能与下面这句相同
            assertNotEquals(a, n.normalize("融合算子的编译产物怎么看"));
            assertNotNull(b);
        }
    }

    /* ================= 边界 ================= */

    @Nested
    @DisplayName("边界与降级")
    class Edges {

        @Test
        @DisplayName("空输入与纯虚词返回 null——无法归一化就不该登记缺口")
        void unNormalizableReturnsNull() {
            assertNull(n.normalize(null));
            assertNull(n.normalize("   "));
            assertNull(n.normalize("是的吗？"),
                    "全是停用词时无法去重，登记它只会在台账里堆出永远聚不起来的噪声");
        }

        @Test
        @DisplayName("单字母与纯数字被丢弃")
        void dropsNoiseTokens() {
            String out = n.normalize("a 和 3 与 mlir");
            assertEquals("mlir", out);
        }

        @Test
        @DisplayName("超长问题被截断到列宽上限")
        void truncatedToColumnWidth() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) sb.append("term").append(i).append(' ');
            String out = n.normalize(sb.toString());
            assertNotNull(out);
            assertTrue(out.length() <= QuestionNormalizer.MAX_LEN,
                    "必须不超过 kb_gap.norm_question 的列宽，否则插库会截断进而破坏唯一键语义");
        }

        @Test
        @DisplayName("token 去重：重复词不放大归一化串")
        void deduplicatesTokens() {
            assertEquals(n.normalize("mlir mlir mlir"), n.normalize("mlir"));
        }

        @Test
        @DisplayName("summarize 压成一行并截断，用于看板展示")
        void summarize() {
            assertEquals("a b", n.summarize("  a\n  b  ", 50));
            assertEquals("abc…", n.summarize("abcdef", 3));
        }
    }
}
