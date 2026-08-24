package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zhzssp.memorandum.feature.codex.distill.CheckpointTemplate;
import org.zhzssp.memorandum.feature.codex.distill.ExamService;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * L1 单元测试：出题的两条命脉。
 *
 * <h3>① 模板与解析器的往返对齐</h3>
 * <p>出题产物走的是「渲染 Markdown → 既有 {@code CheckpointParser} → 数据库」这条路。
 * 若模板写 {@code **验收命令:**} 而解析器认 {@code **验收命令**：}，结果<strong>不是报错</strong>，
 * 而是那一节被静默忽略——题目落库了、但没有判据，于是永远无法被运行，
 * 而从数据库里看它是一条完全正常的 checkpoint。这组测试的全部意义就是守住这条缝。</p>
 *
 * <h3>② ★路径必须真实存在</h3>
 * <p>LLM 出题最典型的失效是编出一个不存在的脚本。这种题从文件上看完全合规，
 * 跑起来必然失败，而失败原因是「文件不存在」而非「知识没掌握」——
 * 它会污染 checkpoint 通过率，也就是本产品唯一号称无法造假的指标。
 * <strong>一个被污染的、无法造假的指标，比没有这个指标更糟，因为用户仍会相信它。</strong></p>
 */
class ExamRoundTripTest {

    @TempDir
    Path tmp;

    private CheckpointTemplate template;
    private CheckpointParser parser;
    private ExamService exam;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        template = new CheckpointTemplate();
        parser = new CheckpointParser(new ObjectMapper());

        repoRoot = tmp.resolve("kb");
        Files.createDirectories(repoRoot.resolve("mlir-lab/scripts"));
        Files.writeString(repoRoot.resolve("mlir-lab/scripts/all.sh"), "echo ok\n");
        Files.writeString(repoRoot.resolve("mlir-lab/README.md"), "# lab\n");
        Files.writeString(repoRoot.resolve("mlir-lab/tiny_mlp.mlir"), "// ir\n");

        // 只用到 firstMissingPath / parseItems 这两个纯函数，其余依赖不参与
        exam = new ExamService(template, parser, mock(
                        org.zhzssp.memorandum.feature.codex.verify.CheckpointService.class),
                mock(org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard.class),
                mock(org.zhzssp.memorandum.feature.codex.service.RepoRegistryService.class),
                mock(org.zhzssp.memorandum.feature.codex.service.RepoWriteService.class),
                mock(org.zhzssp.memorandum.feature.codex.service.RepoSyncService.class),
                mock(org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository.class),
                mock(org.zhzssp.memorandum.feature.agent.service.LlmGateway.class),
                new org.zhzssp.memorandum.feature.codex.service.CodexMetrics());
    }

    private CheckpointTemplate.Item item(String level, int ord, String cmd) {
        return new CheckpointTemplate.Item(level, ord,
                "把 tiny_mlp 降到 linalg 并跑通", "能说清 bufferization 发生在哪一步",
                null,
                "改 `scripts/all.sh` 里的 pass pipeline，加一个 `-linalg-bufferize`。",
                "- 加了这个 pass 后 IR 里会多出什么类型的 op？\n- 显存占用会怎么变？",
                cmd,
                "退出码为 0，且输出里出现 `memref.alloc`。",
                "若报 `failed to legalize`，说明没搞懂方言转换的边界，回看 §4.2。",
                "local", "1.5h");
    }

    /* ================= ① 往返对齐 ================= */

    @Nested
    @DisplayName("模板 → 既有解析器的往返对齐")
    class RoundTrip {

        @Test
        @DisplayName("★渲染出的整册能被 CheckpointParser 完整读回")
        void bookRoundTrips() {
            CheckpointTemplate.Book book = new CheckpointTemplate.Book(
                    "MLIR · 落地检验（AI 起草）", "MLIR",
                    "docs/learning-guides/mlir-learning-guide.md", "mlir-lab",
                    List.of(item("L1", 1, "cd mlir-lab\nbash scripts/all.sh"),
                            item("L2", 2, "bash scripts/all.sh")));

            List<CheckpointParser.Parsed> back = parser.parse(template.render(book), null);
            assertEquals(2, back.size(), "解析回来的条数必须与写出的一致");
            assertEquals("L1-MLIR-01", back.get(0).code());
            assertEquals("L2-MLIR-02", back.get(1).code());
            assertEquals(KbCheckpoint.Level.L1, back.get(0).level());
            assertTrue(back.get(0).hasCommand(),
                    "验收命令读不回来的题永远无法运行，而它在库里看起来完全正常");
            assertNotNull(back.get(0).verifyJson());
        }

        @Test
        @DisplayName("元信息字段全部读回（字段名与冒号形状都对得上）")
        void metaRoundTrips() {
            String md = template.renderItem("MLIR", item("L1", 1, "bash scripts/all.sh"));
            CheckpointParser.Parsed p = parser.parse(md, "mlir-lab").get(0);
            assertNotNull(p.checksWhat(), "「检验什么」没读回来");
            assertEquals("local", p.resourceTag(), "资源标签需去掉反引号并归一");
            assertNotNull(p.estHours());
            assertNotNull(p.passCriteria(), "「通过标准」没读回来");
            assertNotNull(p.predictionQuestions(), "「先预测再动手」没读回来");
            assertNotNull(p.blindSpots(), "「常见失败 → 盲点」没读回来");
        }

        @Test
        @DisplayName("★机器出的题被标成 AGENT_DRAFT，而不是与人写的题混为 PARSED")
        void markedAsAgentDraft() {
            String md = template.renderItem("MLIR", item("L2", 1, "bash scripts/all.sh"));
            CheckpointParser.Parsed p = parser.parse(md, "mlir-lab").get(0);
            assertEquals(KbCheckpoint.VerifySource.AGENT_DRAFT, p.verifySource(),
                    "不区分的话，「12 条通过 9 条」里会混进机器自己出题自己判的条目，"
                            + "而那个数字是本产品唯一号称无法造假的证据");
        }

        @Test
        @DisplayName("标记跟着条目走：剪贴到没有 front-matter 的册子里仍被识别")
        void markTravelsWithItem() {
            String itemOnly = template.renderItem("MLIR", item("L1", 1, "bash scripts/all.sh"));
            // 模拟用户把这一条剪贴进自己手写的册子（没有 authored_by front-matter）
            String handWrittenBook = "# 我自己的检验册\n\n" + itemOnly;
            CheckpointParser.Parsed p = parser.parse(handWrittenBook, "mlir-lab").get(0);
            assertEquals(KbCheckpoint.VerifySource.AGENT_DRAFT, p.verifySource(),
                    "标记若只写在册头，搬动内容时就会丢失");
        }

        @Test
        @DisplayName("lab 目录能从册头的「对应动手项目」推断出来（决定命令的 cwd）")
        void detectsLab() {
            CheckpointTemplate.Book book = new CheckpointTemplate.Book(
                    "T", "MLIR", "docs/learning-guides/mlir-learning-guide.md", "mlir-lab",
                    List.of(item("L1", 1, "bash scripts/all.sh")));
            CheckpointParser.Parsed p = parser.parse(template.render(book), null).get(0);
            assertTrue(p.verifyJson().contains("mlir-lab"),
                    "cwd 推断不出来时命令会在仓库根执行，然后失败在「找不到文件」上");
        }
    }

    /* ================= ② 路径存在性门禁 ================= */

    @Nested
    @DisplayName("★验收命令的路径必须真实存在")
    class PathGate {

        @Test
        @DisplayName("引用真实存在的脚本 → 放行")
        void acceptsExisting() {
            assertNull(exam.firstMissingPath(repoRoot, "mlir-lab", "bash scripts/all.sh"));
            assertNull(exam.firstMissingPath(repoRoot, "mlir-lab",
                    "mlir-opt tiny_mlp.mlir"));
        }

        @Test
        @DisplayName("★引用不存在的脚本 → 指出是哪一个")
        void rejectsMissing() {
            String bad = exam.firstMissingPath(repoRoot, "mlir-lab", "bash scripts/run_all.sh");
            assertEquals("scripts/run_all.sh", bad,
                    "这类题跑起来失败在环境上而非知识上，会污染通过率");
        }

        @Test
        @DisplayName("lab 目录写错时也能发现（路径按 lab 与仓库根两级解析）")
        void rejectsWrongLab() {
            assertNotNull(exam.firstMissingPath(repoRoot, "llvm-lab", "bash scripts/all.sh"));
        }

        @Test
        @DisplayName("不判绝对路径、URL、变量与 glob——从严会造成用户看不出原因的误杀")
        void skipsUnjudgeable() {
            for (String cmd : List.of(
                    "mlir-opt --help",
                    "curl https://example.com/x.sh",
                    "bash /usr/local/bin/setup.sh",
                    "python -m pytest",
                    "bash $LAB_HOME/run.sh",
                    "rm -f out/*.mlir")) {
                assertNull(exam.firstMissingPath(repoRoot, "mlir-lab", cmd),
                        "不该判定为缺失路径：" + cmd);
            }
        }

        @Test
        @DisplayName("越出仓库的路径一律算不存在（不给 ../../ 逃出去的机会）")
        void treatsEscapeAsMissing() {
            assertNotNull(exam.firstMissingPath(repoRoot, "mlir-lab",
                    "bash ../../../etc/init.sh"));
        }

        @Test
        @DisplayName("注释行不参与判定")
        void ignoresComments() {
            assertNull(exam.firstMissingPath(repoRoot, "mlir-lab",
                    "# 先看 scripts/not_exist.sh\nbash scripts/all.sh"));
        }
    }

    /* ================= 输出解析 ================= */

    @Nested
    @DisplayName("模型输出解析（分隔符协议）")
    class OutputParsing {

        private static final String RAW = """
                @@ITEM@@
                @@LEVEL@@L1
                @@TITLE@@加一个 bufferize pass
                @@CHECKS@@能说清 bufferization 在哪一步
                @@TASK@@改 pipeline
                @@PREDICT@@- IR 会多出什么 op？
                @@CMD@@
                ```bash
                bash scripts/all.sh
                ```
                @@CRITERIA@@退出码 0
                @@BLIND@@报 failed to legalize 说明没搞懂边界
                @@ITEMEND@@
                @@ITEM@@
                @@LEVEL@@L2
                @@TITLE@@新增一个 pattern
                @@CHECKS@@能写出匹配条件
                @@TASK@@加 pattern
                @@PREDICT@@- 会命中几次？
                @@CMD@@
                bash scripts/all.sh
                @@CRITERIA@@输出含 memref.alloc
                @@BLIND@@没命中说明 benefit 设错
                @@ITEMEND@@
                """;

        @Test
        @DisplayName("按分隔符切出多条，并剥掉模型多包的代码围栏")
        void parsesItems() {
            List<CheckpointTemplate.Item> items = exam.parseItems(RAW, 4);
            assertEquals(2, items.size());
            assertEquals("L1", items.get(0).level());
            assertEquals("bash scripts/all.sh", items.get(0).command(),
                    "模型常把命令再包一层 ```，不剥掉会在渲染后出现嵌套围栏");
            assertEquals("L2", items.get(1).level());
        }

        @Test
        @DisplayName("★缺一个分隔符只丢一条，不是整批作废")
        void partialFailureIsLocal() {
            String broken = RAW.replaceFirst("@@CMD@@", "@@CMDX@@");
            List<CheckpointTemplate.Item> items = exam.parseItems(broken, 4);
            assertEquals(1, items.size(),
                    "这正是用分隔符而不是 JSON 的理由：JSON 转义出错会让整批作废，"
                            + "而正文里必然含代码块与换行");
        }

        @Test
        @DisplayName("level 写得不规范时归一到 L1，不抛异常")
        void normalizesLevel() {
            String weird = RAW.replace("@@LEVEL@@L1", "@@LEVEL@@ 等级：l1 级 ");
            assertEquals("L1", exam.parseItems(weird, 1).get(0).level());
        }

        @Test
        @DisplayName("超出期望条数即停（成本闸门在解析层也生效）")
        void respectsWant() {
            assertEquals(1, exam.parseItems(RAW, 1).size());
        }
    }
}
