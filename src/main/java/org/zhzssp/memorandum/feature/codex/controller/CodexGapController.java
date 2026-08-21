package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KbEntity;
import org.zhzssp.memorandum.feature.codex.entity.KbGap;
import org.zhzssp.memorandum.feature.codex.entity.KbScopeDecision;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.gap.GapService;
import org.zhzssp.memorandum.feature.codex.gap.ScopeRecallService;
import org.zhzssp.memorandum.feature.codex.repository.KbEntityRepository;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识缺口看板 REST API（P3）。
 *
 * <ul>
 *   <li>{@code GET  /api/codex/gap}              — 台账（按被问次数倒序）+ 摘要</li>
 *   <li>{@code POST /api/codex/gap}              — 手工登记（source=MANUAL）</li>
 *   <li>{@code POST /api/codex/gap/{id}/plan}    — ★转学习计划（复用目标体系）</li>
 *   <li>{@code POST /api/codex/gap/{id}/close}   — 关闭（须给证据文档）</li>
 *   <li>{@code POST /api/codex/gap/{id}/dismiss} — 忽略</li>
 *   <li>{@code POST /api/codex/gap/{id}/issue}   — 外化为 GitHub Issue（可选）</li>
 *   <li>{@code GET  /api/codex/scope/skipped}    — ★止损线清单 + 命中次数</li>
 *   <li>{@code POST /api/codex/scope/decision}   — 改判止损线</li>
 *   <li>{@code POST /api/codex/scope/sync}       — 重新解析「先跳过」清单</li>
 * </ul>
 *
 * <h3>手工登记与自动登记的区别要在 API 上体现</h3>
 * <p>自动登记来自行为证据，手工登记来自用户判断——两者都合法，
 * 但必须能分开统计。若混成一类，「我的盲区里有多少是靠证据发现的」
 * 这个问题就答不出来了，而那正是这套机制相对「手写待学清单」的全部优势。</p>
 */
@RestController
@RequestMapping("/api/codex")
public class CodexGapController {

    private final GapService gapService;
    private final ScopeRecallService scopeRecall;
    private final RepoRegistryService registry;
    private final KbEntityRepository entityRepo;
    private final UserRepository userRepository;

    public CodexGapController(GapService gapService,
                              ScopeRecallService scopeRecall,
                              RepoRegistryService registry,
                              KbEntityRepository entityRepo,
                              UserRepository userRepository) {
        this.gapService = gapService;
        this.scopeRecall = scopeRecall;
        this.registry = registry;
        this.entityRepo = entityRepo;
        this.userRepository = userRepository;
    }

    @GetMapping("/gap/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", gapService.enabled());
        m.put("skipRecallThreshold", scopeRecall.threshold());
        if (!gapService.enabled()) {
            // 必须解释为什么默认关：否则用户以为功能坏了
            m.put("hint", "codex.gap.enabled=false。默认关闭的原因是缺口记录挂在检索工具的"
                    + "调用路径上，而评测套件也会走这些工具——开启后跑评测会往台账灌入假数据，"
                    + "而缺口表是唯一不可重建的表。");
        }
        return m;
    }

    /* ==================== 台账 ==================== */

    @GetMapping("/gap")
    public ResponseEntity<?> list(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String source,
                                  @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();

        List<KbGap> gaps = (status == null || status.isBlank())
                ? gapService.actionable(u.getId())
                : gapService.all(u.getId(), repoId).stream()
                        .filter(g -> g.getStatus() == KbGap.Status.of(status))
                        .toList();
        if (source != null && !source.isBlank()) {
            KbGap.Source s = KbGap.Source.of(source);
            gaps = gaps.stream().filter(g -> g.getSource() == s).toList();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", gapService.enabled());
        m.put("summary", gapService.summary(u.getId()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KbGap g : gaps) rows.add(gapMap(g));
        m.put("gaps", rows);
        return ResponseEntity.ok(m);
    }

    public record ManualGapBody(Long repoId, String question, String note) {}

    @PostMapping("/gap")
    public ResponseEntity<?> record(@AuthenticationPrincipal UserDetails principal,
                                    @RequestBody ManualGapBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (body == null || body.question() == null || body.question().isBlank()) {
            return ResponseEntity.badRequest().body(err("QUESTION_EMPTY", "问题为空"));
        }
        KbGap g = gapService.upsert(u.getId(), body.repoId(), KbGap.Source.MANUAL,
                body.question(), null, body.note());
        if (g == null) {
            return ResponseEntity.badRequest().body(err("NOT_RECORDED",
                    gapService.enabled()
                            ? "未登记：问题无法归一化，或待处理缺口已达上限。"
                            : "知识缺口闭环未启用（codex.gap.enabled=false）。"));
        }
        return ResponseEntity.ok(gapMap(g));
    }

    public record ConstraintsBody(List<String> constraints) {}

    @PostMapping("/gap/{id}/plan")
    public ResponseEntity<?> plan(@AuthenticationPrincipal UserDetails principal,
                                  @PathVariable Long id,
                                  @RequestBody(required = false) ConstraintsBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        Map<String, Object> r = gapService.toLearningPlan(u.getId(), id,
                body == null ? null : body.constraints());
        return Boolean.TRUE.equals(r.get("ok"))
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    public record CloseBody(String documentPath) {}

    @PostMapping("/gap/{id}/close")
    public ResponseEntity<?> close(@AuthenticationPrincipal UserDetails principal,
                                   @PathVariable Long id,
                                   @RequestBody(required = false) CloseBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        Map<String, Object> r = gapService.close(u.getId(), id,
                body == null ? null : body.documentPath());
        return Boolean.TRUE.equals(r.get("ok"))
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    public record DismissBody(String reason) {}

    @PostMapping("/gap/{id}/dismiss")
    public ResponseEntity<?> dismiss(@AuthenticationPrincipal UserDetails principal,
                                     @PathVariable Long id,
                                     @RequestBody(required = false) DismissBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        Map<String, Object> r = gapService.dismiss(u.getId(), id,
                body == null ? null : body.reason());
        return Boolean.TRUE.equals(r.get("ok"))
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    @PostMapping("/gap/{id}/issue")
    public ResponseEntity<?> issue(@AuthenticationPrincipal UserDetails principal,
                                   @PathVariable Long id) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        Map<String, Object> r = gapService.toIssue(u.getId(), id);
        return Boolean.TRUE.equals(r.get("ok"))
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    /* ==================== 止损线 ==================== */

    @GetMapping("/scope/skipped")
    public ResponseEntity<?> skipped(@AuthenticationPrincipal UserDetails principal,
                                     @RequestParam Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (registry.find(u.getId(), repoId).isEmpty()) {
            return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threshold", scopeRecall.threshold());
        m.put("skipped", scopeRecall.skippedList(u.getId(), repoId));
        return ResponseEntity.ok(m);
    }

    public record ScopeBody(Long entityId, String decision) {}

    @PostMapping("/scope/decision")
    public ResponseEntity<?> setDecision(@AuthenticationPrincipal UserDetails principal,
                                         @RequestBody ScopeBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (body == null || body.entityId() == null) {
            return ResponseEntity.badRequest().body(err("BAD_BODY", "entityId 为空"));
        }
        KbScopeDecision.Decision d;
        try {
            d = KbScopeDecision.Decision.valueOf(body.decision().strip().toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(err("BAD_DECISION", "decision 只能是 MUST / SKIP / DROPPED"));
        }
        boolean ok = scopeRecall.setDecision(u.getId(), body.entityId(), d);
        KbEntity e = entityRepo.findById(body.entityId()).orElse(null);
        return ok
                ? ResponseEntity.ok(Map.of("ok", true, "decision", d.name(),
                        "term", e == null ? "" : e.getName()))
                : ResponseEntity.badRequest().body(err("UPDATE_FAILED", "改判失败"));
    }

    /**
     * 手动重新解析「先跳过」清单。
     *
     * <p>正常路径是索引后自动触发；这个端点用于「刚开启开关、但还不想重跑索引」的情况。</p>
     */
    @PostMapping("/scope/sync")
    public ResponseEntity<?> syncScope(@AuthenticationPrincipal UserDetails principal,
                                       @RequestParam Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = registry.find(u.getId(), repoId).orElse(null);
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        ScopeRecallService.SyncResult r = scopeRecall.syncFromRepo(repo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentsScanned", r.documentsScanned());
        m.put("termsFound", r.termsFound());
        m.put("entitiesCreated", r.entitiesCreated());
        m.put("decisionsCreated", r.decisionsCreated());
        m.put("decisionsKept", r.decisionsKept());
        if (r.termsFound() == 0) {
            m.put("warning", "未解析到任何「先跳过」条目——止损线召回对该仓库不会触发。"
                    + "检查小节标题是否含「先跳过 / 可推迟 / 可以跳过」等措辞。");
        }
        return ResponseEntity.ok(m);
    }

    /* ==================== 内部 ==================== */

    private Map<String, Object> gapMap(KbGap g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("question", g.getQuestion());
        m.put("source", g.getSource().name());
        m.put("machineJudged", g.getSource().machineJudged());
        m.put("askCount", g.getAskCount());
        m.put("status", g.getStatus().name());
        m.put("priority", g.getPriority());
        m.put("note", g.getNote());
        m.put("firstAt", g.getFirstAt() == null ? null : g.getFirstAt().toString());
        m.put("lastAt", g.getLastAt() == null ? null : g.getLastAt().toString());
        m.put("goalId", g.getGoalId());
        m.put("githubIssueNumber", g.getGithubIssueNumber());
        m.put("closedByDocumentId", g.getClosedByDocumentId());
        if (g.getEntityId() != null) {
            entityRepo.findById(g.getEntityId()).ifPresent(e -> m.put("entity", e.getName()));
        }
        return m;
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).orElse(null);
    }

    private ResponseEntity<?> unauth() {
        return ResponseEntity.status(401).body(err("UNAUTHENTICATED", "未登录"));
    }

    private Map<String, Object> err(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
