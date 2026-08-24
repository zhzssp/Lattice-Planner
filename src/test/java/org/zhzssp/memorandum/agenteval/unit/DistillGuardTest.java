package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.distill.DistillGuard;
import org.zhzssp.memorandum.feature.codex.distill.GuideTemplate;
import org.zhzssp.memorandum.feature.codex.gap.ScopeListParser;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;
import org.zhzssp.memorandum.feature.codex.index.MarkdownStructureParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：蒸馏产物的结构门禁。
 *
 * <h3>这组测试真正在守的东西</h3>
 * <p>蒸馏的失败模式不是「写错」而是「写空」——产出一篇结构完整、读起来专业、
 * 但里面没有一处能对着原文核对的内容的 guide。这种产物用肉眼快速扫一遍是发现不了的，
 * 它必须由机器挡住。</p>
 *
 * <h3>★最重要的一条：止损线判据是「解析器真能抽出术语」</h3>
 * <p>不是「有没有那个小节」。差别是决定性的：一篇有小节但抽不出术语的 guide，
 * 在 P3 的 skip 召回机制里是<strong>静默失效</strong>的——文件看起来合规，
 * 而它的止损线永远不会触发提醒，从文件本身完全看不出这一点。
 * 用真实解析器做判据，把「看起来合规」与「实际可用」之间那条缝焊死了。</p>
 */
class DistillGuardTest {

    private DistillGuard guard;
    private GuideTemplate template;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        guard = new DistillGuard(new ScopeListParser(),
                new MarkdownStructureParser(), new FrontMatterParser(om));
        template = new GuideTemplate();
    }

    /* ================= 合格产物 ================= */

    private GuideTemplate.Spec good() {
        return new GuideTemplate.Spec(
                "FlashAttention",
                "flashattention.pdf", "paper/flashattention.pdf", "ai-infra",
                "把 attention 的 softmax 做成分块在线计算，用重算换掉 O(N²) 的显存读写。",
                """
                一次 forward 的站点顺序：`load Q/K tile` → `online softmax` → `accumulate O`。

                ```text
                for j in range(0, N, Bc):
                    K_j, V_j = load(K[j:j+Bc]), load(V[j:j+Bc])
                    S_ij = Q_i @ K_j.T          # (Br, Bc)
                    m_new = max(m_i, rowmax(S_ij))
                    l_new = exp(m_i - m_new) * l_i + rowsum(exp(S_ij - m_new))
                ```

                关键状态只有两个标量向量：`m_i`（running max）与 `l_i`（running sum）。
                """,
                """
                | 特性 | 为什么必学 | 原文位置 |
                |------|-----------|----------|
                | 分块在线 softmax | 不物化 (N,N) 注意力矩阵的全部原因 | §3.1 Alg 1 |
                | 重算反向 | 反向不存 S 而是重算，省显存 | §3.2 |
                | IO 复杂度分析 | 解释为什么快的是访存不是算力 | §3.3 Table 1 |
                """,
                """
                - 能写出 online softmax 的两个 running 状态如何更新
                - 能说清它与普通 softmax 在数值上为什么等价
                - 能预测把 block size 调大一倍后显存与耗时各会怎么变
                - 能指出反向为什么可以不存 S
                """,
                List.of("`flash-decoding` 的具体实现（除非要做推理优化）",
                        "**dropout** 在分块下的随机数对齐细节（遇到再看）",
                        "`Triton` 版本与 CUDA 版本的差异（先只看一种）"),
                List.of("Table 1 的具体数字未核对"),
                48000, 12);
    }

    @Test
    @DisplayName("结构完整的草稿通过，并抽出止损线术语")
    void passesGoodDraft() {
        DistillGuard.Verdict v = guard.check(template.render(good()));
        assertTrue(v.pass(), "不该被拒：" + v.firstError());
        assertFalse(v.skipTerms().isEmpty(), "止损线必须能抽出术语");
        assertTrue(v.skipTerms().stream().anyMatch(t -> t.toLowerCase().contains("flash-decoding")));
    }

    /* ================= 止损线 ================= */

    @Nested
    @DisplayName("止损线（★不给出即判不合格）")
    class SkipList {

        @Test
        @DisplayName("完全没有止损线 → ERROR")
        void rejectsMissing() {
            GuideTemplate.Spec s = withSkips(List.of());
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertFalse(v.pass());
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("MISSING_SKIP")
                            || e.code().equals("SKIP_UNPARSEABLE")),
                    "实际错误：" + v.errors());
        }

        @Test
        @DisplayName("★有小节但抽不出术语 → 同样 ERROR（这比缺失更隐蔽）")
        void rejectsUnparseable() {
            // 全是以虚词收尾的长句：解析器一条术语都抽不出来，
            // 于是这篇 guide 的 skip 召回永远不会触发，而文件看起来是合规的
            GuideTemplate.Spec s = withSkips(List.of(
                    "一些暂时不需要关心的边角情况和特殊处理以及其他杂项内容的",
                    "另外那些看起来比较复杂而且现在还用不上的部分与"));
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertFalse(v.pass(), "抽不出术语的止损线在系统里是静默失效的");
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("SKIP_UNPARSEABLE")),
                    "实际错误：" + v.errors());
        }

        /**
         * ★这条守的是一个比上一条更难发现的缝。
         *
         * <p>{@code ScopeListParser} 刻意宽容——它要能从用户几年写下的各种写法里抽出东西，
         * 所以「实现细节」这种领头短语它也会收。<strong>对解析既有语料这是对的。</strong></p>
         *
         * <p>但模型会非常乐意写「实现细节」「其余部分」。那种条目<em>能</em>被抽成术语，
         * 于是 {@code SKIP_UNPARSEABLE} 不会报，而它永远匹配不上任何一次真实提问——
         * skip 召回照样是死的，只不过表面上有术语了。
         * 同一个解析器，对既有语料与对新产出必须用两套严格度。</p>
         */
        @Test
        @DisplayName("★能抽出术语但全是空话 → ERROR（宽容的解析器不能当严格的门禁用）")
        void rejectsVagueTerms() {
            GuideTemplate.Spec s = withSkips(List.of("实现细节", "其余部分", "一些优化"));
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertFalse(v.pass(),
                    "「实现细节」匹配不上任何真实提问，写进去等于没写");
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("SKIP_TOO_VAGUE")),
                    "实际错误：" + v.errors());
        }

        @Test
        @DisplayName("术语只要有一条能抽出就放行——判据是可用性而非数量")
        void acceptsSingleUsableTerm() {
            GuideTemplate.Spec s = withSkips(List.of("`flash-decoding` 的实现（遇到再学）"));
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertTrue(v.pass(), "不该被拒：" + v.firstError());
        }
    }

    /* ================= 写空 ================= */

    @Nested
    @DisplayName("「写空」检测")
    class Emptiness {

        @Test
        @DisplayName("★框架一节全是无法验证的句子 → ERROR")
        void rejectsAbstractFramework() {
            GuideTemplate.Spec g = good();
            GuideTemplate.Spec s = new GuideTemplate.Spec(g.title(), g.sourceRef(),
                    g.sourcePath(), g.domain(), g.oneLiner(),
                    // 这段话读起来完全通顺，却既无法验证也无法反驳——正是要挡住的东西
                    "该模块负责处理相关的核心逻辑，并在必要时与其他组件协同工作，"
                            + "从而实现整体上的性能优化目标。其内部流程经过精心设计。",
                    g.features(), g.mastery(), g.skipItems(), g.openIssues(),
                    g.sourceChars(), g.sourcePages());
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertFalse(v.pass());
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("FRAMEWORK_TOO_ABSTRACT")),
                    "实际错误：" + v.errors());
        }

        @Test
        @DisplayName("特性表不是表格 → WARN（不阻塞：偶尔用列表也说得通）")
        void warnsNonTableFeatures() {
            GuideTemplate.Spec g = good();
            GuideTemplate.Spec s = new GuideTemplate.Spec(g.title(), g.sourceRef(),
                    g.sourcePath(), g.domain(), g.oneLiner(), g.framework(),
                    "- 分块在线 softmax：不物化注意力矩阵\n- 重算反向：省显存\n",
                    g.mastery(), g.skipItems(), g.openIssues(),
                    g.sourceChars(), g.sourcePages());
            DistillGuard.Verdict v = guard.check(template.render(s));
            assertTrue(v.pass(), "WARN 不该阻塞——满屏红色的门禁等于没有门禁");
            assertTrue(v.warns().stream().anyMatch(w -> w.code().equals("FEATURES_NOT_TABLE")));
        }

        @Test
        @DisplayName("整节缺失 → ERROR，且指出缺的是哪一节")
        void rejectsMissingSection() {
            GuideTemplate.Spec g = good();
            GuideTemplate.Spec s = new GuideTemplate.Spec(g.title(), g.sourceRef(),
                    g.sourcePath(), g.domain(), g.oneLiner(), g.framework(),
                    g.features(), null, g.skipItems(), g.openIssues(),
                    g.sourceChars(), g.sourcePages());
            DistillGuard.Verdict v = guard.check(template.render(s));
            // 掌握标准缺失时模板会填占位注释，因此至少要有 PLACEHOLDER_LEFT 提醒
            assertTrue(v.warns().stream().anyMatch(w -> w.code().equals("PLACEHOLDER_LEFT"))
                            || v.errors().stream().anyMatch(e -> e.code().equals("MISSING_MASTERY")),
                    "空的一节必须被指出来：占位注释在渲染后不可见，用户不会注意到");
        }
    }

    /* ================= 草稿标记 ================= */

    @Nested
    @DisplayName("草稿标记不可缺（未核对内容必须一眼可辨）")
    class DraftMarking {

        @Test
        @DisplayName("模板产物同时有 front-matter 与可见警示")
        void templateMarksBoth() {
            String md = template.render(good());
            assertTrue(md.contains("maturity: draft"));
            assertTrue(md.contains("尚未经人工核对"),
                    "front-matter 在多数渲染器里不显示，只靠它等于没标注");
            assertTrue(md.contains("distilled_by: lattice-agent"),
                    "溯源要写进文件本身，仓库才能脱离本软件独立存在");
        }

        @Test
        @DisplayName("★去掉可见警示 → ERROR")
        void rejectsWhenBannerRemoved() {
            String md = template.render(good()).replace(GuideTemplate.DRAFT_BANNER, "");
            DistillGuard.Verdict v = guard.check(md);
            assertFalse(v.pass());
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("NO_VISIBLE_BANNER")));
        }

        @Test
        @DisplayName("★把 maturity 改成 stable → ERROR")
        void rejectsFakeMaturity() {
            String md = template.render(good()).replace("maturity: draft", "maturity: stable");
            DistillGuard.Verdict v = guard.check(md);
            assertFalse(v.pass());
            assertTrue(v.errors().stream().anyMatch(e -> e.code().equals("NOT_MARKED_DRAFT")),
                    "未核对内容一旦看起来与手写 guide 无异，半年后会被当作可信来源引用");
        }
    }

    /* ================= 固定核对项 ================= */

    @Test
    @DisplayName("待核对清单固定含三条：公式图表 / 接口与维度 / 止损线是否合理")
    void alwaysHasFixedReviewItems() {
        String md = template.render(good());
        assertTrue(md.contains("公式与图表"), "PDF 提取必然丢公式与全部图");
        assertTrue(md.contains("接口名与维度"), "这类错误读起来完全通顺，最难发现");
        assertTrue(md.contains("止损线是否合理"), "跳错了几周后会变成挡路的盲区");
    }

    private GuideTemplate.Spec withSkips(List<String> skips) {
        GuideTemplate.Spec g = good();
        return new GuideTemplate.Spec(g.title(), g.sourceRef(), g.sourcePath(), g.domain(),
                g.oneLiner(), g.framework(), g.features(), g.mastery(), skips,
                g.openIssues(), g.sourceChars(), g.sourcePages());
    }
}
