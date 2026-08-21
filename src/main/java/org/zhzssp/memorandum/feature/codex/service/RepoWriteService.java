package org.zhzssp.memorandum.feature.codex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.git.GitHubPrClient;
import org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识仓库的 Git 写入编排。
 *
 * <h3>四条铁律（全部为执行层校验，不是文档约定）</h3>
 * <ol>
 *   <li><strong>永不向默认分支提交</strong>——默认分支上的提交不经审阅即进入历史；</li>
 *   <li><strong>脏工作副本一律拒绝，绝不 stash</strong>——把用户正在编辑的内容
 *       挪到他不知道的地方，比拒绝执行糟糕得多；</li>
 *   <li><strong>{@code git add} 精确到文件，且提交前自校验暂存区</strong>——
 *       知识仓库同时是动手实验目录，本机常有未 ignore 的编译产物；</li>
 *   <li><strong>提交信息标注 Agent 参与</strong>——半年后用 {@code git log} 就能分清
 *       哪些内容是自己写的、哪些是机器起草的。这条影响的是对整个知识库的信任度。</li>
 * </ol>
 *
 * <h3>为什么第 3 条要「提交前自校验」而不只是精确 add</h3>
 * <p>精确 add 只保证「我加了什么」，不保证「暂存区里只有这些」。
 * 若用户在别处已 {@code git add} 过其他文件，那些内容会被搭车提交进这次 PR。
 * 比对 {@code git diff --cached --name-only} 与预期集合，才能把这种搭车挡住。</p>
 */
@Service
public class RepoWriteService {

    private static final Logger log = LoggerFactory.getLogger(RepoWriteService.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 提交署名 trailer——让 git blame / GitHub 都能识别机器参与。 */
    private static final String CO_AUTHOR =
            "Co-authored-by: Lattice Agent <lattice-agent@localhost>";

    public record BranchResult(boolean ok, String code, String message, String branch,
                               boolean created) {}

    public record CommitResult(boolean ok, String code, String message, String sha,
                               String branch, List<String> files) {}

    public record PushResult(boolean ok, String code, String message, String branch,
                             Integer prNumber, String prUrl) {}

    public record DiffView(String branch, String base, List<String> files, String patch,
                           boolean uncommitted) {}

    private final RepoRegistryService registry;
    private final GitClient git;
    private final GitHubPrClient prClient;
    private final DocWriteGuard guard;

    @Value("${codex.write.diff-max-chars:20000}")
    private int diffMaxChars;

    public RepoWriteService(RepoRegistryService registry,
                            GitClient git,
                            GitHubPrClient prClient,
                            DocWriteGuard guard) {
        this.registry = registry;
        this.git = git;
        this.prClient = prClient;
        this.guard = guard;
    }

    /* ==================== 分支 ==================== */

    /** 生成规范分支名：{@code lattice/sediment-20260821-llvm-phi}。 */
    public String branchNameFor(String kind, String slug) {
        String k = (kind == null || kind.isBlank()) ? "sediment" : kind.strip();
        String s = (slug == null || slug.isBlank()) ? "note" : slug.strip();
        return guard.branchPrefix() + k + "-" + LocalDate.now().format(DATE) + "-" + s;
    }

    /**
     * 确保工作分支存在并已切换过去。
     *
     * <p>新分支<strong>从默认分支拉</strong>而非从当前分支：否则第二次沉淀会叠在第一次之上，
     * 第一个 PR 被否时第二个也连带作废。每次独立起点才能各自合并或丢弃。</p>
     */
    public BranchResult ensureBranch(KnowledgeRepo repo, String branch) {
        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return new BranchResult(false, en.code(), en.message(), null, false);
        }
        if (branch == null || branch.isBlank()) {
            return new BranchResult(false, "BRANCH_EMPTY", "分支名为空。", null, false);
        }
        String b = branch.strip();
        if (!b.startsWith(guard.branchPrefix())) {
            return new BranchResult(false, "BRANCH_PREFIX",
                    "分支名必须以 " + guard.branchPrefix() + " 开头，实际：" + b
                            + "。统一命名空间是为了让「哪些分支是软件建的」一目了然，"
                            + "从而可以安全地批量清理。", null, false);
        }

        Path root = registry.rootOf(repo);
        String def = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        try {
            if (git.branchExists(root, b)) {
                if (!b.equals(git.currentBranch(root))) {
                    // 切分支会改动工作副本文件，必须先确认没有未提交内容会被覆盖
                    DocWriteGuard.Decision clean = guard.checkWorkingTree(repo, Set.of());
                    if (!clean.allowed()) {
                        return new BranchResult(false, clean.code(), clean.message(), b, false);
                    }
                    git.checkout(root, b);
                }
                return new BranchResult(true, "EXISTS", "分支已存在，已切换。", b, false);
            }
            DocWriteGuard.Decision clean = guard.checkWorkingTree(repo, Set.of());
            if (!clean.allowed()) {
                return new BranchResult(false, clean.code(), clean.message(), b, false);
            }
            git.createBranch(root, b, def);
            return new BranchResult(true, "CREATED",
                    "已从 " + def + " 创建并切到分支 " + b, b, true);
        } catch (Exception e) {
            return new BranchResult(false, "GIT_FAILED",
                    "创建/切换分支失败：" + e.getMessage(), b, false);
        }
    }

    /**
     * 丢弃软件创建的分支：切回默认分支并删除。
     *
     * <p>只允许删自己命名空间下的分支。丢弃前要求工作副本干净——
     * {@code branch -D} 之后那些改动就再也找不回来了。</p>
     */
    public BranchResult discardBranch(KnowledgeRepo repo, String branch) {
        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return new BranchResult(false, en.code(), en.message(), branch, false);
        }
        if (branch == null || !branch.startsWith(guard.branchPrefix())) {
            return new BranchResult(false, "BRANCH_PREFIX",
                    "只允许丢弃 " + guard.branchPrefix() + " 命名空间下的分支。"
                            + "你自己的分支不在本软件的处置范围内。", branch, false);
        }
        Path root = registry.rootOf(repo);
        String def = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        try {
            String current = git.currentBranch(root);
            if (branch.equals(current)) {
                // 未提交的改动一旦随分支删除就无法恢复，这里必须挡住
                GitClient.WorkingStatus st = git.status(root);
                if (!st.clean()) {
                    return new BranchResult(false, "WORKTREE_DIRTY",
                            "该分支上有未提交改动（" + st.dirtyPaths().size() + " 个文件），"
                                    + "删除后无法恢复，已拒绝。",
                            branch, false);
                }
                git.checkout(root, def);
            }
            git.deleteBranch(root, branch);
            return new BranchResult(true, "DISCARDED",
                    "已切回 " + def + " 并删除分支 " + branch, branch, false);
        } catch (Exception e) {
            return new BranchResult(false, "GIT_FAILED",
                    "丢弃分支失败：" + e.getMessage(), branch, false);
        }
    }

    /** 本软件创建的分支列表。 */
    public List<String> latticeBranches(KnowledgeRepo repo) {
        try {
            return git.listBranches(registry.rootOf(repo)).stream()
                    .filter(b -> b.startsWith(guard.branchPrefix()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ==================== 提交 ==================== */

    /**
     * 提交指定文件。
     *
     * @param paths   仓库内相对路径（精确列举，绝不 {@code add -A}）
     * @param subject 提交标题
     * @param bodyNote 额外说明（可空）
     * @param sessionId 会话标识，写进 trailer 以便回溯是哪次对话产生的
     */
    public CommitResult commit(KnowledgeRepo repo, List<String> paths,
                               String subject, String bodyNote, String sessionId) {
        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return new CommitResult(false, en.code(), en.message(), null, null, List.of());
        }
        if (paths == null || paths.isEmpty()) {
            return new CommitResult(false, "NO_PATHS", "未指定要提交的文件。", null, null, List.of());
        }
        DocWriteGuard.Decision br = guard.checkBranch(repo);
        if (!br.allowed()) {
            return new CommitResult(false, br.code(), br.message(), null, null, List.of());
        }

        Path root = registry.rootOf(repo);
        String branch;
        try {
            branch = git.currentBranch(root);
        } catch (Exception e) {
            return new CommitResult(false, "GIT_FAILED",
                    "无法确定当前分支：" + e.getMessage(), null, null, List.of());
        }

        Set<String> expected = new LinkedHashSet<>();
        for (String p : paths) {
            DocWriteGuard.Decision d = guard.checkPath(repo, p);
            if (!d.allowed()) {
                return new CommitResult(false, d.code(),
                        "拒绝提交 " + p + "：" + d.message(), null, branch, List.of());
            }
            expected.add(p.replace('\\', '/').strip());
        }

        try {
            // 提交前先看暂存区里有没有别处已 add 的内容——那会搭车进这次提交
            List<String> preStaged = git.stagedFiles(root);
            List<String> foreign = new ArrayList<>();
            for (String s : preStaged) {
                if (!expected.contains(s)) foreign.add(s);
            }
            if (!foreign.isEmpty()) {
                return new CommitResult(false, "STAGED_FOREIGN",
                        "暂存区中已有不属于本次改动的文件，拒绝提交以免搭车："
                                + String.join("、", foreign.subList(0, Math.min(5, foreign.size())))
                                + "。请先 git reset 清空暂存区。",
                        null, branch, foreign);
            }

            git.add(root, new ArrayList<>(expected));

            List<String> staged = git.stagedFiles(root);
            if (staged.isEmpty()) {
                return new CommitResult(false, "NOTHING_TO_COMMIT",
                        "没有任何改动可提交（文件内容与 HEAD 一致）。", null, branch, List.of());
            }
            List<String> unexpected = new ArrayList<>();
            for (String s : staged) {
                if (!expected.contains(s)) unexpected.add(s);
            }
            if (!unexpected.isEmpty()) {
                return new CommitResult(false, "STAGED_MISMATCH",
                        "暂存区内容与预期不一致，已中止：" + String.join("、", unexpected),
                        null, branch, staged);
            }

            GitClient.CommitInfo info = git.commit(root,
                    buildMessage(subject, bodyNote, sessionId, staged));
            return new CommitResult(true, "COMMITTED",
                    "已提交 " + staged.size() + " 个文件。",
                    info == null ? null : info.sha(), branch, staged);
        } catch (Exception e) {
            return new CommitResult(false, "GIT_FAILED",
                    "提交失败：" + e.getMessage(), null, branch, List.of());
        }
    }

    /**
     * 构造提交信息。
     *
     * <p>Agent 参与必须写进提交信息本身而非只记在应用数据库里：
     * 仓库要能脱离本软件独立存在，这条溯源信息只有留在 git 历史里才带得走。</p>
     */
    public String buildMessage(String subject, String bodyNote, String sessionId, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append(subject == null || subject.isBlank() ? "docs: 更新知识资产" : subject.strip());
        sb.append("\n\n");
        if (bodyNote != null && !bodyNote.isBlank()) {
            sb.append(bodyNote.strip()).append("\n\n");
        }
        sb.append("Generated-by: Lattice Codex (curate mode)\n");
        if (sessionId != null && !sessionId.isBlank()) {
            sb.append("Lattice-Session: ").append(sessionId.strip()).append('\n');
        }
        if (files != null && !files.isEmpty()) {
            sb.append("Lattice-Files: ").append(String.join(", ", files)).append('\n');
        }
        sb.append(CO_AUTHOR).append('\n');
        return sb.toString();
    }

    /* ==================== 推送与 PR ==================== */

    /**
     * 推送当前分支并尝试创建 PR。
     *
     * <p>推送成功但 PR 创建失败时<strong>仍返回 ok=true</strong>：
     * 内容已经安全地到了远端，把 API 失败升级成整体失败会让用户以为工作白做了。
     * PR 的失败原因单独回报，用户可在网页端手动开。</p>
     */
    public PushResult pushAndOpenPr(KnowledgeRepo repo, String branch,
                                    String title, String body) {
        DocWriteGuard.Decision en = guard.checkEnabled();
        if (!en.allowed()) {
            return new PushResult(false, en.code(), en.message(), branch, null, null);
        }
        if (repo.getProvider() != KnowledgeRepo.RepoProvider.GITHUB) {
            return new PushResult(false, "PROVIDER_LOCAL",
                    "该仓库为本地仓库（provider=LOCAL），不推送远端。"
                            + "改动已在本地分支 " + branch + " 上，可在「查看 diff」中审阅后自行合并。",
                    branch, null, null);
        }
        String remote = repo.getRemoteUrl();
        if (remote == null || remote.isBlank()) {
            try {
                remote = git.remoteUrl(registry.rootOf(repo));
            } catch (Exception ignored) {
                remote = null;
            }
        }
        if (remote == null) {
            return new PushResult(false, "NO_REMOTE",
                    "未配置远端地址，无法推送。", branch, null, null);
        }

        try {
            git.push(registry.rootOf(repo), "origin", branch, true);
        } catch (Exception e) {
            return new PushResult(false, "PUSH_FAILED",
                    "推送失败：" + e.getMessage()
                            + "。提交仍在本地分支上，内容未丢失。", branch, null, null);
        }

        String base = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        GitHubPrClient.PrResult pr = prClient.createPullRequest(
                remote, repo.getTokenRef(), branch, base, title, body);
        if (pr.ok()) {
            return new PushResult(true, "PR_CREATED",
                    "已推送并创建 PR #" + pr.number(), branch, pr.number(), pr.url());
        }
        return new PushResult(true, "PUSHED_NO_PR",
                "已推送分支，但未创建 PR：" + pr.message(), branch, null, null);
    }

    /* ==================== 审阅 ==================== */

    /**
     * 取用于审阅的 diff。
     *
     * <p>未提交时看工作副本相对 HEAD 的改动，已提交时看分支相对默认分支的改动。
     * 用户关心的始终是「相对我原来的内容，多了什么」，而不是 git 的暂存态。</p>
     */
    public DiffView diff(KnowledgeRepo repo, String branch) {
        Path root = registry.rootOf(repo);
        String base = repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch();
        try {
            GitClient.WorkingStatus st = git.status(root);
            String b = (branch == null || branch.isBlank()) ? st.branch() : branch;
            if (!st.clean()) {
                return new DiffView(b, "HEAD", st.dirtyPaths(),
                        git.diffWorkingTree(root, diffMaxChars), true);
            }
            List<String> files = git.changedFiles(root, base, b);
            return new DiffView(b, base, files, git.diff(root, base, b, diffMaxChars), false);
        } catch (Exception e) {
            log.debug("[Codex] 取 diff 失败：{}", e.getMessage());
            return new DiffView(branch, base, List.of(),
                    "(取 diff 失败：" + e.getMessage() + ")", false);
        }
    }
}
