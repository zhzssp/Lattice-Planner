package org.zhzssp.memorandum.feature.codex.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;

/**
 * 索引完成后自动同步检验条目。
 *
 * <p>用监听器而非在 {@code RepoIndexer} 里直接调用，是为了让
 * 「验证闭环关闭时索引行为与 P0 完全一致」成为<strong>结构性保证</strong>
 * ——{@code codex.verify.enabled=false} 时本监听器直接返回，
 * 索引路径上不存在任何与验证相关的代码分支。</p>
 *
 * <p>这与项目 core/feature 的解耦立场一致：下游特性监听上游事件，
 * 上游不反向依赖下游。</p>
 */
@Component
public class CheckpointSyncListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointSyncListener.class);

    private final CheckpointService service;

    public CheckpointSyncListener(CheckpointService service) {
        this.service = service;
    }

    @EventListener
    public void onRepoIndexed(RepoSyncService.RepoIndexedEvent event) {
        if (!service.enabled()) return;
        try {
            CheckpointService.SyncResult r = service.syncFromRepo(event.repo());
            if (r.parsed() > 0) {
                log.info("[Codex/Verify] 仓库「{}」检验条目已同步：解析 {} 条，"
                                + "其中 {} 条带验收命令（新建 {} / 更新 {}）",
                        event.repo().getName(), r.parsed(), r.withCommand(),
                        r.created(), r.updated());
            }
        } catch (Exception e) {
            // 检验解析失败不应影响索引结果——索引已经成功落库了
            log.warn("[Codex/Verify] 检验同步失败（索引不受影响）：{}", e.getMessage());
        }
    }
}
