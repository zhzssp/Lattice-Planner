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

    private EvalReport() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::emit, "agent-eval-report"));
    }

    /**
     * 记录一个用例的结果。
     *
     * @param caseId        用例 id
     * @param trace         轨迹
     * @param driftWarnings prompt 漂移警告
     * @param elapsedMs     用例耗时
     */
    public void record(String caseId, CollectingTraceListener trace,
                       List<String> driftWarnings, long elapsedMs) {
        results.add(new CaseResult(
                caseId,
                trace.converged(),
                trace.isExhausted(),
                trace.llmFailure(),
                trace.usedSteps(),
                trace.llmCallCount(),
                new ArrayList<>(trace.toolSequence()),
                new ArrayList<>(trace.hallucinatedTools()),
                new ArrayList<>(trace.failedTools()),
                new ArrayList<>(driftWarnings),
                elapsedMs
        ));
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

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generatedAt", LocalDateTime.now().toString());
        m.put("mode", System.getProperty("agent.eval.mode", "replay"));
        m.put("totalCases", total);

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
          .append("   用例数=").append(r.get("totalCases")).append("\n");
        sb.append("══════════════════════════════════════════════════════════════\n");

        Map<String, Object> outcome = (Map<String, Object>) r.get("outcome");
        sb.append(String.format("  收敛率        %-8s (收敛 %s / 步数耗尽 %s / LLM失败 %s)%n",
                pct(outcome.get("convergenceRate")), outcome.get("converged"),
                outcome.get("stepsExhausted"), outcome.get("llmFailed")));

        Map<String, Object> quality = (Map<String, Object>) r.get("quality");
        sb.append(String.format("  工具幻觉率    %-8s (%s 次编造 / %s 个用例受影响)%n",
                pct(quality.get("hallucinationRate")), quality.get("totalHallucinations"),
                quality.get("casesWithHallucination")));
        sb.append(String.format("  含工具失败    %s 个用例%n", quality.get("casesWithToolFailure")));

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
            String status = c.converged() ? "PASS" : (c.exhausted() ? "EXHAUST" : "FAIL");
            sb.append(String.format("   %-7s %-34s steps=%-3s llm=%-3s tools=%s%n",
                    status, truncate(c.caseId(), 34), c.usedSteps(), c.llmCalls(),
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

    private static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String pct(Object v) {
        double d = (v instanceof Number n) ? n.doubleValue() : 0.0;
        return String.format("%.1f%%", d * 100);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 单用例结果。字段为 public 供 Jackson 序列化。 */
    public record CaseResult(
            String caseId,
            boolean converged,
            boolean exhausted,
            String llmFailure,
            int usedSteps,
            long llmCalls,
            List<String> toolSequence,
            List<String> hallucinatedTools,
            List<String> failedTools,
            List<String> driftWarnings,
            long elapsedMs
    ) {}
}
