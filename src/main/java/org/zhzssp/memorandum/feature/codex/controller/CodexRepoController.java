package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;
import org.zhzssp.memorandum.feature.codex.service.RepoHealthService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识仓库 REST API。
 *
 * <p>端点（均需登录，走 WebSecurityConfig 的 anyRequest().authenticated()）：</p>
 * <ul>
 *   <li>{@code GET  /api/codex/repos}              — 仓库列表 + 健康摘要</li>
 *   <li>{@code POST /api/codex/repos}              — 注册本地 git 仓库</li>
 *   <li>{@code DELETE /api/codex/repos/{id}}       — 注销（本地文件保留）</li>
 *   <li>{@code POST /api/codex/repos/{id}/sync}    — 同步 + 增量索引</li>
 *   <li>{@code POST /api/codex/repos/{id}/rebuild} — ★全量重建（验证「可重建」硬约束）</li>
 *   <li>{@code GET  /api/codex/repos/{id}/health}  — 健康详情（死链/截断/孤岛清单）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/codex")
public class CodexRepoController {

    private final RepoRegistryService registry;
    private final RepoSyncService syncService;
    private final RepoHealthService health;
    private final RepoIndexer indexer;
    private final UserRepository userRepository;

    public CodexRepoController(RepoRegistryService registry,
                               RepoSyncService syncService,
                               RepoHealthService health,
                               RepoIndexer indexer,
                               UserRepository userRepository) {
        this.registry = registry;
        this.syncService = syncService;
        this.health = health;
        this.indexer = indexer;
        this.userRepository = userRepository;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", registry.enabled());
        m.put("operational", registry.operational());
        m.put("gitVersion", registry.gitVersion());
        if (!registry.enabled()) {
            m.put("hint", "codex.enabled=false，请在 application.properties 开启后重启");
        } else if (!registry.operational()) {
            m.put("hint", "未检测到 git，请安装 git 并确保在 PATH 中");
        }
        return m;
    }

    @GetMapping("/repos")
    public ResponseEntity<?> list(@AuthenticationPrincipal UserDetails principal) {
        User u = currentUser(principal);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeRepo r : registry.list(u.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("kind", r.getKind().name());
            m.put("provider", r.getProvider().name());
            m.put("localPath", r.getLocalPath());
            m.put("remoteUrl", r.getRemoteUrl());
            m.put("branch", r.getDefaultBranch());
            m.put("syncStatus", r.getSyncStatus().name());
            m.put("syncError", r.getSyncError());
            m.put("lastSyncedAt", r.getLastSyncedAt() == null ? null : r.getLastSyncedAt().toString());
            m.put("lastSyncedSha", r.getLastSyncedSha());
            m.put("enabled", r.getEnabled());
            m.put("health", healthMap(health.snapshot(r.getId())));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 注册请求体。 */
    public record RegisterRequest(String name, String localPath, String kind,
                                  String provider, String remoteUrl) {}

    @PostMapping("/repos")
    public ResponseEntity<?> register(@AuthenticationPrincipal UserDetails principal,
                                      @RequestBody RegisterRequest req) {
        User u = currentUser(principal);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        try {
            KnowledgeRepo repo = registry.register(u.getId(), req.name(), req.localPath(),
                    parseKind(req.kind()), parseProvider(req.provider()), req.remoteUrl());
            return ResponseEntity.ok(Map.of("id", repo.getId(), "name", repo.getName(),
                    "localPath", repo.getLocalPath(), "branch", repo.getDefaultBranch()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/repos/{id}")
    public ResponseEntity<?> unregister(@AuthenticationPrincipal UserDetails principal,
                                        @PathVariable Long id) {
        User u = currentUser(principal);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        // 先清派生索引，再删注册；本地文件绝不删除
        indexer.clearDerived(id);
        registry.unregister(u.getId(), id);
        return ResponseEntity.ok(Map.of("deleted", true,
                "note", "仅移除索引与注册信息，本地仓库文件未做任何改动"));
    }

    @PostMapping("/repos/{id}/sync")
    public ResponseEntity<?> sync(@AuthenticationPrincipal UserDetails principal,
                                  @PathVariable Long id,
                                  @RequestParam(defaultValue = "false") boolean pull) {
        return doIndex(principal, id, false, pull);
    }

    /**
     * 全量重建索引。
     *
     * <p>这个端点的存在本身就是架构约束的验收工具：先 {@code DELETE FROM kb_*}
     * 再调用它，若结果与增量索引一致，就证明了「Git 是唯一权威源、MySQL 可丢弃」。</p>
     */
    @PostMapping("/repos/{id}/rebuild")
    public ResponseEntity<?> rebuild(@AuthenticationPrincipal UserDetails principal,
                                     @PathVariable Long id) {
        return doIndex(principal, id, true, false);
    }

    private ResponseEntity<?> doIndex(UserDetails principal, Long id, boolean full, boolean pull) {
        User u = currentUser(principal);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        if (!registry.operational()) {
            return ResponseEntity.badRequest().body(Map.of("error", "CODEX_NOT_OPERATIONAL",
                    "enabled", registry.enabled(), "gitVersion", registry.gitVersion()));
        }
        KnowledgeRepo repo = registry.find(u.getId(), id).orElse(null);
        if (repo == null) {
            return ResponseEntity.status(404).body(Map.of("error", "REPO_NOT_FOUND"));
        }
        RepoSyncService.SyncResult r = syncService.sync(repo, full, pull);
        RepoIndexer.IndexReport rep = r.report();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pulled", r.pulled());
        m.put("dirty", r.dirty());
        m.put("headSha", r.headSha());
        m.put("mode", rep.mode().name());
        m.put("docsTotal", rep.docsTotal());
        m.put("docsReindexed", rep.docsReindexed());
        m.put("docsSkipped", rep.docsSkipped());
        m.put("docsRemoved", rep.docsRemoved());
        m.put("chunksWritten", rep.chunksWritten());
        m.put("embedCalls", rep.embedCalls());
        m.put("brokenLinks", rep.brokenLinks());
        m.put("truncatedDocs", rep.truncatedDocs());
        m.put("skipRate", Math.round(rep.skipRate() * 10000) / 10000.0);
        m.put("durationMs", rep.durationMs());
        if (r.warning() != null) m.put("warning", r.warning());
        if (!rep.success()) m.put("error", rep.error());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/repos/{id}/health")
    public ResponseEntity<?> healthDetail(@AuthenticationPrincipal UserDetails principal,
                                          @PathVariable Long id) {
        User u = currentUser(principal);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "UNAUTHENTICATED"));
        if (registry.find(u.getId(), id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "REPO_NOT_FOUND"));
        }
        Map<String, Object> m = new LinkedHashMap<>(healthMap(health.snapshot(id)));
        m.put("brokenLinkList", health.brokenLinks(id, 100));
        m.put("truncatedList", health.truncatedDocuments(id));
        m.put("orphanList", health.orphanDocuments(id, 100));
        return ResponseEntity.ok(m);
    }

    /* ---------------- 内部 ---------------- */

    private Map<String, Object> healthMap(RepoHealthService.Health h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents", h.documents());
        m.put("sections", h.sections());
        m.put("chunks", h.chunks());
        m.put("truncatedDocs", h.truncatedDocs());
        m.put("brokenLinks", h.brokenLinks());
        m.put("orphanDocs", h.orphanDocs());
        m.put("kindDistribution", h.kindDistribution());
        m.put("lastRun", h.lastRun());
        return m;
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).orElse(null);
    }

    private KnowledgeRepo.RepoKind parseKind(String s) {
        if (s == null || s.isBlank()) return KnowledgeRepo.RepoKind.LEARNING;
        try {
            return KnowledgeRepo.RepoKind.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return KnowledgeRepo.RepoKind.LEARNING;
        }
    }

    private KnowledgeRepo.RepoProvider parseProvider(String s) {
        if (s == null || s.isBlank()) return KnowledgeRepo.RepoProvider.LOCAL;
        try {
            return KnowledgeRepo.RepoProvider.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return KnowledgeRepo.RepoProvider.LOCAL;
        }
    }
}
