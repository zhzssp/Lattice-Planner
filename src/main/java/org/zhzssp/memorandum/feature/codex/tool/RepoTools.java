package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.git.GitClient;
import org.zhzssp.memorandum.feature.codex.index.RepoIndexer;
import org.zhzssp.memorandum.feature.codex.service.RepoHealthService;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识仓库管理工具集。
 *
 * <p>可见性：全部带 {@code codex} tag，因此只在新增的 {@code study/curate/verify}
 * 三个模式下可见。既有四个模式（chat/plan/reflect/learn）的 allowTags 一字未改，
 * {@code CHAT} 则通过新增 denyTags 显式排除——目的是让方案 A 的评测 cassette
 * 不因工具列表变化而失效。</p>
 *
 * <p>多用户隔离：所有入口走 {@link AgentContext#requireUser()} 再按 userId 过滤，
 * 越权访问他人仓库天然不可达。</p>
 */
@Component
public class RepoTools {

    private final RepoRegistryService registry;
    private final RepoSyncService syncService;
    private final RepoHealthService health;
    private final GitClient git;

    public RepoTools(RepoRegistryService registry,
                     RepoSyncService syncService,
                     RepoHealthService health,
                     GitClient git) {
        this.registry = registry;
        this.syncService = syncService;
        this.health = health;
        this.git = git;
    }

    @AgentTool(name = "repo.list", tags = {"codex", "read"},
            description = "列出用户已接入的知识仓库（名称/类型/本地路径/分支/同步状态/文档数）。" +
                    "用户问「我有哪些知识库/仓库」时调用。")
    public List<Map<String, Object>> list() {
        User u = AgentContext.requireUser();
        if (!registry.operational()) {
            return List.of(Map.of("error", "CODEX_DISABLED",
                    "message", disabledMessage()));
        }
        return registry.list(u.getId()).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("kind", r.getKind().name());
            m.put("provider", r.getProvider().name());
            m.put("localPath", r.getLocalPath());
            m.put("branch", r.getDefaultBranch());
            m.put("syncStatus", r.getSyncStatus().name());
            m.put("enabled", r.getEnabled());
            RepoHealthService.Health h = health.snapshot(r.getId());
            m.put("documents", h.documents());
            m.put("chunks", h.chunks());
            return m;
        }).toList();
    }

    @AgentTool(name = "repo.status", tags = {"codex", "read"},
            description = "查看某个知识仓库的健康状况：文档数/切片数/被截断文档/死链/孤岛/最近索引情况。" +
                    "用户问「我的知识库健康吗/有没有死链」时调用。")
    public Map<String, Object> status(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (!registry.operational()) {
            return Map.of("error", "CODEX_DISABLED", "message", disabledMessage());
        }
        KnowledgeRepo repo = resolveRepo(u, repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND",
                    "message", "未找到仓库" + (repoName == null ? "" : "：" + repoName)
                            + "。可先调用 repo.list 查看已接入的仓库。");
        }
        RepoHealthService.Health h = health.snapshot(repo.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", repo.getName());
        m.put("documents", h.documents());
        m.put("sections", h.sections());
        m.put("chunks", h.chunks());
        m.put("kindDistribution", h.kindDistribution());
        m.put("truncatedDocs", h.truncatedDocs());
        m.put("brokenLinks", h.brokenLinks());
        m.put("orphanDocs", h.orphanDocs());
        m.put("lastIndexRun", h.lastRun());
        if (h.truncatedDocs() > 0) {
            // 主动把截断透给模型：它需要在回答里明示「这些文档只索引了一部分」
            m.put("truncatedDetail", health.truncatedDocuments(repo.getId()));
            m.put("_warning", "存在被截断的文档，其后半部分未被索引，检索不到不代表原文没写。");
        }
        if (h.brokenLinks() > 0) {
            m.put("brokenDetail", health.brokenLinks(repo.getId(), 20));
        }
        try {
            GitClient.WorkingStatus ws = git.status(registry.rootOf(repo));
            m.put("gitBranch", ws.branch());
            m.put("gitClean", ws.clean());
            if (!ws.clean()) {
                m.put("gitDirtyPaths", ws.dirtyPaths().stream().limit(10).toList());
            }
        } catch (Exception e) {
            m.put("gitStatusError", e.getMessage());
        }
        return m;
    }

    @AgentTool(name = "repo.sync", tags = {"codex", "write"}, requiresConfirm = true,
            description = "同步并重建知识仓库索引（可选先 git pull）。" +
                    "用户说「同步/更新知识库索引」时调用。" +
                    "注意：工作副本有未提交改动时会跳过 pull 但仍会重建索引。")
    public Map<String, Object> sync(
            @ToolParam(value = "repoName", desc = "仓库名称；省略则取第一个仓库") String repoName,
            @ToolParam(value = "full", desc = "true = 全量重建（忽略增量判定），默认 false") Boolean full,
            @ToolParam(value = "pull", desc = "true = 先执行 git pull，默认 false") Boolean pull
    ) {
        User u = AgentContext.requireUser();
        if (!registry.operational()) {
            return Map.of("error", "CODEX_DISABLED", "message", disabledMessage());
        }
        KnowledgeRepo repo = resolveRepo(u, repoName);
        if (repo == null) {
            return Map.of("error", "REPO_NOT_FOUND", "message", "未找到仓库，可先调用 repo.list。");
        }
        RepoSyncService.SyncResult r = syncService.sync(repo,
                Boolean.TRUE.equals(full), Boolean.TRUE.equals(pull));
        RepoIndexer.IndexReport rep = r.report();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", repo.getName());
        m.put("pulled", r.pulled());
        m.put("dirty", r.dirty());
        m.put("mode", rep.mode().name());
        m.put("docsTotal", rep.docsTotal());
        m.put("docsReindexed", rep.docsReindexed());
        m.put("docsSkipped", rep.docsSkipped());
        m.put("docsRemoved", rep.docsRemoved());
        m.put("chunksWritten", rep.chunksWritten());
        m.put("embedCalls", rep.embedCalls());
        m.put("brokenLinks", rep.brokenLinks());
        m.put("truncatedDocs", rep.truncatedDocs());
        m.put("durationMs", rep.durationMs());
        if (r.warning() != null) m.put("warning", r.warning());
        if (!rep.success()) m.put("error", rep.error());
        return m;
    }

    /** 名称解析：省略时取第一个启用仓库（单仓库场景下省一次追问）。 */
    private KnowledgeRepo resolveRepo(User u, String repoName) {
        if (repoName != null && !repoName.isBlank()) {
            return registry.findByName(u.getId(), repoName.strip()).orElse(null);
        }
        List<KnowledgeRepo> all = registry.listEnabled(u.getId());
        return all.isEmpty() ? null : all.get(0);
    }

    private String disabledMessage() {
        if (!registry.enabled()) {
            return "知识仓库功能未启用（codex.enabled=false）。请在配置中开启后重启。";
        }
        return "系统未安装 git 或不在 PATH 中，知识仓库功能不可用。当前检测：" + registry.gitVersion();
    }
}
