package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：{@link CheckpointParser}。
 *
 * <h3>这组测试守的是「零迁移」承诺</h3>
 * <p>目标仓库已有 86 条手写检验，分布在 9 册 Markdown 里，写法并不统一。
 * 若解析器只认一种格式，用户就得先把 86 条重写一遍——那等于要求
 * 「先交作业才能用」，功能会永远停在纸面。</p>
 *
 * <p>因此这里的用例大量取自真实仓库的实际写法（含各种不一致），
 * 「容忍度」用例比「精确提取」用例更受重视——与方案 E「宁松勿严」同一立场：
 * <strong>解析不出某字段就留空，绝不因此丢掉整个条目。</strong></p>
 */
class CheckpointParserTest {

    private final CheckpointParser parser = new CheckpointParser(new ObjectMapper());

    /* ================= 格式容忍度（最重要） ================= */

    @Nested
    @DisplayName("真实仓库的格式不一致必须全部容忍")
    class FormatTolerance {

        @Test
        @DisplayName("### 三级标题（01-llvm / 02-mlir 的写法）")
        void threeLevelHeading() {
            var out = parser.parse("""
                    ## L0 复现

                    ### L0-MLIR-01｜跑通全流程，九组演示各能说出一句话

                    - **检验什么**：你知道这个 dialect 里有哪些 op
                    - **资源**：本地+工具链
                    - **预计耗时**：1.5h
                    """, null);
            assertEquals(1, out.size());
            assertEquals("L0-MLIR-01", out.get(0).code());
            assertEquals(KbCheckpoint.Level.L0, out.get(0).level());
            assertTrue(out.get(0).title().startsWith("跑通全流程"));
        }

        @Test
        @DisplayName("#### 四级标题（08-distributed 的写法）")
        void fourLevelHeading() {
            var out = parser.parse("""
                    ### L2：加组件（主判据）

                    #### L2-DIST-06｜DDP vs 单卡：显存几乎不降，吞吐接近线性

                    - **资源**：`多卡GPU`（同机 2 卡）
                    - **预计耗时**：半天
                    """, null);
            assertEquals(1, out.size(), "四级标题也必须能识别");
            assertEquals("L2-DIST-06", out.get(0).code());
            assertEquals(KbCheckpoint.Level.L2, out.get(0).level());
        }

        @Test
        @DisplayName("资源标签带反引号与括注时正确归一")
        void resourceWithBackticksAndParens() {
            var out = parser.parse("""
                    ### L1-DIST-05｜单卡显存峰值实测

                    - **资源**：`单卡GPU`
                    - **预计耗时**：2h
                    """, null);
            assertEquals("gpu1", out.get(0).resourceTag());

            var out2 = parser.parse("""
                    ### L0-DIST-02｜CPU/gloo 两进程跑通 DDP

                    - **资源**：`本地`（纯 CPU，两个进程跑在同一台笔记本上）
                    """, null);
            assertEquals("local", out2.get(0).resourceTag(),
                    "括注必须被剥离，否则标签无法用于资源筛选");
        }

        @Test
        @DisplayName("「通过标准」带括注（机器可判定）时仍能提取")
        void passCriteriaWithAnnotation() {
            var out = parser.parse("""
                    ### L2-DIST-07｜FSDP vs DDP

                    **通过标准**（机器可判定）：

                    显存随卡数近似 1/N，吞吐略降。
                    """, null);
            assertNotNull(out.get(0).passCriteria());
            assertTrue(out.get(0).passCriteria().contains("1/N"));
        }

        @Test
        @DisplayName("五种资源标签全部归一")
        void allResourceTags() {
            String tpl = """
                    ### L0-X-01｜标题

                    - **资源**：%s
                    """;
            assertEquals("local", parser.parse(tpl.formatted("本地"), null).get(0).resourceTag());
            assertEquals("local+toolchain",
                    parser.parse(tpl.formatted("本地+工具链"), null).get(0).resourceTag());
            assertEquals("gpu1", parser.parse(tpl.formatted("单卡GPU"), null).get(0).resourceTag());
            assertEquals("gpuN", parser.parse(tpl.formatted("多卡GPU"), null).get(0).resourceTag());
            assertEquals("multinode",
                    parser.parse(tpl.formatted("多机多卡"), null).get(0).resourceTag());
        }

        @Test
        @DisplayName("耗时支持 h / 半天 / 一天")
        void estHoursVariants() {
            String tpl = "### L0-X-01｜T\n\n- **预计耗时**：%s\n";
            assertEquals(0, new java.math.BigDecimal("1.5").compareTo(
                    parser.parse(tpl.formatted("1.5h"), null).get(0).estHours()));
            assertEquals(0, new java.math.BigDecimal("4").compareTo(
                    parser.parse(tpl.formatted("半天"), null).get(0).estHours()));
            assertEquals(0, new java.math.BigDecimal("2").compareTo(
                    parser.parse(tpl.formatted("2h（含建目录与写 common.py）"), null)
                            .get(0).estHours()));
        }

        @Test
        @DisplayName("缺字段只留空，不丢条目")
        void missingFieldsDoNotDropEntry() {
            var out = parser.parse("### L3-X-09｜只有标题没有任何元信息\n", null);
            assertEquals(1, out.size(), "缺字段绝不能导致整个条目被丢掉");
            assertNull(out.get(0).resourceTag());
            assertNull(out.get(0).estHours());
            assertNull(out.get(0).verifyJson());
            assertFalse(out.get(0).hasCommand());
        }

        @Test
        @DisplayName("标题里的链接与行内代码被清理")
        void titleCleaned() {
            var out = parser.parse(
                    "### L2-DIST-08｜切换 FSDP 的 `sharding_strategy` 三档\n", null);
            assertFalse(out.get(0).title().contains("`"));
            assertTrue(out.get(0).title().contains("sharding_strategy"));
        }
    }

    /* ================= 多条目切分 ================= */

    @Nested
    @DisplayName("多条目切分")
    class MultipleEntries {

        @Test
        @DisplayName("条目正文按下一条目起点截断，字段不串台")
        void bodiesDoNotBleed() {
            var out = parser.parse("""
                    ### L0-A-01｜第一条

                    - **资源**：本地
                    - **预计耗时**：1h

                    ### L1-A-02｜第二条

                    - **资源**：单卡GPU
                    - **预计耗时**：2h
                    """, null);
            assertEquals(2, out.size());
            assertEquals("local", out.get(0).resourceTag());
            assertEquals("gpu1", out.get(1).resourceTag(),
                    "第二条的资源不能被第一条覆盖");
        }

        @Test
        @DisplayName("章节分组标题（## L0 复现）不被误当条目")
        void groupHeadingsIgnored() {
            var out = parser.parse("""
                    ## L0 复现

                    ### L0-A-01｜真条目

                    ## L1 改一处

                    ### L1-A-02｜另一条真条目
                    """, null);
            assertEquals(2, out.size(), "分组标题无 ｜ 分隔符，不应被当作条目");
        }

        @Test
        @DisplayName("无条目文档返回空列表而非抛异常")
        void noEntries() {
            assertTrue(parser.parse("# 只是一篇普通文档\n\n正文。\n", null).isEmpty());
            assertTrue(parser.parse("", null).isEmpty());
            assertTrue(parser.parse(null, null).isEmpty());
        }
    }

    /* ================= 验收命令提取（保守优先） ================= */

    @Nested
    @DisplayName("验收命令提取：保守优先于智能")
    class VerifyCommand {

        @Test
        @DisplayName("从 cd + 命令中推断 cwd，cd 本身不执行")
        void inferCwdFromCd() {
            var out = parser.parse("""
                    ### L0-MLIR-01｜跑通全流程

                    **验收命令**：

                    ```bash
                    cd mlir-toy-dialect
                    bash scripts/all.sh
                    ```
                    """, null);
            String v = out.get(0).verifyJson();
            assertNotNull(v);
            assertTrue(v.contains("\"cmd\":\"bash scripts/all.sh\""),
                    "实际 verifyJson=" + v);
            assertTrue(v.contains("\"cwd\":\"mlir-toy-dialect\""));
            assertTrue(out.get(0).hasCommand());
        }

        @Test
        @DisplayName("含管道/重定向/heredoc 的行被剔除——不做 shell 语义模拟")
        void shellFeaturesExcluded() {
            var out = parser.parse("""
                    ### L0-X-01｜T

                    **验收命令**：

                    ```bash
                    cd lab
                    find build -name '*.inc' | head -5
                    bash scripts/test.sh
                    ```
                    """, null);
            String v = out.get(0).verifyJson();
            assertNotNull(v);
            assertFalse(v.contains("head -5"), "含管道的行不得成为候选命令");
            assertTrue(v.contains("bash scripts/test.sh"));
        }

        @Test
        @DisplayName("heredoc 块整体被跳过")
        void heredocSkipped() {
            var out = parser.parse("""
                    ### L1-MLIR-03｜给 toy.mul 加代数化简

                    **验收命令**：

                    ```bash
                    cd mlir-toy-dialect
                    bash scripts/build.sh
                    cat > /tmp/mulfold.mlir <<'EOF'
                    func.func @f() {}
                    EOF
                    bash scripts/test.sh
                    ```
                    """, null);
            String v = out.get(0).verifyJson();
            assertNotNull(v);
            assertTrue(v.contains("bash scripts/build.sh"), "应取第一条安全候选");
            assertFalse(v.contains("EOF"));
            assertFalse(v.contains("cat >"));
        }

        @Test
        @DisplayName("注释行被忽略")
        void commentsIgnored() {
            var out = parser.parse("""
                    ### L0-X-01｜T

                    **验收命令**：

                    ```bash
                    # 开工前自查
                    bash scripts/setup.sh
                    ```
                    """, null);
            String v = out.get(0).verifyJson();
            assertTrue(v.contains("bash scripts/setup.sh"));
            assertFalse(v.contains("开工前自查"));
        }

        @Test
        @DisplayName("其余候选存进 alternatives，不自动串联执行")
        void extraCandidatesKeptAsAlternatives() {
            var out = parser.parse("""
                    ### L0-X-01｜T

                    **验收命令**：

                    ```bash
                    bash scripts/build.sh
                    bash scripts/test.sh
                    bash scripts/run.sh
                    ```
                    """, null);
            String v = out.get(0).verifyJson();
            assertTrue(v.contains("alternatives"),
                    "多条命令应保留供用户选择，而不是自动串联（串联会执行意料之外的命令）");
        }

        @Test
        @DisplayName("expect 只放退出码——不从中文标准里猜关键词")
        void expectOnlyExitCode() {
            var out = parser.parse("""
                    ### L0-X-01｜T

                    **验收命令**：

                    ```bash
                    bash scripts/all.sh
                    ```

                    **通过标准**：`check-toy` 全部用例 PASS；不应出现 toy.mul。
                    """, null);
            String v = out.get(0).verifyJson();
            assertTrue(v.contains("exit_code"));
            // 中文通过标准里常含「不应出现」「消失」等否定语，
            // 机械提取关键词会产出方向相反的断言
            assertFalse(v.contains("stdout_contains"),
                    "不应从自然语言通过标准里猜测内容断言");
        }

        @Test
        @DisplayName("无代码块时 verifyJson 为 null，hasCommand=false")
        void noCodeBlock() {
            var out = parser.parse("""
                    ### L3-X-09｜纯口述题

                    **通过标准**：能讲清 SSA 与 phi 的关系。
                    """, null);
            assertNull(out.get(0).verifyJson());
            assertFalse(out.get(0).hasCommand());
        }

        @Test
        @DisplayName("labHint 作为默认 cwd")
        void labHintAsDefaultCwd() {
            var out = parser.parse("""
                    ### L0-X-01｜T

                    **验收命令**：

                    ```bash
                    bash scripts/run.sh
                    ```
                    """, "iree-lab");
            assertTrue(out.get(0).verifyJson().contains("\"cwd\":\"iree-lab\""));
        }
    }

    /* ================= 其他字段 ================= */

    @Nested
    @DisplayName("预测提问与盲点映射")
    class OtherBlocks {

        @Test
        @DisplayName("提取「先预测再动手」——它是预测门禁的引导内容")
        void predictionQuestions() {
            var out = parser.parse("""
                    ### L1-MLIR-03｜T

                    **先预测再动手**：

                    1. 返回 Value 与返回 Attribute，框架后续处理有什么不同？
                    2. toy.mul 有 Commutative trait，常量在左边时还能命中吗？

                    **验收命令**：

                    ```bash
                    bash x.sh
                    ```
                    """, null);
            String q = out.get(0).predictionQuestions();
            assertNotNull(q, "预测提问必须提取，否则用户不知道该预测什么");
            assertTrue(q.contains("Commutative"));
            assertFalse(q.contains("验收命令"), "段落边界不能越过下一个段落标题");
        }

        @Test
        @DisplayName("提取「常见失败 → 盲点」——失败时用它回指知识点")
        void blindSpots() {
            var out = parser.parse("""
                    ### L1-MLIR-03｜T

                    **常见失败 → 说明你哪里没懂**：

                    | 现象 | 盲点 |
                    |------|------|
                    | fold 写了但不生效 | 没跑 --canonicalize |
                    """, null);
            String b = out.get(0).blindSpots();
            assertNotNull(b);
            assertTrue(b.contains("canonicalize"));
        }

        @Test
        @DisplayName("提取「检验什么」与「前置」")
        void checksWhatAndPrerequisite() {
            var out = parser.parse("""
                    ### L0-MLIR-02｜T

                    - **检验什么**：这条通过 = 你不再把 .td 当黑魔法
                    - **前置**：L0-MLIR-01
                    """, null);
            assertTrue(out.get(0).checksWhat().contains("黑魔法"));
            assertEquals("L0-MLIR-01", out.get(0).prerequisite());
        }
    }
}
