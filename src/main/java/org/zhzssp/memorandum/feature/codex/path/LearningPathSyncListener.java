package org.zhzssp.memorandum.feature.codex.path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.service.RepoSyncService;

/**
 * 索引完成后同步路径文件。不依赖 {@code codex.verify} 等子开关——路径是体系骨架。
 */
@Component
public class LearningPathSyncListener {

    private static final Logger log = LoggerFactory.getLogger(LearningPathSyncListener.class);

    private final LearningPathIndexService index;

    public LearningPathSyncListener(LearningPathIndexService index) {
        this.index = index;
    }

    @EventListener
    public void onRepoIndexed(RepoSyncService.RepoIndexedEvent event) {
        try {
            index.syncFromRepo(event.repo());
        } catch (Exception e) {
            log.warn("[Codex/Path] 路径同步失败（索引不受影响）：{}", e.getMessage());
        }
    }
}
