package org.zhzssp.memorandum.feature.codex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;

import java.nio.file.Path;

/**
 * 仓库同步：拉取远端 + 触发索引。
 *
 * <h3>为什么脏工作区要拒绝而不是自动 stash</h3>
 * <p>用户很可能正在 IDE 里改到一半。自动 stash 看似贴心，实际是把用户的未完成工作
 * 挪到一个他不知道的地方——一旦后续操作失败，用户会认为「软件吃掉了我的改动」。
 * 在「数据安全」与「少一次点击」之间，必须选前者。</p>
 *
 * <p>注意：脏工作区<strong>不阻止索引</strong>。索引读的是工作副本当前内容，
 * 用户改了什么就索引什么——这正是「软件不是唯一写入方」的正确行为。
 * 拒绝的只有需要动 git 历史的操作（pull / commit）。</p>
 */
@Service
public class RepoSyncService {

    private static final Logger log = LoggerFactory.getLogger(RepoSyncService.class);

    private final GitClient git;
    private final RepoIndexer indexer;
    private final RepoRegistryService registry;

    public RepoSyncService(GitClient git, RepoIndexer indexer, RepoRegistryService registry) {
        this.git = git;
        this.indexer = indexer;
        this.registry = registry;
    }

    /**
     * 同步结果。
     *
     * @param pulled  是否真的执行了 pull
     * @param dirty   工作副本是否有未提交改动
     * @param report  索引报告
     */
    public record SyncResult(boolean pulled, boolean dirty, String headSha,
                            RepoIndexer.IndexReport report, String warning) {}

    /**
     * 同步并索引。
     *
     * @param full     true = 全量重建索引
     * @param doPull   true = 尝试 git pull（provider=LOCAL 时无意义，会被跳过）
     */
    public SyncResult sync(KnowledgeRepo repo, boolean full, boolean doPull) {
        Path root = registry.rootOf(repo);
        String warning = null;
        boolean pulled = false;
        boolean dirty = false;
        String head = null;

        GitClient.WorkingStatus status = null;
        try {
            status = git.status(root);
            dirty = !status.clean();
            head = status.headSha();
        } catch (Exception e) {
            warning = "读取 git 状态失败：" + e.getMessage();
            log.debug("[Codex] {}", warning);
        }

        if (doPull && repo.getProvider() == KnowledgeRepo.RepoProvider.GITHUB) {
            if (dirty) {
                // 刻意不自动 stash：见类注释
                warning = "工作副本有未提交改动（" + describeDirty(status)
                        + "），已跳过 pull。请先自行提交或撤销后重试。";
                log.info("[Codex] 仓库「{}」{}", repo.getName(), warning);
            } else {
                try {
                    head = git.pull(root);
                    pulled = true;
                } catch (Exception e) {
                    warning = "git pull 失败：" + e.getMessage();
                    log.warn("[Codex] 仓库「{}」{}", repo.getName(), warning);
                }
            }
        }

        if (dirty) {
            repo.setSyncStatus(KnowledgeRepo.SyncStatus.DIRTY);
            registry.save(repo);
        }

        // 索引始终执行：读的是工作副本当前内容，脏不脏都要如实反映
        RepoIndexer.IndexReport report = indexer.index(repo, full);
        return new SyncResult(pulled, dirty, head, report, warning);
    }

    private String describeDirty(GitClient.WorkingStatus status) {
        if (status == null || status.dirtyPaths().isEmpty()) return "未知";
        int n = status.dirtyPaths().size();
        String head = String.join(", ", status.dirtyPaths().subList(0, Math.min(3, n)));
        return n <= 3 ? head : head + " 等 " + n + " 个文件";
    }
}
