package org.zhzssp.memorandum.feature.codex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.repository.KnowledgeRepoRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * 仓库注册与凭证管理。
 *
 * <p>本服务操作的是 Codex 唯一的权威表。它有两条不可退让的校验：</p>
 * <ol>
 *   <li><strong>必须是真实 git 工作副本</strong>：否则 blobHash / 提交时间 / 跟踪状态
 *       全部拿不到，增量索引与腐化检测会静默退化成「每次全量 + 无腐化数据」；</li>
 *   <li><strong>路径必须规范化为绝对路径</strong>：后续受限执行的沙箱边界以它为准，
 *       留着相对路径或未解析的符号链接会让沙箱判定出现绕过面。</li>
 * </ol>
 */
@Service
public class RepoRegistryService {

    private static final Logger log = LoggerFactory.getLogger(RepoRegistryService.class);

    private final KnowledgeRepoRepository repoRepo;
    private final GitClient git;

    @Value("${codex.enabled:false}")
    private boolean codexEnabled;

    public RepoRegistryService(KnowledgeRepoRepository repoRepo, GitClient git) {
        this.repoRepo = repoRepo;
        this.git = git;
    }

    public boolean enabled() {
        return codexEnabled;
    }

    /** Codex 是否真正可用（开关 + git 可用性）。 */
    public boolean operational() {
        return codexEnabled && git.available();
    }

    public String gitVersion() {
        return git.version();
    }

    public List<KnowledgeRepo> list(Long userId) {
        return repoRepo.findByUserIdOrderByIdAsc(userId);
    }

    public List<KnowledgeRepo> listEnabled(Long userId) {
        return repoRepo.findByUserIdAndEnabledTrueOrderByIdAsc(userId);
    }

    public Optional<KnowledgeRepo> find(Long userId, Long repoId) {
        return repoRepo.findByIdAndUserId(repoId, userId);
    }

    public Optional<KnowledgeRepo> findByName(Long userId, String name) {
        return repoRepo.findByUserIdAndName(userId, name);
    }

    /**
     * 注册一个已存在的本地 git 仓库。
     *
     * @throws IllegalArgumentException 路径不存在 / 不是 git 仓库 / 名称重复
     */
    public KnowledgeRepo register(Long userId,
                                  String name,
                                  String localPath,
                                  KnowledgeRepo.RepoKind kind,
                                  KnowledgeRepo.RepoProvider provider,
                                  String remoteUrl) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }
        if (repoRepo.findByUserIdAndName(userId, name).isPresent()) {
            throw new IllegalArgumentException("已存在同名仓库：" + name);
        }
        Path root = Paths.get(localPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("路径不存在或不是目录：" + root);
        }
        if (!git.available()) {
            throw new IllegalStateException("系统未安装 git 或不在 PATH 中，无法接入知识仓库");
        }
        if (!git.isRepository(root)) {
            throw new IllegalArgumentException(
                    "该目录不是 git 工作副本：" + root + "。请先执行 git init 或 git clone。");
        }

        KnowledgeRepo repo = new KnowledgeRepo();
        repo.setUserId(userId);
        repo.setName(name.strip());
        repo.setLocalPath(root.toString());
        repo.setKind(kind == null ? KnowledgeRepo.RepoKind.LEARNING : kind);
        repo.setProvider(provider == null ? KnowledgeRepo.RepoProvider.LOCAL : provider);
        repo.setRemoteUrl(remoteUrl);
        try {
            repo.setDefaultBranch(git.currentBranch(root));
        } catch (Exception e) {
            repo.setDefaultBranch("main");
        }
        repo.setSyncStatus(KnowledgeRepo.SyncStatus.IDLE);
        repo.setEnabled(true);
        KnowledgeRepo saved = repoRepo.save(repo);
        log.info("[Codex] 注册知识仓库「{}」：{}（branch={}, provider={}）",
                saved.getName(), saved.getLocalPath(), saved.getDefaultBranch(), saved.getProvider());
        return saved;
    }

    /** 删除仓库注册（派生索引由调用方先清理）。工作副本文件<strong>绝不删除</strong>。 */
    public void unregister(Long userId, Long repoId) {
        repoRepo.findByIdAndUserId(repoId, userId).ifPresent(r -> {
            repoRepo.delete(r);
            log.info("[Codex] 注销知识仓库「{}」（本地文件保留：{}）", r.getName(), r.getLocalPath());
        });
    }

    public KnowledgeRepo save(KnowledgeRepo repo) {
        return repoRepo.save(repo);
    }

    /** 仓库根目录（已规范化的绝对路径）。 */
    public Path rootOf(KnowledgeRepo repo) {
        return Paths.get(repo.getLocalPath()).toAbsolutePath().normalize();
    }
}
