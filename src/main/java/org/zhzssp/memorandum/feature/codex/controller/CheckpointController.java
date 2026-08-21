package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpointRun;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointRunner;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointService;
import org.zhzssp.memorandum.feature.codex.verify.CommandGuard;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识落地检验 REST API。
 *
 * <h3>为什么预测提交必须走 HTTP 而非 Agent 工具</h3>
 * <p>{@code POST /predict} 是<strong>用户填写预测的唯一入口</strong>。
 * Agent 侧刻意不提供任何写入预测的工具——否则模型会"贴心地"代填，
 * 而「先预测再动手」的全部价值就在于暴露用户自己的心智模型。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET  /api/codex/checkpoints}            — 列表（可按 repo/level/status 过滤）</li>
 *   <li>{@code GET  /api/codex/checkpoints/{id}}       — 详情 + 运行历史</li>
 *   <li>{@code POST /api/codex/checkpoints/{id}/predict} — ★提交预测（冻结后不可改）</li>
 *   <li>{@code POST /api/codex/checkpoints/{id}/run}   — 执行验收</li>
 *   <li>{@code POST /api/codex/checkpoints/{id}/grade} — 用户覆盖预测判定</li>
 *   <li>{@code GET  /api/codex/checkpoints/stats}      — 统计</li>
 *   <li>{@code POST /api/codex/checkpoints/sync}       — 从检验册重新解析</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/codex/checkpoints")
public class CheckpointController {

    private final CheckpointService service;
    private final RepoRegistryService registry;
    private final CommandGuard guard;
    private final UserRepository userRepository;

    public CheckpointController(CheckpointService service,
                                RepoRegistryService registry,
                                CommandGuard guard,
                                UserRepository userRepository) {
        this.service = service;
        this.registry = registry;
        this.guard = guard;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam(required = false) Long repoId,
                                  @RequestParam(required = false) String level,
                                  @RequestParam(required = false) String status) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        List<Map<String, Object>> out = new ArrayList<>();
        for (KbCheckpoint cp : service.list(u.getId(), repoId)) {
            if (level != null && !level.isBlank()
                    && !cp.getLevel().name().equalsIgnoreCase(level)) continue;
            if (status != null && !status.isBlank()
                    && !cp.getStatus().name().equalsIgnoreCase(status)) continue;
            out.add(view(cp, false));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@AuthenticationPrincipal UserDetails principal,
                                   @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        Long rid = repoId;
        if (rid == null) {
            List<KnowledgeRepo> repos = registry.listEnabled(u.getId());
            if (repos.isEmpty()) return ResponseEntity.ok(Map.of("total", 0));
            rid = repos.get(0).getId();
        } else if (registry.find(u.getId(), rid).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "REPO_NOT_FOUND"));
        }
        Map<String, Object> m = new LinkedHashMap<>(service.stats(rid));
        m.put("requirePrediction", service.requirePrediction());
        m.put("allowedExecutables", guard.allowedExecutablesList());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        KbCheckpoint cp = service.byId(u.getId(), id).orElse(null);
        if (cp == null) return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));

        Map<String, Object> m = view(cp, true);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (KbCheckpointRun r : service.runsOf(cp.getId())) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("startedAt", r.getStartedAt() == null ? null : r.getStartedAt().toString());
            x.put("passed", r.getPassed());
            x.put("exitCode", r.getExitCode());
            x.put("durationMs", r.getDurationMs());
            x.put("timedOut", r.getTimedOut());
            x.put("outputTruncated", r.getOutputTruncated());
            x.put("cmd", r.getCmdExecuted());
            x.put("rejectReason", r.getRejectReason());
            x.put("stdout", r.getStdoutExcerpt());
            x.put("stderr", r.getStderrExcerpt());
            x.put("assertions", r.getExpectResultJson());
            runs.add(x);
        }
        m.put("runs", runs);
        return ResponseEntity.ok(m);
    }

    public record PredictRequest(String prediction) {}

    /**
     * 提交预测（★用户唯一入口，Agent 无对应工具）。
     *
     * <p>已冻结的预测返回 409 而非静默覆盖：预测可事后修改的话，
     * {@code predictionAccuracy} 这个指标就失去全部意义。</p>
     */
    @PostMapping("/{id}/predict")
    public ResponseEntity<?> predict(@AuthenticationPrincipal UserDetails principal,
                                     @PathVariable Long id,
                                     @RequestBody PredictRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        CheckpointService.PredictionResult r =
                service.submitPrediction(u.getId(), id, req.prediction());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", r.accepted());
        m.put("message", r.message());
        m.put("frozenAt", r.frozenAt() == null ? null : r.frozenAt().toString());
        return r.accepted() ? ResponseEntity.ok(m) : ResponseEntity.status(409).body(m);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        // 从 UI 触发即视为用户已批准（页面上有明确的命令预览与确认按钮）
        CheckpointService.RunResult r = service.run(u.getId(), id, true);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executed", r.executed());
        if (r.rejectReason() != null) {
            m.put("rejected", true);
            m.put("rejectReason", r.rejectReason());
            return ResponseEntity.badRequest().body(m);
        }
        if (!r.executed()) {
            m.put("error", r.error());
            return ResponseEntity.badRequest().body(m);
        }
        m.put("passed", r.passed());
        m.put("exitCode", r.exitCode());
        m.put("durationMs", r.durationMs());
        m.put("timedOut", r.timedOut());
        m.put("outputTruncated", r.truncated());
        m.put("cmd", r.cmd());
        m.put("stdout", r.stdoutExcerpt());
        m.put("stderr", r.stderrExcerpt());
        m.put("blindSpotHint", r.blindSpotHint());

        List<Map<String, Object>> ds = new ArrayList<>();
        for (CheckpointRunner.ExpectResult d : r.details()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", d.kind());
            x.put("expected", d.expected());
            x.put("actual", d.actual());
            x.put("passed", d.passed());
            ds.add(x);
        }
        m.put("assertions", ds);
        return ResponseEntity.ok(m);
    }

    public record GradeRequest(Boolean correct, String divergence) {}

    /**
     * 用户覆盖预测判定。
     *
     * <p>与 Agent 的 {@code checkpoint.grade} 的区别在裁判标记：
     * 这里记 {@code USER}（权威），Agent 记 {@code AI}（可能误判）。
     * 指标里必须能区分二者，否则 {@code predictionAccuracy} 没有解释力。</p>
     */
    @PostMapping("/{id}/grade")
    public ResponseEntity<?> grade(@AuthenticationPrincipal UserDetails principal,
                                   @PathVariable Long id,
                                   @RequestBody GradeRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        CheckpointService.JudgeResult r = service.recordPredictionJudgement(
                u.getId(), id, req.correct(), req.divergence(),
                KbCheckpoint.PredictionJudge.USER);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recorded", r.recorded());
        m.put("correct", r.correct());
        m.put("judge", r.judge());
        m.put("message", r.message());
        return r.recorded() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauthenticated();
        if (!service.enabled()) return disabled();

        List<KnowledgeRepo> targets = new ArrayList<>();
        if (repoId != null) {
            registry.find(u.getId(), repoId).ifPresent(targets::add);
        } else {
            targets.addAll(registry.listEnabled(u.getId()));
        }
        if (targets.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "REPO_NOT_FOUND"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeRepo repo : targets) {
            CheckpointService.SyncResult r = service.syncFromRepo(repo);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", repo.getName());
            m.put("booksScanned", r.booksScanned());
            m.put("parsed", r.parsed());
            m.put("created", r.created());
            m.put("updated", r.updated());
            m.put("withCommand", r.withCommand());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> view(KbCheckpoint cp, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cp.getId());
        m.put("code", cp.getCode());
        m.put("level", cp.getLevel().name());
        m.put("title", cp.getTitle());
        m.put("status", cp.getStatus().name());
        m.put("resourceTag", cp.getResourceTag());
        m.put("estHours", cp.getEstHours());
        m.put("verifySource", cp.getVerifySource().name());
        m.put("predictRequired", cp.getPredictRequired());
        m.put("hasPrediction", cp.getPrediction() != null && !cp.getPrediction().isBlank());
        m.put("predictedAt", cp.getPredictedAt() == null ? null : cp.getPredictedAt().toString());
        m.put("predictionCorrect", cp.getPredictionCorrect());
        m.put("predictionJudge", cp.getPredictionJudge() == null
                ? null : cp.getPredictionJudge().name());
        m.put("canRun", cp.predictionSatisfied() && cp.getVerifyJson() != null);
        if (full) {
            m.put("checksWhat", cp.getChecksWhat());
            m.put("prerequisite", cp.getPrerequisite());
            m.put("predictionQuestions", cp.getPredictionQuestions());
            m.put("prediction", cp.getPrediction());
            m.put("passCriteria", cp.getPassCriteria());
            m.put("blindSpots", cp.getBlindSpots());
            m.put("verify", cp.getVerifyJson());
            m.put("divergence", cp.getDivergence());
            m.put("documentId", cp.getDocumentId());
        }
        return m;
    }

    private ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
    }

    private ResponseEntity<?> disabled() {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VERIFY_DISABLED",
                "message", "验证闭环未启用，请设置 codex.verify.enabled=true 后重启"));
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).orElse(null);
    }
}
