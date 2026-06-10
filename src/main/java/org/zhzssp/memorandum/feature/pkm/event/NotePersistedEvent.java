package org.zhzssp.memorandum.feature.pkm.event;

import org.springframework.context.ApplicationEvent;
import org.zhzssp.memorandum.entity.Note;

/**
 * 笔记创建 / 更新落库后发布。监听器异步重建：
 *   1) NOTE→NOTE 双向链接（Stage 1）
 *   2) note_embedding 向量索引（Stage 2 启用）
 *
 * 与 TaskCreatedEvent / GoalProgressEvent 同构，复用 Spring 事件总线。
 */
public class NotePersistedEvent extends ApplicationEvent {

    private final Note note;

    public NotePersistedEvent(Object source, Note note) {
        super(source);
        this.note = note;
    }

    public Note getNote() {
        return note;
    }
}
