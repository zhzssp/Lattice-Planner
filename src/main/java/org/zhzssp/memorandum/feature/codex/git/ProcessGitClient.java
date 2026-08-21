package org.zhzssp.memorandum.feature.codex.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通过子进程调用系统 {@code git} 的实现。
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><strong>永不拼接 shell 字符串</strong>：全部走 {@link ProcessBuilder} 的数组形式，
 *       因此参数中的空格、引号、{@code &&}、{@code $()} 都只会被当作字面量，
 *       不存在命令注入面。</li>
 *   <li><strong>逐条超时</strong>：超时即 {@code destroyForcibly}，避免挂死的 git 进程
 *       （如等待凭证输入）拖住线程池。</li>
 *   <li><strong>禁用交互式凭证提示</strong>：设置 {@code GIT_TERMINAL_PROMPT=0}，
 *       否则私有仓库会让 git 卡在等待用户名输入直到超时。</li>
 * </ul>
 *
 * <h3>可降级</h3>
 * <p>{@link #available()} 为 false 时（未装 git / 不在 PATH），
 * 所有 Codex 功能优雅停用并给出明确提示，<strong>不影响任务/目标/笔记等既有功能</strong>。</p>
 */
@Component
public class ProcessGitClient implements GitClient {

    private static final Logger log = LoggerFactory.getLogger(ProcessGitClient.class);

    /** git log 的定制格式：sha \u001f author \u001f unix 时间 \u001f 标题。用 0x1F 分隔避免与内容冲突。 */
    private static final String LOG_FORMAT = "%H%x1f%an%x1f%at%x1f%s";

    private final String gitExecutable;
    private final int timeoutSeconds;
    private final int cloneTimeoutSeconds;

    private volatile Boolean availableCache;
    private volatile String versionCache;

    public ProcessGitClient(@Value("${codex.git.executable:git}") String gitExecutable,
                            @Value("${codex.git.timeout-seconds:60}") int timeoutSeconds,
                            @Value("${codex.git.clone-timeout-seconds:300}") int cloneTimeoutSeconds) {
        this.gitExecutable = (gitExecutable == null || gitExecutable.isBlank()) ? "git" : gitExecutable;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.cloneTimeoutSeconds = Math.max(30, cloneTimeoutSeconds);
    }

    @Override
    public boolean available() {
        Boolean cached = availableCache;
        if (cached != null) return cached;
        try {
            Result r = exec(null, timeoutSeconds, "--version");
            boolean ok = r.exitCode() == 0;
            versionCache = ok ? r.stdout().trim() : null;
            availableCache = ok;
            if (ok) {
                log.info("[Codex] git 可用：{}", versionCache);
            } else {
                log.warn("[Codex] git 不可用（exit={}），Codex 功能将停用", r.exitCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("[Codex] git 不可用（{}），Codex 功能将停用。请确认已安装 git 并在 PATH 中。",
                    e.getMessage());
            availableCache = false;
            return false;
        }
    }

    @Override
    public String version() {
        if (versionCache == null) available();
        return versionCache == null ? "unavailable" : versionCache;
    }

    @Override
    public boolean isRepository(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try {
            Result r = exec(dir, timeoutSeconds, "rev-parse", "--is-inside-work-tree");
            return r.exitCode() == 0 && r.stdout().trim().equals("true");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String currentBranch(Path repo) {
        Result r = mustExec(repo, timeoutSeconds, "rev-parse", "--abbrev-ref", "HEAD");
        return r.stdout().trim();
    }

    @Override
    public String headSha(Path repo) {
        Result r = mustExec(repo, timeoutSeconds, "rev-parse", "HEAD");
        return r.stdout().trim();
    }

    @Override
    public WorkingStatus status(Path repo) {
        Result r = mustExec(repo, timeoutSeconds, "status", "--porcelain");
        List<String> dirty = new ArrayList<>();
        for (String line : r.stdout().split("\\R")) {
            if (line == null || line.isBlank()) continue;
            // porcelain 格式：XY <path>；截掉前 3 列状态标志
            String p = line.length() > 3 ? line.substring(3).trim() : line.trim();
            // 处理带引号的路径（含非 ASCII 时 git 会加引号转义）
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() > 1) {
                p = p.substring(1, p.length() - 1);
            }
            dirty.add(p);
        }
        String branch;
        String head;
        try {
            branch = currentBranch(repo);
        } catch (Exception e) {
            branch = "HEAD";
        }
        try {
            head = headSha(repo);
        } catch (Exception e) {
            head = null;   // 空仓库（无任何提交）时 rev-parse HEAD 会失败，属正常情况
        }
        return new WorkingStatus(branch, head, dirty.isEmpty(), dirty);
    }

    @Override
    public String blobHash(Path repo, Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            Result r = exec(repo, timeoutSeconds, "hash-object", file.toAbsolutePath().toString());
            return r.exitCode() == 0 ? r.stdout().trim() : null;
        } catch (Exception e) {
            log.debug("[Codex] hash-object 失败 {}：{}", file, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> blobHashes(Path repo, List<Path> files) {
        if (files == null || files.isEmpty()) return List.of();
        // 一次进程调用处理全部文件：61 个文件逐个 fork 进程会有显著开销（Windows 上尤甚）
        List<String> args = new ArrayList<>(files.size() + 2);
        args.add("hash-object");
        args.add("--stdin-paths");
        StringBuilder in = new StringBuilder();
        for (Path f : files) {
            in.append(f.toAbsolutePath()).append('\n');
        }
        try {
            Result r = exec(repo, timeoutSeconds, in.toString(), args.toArray(new String[0]));
            if (r.exitCode() != 0) return fallbackHashes(repo, files);
            List<String> out = new ArrayList<>(files.size());
            for (String line : r.stdout().split("\\R")) {
                if (line != null && !line.isBlank()) out.add(line.trim());
            }
            // 行数必须与输入一致，否则对应关系错乱，宁可退回逐个计算
            if (out.size() != files.size()) {
                log.debug("[Codex] hash-object 批量结果行数 {} != 输入 {}，退回逐个计算",
                        out.size(), files.size());
                return fallbackHashes(repo, files);
            }
            return out;
        } catch (Exception e) {
            log.debug("[Codex] hash-object 批量失败，退回逐个计算：{}", e.getMessage());
            return fallbackHashes(repo, files);
        }
    }

    private List<String> fallbackHashes(Path repo, List<Path> files) {
        List<String> out = new ArrayList<>(files.size());
        for (Path f : files) out.add(blobHash(repo, f));
        return out;
    }

    @Override
    public CommitInfo lastCommit(Path repo, String relativePath) {
        try {
            Result r = exec(repo, timeoutSeconds,
                    "log", "-1", "--format=" + LOG_FORMAT, "--", relativePath);
            if (r.exitCode() != 0) return null;
            return parseLogLine(r.stdout().trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Map<String, CommitInfo> lastCommits(Path repo, List<String> relativePaths) {
        Map<String, CommitInfo> out = new LinkedHashMap<>();
        if (relativePaths == null || relativePaths.isEmpty()) return out;
        // git 没有「一次查多个路径各自末次提交」的原语；
        // 遍历全部提交的 name-only 输出一次扫完，比 N 次 git log 快一个数量级。
        try {
            Result r = exec(repo, timeoutSeconds,
                    "log", "--format=" + LOG_FORMAT, "--name-only", "--no-renames");
            if (r.exitCode() != 0) return out;
            CommitInfo current = null;
            for (String raw : r.stdout().split("\\R")) {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.indexOf('\u001f') >= 0) {
                    current = parseLogLine(line);
                } else if (current != null) {
                    // 首次出现即末次提交（git log 默认逆序），已存在则不覆盖
                    out.putIfAbsent(line, current);
                }
            }
        } catch (Exception e) {
            log.debug("[Codex] 批量 git log 失败：{}", e.getMessage());
        }
        return out;
    }

    private CommitInfo parseLogLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\u001f", -1);
        if (parts.length < 4) return null;
        LocalDateTime ts;
        try {
            ts = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(parts[2].trim())), ZoneId.systemDefault());
        } catch (Exception e) {
            ts = null;
        }
        return new CommitInfo(parts[0].trim(), parts[1].trim(), ts, parts[3].trim());
    }

    @Override
    public List<String> listTrackedFiles(Path repo) {
        try {
            Result r = exec(repo, timeoutSeconds, "ls-files");
            if (r.exitCode() != 0) return List.of();
            List<String> out = new ArrayList<>();
            for (String line : r.stdout().split("\\R")) {
                if (line != null && !line.isBlank()) out.add(line.trim());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public boolean isTracked(Path repo, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return false;
        try {
            Result r = exec(repo, timeoutSeconds, "ls-files", "--error-unmatch", relativePath);
            return r.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String pull(Path repo) {
        // --ff-only：拒绝产生 merge commit。若远端与本地分叉，宁可失败并让用户手动处理，
        // 也不要让软件擅自 merge——那会污染用户的提交历史。
        mustExec(repo, cloneTimeoutSeconds, "pull", "--ff-only");
        return headSha(repo);
    }

    @Override
    public void clone(String remoteUrl, Path targetDir) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new GitCommandException("远端地址为空", 1, null);
        }
        Path parent = targetDir.getParent();
        try {
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new GitCommandException("创建目标目录失败", e);
        }
        mustExec(parent, cloneTimeoutSeconds, "clone", remoteUrl, targetDir.toAbsolutePath().toString());
    }

    /* ---------------- 进程执行 ---------------- */

    private record Result(int exitCode, String stdout, String stderr) {}

    private Result mustExec(Path cwd, int timeout, String... args) {
        try {
            Result r = exec(cwd, timeout, args);
            if (r.exitCode() != 0) {
                throw new GitCommandException("git " + String.join(" ", args) + " 失败",
                        r.exitCode(), r.stderr());
            }
            return r;
        } catch (GitCommandException e) {
            throw e;
        } catch (Exception e) {
            throw new GitCommandException("git " + String.join(" ", args) + " 异常", e);
        }
    }

    private Result exec(Path cwd, int timeout, String... args) throws Exception {
        return exec(cwd, timeout, null, args);
    }

    private Result exec(Path cwd, int timeout, String stdin, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add(gitExecutable);
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd.toFile());
        // 关键：禁止 git 弹交互式凭证提示，否则私有仓库会卡到超时
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        // 统一输出语言，避免按 locale 解析 git 文案出错
        pb.environment().put("LC_ALL", "C");

        Process p = pb.start();
        if (stdin != null) {
            try (var os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } else {
            p.getOutputStream().close();
        }

        // 必须并发读 stdout/stderr：git 输出量大时（如 ls-files）
        // 只读一个流会让另一个管道缓冲区填满，进程永久阻塞
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        Thread errReader = new Thread(() -> drain(p.getErrorStream(), errBuf), "git-stderr");
        errReader.setDaemon(true);
        errReader.start();
        drain(p.getInputStream(), outBuf);

        boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new GitCommandException("git " + String.join(" ", args)
                    + " 超时（" + timeout + "s）", -1, null);
        }
        errReader.join(1000);
        return new Result(p.exitValue(), outBuf.toString(), errBuf.toString());
    }

    private void drain(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // 进程被强杀时读流会异常，属预期
        }
    }
}
