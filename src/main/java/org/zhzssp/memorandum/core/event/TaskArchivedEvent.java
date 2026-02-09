package org.zhzssp.memorandum.core.event;

import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;

/**
 * 任务归档事件。用于插件扩展，如统计更新等。
 */
public class TaskArchivedEvent {
    private final Task task;
    private final User user;

    public TaskArchivedEvent(Task task, User user) {
        this.task = task;
        this.user = user;
    }

    public Task getTask() {
        return task;
    }

    public User getUser() {
        return user;
    }
}
