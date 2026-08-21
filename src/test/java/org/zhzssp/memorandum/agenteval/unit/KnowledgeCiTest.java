package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck;
import org.zhzssp.memorandum.feature.codex.ci.KnowledgeCiService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：知识 CI 的判据与结果模型。
 *
 * <h3>这里守的是两条容易被"优化"掉的设计</h3>
 * <ol>
 *   <li><strong>{@code SKIPPED} 不等于通过</strong>：把「没检查」显示成「通过」，
 *       会让用户以为已经验过了——那比不做这项检查更有害。
 *       所以 {@link CiCheck.Status} 必须保留独立的 SKIPPED 值，
 *       且 {@code passed} 只由 ERROR 数决定。</li>
 *   <li><strong>WARN/INFO 不阻塞</strong>：现存 61 篇文档一篇都没有 front-matter，
 *       若判成 ERROR，启用第一天就是满屏红色——而满屏红色的 CI 等于没有 CI，
 *       用户会永久无视它。</li>
 * </ol>
 */
class KnowledgeCiTest {

    /* ================= 示例判据 ================= */

    @Nested
    @DisplayName("示例判据：代码块或对照表都算示例")
    class ExampleDetection {

        @Test
        @DisplayName("fenced code block 算示例")
        void fenceCounts() {
            assertTrue(KnowledgeCiService.hasExample("""
                    ## 示例

                    ```llvm
                    %r = phi i32 [ %a, %then ]
                    ```
                    """));
            assertTrue(KnowledgeCiService.hasExample("~~~python\nprint(1)\n~~~\n"),
                    "~~~ 也是合法围栏");
        }

        @Test
        @DisplayName("Markdown 对照表算示例——对照本身就是知识")
        void tableCounts() {
            assertTrue(KnowledgeCiService.hasExample("""
                    | LLVM IR | MLIR |
                    |---------|------|
                    | phi | block argument |
                    """));
            assertTrue(KnowledgeCiService.hasExample("| a | b |\n|:--|--:|\n| 1 | 2 |\n"),
                    "带对齐标记的分隔行同样应识别");
        }

        @Test
        @DisplayName("纯文字不算示例")
        void proseDoesNot() {
            assertFalse(KnowledgeCiService.hasExample(
                    "phi 是控制流汇合点选值的指令，两条规则见原文。"));
            assertFalse(KnowledgeCiService.hasExample(""));
            assertFalse(KnowledgeCiService.hasExample(null));
        }

        @Test
        @DisplayName("单行竖线不误判为表格")
        void singlePipeIsNotTable() {
            assertFalse(KnowledgeCiService.hasExample("命令是 a | b 这种管道写法。"));
        }
    }

    /* ================= 相对链接建议 ================= */

    @Nested
    @DisplayName("修复建议里的相对路径")
    class RelativeHint {

        @Test
        @DisplayName("guide 视角下笔记路径为 ../notes/x.md")
        void guideToNote() {
            assertEquals("../notes/x.md", KnowledgeCiService.relativeLink(
                    "docs/learning-guides/g.md", "docs/notes/x.md"));
        }

        @Test
        @DisplayName("源在根目录时退回原路径而非抛异常")
        void fromRoot() {
            assertEquals("docs/notes/x.md",
                    KnowledgeCiService.relativeLink("README.md", "docs/notes/x.md"));
        }
    }

    /* ================= protagonist 声明解析 ================= */

    @Nested
    @DisplayName("protagonist.yml 解析")
    class FlatYaml {

        @Test
        @DisplayName("标量与列表都能解析，引号被剥离")
        void parsesScalarAndList() {
            Map<String, List<String>> m = KnowledgeCiService.parseFlatYaml("""
                    # 主角数值
                    shapes: [2.5, 3.5, 4.5, 7.5]
                    model: "resnet18"
                    batch: 8
                    """);
            assertEquals(List.of("2.5", "3.5", "4.5", "7.5"), m.get("shapes"));
            assertEquals(List.of("resnet18"), m.get("model"));
            assertEquals(List.of("8"), m.get("batch"));
        }

        @Test
        @DisplayName("注释与空行被忽略，空值不入表")
        void ignoresNoise() {
            Map<String, List<String>> m = KnowledgeCiService.parseFlatYaml("""
                    # comment

                    empty:
                    a: 1
                    """);
            assertFalse(m.containsKey("empty"));
            assertEquals(1, m.size());
        }

        @Test
        @DisplayName("null 输入返回空表而非抛异常")
        void nullSafe() {
            assertTrue(KnowledgeCiService.parseFlatYaml(null).isEmpty());
        }
    }

    /* ================= 结果模型的语义约束 ================= */

    @Nested
    @DisplayName("报告语义：SKIPPED ≠ 通过，WARN/INFO 不阻塞")
    class ReportSemantics {

        private CiCheck.Report report(List<CiCheck.CheckResult> checks) {
            int e = 0;
            int w = 0;
            int i = 0;
            for (CiCheck.CheckResult c : checks) {
                e += (int) c.errors();
                w += (int) c.warns();
                i += (int) c.infos();
            }
            return new CiCheck.Report(1L, "kb", checks, e, w, i, e == 0, 1);
        }

        @Test
        @DisplayName("★SKIPPED 必须是独立状态，不能与 OK 混为一谈")
        void skippedIsDistinct() {
            CiCheck.CheckResult skipped = new CiCheck.CheckResult(
                    CiCheck.CheckId.SCOPE_DANGLING, CiCheck.Status.SKIPPED,
                    "知识点表为空", List.of(), 0, 1);
            assertNotEquals(CiCheck.Status.OK, skipped.status());
            assertNotNull(skipped.skipReason(),
                    "SKIPPED 必须说明缺什么，否则用户无从判断这项要不要在意");
        }

        @Test
        @DisplayName("只有 ERROR 阻塞；WARN/INFO 不影响 passed")
        void onlyErrorsBlock() {
            CiCheck.CheckResult warnOnly = new CiCheck.CheckResult(
                    CiCheck.CheckId.ORPHAN_DOC, CiCheck.Status.FINDINGS, null,
                    List.of(CiCheck.Finding.warn(CiCheck.CheckId.ORPHAN_DOC,
                                    "docs/a.md", null, "无入链", "考虑链上它"),
                            CiCheck.Finding.info(CiCheck.CheckId.FRONT_MATTER,
                                    null, null, "61 篇缺元数据", "渐进补齐")),
                    2, 1);
            assertTrue(report(List.of(warnOnly)).passed(),
                    "满屏红色的 CI 等于没有 CI——WARN/INFO 绝不能阻塞");

            CiCheck.CheckResult withError = new CiCheck.CheckResult(
                    CiCheck.CheckId.BACKREF_BIDIRECTIONAL, CiCheck.Status.FINDINGS, null,
                    List.of(CiCheck.Finding.error(CiCheck.CheckId.BACKREF_BIDIRECTIONAL,
                            "docs/notes/x.md", null, "无回挂", "插入速记引用")),
                    1, 1);
            assertFalse(report(List.of(withError)).passed());
        }

        @Test
        @DisplayName("发现必须可定位：locator 给出 文件:行")
        void findingsAreLocatable() {
            CiCheck.Finding withLine = CiCheck.Finding.error(
                    CiCheck.CheckId.DEAD_LINK, "docs/a.md", 42, "死链", "改路径");
            assertEquals("docs/a.md:42", withLine.locator());

            CiCheck.Finding noLine = CiCheck.Finding.warn(
                    CiCheck.CheckId.ORPHAN_DOC, "docs/b.md", null, "孤岛", null);
            assertEquals("docs/b.md", noLine.locator());

            CiCheck.Finding repoLevel = CiCheck.Finding.info(
                    CiCheck.CheckId.FRONT_MATTER, null, null, "聚合项", null);
            assertEquals("(repo)", repoLevel.locator());
        }

        @Test
        @DisplayName("九项检查都有中文标签与说明（报告要能直接给人看）")
        void allChecksDocumented() {
            assertEquals(9, CiCheck.CheckId.values().length);
            for (CiCheck.CheckId id : CiCheck.CheckId.values()) {
                assertFalse(id.label().isBlank(), id + " 缺标签");
                assertFalse(id.description().isBlank(), id + " 缺说明");
            }
        }
    }

    /* ================= 提交信息溯源 ================= */

    @Nested
    @DisplayName("提交信息必须标注 Agent 参与")
    class CommitMessage {

        @Test
        @DisplayName("★Co-authored-by 与会话标识写进提交信息本身，而非只记在数据库")
        void carriesProvenance() {
            RepoWriteService svc = new RepoWriteService(null, null, null, null);
            String msg = svc.buildMessage("docs(notes): 沉淀 LLVM phi", null,
                    "sess-123", List.of("docs/notes/llvm-phi.md"));

            assertTrue(msg.startsWith("docs(notes): 沉淀 LLVM phi\n\n"));
            assertTrue(msg.contains("Co-authored-by: Lattice Agent"),
                    "溯源信息只有留在 git 历史里才能随仓库带走——"
                            + "仓库要能脱离本软件独立存在");
            assertTrue(msg.contains("Lattice-Session: sess-123"));
            assertTrue(msg.contains("Lattice-Files: docs/notes/llvm-phi.md"));
        }

        @Test
        @DisplayName("空标题有兜底，不产生空提交信息")
        void fallbackSubject() {
            RepoWriteService svc = new RepoWriteService(null, null, null, null);
            String msg = svc.buildMessage("  ", null, null, List.of());
            assertTrue(msg.startsWith("docs: 更新知识资产"));
            assertFalse(msg.contains("Lattice-Session"));
        }
    }
}
