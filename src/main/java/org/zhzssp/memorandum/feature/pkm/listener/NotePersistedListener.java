package org.zhzssp.memorandum.feature.pkm.listener;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.pkm.event.NotePersistedEvent;
import org.zhzssp.memorandum.feature.pkm.service.NoteIndexService;

/**
 * 监听 NotePersistedEvent，异步重建索引。
 * 索引失败不抛异常，避免影响业务主流程。
 */
@Component
public class NotePersistedListener {

    private final NoteIndexService indexService;

    public NotePersistedListener(NoteIndexService indexService) {
        this.indexService = indexService;
    }

    @Async
    @EventListener
    public void onPersisted(NotePersistedEvent event) {
        try {
            indexService.rebuildForNote(event.getNote());
        } catch (Exception ex) {
            // 索引层失败不影响业务主流程
            System.err.println("[PKM] rebuild note index failed: " + ex.getMessage());
        }
    }
}
