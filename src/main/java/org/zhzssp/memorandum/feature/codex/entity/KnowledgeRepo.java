package org.zhzssp.memorandum.feature.codex.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 知识仓库注册（Codex 权威表）。
 *
 * <p><strong>本表是 Codex 唯一不可重建的表</strong>：它持有用户凭证引用与本地工作副本路径。
 * 其余 {@code kb_*} 表全部是派生索引，删空后可由 {@code rebuild-index} 从 Git 仓库全量重建。
 * 这条边界是 Git-native 架构的核心约束——它保证用户随时能扔掉本软件，
 * 仓库照样用 IDE / Obsidian 打开。</p>
 */
@Entity
@Table(name = "knowledge_repo",
        uniqueConstraints = @UniqueConstraint(name = "uk_repo_user_name",
                columnNames = {"user_id", "name"}),
        indexes = @Index(name = "idx_repo_user", columnList = "user_id"))
@Data
public class KnowledgeRepo {

    /** 仓库用途：决定模板包与 checkpoint 的验证手段。 */
    public enum RepoKind {
        /** 学习仓：source=论文/官方文档，verify=跑 lab 脚本。 */
        LEARNING,
        /** 工作仓：source=需求/设计/工单，verify=跑单测/复现故障。 */
        WORK
    }

    /**
     * 托管方。
     *
     * <p>{@link #LOCAL} 是刻意保留的一等公民：不连任何远端也能全功能运行，
     * 符合项目「可降级」原则——GitHub 只增强，不是必需。</p>
     */
    public enum RepoProvider {
        LOCAL, GITHUB
    }

    public enum SyncStatus {
        IDLE, SYNCING,
        /** 工作副本有未提交改动：Agent 写入被拒绝（数据安全优先，不自动 stash）。 */
        DIRTY,
        ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RepoKind kind = RepoKind.LEARNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RepoProvider provider = RepoProvider.LOCAL;

    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    /** 本地工作副本绝对路径。所有受限执行的沙箱边界都以它为准。 */
    @Column(name = "local_path", nullable = false, length = 512)
    private String localPath;

    @Column(name = "default_branch", nullable = false, length = 64)
    private String defaultBranch = "main";

    /** 凭证存储的键名——刻意不存 token 明文。 */
    @Column(name = "token_ref", length = 128)
    private String tokenRef;

    @Column(name = "template_pack", length = 64)
    private String templatePack;

    @Column(name = "last_synced_sha", length = 64)
    private String lastSyncedSha;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    private SyncStatus syncStatus = SyncStatus.IDLE;

    @Column(name = "sync_error", length = 512)
    private String syncError;

    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
