package org.zhzssp.memorandum.feature.codex.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受限执行器（五道闸门中的第 5 道：运行时限制）。
 *
 * <h3>四项运行时约束及其理由</h3>
 * <ul>
 *   <li><strong>不经 shell</strong>：{@link ProcessBuilder} 数组形式，
 *       参数中的特殊字符只会被当字面量。配合 {@link CommandGuard} 的元字符拒绝，
 *       注入面被彻底关闭。</li>
 *   <li><strong>超时强杀</strong>：lab 脚本可能等待输入而挂死，
 *       {@code destroyForcibly} 保证不拖住线程。</li>
 *   <li><strong>输出截断并标记</strong>：编译日志动辄数十 MB。
 *       截断必须<em>可见</em>——延续项目「截断不可静默」原则。</li>
 *   <li><strong>环境变量白名单</strong>：不透传全部 env。
 *       进程环境里可能有 {@code DEEPSEEK_API_KEY} / {@code EMBED_API_KEY}，
 *       透给用户脚本等于泄漏凭证。</li>
 * </ul>
 *
 * <h3>并发上限为 1</h3>
 * <p>编译类任务本就吃满 CPU，并行跑两条只会互相拖慢并让耗时数据失去意义。
 * 更重要的是：并发执行会让「哪条检验改了工作副本」变得无法归因。</p>
 */
@Component
public class CheckpointRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckpointRunner.class);

    /**
     * 允许透传给子进程的环境变量。
     *
     * <p>{@code PATH} / {@code HOME} 等是脚本运行的最低要求；
     * conda 相关项是 lab 脚本定位工具链所必需（目标仓库的 {@code setup.sh}
     * 把 clang/mlir-opt 装进 conda 环境）。</p>
     */
    private static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "HOMEPATH", "HOMEDRIVE",
            "TMPDIR", "TEMP", "TMP", "SHELL", "TERM", "LANG", "LC_ALL",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "PROCESSOR_ARCHITECTURE",
            "CONDA_PREFIX", "CONDA_DEFAULT_ENV", "CONDA_EXE", "MAMBA_ROOT_PREFIX",
            "JAVA_HOME", "GRADLE_USER_HOME",
            "VIRTUAL_ENV", "PYTHONPATH", "PYTHONHOME",
            "CUDA_HOME", "CUDA_PATH", "LD_LIBRARY_PATH",
            "MLIR_DIR", "LLVM_DIR"
    );

    /** 执行结果。 */
    public record ExecResult(int exitCode,
                             String stdout,
                             String stderr,
                             boolean timedOut,
                             boolean truncated,
                             long durationMs,
                             String cmdExecuted,
                             String cwdExecuted) {}

    @Value("${codex.verify.timeout-seconds:600}")
    private int defaultTimeoutSeconds;

    @Value("${codex.verify.max-timeout-seconds:1800}")
    private int maxTimeoutSeconds;

    @Value("${codex.verify.output-limit-bytes:8192}")
    private int outputLimitBytes;

    @Value("${codex.verify.concurrent-limit:1}")
    private int concurrentLimit;

    /** 当前在跑的检验数（并发闸）。 */
    private final AtomicInteger running = new AtomicInteger(0);

    private final ObjectMapper om;

    public CheckpointRunner(ObjectMapper om) {
        this.om = om;
    }

    /** 是否已达并发上限。 */
    public boolean busy() {
        return running.get() >= Math.max(1, concurrentLimit);
    }

    public int defaultTimeout() {
        return defaultTimeoutSeconds;
    }

    /**
     * 执行一条已通过安全闸门的命令。
     *
     * @param argv           已分词的命令（{@link CommandGuard.Decision#argv()}）
     * @param cwd            已校验的工作目录
     * @param timeoutSeconds 超时；null 用默认值
     */
    public ExecResult run(List<String> argv, Path cwd, Integer timeoutSeconds) throws Exception {
        int timeout = clampTimeout(timeoutSeconds);
        if (running.incrementAndGet() > Math.max(1, concurrentLimit)) {
            running.decrementAndGet();
            throw new IllegalStateException("CONCURRENT_LIMIT：已有检验在运行，请等待其完成");
        }
        long t0 = System.currentTimeMillis();
        String cmdStr = String.join(" ", argv);
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(cwd.toFile());

            // 环境白名单：清空后只放回必需项，避免把 API key 泄漏给用户脚本
            Map<String, String> env = pb.environment();
            Map<String, String> keep = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (ENV_ALLOWLIST.contains(e.getKey().toUpperCase())) {
                    keep.put(e.getKey(), e.getValue());
                }
            }
            env.clear();
            env.putAll(keep);
            // 标记来源，便于用户脚本识别（也便于事后排查是谁跑的）
            env.put("LATTICE_CHECKPOINT_RUN", "1");

            log.info("[Codex/Verify] 执行：{} (cwd={}, timeout={}s)", cmdStr, cwd, timeout);
            p = pb.start();
            p.getOutputStream().close();   // 不给 stdin，避免脚本等待输入

            // 必须并发读两个流：只读一个会让另一个管道缓冲区填满而死锁
            LimitedSink outSink = new LimitedSink(outputLimitBytes);
            LimitedSink errSink = new LimitedSink(outputLimitBytes);
            final Process proc = p;
            Thread errThread = new Thread(() -> drain(proc.getErrorStream(), errSink),
                    "cp-stderr");
            errThread.setDaemon(true);
            errThread.start();
            drain(p.getInputStream(), outSink);

            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                errThread.join(1000);
                return new ExecResult(-1, outSink.text(), errSink.text(),
                        true, outSink.truncated() || errSink.truncated(),
                        System.currentTimeMillis() - t0, cmdStr, cwd.toString());
            }
            errThread.join(2000);
            return new ExecResult(p.exitValue(), outSink.text(), errSink.text(),
                    false, outSink.truncated() || errSink.truncated(),
                    System.currentTimeMillis() - t0, cmdStr, cwd.toString());
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            running.decrementAndGet();
        }
    }

    /* ---------------- 断言判定 ---------------- */

    /** 单条断言的判定结果。 */
    public record ExpectResult(String kind, String expected, String actual, boolean passed) {}

    /** 整体判定结果。 */
    public record Verdict(boolean passed, List<ExpectResult> details) {}

    /**
     * 按 {@code verify.expect} 逐条判定。
     *
     * <p>支持的断言类型刻意保持很少（{@code exit_code} /
     * {@code stdout_contains} / {@code stdout_not_contains} /
     * {@code stderr_contains} / {@code output_contains}）——
     * 断言语言越复杂，用户越容易写出「看起来在判、实际永远通过」的条件。</p>
     *
     * <p><strong>输出被截断时的处理是关键</strong>：若断言依赖 stdout 内容而输出已截断，
     * 判定结果不可信，此时标记为未通过并在 actual 里说明原因——
     * 宁可让用户重跑，也不要给一个基于不完整数据的「通过」。</p>
     */
    public Verdict judge(String verifyJson, ExecResult r) {
        List<ExpectResult> details = new ArrayList<>();
        if (r.timedOut()) {
            details.add(new ExpectResult("timeout", "在超时内结束", "超时被终止", false));
            return new Verdict(false, details);
        }

        List<JsonNode> expects = readExpects(verifyJson);
        if (expects.isEmpty()) {
            // 无声明式断言时退化为退出码判定（PARSED 来源的常态）
            boolean ok = r.exitCode() == 0;
            details.add(new ExpectResult("exit_code", "0", String.valueOf(r.exitCode()), ok));
            return new Verdict(ok, details);
        }

        boolean all = true;
        String combined = (r.stdout() == null ? "" : r.stdout())
                + "\n" + (r.stderr() == null ? "" : r.stderr());

        for (JsonNode e : expects) {
            String kind = e.path("kind").asText("");
            switch (kind) {
                case "exit_code" -> {
                    int want = e.path("value").asInt(0);
                    boolean ok = r.exitCode() == want;
                    details.add(new ExpectResult(kind, String.valueOf(want),
                            String.valueOf(r.exitCode()), ok));
                    all &= ok;
                }
                case "stdout_contains", "stderr_contains", "output_contains" -> {
                    String want = e.path("value").asText("");
                    String hay = switch (kind) {
                        case "stdout_contains" -> nz(r.stdout());
                        case "stderr_contains" -> nz(r.stderr());
                        default -> combined;
                    };
                    if (r.truncated()) {
                        details.add(new ExpectResult(kind, want,
                                "输出已被截断，无法可靠判定（请调大 codex.verify.output-limit-bytes 后重跑）",
                                false));
                        all = false;
                        break;
                    }
                    boolean ok = hay.contains(want);
                    details.add(new ExpectResult(kind, want, ok ? "命中" : "未命中", ok));
                    all &= ok;
                }
                case "stdout_not_contains" -> {
                    String want = e.path("value").asText("");
                    if (r.truncated()) {
                        // 「不包含」在截断输出上尤其危险：被截掉的部分可能正好含它
                        details.add(new ExpectResult(kind, want,
                                "输出已被截断，「不包含」类断言不可信", false));
                        all = false;
                        break;
                    }
                    boolean ok = !nz(r.stdout()).contains(want);
                    details.add(new ExpectResult(kind, "不出现 " + want,
                            ok ? "确实未出现" : "出现了", ok));
                    all &= ok;
                }
                default -> details.add(new ExpectResult(kind, "-",
                        "未知断言类型，已跳过", true));
            }
        }
        return new Verdict(all, details);
    }

    public String serializeDetails(List<ExpectResult> details) {
        try {
            return om.writeValueAsString(details);
        } catch (Exception e) {
            return null;
        }
    }

    private List<JsonNode> readExpects(String verifyJson) {
        List<JsonNode> out = new ArrayList<>();
        if (verifyJson == null || verifyJson.isBlank()) return out;
        try {
            JsonNode root = om.readTree(verifyJson);
            JsonNode arr = root.path("expect");
            if (arr.isArray()) arr.forEach(out::add);
        } catch (Exception e) {
            log.debug("[Codex/Verify] verify_json 解析失败：{}", e.getMessage());
        }
        return out;
    }

    private int clampTimeout(Integer t) {
        int v = (t == null || t <= 0) ? defaultTimeoutSeconds : t;
        return Math.min(Math.max(5, v), Math.max(30, maxTimeoutSeconds));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void drain(java.io.InputStream in, LimitedSink sink) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sink.append(line);
            }
        } catch (Exception ignored) {
            // 进程被强杀时读流会异常，属预期
        }
    }

    /** 带上限的输出收集器：超限后继续消费流但不再累积，避免内存膨胀又不阻塞进程。 */
    private static final class LimitedSink {
        private final StringBuilder sb = new StringBuilder();
        private final int limit;
        private boolean truncated;

        LimitedSink(int limit) {
            this.limit = Math.max(512, limit);
        }

        void append(String line) {
            if (sb.length() >= limit) {
                truncated = true;
                return;   // 继续读流（防死锁）但不再累积
            }
            int room = limit - sb.length();
            if (line.length() + 1 > room) {
                sb.append(line, 0, Math.max(0, room - 1)).append('\n');
                truncated = true;
            } else {
                sb.append(line).append('\n');
            }
        }

        String text() {
            return sb.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
