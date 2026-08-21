package org.zhzssp.memorandum.feature.codex.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zhzssp.memorandum.feature.codex.entity.*;
import org.zhzssp.memorandum.feature.codex.repository.*;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 验证闭环编排服务。
 *
 * <h3>闭环形态</h3>
 * <pre>
 * Guide → 检验条目 → 【先预测（不填则 run 锁定）】→ 受限执行 → 机器判定
 *   ├ 通过且预测对 → 掌握度 +1
 *   ├ 通过但预测错 → ★最高价值信号：结果对但因果理解错 → 建议沉淀笔记
 *   └ 失败 → 按「常见失败 → 盲点」回指 guide 章节
 * </pre>
 *
 * <h3>为什么预测门禁必须是硬锁</h3>
 * <p>「改完再解释，人会自动为既成结果编理由」。靠自觉守不住这条纪律，
 * 所以设计成：未填预测 → {@code run} 直接拒绝，进程根本不启动。</p>
 *
 * <h3>两个反作弊设计</h3>
 * <ol>
 *   <li><strong>Agent 无法代填预测</strong>：本服务的 {@link #submitPrediction} 只由
 *       HTTP 控制器调用，<em>不存在</em>对应的 Agent 工具。否则模型会"贴心地"
 *       帮用户预测，整个机制立刻失效。</li>
 *   <li><strong>预测不可事后修改</strong>：{@code predictedAt} 一旦写入即冻结，
 *       判定时对比的是冻结版本。</li>
 * </ol>
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final KbCheckpointRepository cpRepo;
    private final KbCheckpointRunRepository runRepo;
    private final KbDocumentRepository docRepo;
    private final CheckpointParser parser;
    private final CommandGuard guard;
    private final CheckpointRunner runner;
    private final RepoRegistryService registry;
    private final VerifyMetrics metrics;
    private final ObjectMapper om;

    @Value("${codex.verify.enabled:false}")
    private boolean verifyEnabled;

    @Value("${codex.verify.require-prediction:true}")
    private boolean requirePrediction;

    public CheckpointService(KbCheckpointRepository cpRepo,
                             KbCheckpointRunRepository runRepo,
                             KbDocumentRepository docRepo,
                             CheckpointParser parser,
                             CommandGuard guard,
                             CheckpointRunner runner,
                             RepoRegistryService registry,
                             VerifyMetrics metrics,
                             ObjectMapper om) {
        this.cpRepo = cpRepo;
        this.runRepo = runRepo;
        this.docRepo = docRepo;
        this.parser = parser;
        this.guard = guard;
        this.runner = runner;
        this.registry = registry;
        this.metrics = metrics;
        this.om = om;
    }

    public boolean enabled() {
        return verifyEnabled;
    }

    public boolean requirePrediction() {
        return requirePrediction;
    }

    /* ================= 解析与同步 ================= */

    /** 同步结果。 */
    public record SyncResult(int parsed, int created, int updated, int withCommand,
                             int booksScanned) {}

    /**
     * 从仓库的检验册 Markdown 同步 checkpoint 定义。
     *
     * <p><strong>刻意保留用户数据</strong>：已存在的条目只更新「定义」字段
     * （标题 / 判据 / 通过标准 / 盲点），不动 {@code prediction} /
     * {@code status} / {@code predictionCorrect}。
     * 那些是用户行为数据，重新解析一次文档不该把学习进度清零。</p>
     */
    @Transactional
    public SyncResult syncFromRepo(KnowledgeRepo repo) {
        Path root = registry.rootOf(repo);
        int parsed = 0, created = 0, updated = 0, withCmd = 0, books = 0;

        for (KbDocument doc : docRepo.findByRepoId(repo.getId())) {
            if (doc.getKind() != KbDocument.DocKind.CHECKPOINT_SET) continue;
            books++;

            String content;
            try {
                content = Files.readString(root.resolve(doc.getPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("[Codex/Verify] 读取检验册失败，跳过 {}：{}", doc.getPath(), e.getMessage());
                continue;
            }

            for (CheckpointParser.Parsed p : parser.parse(content, null)) {
                parsed++;
                if (p.hasCommand()) withCmd++;

                KbCheckpoint cp = cpRepo.findByRepoIdAndCode(repo.getId(), p.code())
                        .orElse(null);
                boolean isNew = (cp == null);
                if (isNew) {
                    cp = new KbCheckpoint();
                    cp.setRepoId(repo.getId());
                    cp.setUserId(repo.getUserId());
                    cp.setCode(p.code());
                    cp.setStatus(KbCheckpoint.Status.TODO);
                }
                // 只覆盖「定义」字段，用户填的预测与状态保持不动
                cp.setDocumentId(doc.getId());
                cp.setLevel(p.level());
                cp.setTitle(p.title() == null ? p.code() : p.title());
                cp.setChecksWhat(p.checksWhat());
                cp.setPrerequisite(p.prerequisite());
                cp.setResourceTag(p.resourceTag());
                cp.setEstHours(p.estHours());
                cp.setPredictionQuestions(p.predictionQuestions());
                cp.setPassCriteria(p.passCriteria());
                cp.setBlindSpots(p.blindSpots());
                cp.setVerifyJson(p.verifyJson());
                cp.setVerifySource(KbCheckpoint.VerifySource.PARSED);
                cp.setPredictRequired(true);
                cp.setUpdatedAt(LocalDateTime.now());
                cpRepo.save(cp);

                if (isNew) created++; else updated++;
            }
        }
        log.info("[Codex/Verify] 仓库「{}」检验同步：扫描 {} 册，解析 {} 条（{} 条有验收命令），"
                        + "新建 {} 更新 {}",
                repo.getName(), books, parsed, withCmd, created, updated);
        return new SyncResult(parsed, created, updated, withCmd, books);
    }

    /* ================= 预测门禁 ================= */

    /** 预测提交结果。 */
    public record PredictionResult(boolean accepted, String message, LocalDateTime frozenAt) {}

    /**
     * 提交预测。<strong>只能由用户经 HTTP 调用</strong>——Agent 无对应工具。
     *
     * <p>已冻结的预测不允许覆盖：否则用户可以在看到结果后回头改预测，
     * {@code predictionCorrect} 这个指标就失去全部意义。</p>
     */
    @Transactional
    public PredictionResult submitPrediction(Long userId, Long checkpointId, String prediction) {
        KbCheckpoint cp = cpRepo.findByIdAndUserId(checkpointId, userId).orElse(null);
        if (cp == null) {
            return new PredictionResult(false, "检验条目不存在", null);
        }
        if (prediction == null || prediction.isBlank()) {
            return new PredictionResult(false, "预测内容不能为空", null);
        }
        if (cp.getPredictedAt() != null) {
            return new PredictionResult(false,
                    "该条预测已于 " + cp.getPredictedAt() + " 冻结，不可修改。"
                            + "预测可事后修改的话，「预测准确率」就没有意义了。",
                    cp.getPredictedAt());
        }
        cp.setPrediction(prediction.strip());
        cp.setPredictedAt(LocalDateTime.now());
        if (cp.getStatus() == KbCheckpoint.Status.TODO) {
            cp.setStatus(KbCheckpoint.Status.PREDICTED);
        }
        cp.setUpdatedAt(LocalDateTime.now());
        cpRepo.save(cp);
        metrics.recordPredictionSubmitted();
        return new PredictionResult(true, "预测已冻结，现在可以运行验收", cp.getPredictedAt());
    }

    /* ================= 执行验收 ================= */

    /** 验收执行结果。 */
    public record RunResult(boolean executed,
                            String error,
                            String rejectReason,
                            Boolean passed,
                            Integer exitCode,
                            Long durationMs,
                            boolean timedOut,
                            boolean truncated,
                            String cmd,
                            List<CheckpointRunner.ExpectResult> details,
                            String stdoutExcerpt,
                            String stderrExcerpt,
                            String blindSpotHint) {}

    /**
     * 运行一条检验。
     *
     * <p>执行顺序即闸门顺序：功能开关 → 预测门禁 → 并发闸 → 命令安全 → 运行。
     * 任一环失败都不会启动进程。</p>
     */
    @Transactional
    public RunResult run(Long userId, Long checkpointId, boolean userApproved) {
        if (!verifyEnabled) {
            return err("VERIFY_DISABLED：验证闭环未启用（codex.verify.enabled=false）");
        }
        KbCheckpoint cp = cpRepo.findByIdAndUserId(checkpointId, userId).orElse(null);
        if (cp == null) {
            return err("CHECKPOINT_NOT_FOUND：检验条目不存在");
        }

        // ---- 预测门禁（硬锁）----
        if (requirePrediction && Boolean.TRUE.equals(cp.getPredictRequired())
                && !cp.predictionSatisfied()) {
            metrics.recordBlockedNoPrediction();
            return err("PREDICTION_REQUIRED：该检验要求先写下预测才能运行。"
                    + "请在 Checkpoint 面板填写「你认为会发生什么」后再试。"
                    + "（这条纪律的意义是：改完再解释，人会自动为既成结果编理由。）");
        }

        // ---- 判据完整性 ----
        JsonNode verify = readVerify(cp.getVerifyJson());
        if (verify == null || verify.path("cmd").asText("").isBlank()) {
            return err("NO_VERIFY_COMMAND：该条检验没有可执行的验收命令。"
                    + "它可能是纯口述题，或原文命令含 shell 特性无法安全解析——"
                    + "请手动执行后在面板里标记结果。");
        }

        KnowledgeRepo repo = registry.find(userId, cp.getRepoId()).orElse(null);
        if (repo == null) {
            return err("REPO_NOT_FOUND：所属仓库不存在");
        }

        // ---- 并发闸 ----
        if (runner.busy()) {
            return err("CONCURRENT_LIMIT：已有检验正在运行，请等待其完成再试");
        }

        String cmd = verify.path("cmd").asText();
        String cwd = verify.path("cwd").asText(null);
        Integer timeout = verify.has("timeout") ? verify.path("timeout").asInt() : null;
        Path root = registry.rootOf(repo);

        // ---- 命令安全闸门 ----
        CommandGuard.Decision decision = guard.check(cmd, cwd, root);
        if (!decision.allowed()) {
            // 拒绝也要落审计记录：这是「白名单机制是否必要」的实证数据
            KbCheckpointRun rec = new KbCheckpointRun();
            rec.setCheckpointId(cp.getId());
            rec.setUserId(userId);
            rec.setStartedAt(LocalDateTime.now());
            rec.setPassed(false);
            rec.setCmdExecuted(cmd);
            rec.setCwdExecuted(cwd);
            rec.setRejectReason(trim(decision.reason(), 250));
            rec.setApprovedByUser(userApproved);
            runRepo.save(rec);
            metrics.recordRejected();
            log.warn("[Codex/Verify] 命令被安全闸门拒绝：{} → {}", cmd, decision.reason());
            return new RunResult(false, null, decision.reason(), false, null, null,
                    false, false, cmd, List.of(), null, null, null);
        }

        // ---- 执行 ----
        KbCheckpointRun rec = new KbCheckpointRun();
        rec.setCheckpointId(cp.getId());
        rec.setUserId(userId);
        rec.setStartedAt(LocalDateTime.now());
        rec.setApprovedByUser(userApproved);
        rec.setCmdExecuted(trim(String.join(" ", decision.argv()), 1000));
        rec.setCwdExecuted(trim(decision.resolvedCwd().toString(), 500));

        CheckpointRunner.ExecResult exec;
        try {
            exec = runner.run(decision.argv(), decision.resolvedCwd(), timeout);
        } catch (Exception e) {
            rec.setPassed(false);
            rec.setRejectReason(trim("EXEC_FAILED：" + e.getMessage(), 250));
            runRepo.save(rec);
            return err("EXEC_FAILED：" + e.getMessage());
        }

        CheckpointRunner.Verdict verdict = runner.judge(cp.getVerifyJson(), exec);

        rec.setDurationMs(exec.durationMs());
        rec.setExitCode(exec.exitCode());
        rec.setPassed(verdict.passed());
        rec.setTimedOut(exec.timedOut());
        rec.setOutputTruncated(exec.truncated());
        rec.setStdoutExcerpt(exec.stdout());
        rec.setStderrExcerpt(exec.stderr());
        rec.setExpectResultJson(runner.serializeDetails(verdict.details()));
        runRepo.save(rec);

        // ---- 状态流转 ----
        if (verdict.passed()) {
            // 输出被截断时判定强度下降：标记 DEGRADED 而非 PASSED，
            // 让用户知道「这次通过是在数据不完整的情况下判定的」
            cp.setStatus(exec.truncated()
                    ? KbCheckpoint.Status.DEGRADED
                    : KbCheckpoint.Status.PASSED);
            cp.setPassedAt(LocalDateTime.now());
        } else {
            cp.setStatus(KbCheckpoint.Status.FAILED);
        }
        cp.setUpdatedAt(LocalDateTime.now());
        cpRepo.save(cp);

        metrics.recordRun(verdict.passed(), exec.timedOut(), cp.getLevel().name());

        String hint = verdict.passed() ? null : blindSpotHint(cp);
        return new RunResult(true, null, null, verdict.passed(), exec.exitCode(),
                exec.durationMs(), exec.timedOut(), exec.truncated(),
                rec.getCmdExecuted(), verdict.details(),
                exec.stdout(), exec.stderr(), hint);
    }

    /* ================= 预测一致性判定 ================= */

    /** 判定结果。 */
    public record JudgeResult(boolean recorded, Boolean correct, String judge, String message) {}

    /**
     * 记录「预测是否与实际一致」。
     *
     * <p><strong>「通过但预测错」是本方案最有价值的信号</strong>：
     * 它精确定位「结果对但因果理解错」，这是所有假通过里最危险的一类，
     * 市面上没有任何工具在采集它。</p>
     *
     * <p>判定裁判有两种（{@code AI} / {@code USER}）并落库记录，
     * 因为 LLM 判定自然语言一致性必然有误判——指标口径必须能说清
     * 「这个数字是谁判的」，否则 {@code predictionAccuracy} 没有解释力。</p>
     */
    @Transactional
    public JudgeResult recordPredictionJudgement(Long userId, Long checkpointId,
                                                 Boolean correct, String divergence,
                                                 KbCheckpoint.PredictionJudge judge) {
        KbCheckpoint cp = cpRepo.findByIdAndUserId(checkpointId, userId).orElse(null);
        if (cp == null) {
            return new JudgeResult(false, null, null, "检验条目不存在");
        }
        if (cp.getPredictedAt() == null) {
            return new JudgeResult(false, null, null,
                    "该条未填预测，无法判定预测一致性");
        }
        if (cp.getStatus() == KbCheckpoint.Status.TODO
                || cp.getStatus() == KbCheckpoint.Status.PREDICTED) {
            return new JudgeResult(false, null, null,
                    "该条尚未运行验收，还无法判定预测是否正确");
        }
        cp.setPredictionCorrect(correct);
        cp.setPredictionJudge(judge);
        cp.setDivergence(trim(divergence, 4000));
        cp.setUpdatedAt(LocalDateTime.now());
        cpRepo.save(cp);

        metrics.recordPredictionJudged(Boolean.TRUE.equals(correct));

        String msg;
        if (Boolean.FALSE.equals(correct)) {
            // 这是最该被珍视的一种结果，措辞上要说清它的价值
            msg = "已记录「预测错误」。这是最有价值的一种结果——"
                    + "它说明结果正确但你的因果理解有偏差，正是最容易被忽略的假掌握。"
                    + "建议把「我原以为…实际…」沉淀成一篇笔记。";
            log.info("[Codex/Verify] {} 预测错误（结果对但因果理解错）：{}",
                    cp.getCode(), trim(divergence, 200));
        } else if (Boolean.TRUE.equals(correct)) {
            msg = "已记录「预测正确」。";
        } else {
            msg = "已清除预测判定。";
        }
        return new JudgeResult(true, correct, judge == null ? null : judge.name(), msg);
    }

    /* ================= 查询 ================= */

    public List<KbCheckpoint> list(Long userId, Long repoId) {
        return (repoId == null)
                ? cpRepo.findByUserIdOrderByCodeAsc(userId)
                : cpRepo.findByRepoIdOrderByCodeAsc(repoId);
    }

    public Optional<KbCheckpoint> byId(Long userId, Long id) {
        return cpRepo.findByIdAndUserId(id, userId);
    }

    public Optional<KbCheckpoint> byCode(Long userId, String code) {
        return cpRepo.findByUserIdAndCode(userId, code);
    }

    /** 「下一条该做什么」：按 L0→L3 顺序取首个未完成条目。 */
    public Optional<KbCheckpoint> next(Long userId, Long repoId) {
        List<KbCheckpoint> candidates = (repoId == null)
                ? cpRepo.findByUserIdOrderByCodeAsc(userId).stream()
                    .filter(c -> c.getStatus() == KbCheckpoint.Status.TODO
                            || c.getStatus() == KbCheckpoint.Status.PREDICTED
                            || c.getStatus() == KbCheckpoint.Status.FAILED)
                    .sorted(Comparator.comparing((KbCheckpoint c) -> c.getLevel().ordinal())
                            .thenComparing(KbCheckpoint::getCode))
                    .toList()
                : cpRepo.findNextCandidates(repoId);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    public List<KbCheckpointRun> runsOf(Long checkpointId) {
        return runRepo.findByCheckpointIdOrderByStartedAtDesc(checkpointId);
    }

    /**
     * 统计快照。
     *
     * <p>{@code l2Passed} 单列出来是因为方法论把 L2 定为主判据——
     * 「每个工具至少两条 L2 通过」才算入门。只报总通过数会高估掌握程度。</p>
     */
    public Map<String, Object> stats(Long repoId) {
        Map<String, Object> m = new LinkedHashMap<>();
        long total = cpRepo.countByRepoId(repoId);
        long passed = cpRepo.countByRepoIdAndStatus(repoId, KbCheckpoint.Status.PASSED)
                + cpRepo.countByRepoIdAndStatus(repoId, KbCheckpoint.Status.DEGRADED);
        long l2Passed = cpRepo.countByRepoIdAndLevelAndStatus(repoId,
                KbCheckpoint.Level.L2, KbCheckpoint.Status.PASSED);
        long judged = cpRepo.countPredictionJudged(repoId);
        long correct = cpRepo.countByRepoIdAndPredictionCorrect(repoId, Boolean.TRUE);

        m.put("total", total);
        m.put("passed", passed);
        m.put("passRate", total == 0 ? 0.0 : round3((double) passed / total));
        m.put("l2Passed", l2Passed);
        m.put("predictionJudged", judged);
        m.put("predictionCorrect", correct);
        // 别处拿不到的指标：预测先于结果冻结，无法事后造假
        m.put("predictionAccuracy", judged == 0 ? 0.0 : round3((double) correct / judged));
        m.put("mispredicted", judged - correct);

        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (KbCheckpointRepository.LevelStatusCount c : cpRepo.countByLevelAndStatus(repoId)) {
            byLevel.merge(c.getLevel().name() + ":" + c.getStatus().name(), c.getCnt(), Long::sum);
        }
        m.put("byLevelStatus", byLevel);
        return m;
    }

    /* ================= 内部 ================= */

    /** 失败时把「常见失败 → 盲点」映射回灌，让用户知道该回看哪一节。 */
    private String blindSpotHint(KbCheckpoint cp) {
        if (cp.getBlindSpots() == null || cp.getBlindSpots().isBlank()) return null;
        return "该检验失败。原文给出的「常见失败 → 盲点」对照如下，"
                + "请据此定位是哪个知识点没掌握：\n" + trim(cp.getBlindSpots(), 1500);
    }

    private JsonNode readVerify(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private RunResult err(String message) {
        return new RunResult(false, message, null, null, null, null,
                false, false, null, List.of(), null, null, null);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
