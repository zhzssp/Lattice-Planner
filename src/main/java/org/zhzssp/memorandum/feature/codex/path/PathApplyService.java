package org.zhzssp.memorandum.feature.codex.path;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;

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
 * 路径文件的提议 / 写入。Agent 只能提议，人确认才改权威文件。
 */
@Service
public class PathApplyService {

    public static final String DRAFT_PATH = "docs/learning-path.md.draft";

    public record Proposal(boolean ok, String code, String message,
                           boolean fileExisted, String preview, String delta,
                           int stations, int must, int skip, int version) {

        static Proposal fail(String code, String message) {
            return new Proposal(false, code, message, false, null, null, 0, 0, 0, 0);
        }
    }

    public record ApplyResult(boolean ok, String code, String message,
                              String branch, String path, List<String> changedFiles,
                              String reindex, boolean wroteDraft) {

        static ApplyResult fail(String code, String message) {
            return new ApplyResult(false, code, message, null, null, List.of(), null, false);
        }
    }

    private final LearningPathParser parser;
    private final LearningPathRenderer renderer;
    private final DocWriteGuard writeGuard;
    private final RepoRegistryService registry;
    private final RepoWriteService writeService;
    private final RepoSyncService syncService;
    private final PathProjector projector;

    public PathApplyService(LearningPathParser parser,
                            LearningPathRenderer renderer,
                            DocWriteGuard writeGuard,
                            RepoRegistryService registry,
                            RepoWriteService writeService,
                            RepoSyncService syncService,
                            PathProjector projector) {
        this.parser = parser;
        this.renderer = renderer;
        this.writeGuard = writeGuard;
        this.registry = registry;
        this.writeService = writeService;
        this.syncService = syncService;
        this.projector = projector;
    }

    public Proposal preview(Long userId, String repoName, String deltaOrFull) {
        KnowledgeRepo repo = resolveRepo(userId, repoName);
        if (repo == null) {
            return Proposal.fail("REPO_NOT_FOUND", "未找到知识仓库"
                    + (repoName == null ? "" : "：" + repoName));
        }
        return preview(repo, deltaOrFull);
    }

    public Proposal preview(KnowledgeRepo repo, String deltaOrFull) {
        Merge m = mergeOnDisk(repo, deltaOrFull);
        if (!m.ok()) {
            return Proposal.fail(m.code(), m.message());
        }
        return new Proposal(true, "PREVIEW", "路径补丁可解析，尚未写入。",
                m.fileExisted(), m.rendered(), m.delta(),
                m.merged().stations().size(), m.merged().mustCount(),
                m.merged().skipCount(), m.merged().version());
    }

    /**
     * 确认后写入权威路径文件。解析失败则只写 {@link #DRAFT_PATH}，不改权威文件。
     */
    public ApplyResult apply(Long userId, String repoName, String deltaOrFull, boolean confirmed) {
        if (!confirmed) {
            Proposal p = preview(userId, repoName, deltaOrFull);
            return new ApplyResult(p.ok(), p.ok() ? "NEED_CONFIRM" : p.code(),
                    p.ok() ? "请确认后再写入。预览已生成。" : p.message(),
                    null, null, List.of(), null, false);
        }
        DocWriteGuard.Decision en = writeGuard.checkEnabled(userId);
        if (!en.allowed()) {
            return ApplyResult.fail(en.code(), en.message() + " " + nvl(en.hint()));
        }
        KnowledgeRepo repo = resolveRepo(userId, repoName);
        if (repo == null) {
            return ApplyResult.fail("REPO_NOT_FOUND", "未找到知识仓库"
                    + (repoName == null ? "" : "：" + repoName));
        }
        Merge m = mergeOnDisk(repo, deltaOrFull);
        if (!m.ok()) {
            return ApplyResult.fail(m.code(), m.message());
        }

        boolean parseOk = m.merged().ok();
        String targetRel = parseOk ? LearningPathParser.DEFAULT_PATH : DRAFT_PATH;
        String content = parseOk ? m.rendered() : wrapDraft(m.delta(), m.message());

        DocWriteGuard.Decision p = writeGuard.checkPath(repo, targetRel);
        if (!p.allowed()) {
            return ApplyResult.fail(p.code(), p.message() + " " + nvl(p.hint()));
        }
        DocWriteGuard.Decision size = writeGuard.checkSize(content, writeGuard.maxGuideChars());
        if (!size.allowed()) {
            return ApplyResult.fail(size.code(), size.message() + " " + nvl(size.hint()));
        }

        String branchName = writeService.branchNameFor("path", "apply");
        RepoWriteService.BranchResult br = writeService.ensureBranch(repo, branchName);
        if (!br.ok()) {
            return ApplyResult.fail(br.code(), br.message());
        }
        DocWriteGuard.Decision branch = writeGuard.checkBranch(repo);
        if (!branch.allowed()) {
            return ApplyResult.fail(branch.code(), branch.message() + " " + nvl(branch.hint()));
        }

        Set<String> owned = new LinkedHashSet<>();
        owned.add(targetRel);
        DocWriteGuard.Decision tree = writeGuard.checkWorkingTree(repo, owned);
        if (!tree.allowed()) {
            return ApplyResult.fail(tree.code(), tree.message() + " " + nvl(tree.hint()));
        }

        Path root = registry.rootOf(repo);
        Path target = root.resolve(targetRel);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            return ApplyResult.fail("WRITE_FAILED", "写入失败：" + e.getMessage());
        }

        if (!parseOk) {
            return new ApplyResult(false, "PARSE_FAILED",
                    "补丁写入了草稿 " + DRAFT_PATH + "，权威路径文件未改。"
                            + "原因：" + m.message(),
                    br.branch(), DRAFT_PATH, List.of(DRAFT_PATH), null, true);
        }

        String reindex;
        try {
            RepoSyncService.SyncResult sr = syncService.sync(repo, false, false);
            reindex = "已增量重建索引：重建 " + sr.report().docsReindexed() + " 篇";
        } catch (Exception e) {
            reindex = "索引重建失败（文件已写入，可稍后同步）：" + e.getMessage();
        }
        try {
            projector.reconcile(userId, repo);
        } catch (Exception ignored) {
            // 投影失败不回滚文件：Git 仍是权威
        }
        return new ApplyResult(true, "WRITTEN",
                "已写入 " + LearningPathParser.DEFAULT_PATH
                        + "（version=" + m.merged().version() + "）。尚未 commit。",
                br.branch(), LearningPathParser.DEFAULT_PATH,
                new ArrayList<>(owned), reindex, false);
    }

    public Map<String, Object> toMap(Proposal p) {
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

    public Map<String, Object> toMap(ApplyResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("branch", r.branch());
        m.put("path", r.path());
        m.put("changedFiles", r.changedFiles());
        m.put("reindex", r.reindex());
        m.put("wroteDraft", r.wroteDraft());
        return m;
    }

    public String readExisting(KnowledgeRepo repo) {
        Path file = registry.rootOf(repo).resolve(LearningPathParser.DEFAULT_PATH);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private Merge mergeOnDisk(KnowledgeRepo repo, String deltaOrFull) {
        if (deltaOrFull == null || deltaOrFull.isBlank()) {
            return Merge.fail("EMPTY_DELTA", "路径补丁为空。");
        }
        String existing = readExisting(repo);
        boolean existed = existing != null && !existing.isBlank();
        LearningPathParser.ParsedPath current = null;
        if (existed) {
            current = parser.parseDocument(existing, true);
            if (!current.ok()) {
                return Merge.fail("EXISTING_PARSE_FAILED",
                        "权威路径文件当前无法解析：" + current.error()
                                + "。请先在 IDE 里修好，再应用补丁（避免整文件被一份局部补丁覆盖）。");
            }
        }
        LearningPathParser.ParsedPath incoming;
        if (looksLikeFullDocument(deltaOrFull)) {
            incoming = parser.parseDocument(deltaOrFull, true);
        } else {
            incoming = parser.parseDelta(deltaOrFull);
        }
        if (!incoming.ok()) {
            return new Merge(false, "DELTA_PARSE_FAILED", incoming.error(),
                    existed, deltaOrFull, null, null);
        }
        LearningPathParser.ParsedPath merged = renderer.merge(current, incoming);
        if (merged.cursor() == null && !merged.stations().isEmpty()) {
            merged = new LearningPathParser.ParsedPath(true, null, merged.version(),
                    merged.stations().get(0).id(), merged.stations());
        }
        LearningPathParser.ParsedPath checked = parser.parseDocument(renderer.render(merged), true);
        if (!checked.ok()) {
            return new Merge(false, "MERGED_PARSE_FAILED", checked.error(),
                    existed, deltaOrFull, merged, renderer.render(merged));
        }
        return new Merge(true, "OK", null, existed, deltaOrFull, checked, renderer.render(checked));
    }

    private static boolean looksLikeFullDocument(String text) {
        String t = text.strip();
        return t.startsWith("---") && t.contains("kind:");
    }

    private static String wrapDraft(String delta, String error) {
        return "<!-- PARSE_FAILED: " + (error == null ? "" : error.replace("--", "—")) + " -->\n\n"
                + (delta == null ? "" : delta);
    }

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        var all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private record Merge(boolean ok, String code, String message, boolean fileExisted,
                         String delta, LearningPathParser.ParsedPath merged, String rendered) {
        static Merge fail(String code, String message) {
            return new Merge(false, code, message, false, null, null, null);
        }
    }
}
