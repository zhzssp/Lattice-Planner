package org.zhzssp.memorandum.feature.codex.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.git.GitClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 受限执行的命令安全闸门。
 *
 * <h3>这是整个方案风险最高的一环</h3>
 * <p>它在用户自己的机器上执行真实命令。因此设计立场是
 * <strong>白名单而非黑名单</strong>：黑名单永远列不全（{@code rm} 的变体、
 * 换行注入、环境变量展开、别名……），只要漏一个就是完全绕过。
 * 白名单则相反——漏了只会导致某条合法命令跑不了，代价是「不够方便」而非「被攻破」。</p>
 *
 * <h3>五道闸门中的第 2、3 道</h3>
 * <p>本类负责：命令白名单 + 路径沙箱。另外三道分别是：
 * mode 隔离（{@code AgentMode.VERIFY} + 方案 K 执行层强制）、
 * 人工确认（{@code requiresConfirm} + 禁止 auto-approve）、
 * 运行时限制（超时 / 输出截断 / env 白名单，在 {@code CheckpointRunner}）。</p>
 *
 * <h3>如实说明隔离强度</h3>
 * <p>这里<strong>没有</strong>容器隔离。个人单机软件引入 Docker 依赖不现实，
 * 且 lab 脚本依赖本机 conda 环境，容器内跑不通。真实的风险模型是
 * <em>「在你自己机器上、经你逐次确认、跑你自己仓库里被 git 跟踪的脚本」</em>
 * ——约等于你自己在终端敲这条命令。如实说明这一点，比假装沙箱更负责。</p>
 */
@Component
public class CommandGuard {

    private static final Logger log = LoggerFactory.getLogger(CommandGuard.class);

    /**
     * shell 元字符：出现任一即拒绝。
     *
     * <p>刻意<strong>不做转义处理</strong>：转义是在猜测用户意图，而猜错的代价是
     * 执行了意料之外的命令。直接拒绝并让用户改写检验定义，是唯一安全的选择。</p>
     */
    private static final char[] SHELL_METACHARS = {
            '|', '&', ';', '>', '<', '`', '\n', '\r', '\0'
    };

    /** 命令替换与变量展开的形态。 */
    private static final String[] EXPANSION_PATTERNS = {
            "$(", "${", "$[", "<(", ">("
    };

    private final GitClient git;

    @Value("${codex.verify.allowed-executables:git,bash,sh,python,python3,cmake,ninja,make,mlir-opt,mlir-translate,iree-compile,iree-run-module,opt,llc,clang,clang++,pytest,gradle,gradlew,cargo,go,node,npm}")
    private String allowedExecutablesRaw;

    @Value("${codex.verify.allow-scripts-outside-git:false}")
    private boolean allowUntrackedScripts;

    public CommandGuard(GitClient git) {
        this.git = git;
    }

    /** 校验结果。 */
    public record Decision(boolean allowed,
                           String reason,
                           List<String> argv,
                           Path resolvedCwd) {

        public static Decision deny(String reason) {
            return new Decision(false, reason, List.of(), null);
        }

        public static Decision allow(List<String> argv, Path cwd) {
            return new Decision(true, null, argv, cwd);
        }
    }

    public Set<String> allowedExecutables() {
        Set<String> s = new LinkedHashSet<>();
        for (String x : allowedExecutablesRaw.split(",")) {
            String t = x.strip();
            if (!t.isEmpty()) s.add(t.toLowerCase());
        }
        return s;
    }

    /**
     * 校验一条命令是否可执行。
     *
     * @param rawCmd  命令串（如 {@code bash scripts/all.sh}）
     * @param relCwd  仓库内相对工作目录，可为 null
     * @param repoRoot 仓库根（已规范化的绝对路径）——沙箱边界
     */
    public Decision check(String rawCmd, String relCwd, Path repoRoot) {
        if (rawCmd == null || rawCmd.isBlank()) {
            return Decision.deny("EMPTY_COMMAND：命令为空");
        }
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return Decision.deny("BAD_REPO_ROOT：仓库根目录无效");
        }

        // ---- 闸门 2a：shell 元字符 ----
        for (char c : SHELL_METACHARS) {
            if (rawCmd.indexOf(c) >= 0) {
                return Decision.deny("REJECT_UNSAFE_COMMAND：命令含 shell 元字符 '"
                        + describeChar(c) + "'。受限执行只允许单条简单命令，"
                        + "不支持管道 / 重定向 / 串联。");
            }
        }
        for (String p : EXPANSION_PATTERNS) {
            if (rawCmd.contains(p)) {
                return Decision.deny("REJECT_UNSAFE_COMMAND：命令含变量/命令替换 '"
                        + p + "'，不予执行。");
            }
        }

        // ---- 闸门 2b：可执行文件白名单 ----
        List<String> argv = tokenize(rawCmd);
        if (argv.isEmpty()) {
            return Decision.deny("EMPTY_COMMAND：无法解析出可执行文件");
        }
        String exe = argv.get(0);
        // 带路径的可执行文件一律拒绝：只允许 PATH 中的白名单程序名，
        // 否则 ./x 或绝对路径就成了绕过白名单的通道
        if (exe.contains("/") || exe.contains("\\")) {
            return Decision.deny("REJECT_UNSAFE_COMMAND：可执行文件不得带路径（" + exe
                    + "）。只允许白名单内的程序名。");
        }
        String exeKey = exe.toLowerCase();
        if (exeKey.endsWith(".exe")) exeKey = exeKey.substring(0, exeKey.length() - 4);
        Set<String> allowed = allowedExecutables();
        if (!allowed.contains(exeKey)) {
            return Decision.deny("EXECUTABLE_NOT_ALLOWED：'" + exe
                    + "' 不在白名单内。当前允许：" + String.join(", ", allowed)
                    + "。可通过 codex.verify.allowed-executables 调整。");
        }

        // ---- 闸门 3a：工作目录沙箱 ----
        Path cwd;
        try {
            cwd = (relCwd == null || relCwd.isBlank())
                    ? repoRoot
                    : repoRoot.resolve(relCwd).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Decision.deny("BAD_CWD：工作目录无法解析（" + relCwd + "）");
        }
        // 用 realPath 解析符号链接后再比对：仅做字符串前缀判断会被符号链接绕出仓库
        Path realCwd = realPath(cwd);
        Path realRoot = realPath(repoRoot);
        if (!realCwd.startsWith(realRoot)) {
            return Decision.deny("CWD_OUTSIDE_REPO：工作目录逃出仓库范围（" + relCwd
                    + "）。受限执行只允许在仓库内运行。");
        }
        if (!Files.isDirectory(realCwd)) {
            return Decision.deny("CWD_NOT_FOUND：工作目录不存在（" + relCwd + "）");
        }

        // ---- 闸门 3b：脚本参数必须在仓库内且被 git 跟踪 ----
        for (int i = 1; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (!looksLikeScriptPath(arg)) continue;

            Path script;
            try {
                script = realCwd.resolve(arg).toAbsolutePath().normalize();
            } catch (Exception e) {
                return Decision.deny("BAD_SCRIPT_PATH：脚本路径无法解析（" + arg + "）");
            }
            Path realScript = realPath(script);
            if (!realScript.startsWith(realRoot)) {
                return Decision.deny("SCRIPT_OUTSIDE_REPO：脚本逃出仓库范围（" + arg
                        + "）。这通常意味着检验定义里的路径写错了。");
            }
            if (!Files.isRegularFile(realScript)) {
                return Decision.deny("SCRIPT_NOT_FOUND：脚本不存在（" + arg
                        + "）。可能是仓库结构变了，检验定义已失效。");
            }
            if (!allowUntrackedScripts) {
                String rel = relativize(realRoot, realScript);
                if (!git.isTracked(realRoot, rel)) {
                    // 未被 git 跟踪 = 不在版本控制内 = 无法审计其来源与变更历史
                    return Decision.deny("SCRIPT_NOT_TRACKED：脚本未被 git 跟踪（" + rel
                            + "）。只执行纳入版本控制的脚本，以便其内容可审计、可回溯。");
                }
            }
        }

        return Decision.allow(argv, realCwd);
    }

    /**
     * 极简分词：按空白切分，支持成对引号。
     *
     * <p>不实现完整 shell 词法（转义、嵌套引号、glob 展开）——
     * 因为元字符已在前一道闸门被拒，剩下的形态只有「程序名 + 简单参数」。
     * 分词器越简单，可推理性越强。</p>
     */
    public static List<String> tokenize(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 参数是否像一个脚本/文件路径（需要做沙箱校验）。 */
    private boolean looksLikeScriptPath(String arg) {
        if (arg.startsWith("-")) return false;          // 选项
        String lower = arg.toLowerCase();
        return lower.endsWith(".sh") || lower.endsWith(".py")
                || lower.endsWith(".bash") || lower.endsWith(".mlir")
                || lower.endsWith(".ll") || lower.endsWith(".c")
                || lower.endsWith(".cpp") || arg.contains("/");
    }

    private Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            // 路径不存在时 toRealPath 会抛；退回 normalize 后的绝对路径，
            // 后续的 isDirectory / isRegularFile 检查会给出准确错误
            return p.toAbsolutePath().normalize();
        }
    }

    private String relativize(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.toString();
        }
    }

    private String describeChar(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\0' -> "\\0";
            default -> String.valueOf(c);
        };
    }

    /** 供 UI / 工具展示当前白名单。 */
    public List<String> allowedExecutablesList() {
        return new ArrayList<>(allowedExecutables());
    }

    /** 供诊断：把一条命令的分词结果暴露出来。 */
    public List<String> preview(String cmd) {
        return Arrays.asList(tokenize(cmd).toArray(new String[0]));
    }
}
