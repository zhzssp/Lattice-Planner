package org.zhzssp.memorandum.agenteval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.zhzssp.memorandum.agenteval.cost.BudgetBaseline;
import org.zhzssp.memorandum.agenteval.cost.BudgetGate;
import org.zhzssp.memorandum.agenteval.cost.CostModel;
import org.zhzssp.memorandum.agenteval.cost.PriceTable;
import org.zhzssp.memorandum.agenteval.cost.UsageSnapshot;
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
     * @param elapsedMs       用例耗时（<b>本机墙钟</b>；回放下它衡量的是评测套件自身的速度，
     *                        与线上响应时间无关，别拿它当延迟指标）
     * @param usage           P5：token / 活体字符数 / 上游耗时
     * @param model           计价所用模型名（回放时取自录制盒）
     * @param recordedLatencies 录制盒里存下的<b>录制当时</b>各次调用的真实上游耗时
     */
    public void record(String caseId, int trial, CollectingTraceListener trace,
                       boolean endStateChecked, List<String> driftWarnings, long elapsedMs,
                       UsageSnapshot usage, String model, List<Long> recordedLatencies,
                       org.zhzssp.memorandum.agenteval.trace.CheckLedger ledger) {
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
                elapsedMs,
                usage == null ? UsageSnapshot.EMPTY : usage,
                model,
                new ArrayList<>(recordedLatencies),
                ledger == null ? null : ledger.partialScore(),
                ledger == null ? 0 : ledger.scoredPassed(),
                ledger == null ? 0 : ledger.scoredTotal()
        ));
    }

    /**
     * 本次运行各用例的实测预算指标（同一 caseId 的多个试次取<b>最大值</b>）。
     *
     * <p>取最大而不是平均：不同试次录到的回答长短不同，后续轮次的 prompt 也就不同。
     * 基线是一条<b>上限</b>，用平均值当上限，等于让一半的试次天然踩线。
     */
    public Map<String, Map<String, Long>> budgetActuals() {
        Map<String, Map<String, Long>> out = new java.util.TreeMap<>();
        for (CaseResult r : results) {
            Map<String, Long> m = out.computeIfAbsent(r.caseId(), k -> new LinkedHashMap<>());
            m.merge("llmCalls", r.usage().llmCalls(), Math::max);
            m.merge("requestChars", r.usage().requestChars(), Math::max);
        }
        return out;
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
                        c.driftWarnings(), c.elapsedMs(),
                        c.usage(), c.model(), c.recordedLatenciesMs(),
                        c.partialScore(), c.scoredPassed(), c.scoredTotal()));
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

        m.put("cost", cost());
        m.put("budget", budget());

        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("casesWithPromptDrift", withDrift);
        freshness.put("hint", withDrift > 0
                ? "有用例的 prompt 已变化，录制可能过期。建议重新录制：-Dagent.eval.mode=record"
                : "全部录制与当前 prompt 一致");
        m.put("cassetteFreshness", freshness);

        m.put("partialCredit", partialCredit());
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
     * <b>部分得分</b>（P6）：判定级的通过比例，而非用例级的二值成败。
     *
     * <h4>它补的是二值判定丢掉的那一半信息</h4>
     * 真实例子：{@code batch_complete_overdue_only} 挂掉的那次，
     * 三条过期任务筛得<b>全对</b>、那条不该碰的也<b>确实没碰</b>，
     * 只是最后没执行写入。二值下它和"全错"一样记 0 分，
     * 但两者的<b>改进距离天差地别</b>——而能力集要回答的恰恰是"还差多远"。
     *
     * <p>只统计<b>计分类</b>判定（端状态、轨迹契约），不含
     * "无幻觉""无工具失败"这类<b>不变量</b>：不变量错了就是错了，
     * 没有"差一点"可言，混进分母只会把刻度稀释。
     */
    private Map<String, Object> partialCredit() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<CaseResult> scored = results.stream()
                .filter(r -> r.partialScore() != null).toList();
        if (scored.isEmpty()) {
            m.put("available", false);
            m.put("hint", "本次运行没有任何计分类判定（端状态 / 轨迹契约）");
            return m;
        }
        m.put("available", true);
        m.put("meanScore", round4d(scored.stream()
                .mapToDouble(CaseResult::partialScore).average().orElse(0)));
        m.put("scoredCases", scored.size());

        // 判红但大部分判定已通过的用例：改进优先级最高的一批
        List<String> partiallyCorrect = scored.stream()
                .filter(r -> Boolean.FALSE.equals(r.passed()))
                .filter(r -> r.partialScore() >= 0.5)
                .map(r -> r.caseId() + "(" + r.scoredPassed() + "/" + r.scoredTotal() + ")")
                .distinct().toList();
        m.put("partiallyCorrect", partiallyCorrect);
        return m;
    }

    /**
     * 成本与延迟（P5）。
     *
     * <p><b>这一节的每个数字都带口径，读之前先看 {@code hint}。</b>
     * 回放模式下 token 与成本来自录制盒，是<b>录制当天</b>的开销；
     * 只有 {@code requestChars} 是当前代码算出来的活体值。
     */
    private Map<String, Object> cost() {
        PriceTable prices = PriceTable.load();
        boolean replay = !"record".equalsIgnoreCase(System.getProperty("agent.eval.mode", "replay"));

        UsageSnapshot total = results.stream()
                .map(CaseResult::usage)
                .reduce(UsageSnapshot.EMPTY, UsageSnapshot::plus);

        CostModel.CostEstimate totalCost = results.stream()
                .map(r -> CostModel.estimate(r.usage(), r.model(), prices))
                .reduce(CostModel.CostEstimate.ZERO, CostModel.CostEstimate::plus);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceTableAsOf", prices.asOf());
        m.put("llmCalls", total.llmCalls());
        m.put("callsWithUsage", total.callsWithUsage());
        // ★ 缺 usage 的调用会让成本偏低。偏低的成本指标没有门禁价值，所以必须亮出来。
        if (!total.usageComplete()) {
            m.put("usageIncompleteWarning", String.format(
                    "%d/%d 次调用没有 usage，token 与成本均<偏低>，不可用于对账",
                    total.llmCalls() - total.callsWithUsage(), total.llmCalls()));
        }

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("promptTokens", total.promptTokens());
        tokens.put("completionTokens", total.completionTokens());
        tokens.put("cacheHitTokens", total.cacheHitTokens());
        tokens.put("cacheMissTokens", total.cacheMissTokens());
        tokens.put("cacheHitRate", round4d(total.cacheHitRate()));
        // ★ 这个命中率高得不诚实，警告必须与数字同行出现，
        // 否则它一定会被当成"线上缓存命中率"引用出去。
        tokens.put("cacheHitRateCaveat",
                "★不可当作线上命中率：本批 token 录制于一次连续跑完 13 用例 × 3 试次的会话，"
                        + "各用例共用同一段 system prompt，上游缓存自始至终是热的——"
                        + "连每个用例的<第一次>调用都几乎全命中（实测 6016/6021）。"
                        + "真实用户会话是分散的，首次调用必然冷启。"
                        + "本项目架构保证的是<单轮内>前缀稳定（见 prefix_stability_within_turn），"
                        + "跨会话的热缓存是这次测量方式的产物，不是系统属性。");
        tokens.put("charsPerPromptToken", round4d(total.charsPerPromptToken()));
        tokens.put("source", replay
                ? "★录制盒（录制当天的值）——改了 prompt 这里<不会>变，看 liveSize"
                : "本次真实调用");
        m.put("tokens", tokens);

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("requestChars", total.requestChars());
        live.put("responseChars", total.responseChars());
        live.put("avgRequestCharsPerCall", total.llmCalls() == 0 ? 0
                : Math.round((double) total.requestChars() / total.llmCalls()));
        live.put("source", "本次真实发出的 messages —— 两种模式下都反映当前代码");
        m.put("liveSize", live);

        Map<String, Object> usd = new LinkedHashMap<>();
        usd.put("totalUsd", round6(totalCost.totalUsd()));
        usd.put("withoutPrefixCacheUsd", round6(totalCost.totalWithoutCacheUsd()));
        usd.put("savedByPrefixCacheUsd", round6(totalCost.savedByCacheUsd()));
        usd.put("savedShare", totalCost.totalWithoutCacheUsd() == 0 ? null
                : round4(totalCost.savedByCacheUsd() / totalCost.totalWithoutCacheUsd()));
        if (totalCost.pricedByFallback()) {
            usd.put("warning", "存在未登记单价的模型，已按最贵档高估计价");
        }
        usd.put("hint", "估算，不可对账；但可用于前后对比——门禁比的是比值，价错了会约掉");
        // savedShare 直接由 cacheHitRate 推出，同一个偏高问题会原样传导到"省了 93%"这个结论上。
        usd.put("savedShareCaveat", "★同样偏高：省下的比例由缓存命中率推出，见 tokens.cacheHitRateCaveat");
        m.put("estimatedCost", usd);

        // 延迟：只有真实联网过的那次运行才有意义
        List<Long> lat = new ArrayList<>();
        for (CaseResult r : results) lat.addAll(r.recordedLatenciesMs());
        Long liveLatency = total.upstreamLatencyMs();

        Map<String, Object> latency = new LinkedHashMap<>();
        if (!lat.isEmpty()) {
            List<Integer> sorted = lat.stream().map(Long::intValue).sorted().toList();
            latency.put("source", "录制盒中存下的录制当时真实往返耗时");
            latency.put("samples", sorted.size());
            latency.put("p50Ms", percentile(sorted, 50));
            latency.put("p95Ms", percentile(sorted, 95));
            latency.put("maxMs", sorted.get(sorted.size() - 1));
        } else if (liveLatency != null) {
            latency.put("source", "本次录制的真实往返耗时");
            latency.put("totalMs", liveLatency);
        } else {
            latency.put("source", "n/a");
            latency.put("hint", "回放不联网，本机毫秒数只反映回放速度。"
                    + "该批录制盒早于延迟字段引入，重新录制后即有数据");
        }
        m.put("upstreamLatency", latency);
        return m;
    }

    /**
     * 预算门禁汇总（P5）。
     *
     * <p>判红发生在<b>各用例自己的 tearDown</b> 里，不在这里——
     * 报告是 JVM 退出时才产出的，那时早已没有任何测试可以被判失败。
     * 这里只做全局视图：哪些用例超了、哪些基线该往下收。
     */
    private Map<String, Object> budget() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Map<String, Long>> actual = budgetActuals();

        if (BudgetBaseline.isWriteMode()) {
            BudgetBaseline.write(actual, System.getProperty("agent.eval.mode", "replay"));
            m.put("mode", "write");
            m.put("hint", "已重写预算基线，请 git diff 确认涨幅合理后提交");
            return m;
        }

        Map<String, Map<String, Long>> baseline = BudgetBaseline.load();
        if (baseline.isEmpty()) {
            m.put("mode", "absent");
            m.put("hint", "尚无预算基线。跑一次 -Dagent.eval.budget=write 生成并提交");
            return m;
        }

        List<BudgetGate.Verdict> verdicts = BudgetGate.check(baseline, actual);
        m.put("mode", "check");
        m.put("gatedMetrics", BudgetBaseline.GATED_METRICS);
        m.put("overruns", verdicts.stream().filter(v -> v.status() == BudgetGate.Status.OVER).toList());
        m.put("staleBaselines", verdicts.stream().filter(v -> v.status() == BudgetGate.Status.STALE).toList());
        m.put("untracked", verdicts.stream().filter(v -> v.status() == BudgetGate.Status.UNTRACKED)
                .map(BudgetGate.Verdict::caseId).distinct().toList());
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

        Map<String, Object> ps = (Map<String, Object>) r.get("partialCredit");
        if (Boolean.TRUE.equals(ps.get("available"))) {
            sb.append(String.format("  平均部分得分  %-8s 判定级通过比例（二值判定答不了「还差多远」）%n",
                    pct(ps.get("meanScore"))));
            List<?> partial = (List<?>) ps.get("partiallyCorrect");
            if (partial != null && !partial.isEmpty()) {
                sb.append("  ◐ 差一点      ").append(partial)
                        .append("\n                （判红，但多数判定已通过——离做对最近的那些）\n");
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

        printCost(sb, (Map<String, Object>) r.get("cost"), (Map<String, Object>) r.get("budget"));

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

    /**
     * 成本区的控制台呈现。
     *
     * <p>刻意把「活体尺寸」排在「token」<b>前面</b>：token 那一行在回放下是历史值，
     * 而先映入眼帘的数字最容易被当成结论。把当前有效的量放前面，是一种排版层面的防误读。
     */
    @SuppressWarnings("unchecked")
    private void printCost(StringBuilder sb, Map<String, Object> cost, Map<String, Object> budget) {
        if (cost == null) return;
        sb.append("──────────────────────────────────────────────────────────────\n");

        Map<String, Object> live = (Map<String, Object>) cost.get("liveSize");
        sb.append(String.format("  prompt 活体   %s 字符（均 %s/次）  ← 当前代码，门禁看这个%n",
                live.get("requestChars"), live.get("avgRequestCharsPerCall")));

        Map<String, Object> tk = (Map<String, Object>) cost.get("tokens");
        Object cpt = tk.get("charsPerPromptToken");
        sb.append(String.format("  token         输入 %s（命中缓存 %s）／输出 %s%n",
                tk.get("promptTokens"), pct(tk.get("cacheHitRate")), tk.get("completionTokens")));
        if (cpt != null) {
            sb.append(String.format("                实测 %.2f 字符 ≈ 1 输入 token%n",
                    ((Number) cpt).doubleValue()));
        }
        Object incomplete = cost.get("usageIncompleteWarning");
        if (incomplete != null) {
            sb.append("  ⚠ ").append(incomplete).append('\n');
        }

        Map<String, Object> usd = (Map<String, Object>) cost.get("estimatedCost");
        sb.append(String.format("  成本估算      $%s（若无前缀缓存则 $%s，省下 %s）%n",
                usd.get("totalUsd"), usd.get("withoutPrefixCacheUsd"), pct(usd.get("savedShare"))));
        if (tk.get("cacheHitRateCaveat") != null) {
            sb.append("                ★命中率与省下比例均偏高，口径见 report.json 的 cacheHitRateCaveat\n");
        }

        Map<String, Object> lat = (Map<String, Object>) cost.get("upstreamLatency");
        if (lat.get("p50Ms") != null) {
            sb.append(String.format("  上游延迟      P50 %s ms / P95 %s ms（录制当时，%s 次采样）%n",
                    lat.get("p50Ms"), lat.get("p95Ms"), lat.get("samples")));
        } else {
            sb.append("  上游延迟      n/a（回放不联网）\n");
        }

        if (budget == null) return;
        Object mode = budget.get("mode");
        if ("check".equals(mode)) {
            List<?> over = (List<?>) budget.get("overruns");
            List<?> stale = (List<?>) budget.get("staleBaselines");
            List<?> untracked = (List<?>) budget.get("untracked");
            sb.append(String.format("  预算门禁      超支 %d 项%s，基线偏高 %d 项，未登记 %d 个用例%n",
                    over.size(), over.isEmpty() ? "" : "  ★必须为 0",
                    stale.size(), untracked.size()));
        } else {
            sb.append("  预算门禁      ").append(budget.get("hint")).append('\n');
        }
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

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    /** null 要原样传下去：它表示「测不出」，与 0.0（「测出来是零」）是两回事。 */
    private static Double round4d(Double v) {
        return v == null ? null : round4(v);
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

    /** null 渲染为 n/a 而不是 0.0%——「测不出」和「测出来是零」必须看得出区别。 */
    private static String pct(Object v) {
        if (!(v instanceof Number n)) return "n/a";
        return String.format("%.1f%%", n.doubleValue() * 100);
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
            long elapsedMs,
            /** P5：token（回放时为录制值）与活体字符数。 */
            UsageSnapshot usage,
            /** 计价用模型名。 */
            String model,
            /** 录制当时的真实上游耗时；回放旧盒子时为空。 */
            List<Long> recordedLatenciesMs,
            /**
             * P6 部分得分 [0,1]：计分判定的通过比例；无计分项时为 null（记 n/a）。
             *
             * <p>它回答二值判定答不了的那个问题：<b>还差多远</b>。
             * "筛选全对但一个都没写"和"全错"在二值下都是 0，改进距离却天差地别。
             */
            Double partialScore,
            /** 计分判定：通过数 / 总数。 */
            int scoredPassed,
            int scoredTotal
    ) {}
}
