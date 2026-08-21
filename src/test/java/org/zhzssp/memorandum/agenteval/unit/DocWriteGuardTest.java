package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 单元测试：写入闸门。
 *
 * <h3>这组测试守的是 P2 最重要的一条安全边界</h3>
 * <p>写入白名单只含 {@code docs/notes/**}。它决定了「Agent 能不能弄坏我的 guide」——
 * 用户 6 篇主干 guide 累计 40 万字符、数月写成，一次幻觉造成的损失不可逆。
 * 只要这组断言成立，破坏语料在结构上就不可能，而不是靠 prompt 里写「请不要改动无关章节」。</p>
 *
 * <h3>以及「示例必须入库」的执行层强制</h3>
 * <p>模型天然倾向于把内容压缩得更"整洁"，被压掉的恰恰是让人半年后还能重新看懂的部分。
 * 拒绝写入是唯一可靠的办法——{@code exampleGateRejections} 这个指标一旦不为 0，
 * 就直接证明了只靠 prompt 提醒是不够的。</p>
 */
class DocWriteGuardTest {

    @TempDir
    Path tmp;

    private Path repoRoot;
    private DocWriteGuard guard;
    private GitClient git;
    private KnowledgeRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        repoRoot = tmp.resolve("kb");
        Files.createDirectories(repoRoot.resolve("docs/notes"));
        Files.createDirectories(repoRoot.resolve("docs/learning-guides"));
        Files.writeString(repoRoot.resolve("docs/learning-guides/g.md"), "# G\n");

        repo = new KnowledgeRepo();
        repo.setId(1L);
        repo.setUserId(1L);
        repo.setName("kb");
        repo.setLocalPath(repoRoot.toString());
        repo.setDefaultBranch("main");

        RepoRegistryService registry = mock(RepoRegistryService.class);
        when(registry.enabled()).thenReturn(true);
        when(registry.operational()).thenReturn(true);
        when(registry.rootOf(any())).thenReturn(repoRoot);

        git = mock(GitClient.class);
        when(git.currentBranch(any())).thenReturn("lattice/sediment-20260821-x");
        when(git.status(any())).thenReturn(
                new GitClient.WorkingStatus("lattice/sediment-20260821-x", "abc", true, List.of()));

        guard = new DocWriteGuard(registry, git);
        ReflectionTestUtils.setField(guard, "writeEnabled", true);
        ReflectionTestUtils.setField(guard, "allowedPathsRaw", "docs/notes/**/*.md");
        ReflectionTestUtils.setField(guard, "branchPrefix", "lattice/");
        ReflectionTestUtils.setField(guard, "maxNoteChars", 20000);
        ReflectionTestUtils.setField(guard, "allowOnDefaultBranch", false);
    }

    /* ================= 路径沙箱 ================= */

    @Nested
    @DisplayName("路径沙箱：只能在 docs/notes 下新建 Markdown")
    class PathSandbox {

        @Test
        @DisplayName("白名单内的笔记路径放行")
        void allowsNotePath() {
            assertTrue(guard.checkPath(repo, "docs/notes/llvm-phi.md").allowed());
            assertTrue(guard.checkPath(repo, "docs/notes/sub/topic.md").allowed(),
                    "**/ 应能匹配多级子目录");
        }

        @Test
        @DisplayName("★既有 guide 不可被写——这是不可逆内容损失的唯一防线")
        void rejectsGuidePath() {
            DocWriteGuard.Decision d =
                    guard.checkPath(repo, "docs/learning-guides/g.md");
            assertFalse(d.allowed(),
                    "guide 若可被 doc.write 覆盖，一次幻觉就能毁掉数月工作");
            assertEquals("PATH_NOT_ALLOWED", d.code());
        }

        @Test
        @DisplayName("路径穿越一律拒绝（含规范化后才暴露的形式）")
        void rejectsTraversal() {
            for (String p : List.of(
                    "../outside.md",
                    "docs/notes/../../etc/passwd.md",
                    "docs/notes/a/../../../x.md",
                    "/etc/hosts.md")) {
                DocWriteGuard.Decision d = guard.checkPath(repo, p);
                assertFalse(d.allowed(), "应拒绝穿越路径：" + p);
            }
        }

        @Test
        @DisplayName("反斜杠形式同样受白名单约束（Windows 输入习惯）")
        void normalizesBackslashes() {
            assertTrue(guard.checkPath(repo, "docs\\notes\\x.md").allowed());
            assertFalse(guard.checkPath(repo, "docs\\learning-guides\\g.md").allowed());
        }

        @Test
        @DisplayName("非 Markdown 拒绝")
        void rejectsNonMarkdown() {
            DocWriteGuard.Decision d = guard.checkPath(repo, "docs/notes/x.txt");
            assertFalse(d.allowed());
            assertEquals("PATH_NOT_MARKDOWN", d.code());
        }

        @Test
        @DisplayName("空路径拒绝")
        void rejectsEmpty() {
            assertFalse(guard.checkPath(repo, null).allowed());
            assertFalse(guard.checkPath(repo, "   ").allowed());
        }
    }

    /* ================= 示例入库 ================= */

    @Nested
    @DisplayName("示例入库门禁（执行层强制）")
    class ExampleGate {

        private static final String SRC_WITH_CODE = """
                phi 是控制流汇合点选值的指令。

                ```llvm
                %r = phi i32 [ %a, %then ], [ %b, %else ]
                ```

                含义是从 then 进来取 a。
                """;

        @Test
        @DisplayName("★源含代码块而正文没有 → 拒绝写入")
        void rejectsWhenCodeDropped() {
            String body = "## 是什么\n\nphi 在汇合点选值，具体见原文示例。\n";
            DocWriteGuard.Decision d = guard.checkExamples(SRC_WITH_CODE, body);
            assertFalse(d.allowed(),
                    "把示例压成「见原文示例」正是要拦住的行为");
            assertEquals("MISSING_EXAMPLES", d.code());
        }

        @Test
        @DisplayName("源含代码块且正文保留了代码块 → 放行")
        void allowsWhenCodeKept() {
            String body = """
                    ## 是什么

                    汇合点选值。

                    ## 示例

                    ```llvm
                    %r = phi i32 [ %a, %then ], [ %b, %else ]
                    ```
                    """;
            assertTrue(guard.checkExamples(SRC_WITH_CODE, body).allowed());
        }

        @Test
        @DisplayName("源含对照表而正文既无表也无代码 → 拒绝")
        void rejectsWhenTableDropped() {
            String src = """
                    两者的差别：

                    | LLVM IR | MLIR |
                    |---------|------|
                    | phi | block argument |
                    """;
            DocWriteGuard.Decision d = guard.checkExamples(src, "## 是什么\n\n语义等价。\n");
            assertFalse(d.allowed());
            assertEquals("MISSING_EXAMPLES", d.code());
        }

        @Test
        @DisplayName("源本就没有示例 → 不强求正文有")
        void allowsWhenSourceHasNoExample() {
            assertTrue(guard.checkExamples("一段纯文字解释，没有代码也没有表格。",
                    "## 是什么\n\n一段纯文字。\n").allowed());
        }

        @Test
        @DisplayName("未提供原文 → 拒绝（无法校验就不能放行）")
        void rejectsMissingSource() {
            DocWriteGuard.Decision d = guard.checkExamples(null, "## 是什么\n\n内容\n");
            assertFalse(d.allowed());
            assertEquals("MISSING_SOURCE", d.code());
        }

        @Test
        @DisplayName("按类别对齐：源有代码块时，正文只有表格也不算保留")
        void categoryAligned() {
            String body = """
                    ## 对照

                    | a | b |
                    |---|---|
                    | 1 | 2 |
                    """;
            assertFalse(guard.checkExamples(SRC_WITH_CODE, body).allowed(),
                    "否则模型可以用一个无关表格蒙过检查，却把真正的代码示例丢掉");
        }
    }

    /* ================= 分支与工作副本 ================= */

    @Nested
    @DisplayName("分支与工作副本保护")
    class BranchAndTree {

        @Test
        @DisplayName("★默认分支上禁止写入")
        void deniesOnDefaultBranch() {
            when(git.currentBranch(any())).thenReturn("main");
            DocWriteGuard.Decision d = guard.checkBranch(repo);
            assertFalse(d.allowed(),
                    "默认分支上的提交不经审阅即进入历史");
            assertEquals("ON_DEFAULT_BRANCH", d.code());
        }

        @Test
        @DisplayName("工作分支上允许写入")
        void allowsOnWorkBranch() {
            assertTrue(guard.checkBranch(repo).allowed());
        }

        @Test
        @DisplayName("★脏工作副本拒绝，且不做任何 stash")
        void deniesDirtyTree() {
            when(git.status(any())).thenReturn(new GitClient.WorkingStatus(
                    "lattice/x", "abc", false, List.of("docs/learning-guides/g.md")));
            DocWriteGuard.Decision d = guard.checkWorkingTree(repo, Set.of());
            assertFalse(d.allowed());
            assertEquals("WORKTREE_DIRTY", d.code());
            assertTrue(d.hint().contains("stash"),
                    "必须明确告诉用户软件不会替他 stash，否则他会以为改动被弄丢了");
        }

        @Test
        @DisplayName("本次流程自己写脏的文件不算阻塞")
        void ownedDirtyPathsAreFine() {
            when(git.status(any())).thenReturn(new GitClient.WorkingStatus(
                    "lattice/x", "abc", false, List.of("docs/notes/x.md")));
            assertTrue(guard.checkWorkingTree(repo, Set.of("docs/notes/x.md")).allowed());
        }
    }

    /* ================= 总开关 ================= */

    @Test
    @DisplayName("写入开关关闭时一切写入被拒（CI 只读不受影响）")
    void deniesWhenDisabled() {
        ReflectionTestUtils.setField(guard, "writeEnabled", false);
        DocWriteGuard.Decision d = guard.checkEnabled();
        assertFalse(d.allowed());
        assertEquals("WRITE_DISABLED", d.code());
    }

    @Test
    @DisplayName("笔记体积上限：超限拒绝并解释原因")
    void enforcesSizeLimit() {
        String huge = "x".repeat(20001);
        DocWriteGuard.Decision d = guard.checkSize(huge);
        assertFalse(d.allowed());
        assertEquals("CONTENT_TOO_LARGE", d.code());
        assertTrue(guard.checkSize("## 是什么\n\n短内容\n").allowed());
    }
}
