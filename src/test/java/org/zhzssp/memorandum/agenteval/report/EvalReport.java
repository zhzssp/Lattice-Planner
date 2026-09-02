package org.zhzssp.memorandum.agenteval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.zhzssp.memorandum.agenteval.trace.CollectingTraceListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 评测报告聚合器（进程内单例）。
 *
 * <p>每个用例结束时调用 {@link #record}，JVM 退出前由 shutdown hook 输出：
 * <ul>
 *   <li>控制台可读表格——本地开发时直接看</li>
 *   <li>{@code build/agent-eval/report.json}——CI 归档、趋势对比</li>
 * </ul>
 *
 * <p>用单例而非 JUnit Extension，是为了让报告能跨多个测试类聚合
 * （评测套件可能拆成多个 class）。
 */
public final class EvalReport {

    public static final EvalReport INSTANCE = new EvalReport();

    private static final Path OUTPUT = Paths.get("build", "agent-eval", "report.json");
    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<CaseResult> results = new CopyOnWriteArrayList<>();

    /**
     * 轨迹指标的中转站，键为 {@code caseId#trial}。
     *
     * <p>需要中转是因为时序：轨迹指标在<b>测试体内</b>由
     * {@code TrajectoryAssert.matchesGolden} 算出，而 {@link CaseResult} 要到
     * {@code @AfterEach} 才创建。这里先存后取，避免为了传一个对象再引入一条 ThreadLocal。
     */
    private final java.util.Map<String, TrajectoryMetrics> pendingTrajectory =
            new java.util.concurrent.ConcurrentHashMap<>();

    private EvalReport() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::emit, "agent-eval-report"));
    }

    /**
     * 记录一个用例的结果。
     *
     * @param caseId          用例 id
     * @param trial           试次序号（从 0 起）。单次运行恒为 0；
     *                        多试次（pass^k）时同一 caseId 会有多条记录，靠它区分。
     *                        聚合必须按 {@code (caseId, trial)} 分组，
     *                        否则"同一任务 k 次是否全过"根本无从计算。
     * @param trace           轨迹
     * @param endStateChecked 本用例是否做过端状态断言。为 false 说明该用例
     *                        仍只验证了"工具被调用过"，结论强度有限，报告里要标出来
     * @param driftWarnings   prompt 漂移警告
     * @param elapsedMs       用例耗时
     */
    public void record(String caseId, int trial, CollectingTraceListener trace,
                       boolean endStateChecked, List<String> driftWarnings, long elapsedMs) {
        results.add(new CaseResult(
                caseId,
                trial,
                null,               // 成败稍后由 markOutcome 回填
                pendingTrajectory.remove(key(caseId, trial)),
                trace.converged(),
                trace.isExhausted(),
                trace.llmFailure(),
                trace.usedSteps(),
                trace.llmCallCount(),
                new ArrayList<>(trace.toolSequence()),
                new ArrayList<>(trace.hallucinatedTools()),
                new ArrayList<>(trace.failedTools()),
                endStateChecked,
                new ArrayList<>(driftWarnings),
                elapsedMs
        ));
    }

    /** 登记本用例的轨迹指标，由 {@code TrajectoryAssert.matchesGolden} 在测试体内调用。 */
    public void recordTrajectory(String caseId, int trial, TrajectoryMetrics metrics) {
        pendingTrajectory.put(key(caseId, trial), metrics);
    }

    private static String key(String caseId, int trial) {
        return caseId + "#" + trial;
    }

    /**
     * 回填某次试验的最终成败。
     *
     * <p>必须晚于 {@link #record}：断言是否通过只有 JUnit 生命周期结束后才知道，
     * 而 {@code record} 发生在 {@code @AfterEach} 里，那时测试体刚跑完但结论未定。
     */
    public void markOutcome(String caseId, int trial, boolean passed) {
        for (int i = 0; i < results.size(); i++) {
            CaseResult c = results.get(i);
            if (c.caseId().equals(caseId) && c.trial() == trial && c.passed() == null) {
                results.set(i, new CaseResult(
                        c.caseId(), c.trial(), passed, c.trajectory(), c.converged(), c.exhausted(),
                        c.llmFailure(), c.usedSteps(), c.llmCalls(), c.toolSequence(),
                        c.hallucinatedTools(), c.failedTools(), c.endStateChecked(),
                        c.driftWarnings(), c.elapsedMs()));
                return;
            }
        }
    }

    /* ---- 汇总指标 ---- */

    private Map<String, Object> aggregate() {
        int total = results.size();
        long converged = results.stream().filter(CaseResult::converged).count();
        long exhausted = results.stream().filter(CaseResult::exhausted).count();
        long llmFailed = results.stream().filter(r -> r.llmFailure() != null).count();
        long withHallucination = results.stream().filter(r -> !r.hallucinatedTools().isEmpty()).count();
        long withToolFailure = results.stream().filter(r -> !r.failedTools().isEmpty()).count();
        long withDrift = results.stream().filter(r -> !r.driftWarnings().isEmpty()).count();

        long totalToolCalls = results.stream().mapToLong(r -> r.toolSequence().size()).sum();
        long totalHallucinations = results.stream().mapToLong(r -> r.hallucinatedTools().size()).sum();
        long totalLlmCalls = results.stream().mapToLong(CaseResult::llmCalls).sum();

        List<Integer> steps = results.stream()
                .filter(CaseResult::converged)
                .map(CaseResult::usedSteps)
                .sorted()
                .toList();

        // 按用例分组：一个 caseId 下可能有 k 条试次记录
        Map<String, List<CaseResult>> byCase = new LinkedHashMap<>();
        for (CaseResult r : results) {
            byCase.computeIfAbsent(r.caseId(), x -> new ArrayList<>()).add(r);
        }
        int maxTrials = byCase.values().stream().mapToInt(List::size).max().orElse(1);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generatedAt", LocalDateTime.now().toString());
        m.put("mode", System.getProperty("agent.eval.mode", "replay"));
        m.put("distinctCases", byCase.size());
        m.put("trialsPerCase", maxTrials);
        m.put("totalCases", total);
        m.put("reliability", reliability(byCase, maxTrials));

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("converged", converged);
        outcome.put("stepsExhausted", exhausted);
        outcome.put("llmFailed", llmFailed);
        outcome.put("convergenceRate", rate(converged, total));
        m.put("outcome", outcome);

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("casesWithHallucination", withHallucination);
        quality.put("totalHallucinations", totalHallucinations);
        // 幻觉率 = 编造的工具调用 / (真实工具调用 + 编造的)
        quality.put("hallucinationRate", rate(totalHallucinations, totalToolCalls + totalHallucinations));
        quality.put("casesWithToolFailure", withToolFailure);
        m.put("quality", quality);

        // 断言强度：有多少用例真正校验了「世界被改成什么样」，而不只是「工具被调用过」。
        // 这个数字低，意味着上面那些漂亮的收敛率/幻觉率其实证明不了多少东西。
        long endStateChecked = results.stream().filter(CaseResult::endStateChecked).count();
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put("casesWithEndStateAssertion", endStateChecked);
        strength.put("endStateCoverage", rate(endStateChecked, total));
        strength.put("casesTrajectoryOnly", total - endStateChecked);
        strength.put("hint", endStateChecked == total
                ? "全部用例均已校验数据库末态"
                : "存在仅验证轨迹的用例，其结论仅能说明「工具被调用过」，不能说明「事情被做对」");
        m.put("assertionStrength", strength);

        m.put("toolSelection", toolSelection());

        Map<String, Object> efficiency = new LinkedHashMap<>();
        efficiency.put("totalToolCalls", totalToolCalls);
        efficiency.put("totalLlmCalls", totalLlmCalls);
        efficiency.put("avgLlmCallsPerCase", total == 0 ? 0.0 : round2((double) totalLlmCalls / total));
        efficiency.put("stepsP50", percentile(steps, 50));
        efficiency.put("stepsP95", percentile(steps, 95));
        efficiency.put("stepsMax", steps.isEmpty() ? 0 : steps.get(steps.size() - 1));
        m.put("efficiency", efficiency);

        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("casesWithPromptDrift", withDrift);
        freshness.put("hint", withDrift > 0
                ? "有用例的 prompt 已变化，录制可能过期。建议重新录制：-Dagent.eval.mode=record"
                : "全部录制与当前 prompt 一致");
        m.put("cassetteFreshness", freshness);

        m.put("cases", results);
        return m;
    }

    /**
     * 可靠性指标：{@code pass@k} 与 {@code pass^k}。
     *
     * <p>设用例 i 在 k 次试验中通过 c_i 次：
     * <pre>
     *   pass@k = |{ i : c_i ≥ 1 }| / N    能力：至少成功一次
     *   pass^k = |{ i : c_i = k }| / N    可靠性：每次都成功
     * </pre>
     *
     * <p><b>两者必须同时报出。</b>只报 pass@k 是粉饰：
     * 单次成功率 75% 的 Agent，pass@3 高达 98.4%，而 pass^3 只有 42%。
     * 对一个要替用户改数据的助手来说，后者才是它真实的样子。
     * 两者之差即<b>不稳定度</b>。
     */
    private Map<String, Object> reliability(Map<String, List<CaseResult>> byCase, int maxTrials) {
        Map<String, Object> m = new LinkedHashMap<>();

        long withOutcome = results.stream().filter(r -> r.passed() != null).count();
        if (withOutcome == 0) {
            m.put("available", false);
            m.put("hint", "未捕获用例成败（用例需标注 @EvalTrial 才会回填），"
                    + "本次仅能报收敛率，无法计算 pass@k / pass^k");
            return m;
        }

        Map<String, List<Boolean>> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, List<CaseResult>> e : byCase.entrySet()) {
            outcomes.put(e.getKey(), e.getValue().stream()
                    .map(r -> Boolean.TRUE.equals(r.passed()))
                    .toList());
        }
        ReliabilityMetrics rm = ReliabilityMetrics.of(outcomes);

        m.put("available", true);
        m.put("k", maxTrials);
        m.put("passAtK", rm.passAtK());
        m.put("passHatK", rm.passHatK());
        // 不稳定度：有时过有时不过的用例占比。这才是"能力"与"可靠"之间的差
        m.put("instability", rm.instability());
        m.put("flakyCases", rm.flakyCases());
        if (maxTrials == 1) {
            m.put("hint", "k=1 时 pass@k 与 pass^k 必然相等，测不出稳定性。"
                    + "用 -Dagent.eval.trials=3 才有意义（需已录制 3 次试验）");
        }
        return m;
    }

    /**
     * 工具选择质量汇总。
     *
     * <p>顺序一致性只在<b>声明了参考顺序</b>的用例上平均，并单独报出参与用例数——
     * 把单步用例的 n/a 当成满分混进去，会稀释出一个虚高的分数。
     */
    private Map<String, Object> toolSelection() {
        List<TrajectoryMetrics> ms = results.stream()
                .map(CaseResult::trajectory)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("casesWithGoldenTask", ms.size());
        if (ms.isEmpty()) {
            m.put("hint", "没有用例声明 GoldenTask，无法度量工具选择质量");
            return m;
        }

        m.put("precision", round4(ms.stream().mapToDouble(TrajectoryMetrics::precision).average().orElse(0)));
        m.put("recall", round4(ms.stream().mapToDouble(TrajectoryMetrics::recall).average().orElse(0)));
        m.put("totalRedundantCalls", ms.stream().mapToInt(TrajectoryMetrics::redundantCalls).sum());
        // 禁用工具被调用：任何非 0 都应判红，这里汇总只为一眼可见
        m.put("totalForbiddenHits", ms.stream().mapToInt(TrajectoryMetrics::forbiddenHits).sum());

        List<TrajectoryMetrics> ordered = ms.stream()
                .filter(x -> x.kendallTau() != null).toList();
        m.put("orderedCases", ordered.size());
        m.put("kendallTau", ordered.isEmpty() ? null
                : round4(ordered.stream().mapToDouble(TrajectoryMetrics::kendallTau).average().orElse(0)));
        if (ordered.isEmpty()) {
            m.put("orderHint", "无用例声明参考顺序（多数任务是单步的，顺序一致性不适用）");
        }
        return m;
    }

    /* ---- 输出 ---- */

    private void emit() {
        if (results.isEmpty()) return;
        Map<String, Object> report = aggregate();
        printConsole(report);
        writeJson(report);
    }

    @SuppressWarnings("unchecked")
    private void printConsole(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("══════════════════════════════════════════════════════════════\n");
        sb.append("  Agent 评测报告   mode=").append(r.get("mode"))
          .append("   用例=").append(r.get("distinctCases"))
          .append(" × 试次=").append(r.get("trialsPerCase")).append("\n");
        sb.append("══════════════════════════════════════════════════════════════\n");

        Map<String, Object> rel = (Map<String, Object>) r.get("reliability");
        if (Boolean.TRUE.equals(rel.get("available"))) {
            int k = (int) rel.get("k");
            sb.append(String.format("  pass@%-2d       %-8s 能力：至少成功一次%n",
                    k, pct(rel.get("passAtK"))));
            sb.append(String.format("  pass^%-2d       %-8s 可靠：k 次全部成功%n",
                    k, pct(rel.get("passHatK"))));
            sb.append(String.format("  不稳定度      %-8s %s%n", pct(rel.get("instability")),
                    k == 1 ? "(k=1，测不出稳定性)" : ""));
            List<?> flaky = (List<?>) rel.get("flakyCases");
            if (flaky != null && !flaky.isEmpty()) {
                sb.append("  ⚠ 时好时坏    ").append(flaky).append('\n');
            }
            sb.append("──────────────────────────────────────────────────────────────\n");
        }

        Map<String, Object> outcome = (Map<String, Object>) r.get("outcome");
        sb.append(String.format("  收敛率        %-8s (收敛 %s / 步数耗尽 %s / LLM失败 %s)%n",
                pct(outcome.get("convergenceRate")), outcome.get("converged"),
                outcome.get("stepsExhausted"), outcome.get("llmFailed")));

        Map<String, Object> quality = (Map<String, Object>) r.get("quality");
        sb.append(String.format("  工具幻觉率    %-8s (%s 次编造 / %s 个用例受影响)%n",
                pct(quality.get("hallucinationRate")), quality.get("totalHallucinations"),
                quality.get("casesWithHallucination")));
        sb.append(String.format("  含工具失败    %s 个用例%n", quality.get("casesWithToolFailure")));

        Map<String, Object> strength = (Map<String, Object>) r.get("assertionStrength");
        sb.append(String.format("  端状态覆盖    %-8s (%s 个用例校验了库末态 / %s 个仅验轨迹)%n",
                pct(strength.get("endStateCoverage")), strength.get("casesWithEndStateAssertion"),
                strength.get("casesTrajectoryOnly")));

        Map<String, Object> ts = (Map<String, Object>) r.get("toolSelection");
        Object golden = ts.get("casesWithGoldenTask");
        if (golden instanceof Number gn && gn.intValue() > 0) {
            sb.append("──────────────────────────────────────────────────────────────\n");
            sb.append(String.format("  工具选择精确率 %-7s 召回率 %-7s (%s 个用例声明了契约)%n",
                    fmt(ts.get("precision")), fmt(ts.get("recall")), golden));
            sb.append(String.format("  冗余调用      %-8s 禁用工具命中 %s%s%n",
                    ts.get("totalRedundantCalls"), ts.get("totalForbiddenHits"),
                    toInt(ts.get("totalForbiddenHits")) > 0 ? "  ★必须为 0" : ""));
            Object tau = ts.get("kendallTau");
            sb.append(String.format("  顺序一致性 τ  %-8s (%s 个多步用例参与)%n",
                    tau == null ? "n/a" : fmt(tau), ts.get("orderedCases")));
        }

        Map<String, Object> eff = (Map<String, Object>) r.get("efficiency");
        sb.append(String.format("  步数 P50/P95  %s / %s   最大 %s%n",
                eff.get("stepsP50"), eff.get("stepsP95"), eff.get("stepsMax")));
        sb.append(String.format("  LLM 调用      共 %s 次，均 %s 次/用例%n",
                eff.get("totalLlmCalls"), eff.get("avgLlmCallsPerCase")));

        Map<String, Object> fresh = (Map<String, Object>) r.get("cassetteFreshness");
        Object drift = fresh.get("casesWithPromptDrift");
        if (drift instanceof Number n && n.intValue() > 0) {
            sb.append(String.format("  ⚠ 录制漂移    %s 个用例%n", drift));
        }

        sb.append("──────────────────────────────────────────────────────────────\n");
        sb.append("  逐用例明细\n");
        for (CaseResult c : results) {
            // 断言结论优先于收敛状态。「收敛」只说明主循环正常退出，
            // 不代表它做对了事——一个收敛但断言没过的用例若显示 PASS，
            // 报告自己就成了假绿的来源。
            String status = Boolean.FALSE.equals(c.passed()) ? "FAIL"
                    : c.exhausted() ? "EXHAUST"
                    : c.converged() ? "PASS" : "FAIL";
            // 末态已校验的用例标 [E]，一眼能看出哪些用例的结论是"强"的
            String label = truncate(c.caseId(), 30) + (c.trial() > 0 ? "#" + c.trial() : "");
            sb.append(String.format("   %-7s %-3s %-32s steps=%-3s llm=%-3s tools=%s%n",
                    status, c.endStateChecked() ? "[E]" : "   ", label, c.usedSteps(), c.llmCalls(),
                    c.toolSequence().isEmpty() ? "-" : String.join("→", c.toolSequence())));
            if (!c.hallucinatedTools().isEmpty()) {
                sb.append("            ↳ 幻觉工具: ").append(c.hallucinatedTools()).append('\n');
            }
            if (!c.failedTools().isEmpty()) {
                sb.append("            ↳ 失败工具: ").append(c.failedTools()).append('\n');
            }
            if (!c.driftWarnings().isEmpty()) {
                sb.append("            ↳ 漂移: ").append(c.driftWarnings().size()).append(" 处\n");
            }
        }
        sb.append("══════════════════════════════════════════════════════════════\n");
        sb.append("  完整报告: ").append(OUTPUT.toAbsolutePath()).append('\n');
        sb.append("══════════════════════════════════════════════════════════════\n");
        System.out.println(sb);
    }

    private void writeJson(Map<String, Object> report) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            OM.writeValue(OUTPUT.toFile(), report);
        } catch (Exception e) {
            System.err.println("[AgentEval] 写报告失败：" + e.getMessage());
        }
    }

    /* ---- 工具方法 ---- */

    private static double rate(long num, long den) {
        return den == 0 ? 0.0 : Math.round((double) num / den * 10000.0) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String fmt(Object v) {
        return (v instanceof Number n) ? String.format("%.2f", n.doubleValue()) : "n/a";
    }

    private static int toInt(Object v) {
        return (v instanceof Number n) ? n.intValue() : 0;
    }

    private static String pct(Object v) {
        double d = (v instanceof Number n) ? n.doubleValue() : 0.0;
        return String.format("%.1f%%", d * 100);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * 单用例单试次的结果。字段为 public 供 Jackson 序列化。
     *
     * @param passed 用例<b>断言是否全部通过</b>，由 {@code EvalTrialExtension} 在
     *               JUnit 生命周期结束后回填。null 表示未知（未走多试次扩展）。
     *               注意它与 {@code converged} 不是一回事：Agent 可以正常收敛，
     *               但答复内容或数据库末态不对，此时 converged=true 而 passed=false。
     */
    public record CaseResult(
            String caseId,
            int trial,
            Boolean passed,
            /** 工具选择质量分解；未声明 GoldenTask 的用例为 null。 */
            TrajectoryMetrics trajectory,
            boolean converged,
            boolean exhausted,
            String llmFailure,
            int usedSteps,
            long llmCalls,
            List<String> toolSequence,
            List<String> hallucinatedTools,
            List<String> failedTools,
            boolean endStateChecked,
            List<String> driftWarnings,
            long elapsedMs
    ) {}
}
