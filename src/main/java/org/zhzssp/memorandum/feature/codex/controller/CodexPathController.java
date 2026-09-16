package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.path.PathApplyService;
import org.zhzssp.memorandum.feature.codex.path.PathProjector;
import org.zhzssp.memorandum.feature.codex.path.PathQueryService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习路径 API：读车站 / 预览补丁 / 确认写入 / 投影任务。
 */
@RestController
@RequestMapping("/api/codex")
public class CodexPathController {

    private final RepoRegistryService registry;
    private final PathQueryService query;
    private final PathApplyService applyService;
    private final PathProjector projector;
    private final UserRepository userRepository;

    public CodexPathController(RepoRegistryService registry,
                               PathQueryService query,
                               PathApplyService applyService,
                               PathProjector projector,
                               UserRepository userRepository) {
        this.registry = registry;
        this.query = query;
        this.applyService = applyService;
        this.projector = projector;
        this.userRepository = userRepository;
    }

    @GetMapping("/path")
    public ResponseEntity<?> get(@AuthenticationPrincipal UserDetails principal,
                                 @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = repoId == null
                ? firstEnabled(u.getId())
                : registry.find(u.getId(), repoId).orElse(null);
        if (repo == null) {
            return ResponseEntity.ok(Map.of(
                    "present", false,
                    "parseOk", false,
                    "error", "NO_REPO",
                    "message", "没有已接入的知识仓库。",
                    "stations", List.of(),
                    "projections", List.of()));
        }
        return ResponseEntity.ok(query.view(repo));
    }

    public record DeltaRequest(String delta, String repoName, Long repoId, Boolean confirmed) {}

    @PostMapping("/path/preview")
    public ResponseEntity<?> preview(@AuthenticationPrincipal UserDetails principal,
                                     @RequestBody DeltaRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (req == null || req.delta() == null || req.delta().isBlank()) {
            return ResponseEntity.badRequest().body(err("EMPTY_DELTA", "路径补丁为空"));
        }
        PathApplyService.Proposal p = applyService.preview(u.getId(), req.repoName(), req.delta());
        return ResponseEntity.ok(applyService.toMap(p));
    }

    @PostMapping("/path/apply")
    public ResponseEntity<?> apply(@AuthenticationPrincipal UserDetails principal,
                                   @RequestBody DeltaRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (req == null || req.delta() == null || req.delta().isBlank()) {
            return ResponseEntity.badRequest().body(err("EMPTY_DELTA", "路径补丁为空"));
        }
        boolean confirmed = Boolean.TRUE.equals(req.confirmed());
        PathApplyService.ApplyResult r = applyService.apply(
                u.getId(), req.repoName(), req.delta(), confirmed);
        return ResponseEntity.ok(applyService.toMap(r));
    }

    public record PointEditRequest(String stationId, String pointId, String level,
                                   String statement, String repoName, Long repoId,
                                   Boolean confirmed) {}

    @PostMapping("/path/point")
    public ResponseEntity<?> updatePoint(@AuthenticationPrincipal UserDetails principal,
                                         @RequestBody PointEditRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (req == null) {
            return ResponseEntity.badRequest().body(err("EMPTY", "缺少要点改动"));
        }
        boolean confirmed = Boolean.TRUE.equals(req.confirmed());
        PathApplyService.ApplyResult r = applyService.updatePoint(
                u.getId(), req.repoName(), req.stationId(), req.pointId(),
                req.level(), req.statement(), confirmed);
        return ResponseEntity.ok(applyService.toMap(r));
    }

    @PostMapping("/path/project")
    public ResponseEntity<?> project(@AuthenticationPrincipal UserDetails principal,
                                     @RequestBody(required = false) DeltaRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = resolve(u.getId(), req);
        if (repo == null) {
            return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "未找到知识仓库"));
        }
        PathProjector.Report r = projector.project(u.getId(), repo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("goalId", r.goalId());
        m.put("created", r.created());
        m.put("active", r.active());
        m.put("stale", r.stale());
        m.put("satisfied", r.satisfied());
        m.put("projections", projector.listProjections(repo.getId()));
        return ResponseEntity.ok(m);
    }

    private KnowledgeRepo resolve(Long userId, DeltaRequest req) {
        if (req != null && req.repoId() != null) {
            return registry.find(userId, req.repoId()).orElse(null);
        }
        if (req != null && req.repoName() != null && !req.repoName().isBlank()) {
            return registry.findByName(userId, req.repoName().strip()).orElse(null);
        }
        return firstEnabled(userId);
    }

    private KnowledgeRepo firstEnabled(Long userId) {
        var all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).orElse(null);
    }

    private ResponseEntity<?> unauth() {
        return ResponseEntity.status(401).body(err("UNAUTHENTICATED", "未登录"));
    }

    private Map<String, Object> err(String code, String message) {
        return Map.of("error", code, "message", message, "ok", false);
    }
}
