package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.codex.ci.CiCheck;
import org.zhzssp.memorandum.feature.codex.ci.KnowledgeCiService;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KbSection;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.repository.KbSectionRepository;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.sediment.SedimentService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策展与沉淀 REST API（P2）。
 *
 * <p>端点（均需登录）：</p>
 * <ul>
 *   <li>{@code POST /api/codex/ci/run}          — 跑一轮知识 CI</li>
 *   <li>{@code GET  /api/codex/ci/last}         — 上一轮报告（不重跑）</li>
 *   <li>{@code POST /api/codex/sediment}        — ★沉淀（服务端从会话取原文，模型无法伪造）</li>
 *   <li>{@code GET  /api/codex/sediment/draft}  — 由「通过但预测错」生成笔记草稿</li>
 *   <li>{@code GET  /api/codex/git/branches}    — 软件创建的工作分支</li>
 *   <li>{@code POST /api/codex/git/branch}      — 创建/切换分支</li>
 *   <li>{@code GET  /api/codex/git/diff}        — 审阅 diff</li>
 *   <li>{@code POST /api/codex/git/commit}      — 提交（精确到文件）</li>
 *   <li>{@code POST /api/codex/git/pr}          — 推送并开 PR</li>
 *   <li>{@code POST /api/codex/git/discard}     — 丢弃工作分支</li>
 * </ul>
 *
 * <h3>为什么沉淀的权威入口在这里而不在 Agent 工具</h3>
 * <p>「示例必须入库」的校验要拿被沉淀的<strong>原文</strong>做对比。
 * 走 Agent 工具时原文由模型自己传——相当于让它自己出考题自己答。
 * 走本端点时用户只传 {@code sessionId}，服务端从会话记忆里取那条 assistant 消息，
 * 模型碰不到这个环节。两条路径都保留，但可靠性差别必须说清楚。</p>
 */
@RestController
@RequestMapping("/api/codex")
public class CodexCurateController {

    private final KnowledgeCiService ci;
    private final SedimentService sediment;
    private final RepoWriteService writeService;
    private final RepoRegistryService registry;
    private final DocWriteGuard guard;
    private final KbDocumentRepository docRepo;
    private final KbSectionRepository sectionRepo;
    private final UserRepository userRepository;

    public CodexCurateController(KnowledgeCiService ci,
                                 SedimentService sediment,
                                 RepoWriteService writeService,
                                 RepoRegistryService registry,
                                 DocWriteGuard guard,
                                 KbDocumentRepository docRepo,
                                 KbSectionRepository sectionRepo,
                                 UserRepository userRepository) {
        this.ci = ci;
        this.sediment = sediment;
        this.writeService = writeService;
        this.registry = registry;
        this.guard = guard;
        this.docRepo = docRepo;
        this.sectionRepo = sectionRepo;
        this.userRepository = userRepository;
    }

    /* ==================== 配置回显 ==================== */

    @GetMapping("/curate/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codexEnabled", registry.enabled());
        m.put("operational", registry.operational());
        m.put("writeEnabled", guard.enabled());
        m.put("branchPrefix", guard.branchPrefix());
        // 回显白名单：用户看到「只能写这些路径」才会理解为什么 guide 改不了
        m.put("allowedWritePaths", guard.allowedPaths());
        return m;
    }

    /* ==================== 文档与锚点（沉淀表单用） ==================== */

    /**
     * 文档清单（可按 kind 过滤）。
     *
     * <p>沉淀表单需要它来做「挂靠文档」下拉：让用户从真实存在的路径里选，
     * 而不是手打——手打路径打错时的报错发生在流程末尾，体验差且容易归因错。</p>
     */
    @GetMapping("/repos/{id}/documents")
    public ResponseEntity<?> documents(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @RequestParam(required = false) String kind) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (registry.find(u.getId(), id).isEmpty()) {
            return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        }
        KbDocument.DocKind want = null;
        if (kind != null && !kind.isBlank()) {
            want = KbDocument.DocKind.of(kind);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbDocument d : docRepo.findByRepoId(id)) {
            if (want != null && d.getKind() != want) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", d.getPath());
            m.put("title", d.getTitle());
            m.put("kind", d.getKind().name());
            m.put("sections", sectionRepo.findByDocumentIdOrderByOrdAsc(d.getId()).size());
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing(x -> String.valueOf(x.get("path"))));
        return ResponseEntity.ok(out);
    }

    /**
     * 某篇文档的章节 anchor 列表。
     *
     * <p>沉淀表单强制从这里选 anchor 而不允许自由输入的原因很具体：
     * anchor 猜错不会报错，只会把速记引用插到一个无关章节里，
     * 而这种错误要等到半年后点开链接才会被发现。</p>
     */
    @GetMapping("/doc/anchors")
    public ResponseEntity<?> anchors(@AuthenticationPrincipal UserDetails principal,
                                     @RequestParam String path,
                                     @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        String p = path == null ? "" : path.strip().replace('\\', '/');
        KbDocument doc = null;
        List<KnowledgeRepo> scope = repoId != null
                ? registry.find(u.getId(), repoId).map(List::of).orElse(List.of())
                : registry.listEnabled(u.getId());
        for (KnowledgeRepo r : scope) {
            doc = docRepo.findByRepoIdAndPath(r.getId(), p).orElse(null);
            if (doc != null) break;
        }
        if (doc == null) {
            return ResponseEntity.status(404).body(err("NOT_FOUND",
                    "索引中没有这篇文档：" + p + "。可先同步索引。"));
        }
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (KbSection s : sectionRepo.findByDocumentIdOrderByOrdAsc(doc.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("anchor", s.getAnchor());
            m.put("heading", s.getHeading());
            m.put("level", s.getLevel());
            anchors.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", doc.getPath());
        out.put("title", doc.getTitle());
        out.put("anchors", anchors);
        return ResponseEntity.ok(out);
    }

    /* ==================== 知识 CI ==================== */

    @PostMapping("/ci/run")
    public ResponseEntity<?> runCi(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam Long repoId,
                                  @RequestParam(required = false) String checks) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = registry.find(u.getId(), repoId).orElse(null);
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        return ResponseEntity.ok(reportMap(ci.run(repo, parseChecks(checks))));
    }

    @GetMapping("/ci/last")
    public ResponseEntity<?> lastCi(@AuthenticationPrincipal UserDetails principal,
                                    @RequestParam Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (registry.find(u.getId(), repoId).isEmpty()) {
            return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        }
        CiCheck.Report r = ci.lastReport(repoId);
        if (r == null) {
            return ResponseEntity.ok(Map.of("hasReport", false,
                    "message", "尚未跑过检查。"));
        }
        Map<String, Object> m = reportMap(r);
        m.put("hasReport", true);
        return ResponseEntity.ok(m);
    }

    /* ==================== 沉淀 ==================== */

    /** 沉淀请求体。字段与 {@link SedimentService.Request} 对应。 */
    public record SedimentBody(String repoName, String title, String body, String summary,
                               String guidePath, String anchor, String sectionLabel,
                               String notePath, String sourceExcerpt, String sessionId,
                               String mode, Boolean createBranch, Boolean insertBackref) {}

    @PostMapping("/sediment")
    public ResponseEntity<?> doSediment(@AuthenticationPrincipal UserDetails principal,
                                        @RequestBody SedimentBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (body == null) return ResponseEntity.badRequest().body(err("BAD_BODY", "请求体为空"));

        SedimentService.WriteMode mode;
        try {
            mode = (body.mode() == null || body.mode().isBlank())
                    ? SedimentService.WriteMode.CREATE
                    : SedimentService.WriteMode.valueOf(body.mode().strip().toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(err("BAD_MODE", "mode 只能是 CREATE / APPEND / REPLACE"));
        }

        SedimentService.Request req = new SedimentService.Request(
                body.repoName(), body.title(), body.body(), body.summary(),
                body.guidePath(), body.anchor(), body.sectionLabel(), body.notePath(),
                body.sourceExcerpt(), body.sessionId(), mode,
                body.createBranch(), body.insertBackref());
        SedimentService.Result r = sediment.sediment(u.getId(), req);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("notePath", r.notePath());
        m.put("guidePath", r.guidePath());
        m.put("backrefLine", r.backrefLine());
        m.put("backrefText", r.backrefText());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("nextStep", r.nextStep());
        if (!r.availableAnchors().isEmpty()) m.put("availableAnchors", r.availableAnchors());
        return r.ok() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    /**
     * 由「通过但预测错」生成笔记草稿。
     *
     * <p>只返回草稿不写入：这类笔记记的是自己的认知被修正的瞬间，
     * 由机器代笔总结「我原来错在哪」是荒谬的。</p>
     */
    @GetMapping("/sediment/draft")
    public ResponseEntity<?> draft(@AuthenticationPrincipal UserDetails principal,
                                   @RequestParam String code) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        return sediment.divergenceDraft(u.getId(), code)
                .<ResponseEntity<?>>map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", d.title());
                    m.put("body", d.body());
                    m.put("summary", d.summary());
                    m.put("guidePath", d.guidePath());
                    m.put("anchor", d.anchor());
                    m.put("source", d.source());
                    m.put("_note", "这是草稿。正文需要你自己过一遍——"
                            + "「我原以为…实际…」是你的心智模型被修正的记录，不该由机器代笔。");
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(err("NO_DIVERGENCE",
                        "该检验条目不存在，或没有记录预测偏差。")));
    }

    /* ==================== Git ==================== */

    @GetMapping("/git/branches")
    public ResponseEntity<?> branches(@AuthenticationPrincipal UserDetails principal,
                                      @RequestParam Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = registry.find(u.getId(), repoId).orElse(null);
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", guard.branchPrefix());
        m.put("defaultBranch", repo.getDefaultBranch());
        m.put("branches", writeService.latticeBranches(repo));
        return ResponseEntity.ok(m);
    }

    public record BranchBody(Long repoId, String slug, String kind) {}

    @PostMapping("/git/branch")
    public ResponseEntity<?> createBranch(@AuthenticationPrincipal UserDetails principal,
                                          @RequestBody BranchBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = repoOf(u, body == null ? null : body.repoId());
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        String name = writeService.branchNameFor(
                body.kind() == null || body.kind().isBlank() ? "curate" : body.kind(), body.slug());
        RepoWriteService.BranchResult r = writeService.ensureBranch(repo, name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("created", r.created());
        return r.ok() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    @GetMapping("/git/diff")
    public ResponseEntity<?> diff(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam Long repoId,
                                  @RequestParam(required = false) String branch) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = registry.find(u.getId(), repoId).orElse(null);
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        RepoWriteService.DiffView v = writeService.diff(repo, branch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch", v.branch());
        m.put("base", v.base());
        m.put("uncommitted", v.uncommitted());
        m.put("files", v.files());
        m.put("patch", v.patch());
        return ResponseEntity.ok(m);
    }

    public record CommitBody(Long repoId, List<String> paths, String subject,
                             String note, String sessionId) {}

    @PostMapping("/git/commit")
    public ResponseEntity<?> commit(@AuthenticationPrincipal UserDetails principal,
                                    @RequestBody CommitBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = repoOf(u, body == null ? null : body.repoId());
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        RepoWriteService.CommitResult r = writeService.commit(repo, body.paths(),
                body.subject(), body.note(), body.sessionId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("sha", r.sha());
        m.put("branch", r.branch());
        m.put("files", r.files());
        return r.ok() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    public record PrBody(Long repoId, String branch, String title, String body) {}

    @PostMapping("/git/pr")
    public ResponseEntity<?> openPr(@AuthenticationPrincipal UserDetails principal,
                                    @RequestBody PrBody req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = repoOf(u, req == null ? null : req.repoId());
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        String branch = req.branch();
        if (branch == null || branch.isBlank()) {
            branch = writeService.diff(repo, null).branch();
        }
        RepoWriteService.PushResult r = writeService.pushAndOpenPr(
                repo, branch, req.title(), req.body());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("prNumber", r.prNumber());
        m.put("prUrl", r.prUrl());
        return r.ok() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    public record DiscardBody(Long repoId, String branch) {}

    @PostMapping("/git/discard")
    public ResponseEntity<?> discard(@AuthenticationPrincipal UserDetails principal,
                                     @RequestBody DiscardBody body) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        KnowledgeRepo repo = repoOf(u, body == null ? null : body.repoId());
        if (repo == null) return ResponseEntity.status(404).body(err("REPO_NOT_FOUND", "仓库不存在"));
        RepoWriteService.BranchResult r = writeService.discardBranch(repo, body.branch());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        return r.ok() ? ResponseEntity.ok(m) : ResponseEntity.badRequest().body(m);
    }

    /* ==================== 内部 ==================== */

    private Map<String, Object> reportMap(CiCheck.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repoId", r.repoId());
        m.put("repo", r.repoName());
        m.put("passed", r.passed());
        m.put("errors", r.errors());
        m.put("warns", r.warns());
        m.put("infos", r.infos());
        m.put("durationMs", r.durationMs());

        List<Map<String, Object>> checks = new ArrayList<>();
        for (CiCheck.CheckResult cr : r.checks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("check", cr.check().name());
            row.put("label", cr.check().label());
            row.put("description", cr.check().description());
            row.put("status", cr.status().name());
            row.put("skipReason", cr.skipReason());
            row.put("scanned", cr.scanned());
            row.put("errors", cr.errors());
            row.put("warns", cr.warns());
            row.put("infos", cr.infos());
            row.put("durationMs", cr.durationMs());
            List<Map<String, Object>> fs = new ArrayList<>();
            for (CiCheck.Finding f : cr.findings()) {
                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("severity", f.severity().name());
                fr.put("path", f.path());
                fr.put("line", f.line());
                fr.put("locator", f.locator());
                fr.put("message", f.message());
                fr.put("hint", f.hint());
                fs.add(fr);
            }
            row.put("findings", fs);
            checks.add(row);
        }
        m.put("checks", checks);
        return m;
    }

    private Set<CiCheck.CheckId> parseChecks(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.allOf(CiCheck.CheckId.class);
        Set<CiCheck.CheckId> out = EnumSet.noneOf(CiCheck.CheckId.class);
        for (String s : raw.split(",")) {
            String k = s.strip().toUpperCase().replace('-', '_');
            if (k.isEmpty()) continue;
            try {
                out.add(CiCheck.CheckId.valueOf(k));
            } catch (Exception ignored) {
                // 忽略无效名
            }
        }
        return out.isEmpty() ? EnumSet.allOf(CiCheck.CheckId.class) : out;
    }

    private KnowledgeRepo repoOf(User u, Long repoId) {
        if (repoId != null) return registry.find(u.getId(), repoId).orElse(null);
        List<KnowledgeRepo> all = registry.listEnabled(u.getId());
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
