package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KbPathSnapshot;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.entity.KbStation;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.path.LearningPathParser;
import org.zhzssp.memorandum.feature.codex.path.PathApplyService;
import org.zhzssp.memorandum.feature.codex.path.PathProjector;
import org.zhzssp.memorandum.feature.codex.repository.KbPathSnapshotRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbPointRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbStationRepository;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.ArrayList;
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
    private final KbStationRepository stationRepo;
    private final KbPointRepository pointRepo;
    private final KbPathSnapshotRepository snapRepo;
    private final PathApplyService applyService;
    private final PathProjector projector;
    private final DocWriteGuard writeGuard;
    private final UserRepository userRepository;

    public CodexPathController(RepoRegistryService registry,
                               KbStationRepository stationRepo,
                               KbPointRepository pointRepo,
                               KbPathSnapshotRepository snapRepo,
                               PathApplyService applyService,
                               PathProjector projector,
                               DocWriteGuard writeGuard,
                               UserRepository userRepository) {
        this.registry = registry;
        this.stationRepo = stationRepo;
        this.pointRepo = pointRepo;
        this.snapRepo = snapRepo;
        this.applyService = applyService;
        this.projector = projector;
        this.writeGuard = writeGuard;
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
        return ResponseEntity.ok(pathView(repo));
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
        return ResponseEntity.ok(proposal(p));
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("wroteDraft", r.wroteDraft());
        return ResponseEntity.ok(m);
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

    private Map<String, Object> pathView(KnowledgeRepo repo) {
        KbPathSnapshot snap = snapRepo.findByRepoId(repo.getId()).orElse(null);
        List<KbStation> stations = stationRepo.findByRepoIdOrderByOrdinalAsc(repo.getId());
        List<KbPoint> points = pointRepo.findByRepoIdOrderByPointIdAsc(repo.getId());
        Map<String, List<Map<String, Object>>> byStation = new LinkedHashMap<>();
        for (KbPoint p : points) {
            byStation.computeIfAbsent(p.getStationId(), k -> new ArrayList<>())
                    .add(Map.of(
                            "id", p.getPointId(),
                            "level", p.getLevel().name(),
                            "statement", p.getStatement()));
        }
        List<Map<String, Object>> stationViews = new ArrayList<>();
        for (KbStation s : stations) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.getStationId());
            x.put("title", s.getTitle());
            x.put("ordinal", s.getOrdinal());
            x.put("cursor", s.isCursorFlag());
            x.put("sources", s.getSourcesCsv());
            x.put("lab", s.getLab());
            x.put("next", s.getNextCsv());
            x.put("points", byStation.getOrDefault(s.getStationId(), List.of()));
            stationViews.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repoId", repo.getId());
        m.put("repo", repo.getName());
        m.put("file", LearningPathParser.DEFAULT_PATH);
        m.put("present", !stations.isEmpty());
        m.put("parseOk", snap != null && snap.isParseOk());
        m.put("error", snap == null ? null : snap.getParseError());
        m.put("version", snap == null ? 0 : snap.getVersion());
        m.put("cursor", snap == null ? null : snap.getCursor());
        m.put("must", snap == null ? 0 : snap.getMustCount());
        m.put("skip", snap == null ? 0 : snap.getSkipCount());
        m.put("writeEnabled", writeGuard.enabled());
        m.put("stations", stationViews);
        m.put("content", applyService.readExisting(repo));
        m.put("projections", projector.listProjections(repo.getId()));
        if (stations.isEmpty()) {
            m.put("message", "尚未抽出路径。蒸馏后预览 PATH_DELTA，或手写 "
                    + LearningPathParser.DEFAULT_PATH + " 再同步。");
        }
        return m;
    }

    private Map<String, Object> proposal(PathApplyService.Proposal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", p.ok());
        m.put("code", p.code());
        m.put("message", p.message());
        m.put("fileExisted", p.fileExisted());
        m.put("preview", p.preview());
        m.put("delta", p.delta());
        m.put("stations", p.stations());
        m.put("must", p.must());
        m.put("skip", p.skip());
        m.put("version", p.version());
        return m;
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
