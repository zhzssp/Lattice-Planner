package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoWriteService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 分支 / 提交 / PR 工具集（仅 curate 模式可见）。
 *
 * <h3>为什么提交必须是一次独立的、需确认的调用</h3>
 * <p>它不是技术限制，而是产品语义：<strong>提交表达的是「我认可这份产出」</strong>。
 * 若沉淀工具顺手把内容提交了，用户就没有说「不」的位置——
 * 而 Agent 起草的知识一旦未经审阅进入历史，用户对整个知识库的信任会一次性崩掉，
 * 此后每条检索结果都要复核，产品价值归零。</p>
 *
 * <h3>没有 repo.push 这个单独工具</h3>
 * <p>推送与开 PR 合成一个 {@code repo.open_pr}：单独推一个没有 PR 的分支
 * 对审阅没有帮助，只会在远端积累无人处理的分支。</p>
 */
@Component
public class GitTools {

    private final RepoWriteService writeService;
    private final RepoRegistryService registry;
    private final DocWriteGuard guard;

    public GitTools(RepoWriteService writeService,
                    RepoRegistryService registry,
                    DocWriteGuard guard) {
        this.writeService = writeService;
        this.registry = registry;
        this.guard = guard;
    }

    @AgentTool(name = "repo.branch", tags = {"codex", "git", "write"}, requiresConfirm = true,
            description = "在知识仓库中创建（或切换到）一个工作分支，名称形如 "
                    + "lattice/curate-20260821-fix-links。用于整理前先离开默认分支。"
                    + "注意：工作副本有未提交改动时会被拒绝——本工具不会自动 stash。")
    public Map<String, Object> branch(
            @ToolParam(value = "slug", desc = "分支用途短标识，如 fix-broken-links",
                    required = true) String slug,
            @ToolParam(value = "kind", desc = "分支类别：curate（默认）/ sediment") String kind,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        String name = writeService.branchNameFor(
                kind == null || kind.isBlank() ? "curate" : kind, slug);
        RepoWriteService.BranchResult r = writeService.ensureBranch(repo, name);
        Map<String, Object> m = new LinkedHashMap<>();
        if (!r.ok()) {
            m.put("error", r.code());
            m.put("message", r.message());
            return m;
        }
        m.put("ok", true);
        m.put("branch", r.branch());
        m.put("created", r.created());
        m.put("message", r.message());
        return m;
    }

    @AgentTool(name = "repo.diff", tags = {"codex", "read"},
            description = "查看知识仓库当前的改动（未提交时看工作副本，已提交时看分支相对默认分支）。"
                    + "提交前必须先看一遍：它是「改了哪些文件、改了什么」的唯一可靠依据。"
                    + "patch 过长会被截断并标注。")
    public Map<String, Object> diff(
            @ToolParam(value = "branch", desc = "分支名；省略则用当前分支") String branch,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (!registry.operational()) {
            return Map.of("error", "CODEX_DISABLED", "message", "知识仓库功能不可用。");
        }
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        RepoWriteService.DiffView v = writeService.diff(repo, branch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch", v.branch());
        m.put("base", v.base());
        m.put("uncommitted", v.uncommitted());
        m.put("changedFiles", v.files());
        m.put("patch", v.patch());
        return m;
    }

    @AgentTool(name = "repo.commit", tags = {"codex", "git", "write"}, requiresConfirm = true,
            description = "提交知识仓库中指定的文件。必须逐一列出文件路径——不存在「提交全部」的选项。"
                    + "调用前应先用 repo.diff 让用户看过改动。"
                    + "会被拒绝的情况：当前在默认分支上、暂存区里有别处 add 的文件、"
                    + "或路径不在写入白名单内。")
    public Map<String, Object> commit(
            @ToolParam(value = "paths", desc = "要提交的文件相对路径，逗号分隔",
                    required = true) String paths,
            @ToolParam(value = "subject", desc = "提交标题，如 docs(notes): 沉淀 LLVM phi",
                    required = true) String subject,
            @ToolParam(value = "note", desc = "提交说明正文（可空）") String note,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        List<String> list = splitPaths(paths);
        if (list.isEmpty()) {
            return Map.of("error", "NO_PATHS", "message", "未指定任何文件路径。");
        }
        RepoWriteService.CommitResult r = writeService.commit(
                repo, list, subject, note, AgentContext.sessionId());

        Map<String, Object> m = new LinkedHashMap<>();
        if (!r.ok()) {
            m.put("error", r.code());
            m.put("message", r.message());
            if (!r.files().isEmpty()) m.put("files", r.files());
            return m;
        }
        m.put("ok", true);
        m.put("sha", r.sha());
        m.put("branch", r.branch());
        m.put("files", r.files());
        m.put("message", r.message());
        m.put("nextStep", "如需在 GitHub 上审阅，调用 repo.open_pr；"
                + "本地仓库则到此结束，用户可自行合并或丢弃分支。");
        return m;
    }

    @AgentTool(name = "repo.open_pr", tags = {"codex", "git", "write"}, requiresConfirm = true,
            description = "推送当前工作分支并在 GitHub 上创建 Pull Request，供用户逐行审阅。"
                    + "仅当仓库 provider=GITHUB 且已配置 token 时可用；"
                    + "本地仓库会明确返回 PROVIDER_LOCAL（改动仍在本地分支上，不会丢）。")
    public Map<String, Object> openPr(
            @ToolParam(value = "title", desc = "PR 标题", required = true) String title,
            @ToolParam(value = "body", desc = "PR 说明：改了什么、为什么、怎么验") String body,
            @ToolParam(value = "branch", desc = "要推送的分支；省略则用当前分支") String branch,
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        String b = branch;
        if (b == null || b.isBlank()) {
            b = writeService.diff(repo, null).branch();
        }
        if (b == null || b.isBlank()) {
            return Map.of("error", "BRANCH_UNKNOWN", "message", "无法确定当前分支。");
        }
        if (b.equals(repo.getDefaultBranch())) {
            return Map.of("error", "ON_DEFAULT_BRANCH",
                    "message", "当前在默认分支上，没有可开 PR 的工作分支。"
                            + "先用 repo.branch 创建分支。");
        }
        RepoWriteService.PushResult r = writeService.pushAndOpenPr(repo, b, title, body);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("branch", r.branch());
        m.put("message", r.message());
        if (r.prNumber() != null) m.put("prNumber", r.prNumber());
        if (r.prUrl() != null) m.put("prUrl", r.prUrl());
        if (!r.ok()) m.put("error", r.code());
        return m;
    }

    @AgentTool(name = "repo.branches", tags = {"codex", "read"},
            description = "列出本软件创建的工作分支（lattice/ 前缀）。"
                    + "用于回答「我还有哪些没处理完的整理/沉淀」。")
    public Map<String, Object> branches(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = resolveRepo(u.getId(), repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到知识仓库。");
        }
        List<String> bs = writeService.latticeBranches(repo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", guard.branchPrefix());
        m.put("count", bs.size());
        m.put("branches", bs);
        m.put("defaultBranch", repo.getDefaultBranch());
        if (bs.isEmpty()) {
            m.put("_hint", "没有待处理的工作分支。");
        }
        return m;
    }

    /* ---------------- 内部 ---------------- */

    private KnowledgeRepo resolveRepo(Long userId, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(userId, repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private List<String> splitPaths(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : raw.split("[,\\n]")) {
            String p = s.strip();
            if (!p.isEmpty()) out.add(p.replace('\\', '/'));
        }
        return new java.util.ArrayList<>(out);
    }
}
