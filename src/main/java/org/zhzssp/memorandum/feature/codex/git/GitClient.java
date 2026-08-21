package org.zhzssp.memorandum.feature.codex.git;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Git 操作抽象。
 *
 * <h3>为什么抽成接口</h3>
 * <p>与 {@code LlmTransport} 同一立场：把「与外部进程/服务交互」的边界显式化，
 * 便于测试时替换为假实现，也为将来可能换 JGit 留出替换点。</p>
 *
 * <h3>为什么生产实现选「调系统 git」而非 JGit</h3>
 * <p>决定性理由是<strong>行为一致性</strong>：用户会在 IDE 里手动操作同一个工作副本，
 * 若软件用另一套 git 实现，两者对 {@code core.autocrlf}、{@code .gitattributes}、
 * {@code .gitignore}、credential helper 的处理差异会产生极难排查的
 * 「软件看到的和我看到的不一样」。</p>
 *
 * <p>其中 {@link #blobHash} 最能说明问题：增量索引依赖内容哈希，
 * 若应用层自己读文件算 SHA-256，在 Windows 上会因行尾转换与 git 的对象哈希不一致，
 * 表现为「增量索引永远不命中」——而这种故障几乎不会被归因到行尾。
 * 用 {@code git hash-object} 则天然一致。</p>
 */
public interface GitClient {

    /** 一条提交的元信息。 */
    record CommitInfo(String sha, String author, LocalDateTime committedAt, String subject) {}

    /** 工作副本状态。 */
    record WorkingStatus(String branch, String headSha, boolean clean, List<String> dirtyPaths) {}

    /** git 可执行文件是否可用（启动自检用）。 */
    boolean available();

    /** git 版本串，用于诊断信息展示。 */
    String version();

    /** 是否是一个 git 工作副本。 */
    boolean isRepository(Path dir);

    /** 当前分支名。 */
    String currentBranch(Path repo);

    /** HEAD 的 sha。 */
    String headSha(Path repo);

    /**
     * 工作副本状态。
     *
     * <p>{@code clean=false} 时 Agent 的任何写入都必须被拒绝——
     * 数据安全优先于便利，刻意不做自动 stash。</p>
     */
    WorkingStatus status(Path repo);

    /**
     * 内容哈希（{@code git hash-object}）。
     *
     * @param file 工作副本内的文件绝对路径
     * @return blob sha1；文件不存在返回 null
     */
    String blobHash(Path repo, Path file);

    /** 批量算 hash（一次进程调用处理多文件，避免 61 次进程启动开销）。 */
    List<String> blobHashes(Path repo, List<Path> files);

    /** 某个路径的末次提交信息；无提交记录返回 null。 */
    CommitInfo lastCommit(Path repo, String relativePath);

    /** 批量取末次提交时间（相对路径 → 提交信息），一次遍历 git log。 */
    java.util.Map<String, CommitInfo> lastCommits(Path repo, List<String> relativePaths);

    /** 被 git 跟踪的文件列表（相对路径，正斜杠）。 */
    List<String> listTrackedFiles(Path repo);

    /** 某个相对路径是否被 git 跟踪——受限执行的信任判据之一。 */
    boolean isTracked(Path repo, String relativePath);

    /** {@code git pull --ff-only}。返回拉取后的 HEAD sha。 */
    String pull(Path repo);

    /** {@code git clone}。 */
    void clone(String remoteUrl, Path targetDir);
}
