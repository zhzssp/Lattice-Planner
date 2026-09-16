package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.distill.SourceReader;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.sediment.NotePromoteService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识工作室：Git 笔记读写、随手记晋升、资料正文阅读。
 */
@RestController
@RequestMapping("/api/codex")
public class CodexStudioController {

    private static final int READ_MAX = 80000;

    private final RepoRegistryService registry;
    private final KbDocumentRepository docRepo;
    private final NoteService notes;
    private final NotePromoteService promote;
    private final DocWriteGuard writeGuard;
    private final RepoWriteService writeService;
    private final RepoSyncService syncService;
    private final SourceReader sourceReader;
    private final UserRepository users;

    public CodexStudioController(RepoRegistryService registry,
                                 KbDocumentRepository docRepo,
                                 NoteService notes,
                                 NotePromoteService promote,
                                 DocWriteGuard writeGuard,
                                 RepoWriteService writeService,
                                 RepoSyncService syncService,
                                 SourceReader sourceReader,
                                 UserRepository users) {
        this.registry = registry;
        this.docRepo = docRepo;
        this.notes = notes;
        this.promote = promote;
        this.writeGuard = writeGuard;
        this.writeService = writeService;
        this.syncService = syncService;
        this.sourceReader = sourceReader;
        this.users = users;
    }

    @GetMapping("/studio")
    public ResponseEntity<?> studio(@AuthenticationPrincipal UserDetails principal,
                                    @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = resolve(u.getId(), repoId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("writeEnabled", writeGuard.enabled(u.getId()));
        m.put("readable", registry.readable(u.getId()));
        List<Map<String, Object>> git = new ArrayList<>();
        if (repo != null) {
            m.put("repoId", repo.getId());
            m.put("repo", repo.getName());
            for (KbDocument d : docRepo.findByRepoId(repo.getId())) {
                String path = d.getPath() == null ? "" : d.getPath().replace('\\', '/');
                if (d.getKind() != KbDocument.DocKind.NOTE && !path.startsWith("docs/notes/")) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", path);
                row.put("title", d.getTitle());
                git.add(row);
            }
        }
        git.sort((a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
        m.put("gitNotes", git);

        List<Map<String, Object>> inbox = new ArrayList<>();
        List<Map<String, Object>> promoted = new ArrayList<>();
        for (Note n : notes.listVisibleByUser(u)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("title", n.getTitle());
            row.put("type", n.getType() == null ? "SCRATCH" : n.getType().name());
            boolean done = n.getPromotedPath() != null && !n.getPromotedPath().isBlank();
            if (done) {
                row.put("promotedPath", n.getPromotedPath());
                promoted.add(row);
            } else {
                inbox.add(row);
            }
        }
        m.put("inbox", inbox);
        m.put("alreadyPromoted", promoted);
        return ResponseEntity.ok(m);
    }

    public record PromoteBody(Long noteId, String pointId, String guidePath,
                              String anchor, String repoName) {}

    @PostMapping("/studio/promote")
    public ResponseEntity<?> promoteNote(@AuthenticationPrincipal UserDetails principal,
                                         @RequestBody PromoteBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (body == null || body.noteId() == null) {
            return ResponseEntity.badRequest().body(err("NOTE_ID", "缺少笔记 id"));
        }
        return ResponseEntity.ok(promote.promote(u, body.noteId(), body.pointId(),
                body.guidePath(), body.anchor(), body.repoName()));
    }

    public record WriteBody(String path, String content, String repoName, Long repoId) {}

    @PostMapping("/studio/write")
    public ResponseEntity<?> writeNote(@AuthenticationPrincipal UserDetails principal,
                                       @RequestBody WriteBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        DocWriteGuard.Decision en = writeGuard.checkEnabled(u.getId());
        if (!en.allowed()) {
            return ResponseEntity.ok(err(en.code(), en.message() + " " + nvl(en.hint())));
        }
        if (body == null || body.content() == null || body.content().isBlank()) {
            return ResponseEntity.badRequest().body(err("CONTENT_EMPTY", "正文为空"));
        }
        KnowledgeRepo repo = body.repoName() != null && !body.repoName().isBlank()
                ? registry.findByName(u.getId(), body.repoName().strip()).orElse(null)
                : resolve(u.getId(), body.repoId());
        if (repo == null) return ResponseEntity.ok(err("REPO_NOT_FOUND", "未找到知识仓库"));
        String rel = RepoIndexer.normalizeSlashes(
                body.path() == null ? "" : body.path().replace('\\', '/').strip());
        if (!rel.startsWith("docs/notes/")) {
            return ResponseEntity.ok(err("PATH_NOT_ALLOWED", "笔记工作室只能写 docs/notes/ 下的 Markdown。"));
        }
        DocWriteGuard.Decision p = writeGuard.checkPath(repo, rel);
        if (!p.allowed()) {
            return ResponseEntity.ok(err(p.code(), p.message() + " " + nvl(p.hint())));
        }
        boolean exists = Files.isRegularFile(registry.rootOf(repo).resolve(rel));
        DocWriteGuard.Decision creatable = writeGuard.checkCreatable(repo, rel, exists);
        if (!creatable.allowed()) {
            return ResponseEntity.ok(err(creatable.code(), creatable.message()));
        }
        DocWriteGuard.Decision size = writeGuard.checkSize(body.content());
        if (!size.allowed()) {
            return ResponseEntity.ok(err(size.code(), size.message()));
        }
        String branchName = writeService.branchNameFor("note", "studio");
        RepoWriteService.BranchResult br = writeService.ensureBranch(repo, branchName);
        if (!br.ok()) return ResponseEntity.ok(err(br.code(), br.message()));
        DocWriteGuard.Decision branch = writeGuard.checkBranch(repo);
        if (!branch.allowed()) {
            return ResponseEntity.ok(err(branch.code(), branch.message()));
        }
        Set<String> owned = new LinkedHashSet<>();
        owned.add(rel);
        DocWriteGuard.Decision tree = writeGuard.checkWorkingTree(repo, owned);
        if (!tree.allowed()) {
            return ResponseEntity.ok(err(tree.code(), tree.message()));
        }
        try {
            Path target = registry.rootOf(repo).resolve(rel);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, body.content(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            return ResponseEntity.ok(err("WRITE_FAILED", e.getMessage()));
        }
        try {
            syncService.sync(repo, false, false);
        } catch (Exception ignored) {
            // 文件已写入
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("path", rel);
        m.put("branch", br.branch());
        m.put("message", "已写入 " + rel + "。尚未 commit。");
        return ResponseEntity.ok(m);
    }

    @GetMapping("/doc/content")
    public ResponseEntity<?> content(@AuthenticationPrincipal UserDetails principal,
                                     @RequestParam String path,
                                     @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (!registry.readable(u.getId())) {
            return ResponseEntity.ok(err("CODEX_DISABLED", registry.notReadableHint()));
        }
        KnowledgeRepo repo = resolve(u.getId(), repoId);
        if (repo == null) return ResponseEntity.ok(err("REPO_NOT_FOUND", "未找到知识仓库"));
        String rel = RepoIndexer.normalizeSlashes(path == null ? "" : path.replace('\\', '/').strip());
        if (rel.isEmpty() || rel.contains("..")) {
            return ResponseEntity.ok(err("PATH_ILLEGAL", "非法路径"));
        }
        Path file = registry.rootOf(repo).resolve(rel).normalize();
        Path root = registry.rootOf(repo).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            return ResponseEntity.ok(err("PATH_ESCAPE", "路径越出仓库"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel);
        String lower = rel.toLowerCase();
        try {
            if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown")) {
                if (!Files.isRegularFile(file)) {
                    return ResponseEntity.ok(err("NOT_FOUND", "文件不存在：" + rel));
                }
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                m.put("ok", true);
                m.put("kind", "text");
                m.put("content", clip(raw));
                m.put("truncated", raw.length() > READ_MAX);
                return ResponseEntity.ok(m);
            }
            SourceReader.Source src = sourceReader.read(file);
            m.put("ok", src.ok() || src.plainText() != null);
            m.put("kind", "extract");
            m.put("code", src.code());
            m.put("message", src.message());
            if (src.plainText() != null) {
                m.put("content", clip(src.plainText()));
                m.put("truncated", src.plainText().length() > READ_MAX);
            }
            return ResponseEntity.ok(m);
        } catch (Exception e) {
            return ResponseEntity.ok(err("READ_FAILED", e.getMessage()));
        }
    }

    private KnowledgeRepo resolve(Long userId, Long repoId) {
        if (repoId != null) return registry.find(userId, repoId).orElse(null);
        var all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= READ_MAX ? s : s.substring(0, READ_MAX) + "\n\n…（已截断）";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return users.findByUsername(principal.getUsername()).orElse(null);
    }

    private ResponseEntity<?> unauth() {
        return ResponseEntity.status(401).body(err("UNAUTHENTICATED", "未登录"));
    }

    private Map<String, Object> err(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
