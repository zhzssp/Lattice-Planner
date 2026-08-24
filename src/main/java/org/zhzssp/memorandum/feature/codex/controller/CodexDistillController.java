package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.mcp.McpLocalFileService;
import org.zhzssp.memorandum.feature.codex.distill.DistillGuard;
import org.zhzssp.memorandum.feature.codex.distill.DistillService;
import org.zhzssp.memorandum.feature.codex.distill.ExamService;
import org.zhzssp.memorandum.feature.codex.entity.KbDocument;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.repository.KbDocumentRepository;
import org.zhzssp.memorandum.feature.codex.route.RouteService;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蒸馏 / 出题 / 定线 的 REST API（P4）。
 *
 * <ul>
 *   <li>{@code GET  /api/codex/distill/config}   — 三个开关与目标目录</li>
 *   <li>{@code GET  /api/codex/distill/sources}  — 可用原料清单（仓库内 + MCP 白名单目录）</li>
 *   <li>{@code POST /api/codex/distill/draft}    — 起草 guide（★不落盘）</li>
 *   <li>{@code POST /api/codex/distill/write}    — 落盘（只新建）</li>
 *   <li>{@code POST /api/codex/exam/draft}       — 起草检验题（★不落盘）</li>
 *   <li>{@code POST /api/codex/exam/write}       — 落盘并载入检验表</li>
 *   <li>{@code GET  /api/codex/route}            — 「我现在该干什么」+ 阶段表</li>
 * </ul>
 *
 * <h3>起草与落盘分成两个请求，不提供合并端点</h3>
 * <p>合并会省一次往返，但会让用户在看到产物之前文件就已经落进仓库了。
 * 蒸馏产物的质量取决于 PDF 排版与论文写法，波动很大——
 * 先看后写这一步是这个功能能不能被信任的关键。</p>
 */
@RestController
@RequestMapping("/api/codex")
public class CodexDistillController {

    /** 草稿暂存（与工具层各自独立，互不干扰）。 */
    private final Map<String, DistillService.Draft> guideDrafts = new ConcurrentHashMap<>();
    private final Map<String, ExamService.Draft> examDrafts = new ConcurrentHashMap<>();

    private final DistillService distill;
    private final ExamService exam;
    private final RouteService route;
    private final RepoRegistryService registry;
    private final KbDocumentRepository docRepo;
    private final DocWriteGuard writeGuard;
    private final McpLocalFileService localFiles;
    private final UserRepository userRepository;

    public CodexDistillController(DistillService distill,
                                  ExamService exam,
                                  RouteService route,
                                  RepoRegistryService registry,
                                  KbDocumentRepository docRepo,
                                  DocWriteGuard writeGuard,
                                  McpLocalFileService localFiles,
                                  UserRepository userRepository) {
        this.distill = distill;
        this.exam = exam;
        this.route = route;
        this.registry = registry;
        this.docRepo = docRepo;
        this.writeGuard = writeGuard;
        this.localFiles = localFiles;
        this.userRepository = userRepository;
    }

    /* ==================== 配置 ==================== */

    @GetMapping("/distill/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("distillEnabled", distill.enabled());
        m.put("examEnabled", exam.enabled());
        m.put("writeEnabled", writeGuard.enabled());
        m.put("outputDir", distill.outputDir());
        m.put("createOnlyPaths", writeGuard.createOnlyPaths());
        m.put("maxGuideChars", writeGuard.maxGuideChars());
        // 三个开关各自独立、可分别打开，这一点必须说清：
        // 「起草能用但写入不能用」是完全正常的中间状态
        m.put("hint", "起草（draft）只需 codex.distill.enabled / codex.exam.enabled，"
                + "不碰磁盘；落盘还需要 codex.write.enabled。"
                + "建议先只开起草，看几篇产物质量再决定给不给写权限。"
                + "★注意 create-only：蒸馏与出题只能新建文件，"
                + "既有的 guide 与检验册永远不会被机器覆盖。");
        return m;
    }

    /* ==================== 原料清单 ==================== */

    /**
     * 列出可蒸馏的原料。
     *
     * <p>两处来源分开列并标明出处：用户需要知道某份 PDF 是「在仓库里」
     * 还是「只在本机」——前者的溯源信息能写进 front-matter 跟着仓库走，
     * 后者只能记个文件名，换台机器就找不到原文了。</p>
     */
    @GetMapping("/distill/sources")
    public ResponseEntity<?> sources(@AuthenticationPrincipal UserDetails principal) {
        User u = currentUser(principal);
        if (u == null) return unauth();

        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeRepo r : registry.listEnabled(u.getId())) {
            Path root;
            try {
                root = registry.rootOf(r);
            } catch (Exception e) {
                continue;
            }
            collect(out, root, root, r.getName(), "REPO", 3);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sources", out);
        m.put("localFilesEnabled", localFiles.isEnabled());
        if (!localFiles.isEnabled()) {
            m.put("localHint", "MCP 本地文件未启用（mcp.server.local-files-enabled=false），"
                    + "因此只能蒸馏已登记仓库内的原料。"
                    + "本功能刻意复用 MCP 的白名单而不另立一套——"
                    + "两套白名单里必有一套会被忘记维护。");
        }
        return ResponseEntity.ok(m);
    }

    private void collect(List<Map<String, Object>> sink, Path root, Path dir,
                         String repoName, String origin, int depth) {
        if (depth < 0 || sink.size() >= 200) return;
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = String.valueOf(p.getFileName());
                if (name.startsWith(".") || name.equals("node_modules")
                        || name.equals("out") || name.equals("build")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    collect(sink, root, p, repoName, origin, depth - 1);
                } else if (distill.supports(name)) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("path", root.relativize(p).toString().replace('\\', '/'));
                    x.put("name", name);
                    x.put("repo", repoName);
                    x.put("origin", origin);
                    try {
                        x.put("sizeKb", Files.size(p) / 1024);
                    } catch (Exception ignored) {
                        x.put("sizeKb", -1);
                    }
                    sink.add(x);
                }
            }
        } catch (Exception ignored) {
            // 目录不可读就跳过，不影响其余
        }
    }

    /* ==================== 蒸馏 ==================== */

    public record DraftRequest(String sourcePath, String title, String domain, String repoName) {}

    @PostMapping("/distill/draft")
    public ResponseEntity<?> draft(@AuthenticationPrincipal UserDetails principal,
                                   @RequestBody DraftRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (req == null || req.sourcePath() == null || req.sourcePath().isBlank()) {
            return ResponseEntity.badRequest().body(err("SOURCE_REQUIRED", "未指定原料路径"));
        }
        Path file = resolveSource(u.getId(), req.repoName(), req.sourcePath());
        if (file == null) {
            return ResponseEntity.status(404).body(err("SOURCE_DENIED",
                    "找不到该原料或它不在允许访问的范围内：" + req.sourcePath()));
        }

        DistillService.Draft d = distill.draft(u.getId(), file, req.title(), req.domain());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", d.ok());
        m.put("code", d.code());
        m.put("message", d.message());
        m.put("title", d.title());
        m.put("targetPath", d.targetPath());
        m.put("llmCalls", d.llmCalls());
        m.put("elapsedMs", d.elapsedMs());
        if (d.source() != null) {
            m.put("source", Map.of(
                    "fileName", d.source().fileName(),
                    "pages", d.source().pageCount(),
                    "chars", d.source().charCount(),
                    "charsPerPage", d.source().charsPerPage(),
                    "chunks", d.source().chunks().size()));
        }
        if (d.verdict() != null) m.put("structureCheck", verdict(d.verdict()));
        // 未通过也把内容返回：用户要能看到「差在哪」，而不是只看到一句被拒
        m.put("content", d.content());
        if (d.ok()) {
            String key = "g" + Math.abs(System.nanoTime() % 1_000_000);
            if (guideDrafts.size() > 16) guideDrafts.clear();
            guideDrafts.put(u.getId() + "|" + key, d);
            m.put("draftKey", key);
        }
        return ResponseEntity.ok(m);
    }

    public record WriteRequest(String draftKey, String path, String repoName) {}

    @PostMapping("/distill/write")
    public ResponseEntity<?> write(@AuthenticationPrincipal UserDetails principal,
                                   @RequestBody WriteRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        DistillService.Draft d = req == null ? null
                : guideDrafts.get(u.getId() + "|" + req.draftKey());
        if (d == null) {
            return ResponseEntity.status(404).body(err("DRAFT_NOT_FOUND",
                    "找不到该草稿（可能已过期或服务重启）。请重新起草。"));
        }
        DistillService.WriteResult r = distill.write(u.getId(), req.repoName(), d, req.path());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("skipTerms", r.skipTerms());
        m.put("nextStep", r.nextStep());
        return ResponseEntity.ok(m);
    }

    /* ==================== 出题 ==================== */

    public record ExamRequest(String guidePath, String labDir, Integer count, String repoName) {}

    @PostMapping("/exam/draft")
    public ResponseEntity<?> examDraft(@AuthenticationPrincipal UserDetails principal,
                                       @RequestBody ExamRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        if (req == null || req.guidePath() == null || req.guidePath().isBlank()) {
            return ResponseEntity.badRequest().body(err("GUIDE_REQUIRED", "未指定知识文档"));
        }
        ExamService.Draft d = exam.draft(u.getId(), req.repoName(),
                req.guidePath(), req.labDir(), req.count());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", d.ok());
        m.put("code", d.errorCode());
        m.put("message", d.message());
        m.put("guidePath", d.guidePath());
        m.put("labDir", d.labDir());
        m.put("accepted", d.accepted());
        List<Map<String, Object>> dis = new ArrayList<>();
        for (ExamService.Discarded x : d.discarded()) {
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("code", x.code());
            y.put("reason", x.reason());
            dis.add(y);
        }
        m.put("discarded", dis);
        m.put("content", d.content());
        m.put("targetPath", d.targetPath());
        if (d.ok()) {
            String key = "e" + Math.abs(System.nanoTime() % 1_000_000);
            if (examDrafts.size() > 16) examDrafts.clear();
            examDrafts.put(u.getId() + "|" + key, d);
            m.put("draftKey", key);
        }
        return ResponseEntity.ok(m);
    }

    @PostMapping("/exam/write")
    public ResponseEntity<?> examWrite(@AuthenticationPrincipal UserDetails principal,
                                       @RequestBody WriteRequest req) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        ExamService.Draft d = req == null ? null
                : examDrafts.get(u.getId() + "|" + req.draftKey());
        if (d == null) {
            return ResponseEntity.status(404).body(err("DRAFT_NOT_FOUND", "找不到该题目草稿。"));
        }
        ExamService.WriteResult r = exam.write(u.getId(), req.repoName(), d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("loadedIntoDb", r.loadedIntoDb());
        m.put("nextStep", r.nextStep());
        return ResponseEntity.ok(m);
    }

    /* ==================== 出题目标：可出题的文档 + lab 候选 ==================== */

    /**
     * 列出可出题的文档，并给出它<strong>实际存在的</strong> lab 候选。
     *
     * <p>lab 候选按命名约定推断但一定要 {@code isDirectory} 验过才返回。
     * 返回一个猜测的目录名会让用户直接选中它，然后在最后一步被拒——
     * 而那时他很难判断是自己填错了还是软件坏了。</p>
     */
    @GetMapping("/exam/targets")
    public ResponseEntity<?> examTargets(@AuthenticationPrincipal UserDetails principal,
                                         @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        List<KnowledgeRepo> scope = repoId == null
                ? registry.listEnabled(u.getId())
                : registry.find(u.getId(), repoId).map(List::of).orElse(List.of());

        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeRepo r : scope) {
            Path root;
            try {
                root = registry.rootOf(r);
            } catch (Exception e) {
                continue;
            }
            for (KbDocument d : docRepo.findByRepoId(r.getId())) {
                if (d.getKind() != KbDocument.DocKind.GUIDE) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("path", d.getPath());
                x.put("title", d.getTitle());
                x.put("repoId", r.getId());
                x.put("labCandidates", labCandidates(root, d.getPath()));
                out.add(x);
            }
        }
        out.sort(java.util.Comparator.comparing(x -> String.valueOf(x.get("path"))));
        return ResponseEntity.ok(out);
    }

    private List<String> labCandidates(Path root, String guidePath) {
        String base = guidePath.substring(guidePath.lastIndexOf('/') + 1)
                .replaceAll("\\.md$", "")
                .replaceAll("-learning-guide$", "")
                .replaceAll("-guide$", "");
        List<String> out = new ArrayList<>();
        for (String c : List.of(base + "-lab", base + "-compile", base + "-dialect", base)) {
            if (Files.isDirectory(root.resolve(c))) out.add(c);
        }
        // 兜底：列出仓库根下所有 *-lab 目录，让用户自己挑
        try (var s = Files.list(root)) {
            for (Path p : s.toList()) {
                String n = String.valueOf(p.getFileName());
                if (Files.isDirectory(p) && n.endsWith("-lab") && !out.contains(n)) {
                    out.add(n);
                }
            }
        } catch (Exception ignored) {
            // 列不出来就只返回按约定命中的
        }
        return out;
    }

    /* ==================== 定线 ==================== */

    @GetMapping("/route")
    public ResponseEntity<?> route(@AuthenticationPrincipal UserDetails principal,
                                   @RequestParam(required = false) Long repoId) {
        User u = currentUser(principal);
        if (u == null) return unauth();
        RouteService.Route r = route.compute(u.getId(), repoId);

        List<Map<String, Object>> acts = new ArrayList<>();
        for (RouteService.Action a : r.actions()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", a.kind());
            x.put("weight", a.weight());
            x.put("what", a.what());
            x.put("why", a.why());
            x.put("ref", a.ref());
            x.put("href", a.href());
            acts.add(x);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        for (RouteService.Stage s : r.stages()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("topic", s.topic());
            x.put("guidePath", s.guidePath());
            x.put("guideTitle", s.guideTitle());
            x.put("maturity", s.maturity());
            x.put("labDir", s.labDir());
            x.put("labExists", s.labExists());
            x.put("checkpointTotal", s.checkpointTotal());
            x.put("passed", s.passed());
            x.put("failed", s.failed());
            x.put("todo", s.todo());
            x.put("l2Passed", s.l2Passed());
            x.put("agentDrafted", s.agentDrafted());
            stages.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", r.repoName());
        m.put("actions", acts);
        m.put("stages", stages);
        m.put("summary", r.summary());
        m.put("caveats", r.caveats());
        return ResponseEntity.ok(m);
    }

    /* ==================== 内部 ==================== */

    private Map<String, Object> verdict(DistillGuard.Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pass", v.pass());
        m.put("summary", v.summary());
        m.put("skipTerms", v.skipTerms());
        m.put("errors", findings(v.errors()));
        m.put("warns", findings(v.warns()));
        return m;
    }

    private List<Map<String, Object>> findings(List<DistillGuard.Finding> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DistillGuard.Finding f : in) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", f.code());
            x.put("message", f.message());
            x.put("hint", f.hint());
            out.add(x);
        }
        return out;
    }

    private Path resolveSource(Long userId, String repoName, String raw) {
        String s = raw.replace('\\', '/').strip();
        if (!s.startsWith("/") && !s.matches("^[A-Za-z]:/.*")) {
            for (KnowledgeRepo r : registry.listEnabled(userId)) {
                if (repoName != null && !repoName.isBlank()
                        && !repoName.strip().equalsIgnoreCase(r.getName())) {
                    continue;
                }
                try {
                    Path root = registry.rootOf(r).toRealPath();
                    Path f = root.resolve(s).normalize();
                    if (f.startsWith(root) && Files.isRegularFile(f)) return f;
                } catch (Exception ignored) {
                    // 试下一个仓库
                }
            }
            return null;
        }
        try {
            Path f = Path.of(s).normalize();
            if (Files.isRegularFile(f) && localFiles.isAllowed(f)) return f;
        } catch (Exception ignored) {
            // 非法路径
        }
        return null;
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
