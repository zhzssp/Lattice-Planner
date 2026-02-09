package org.zhzssp.memorandum.core.event;

import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;

/**
 * 任务完成事件。用于插件扩展，如统计更新、笔记推荐等。
 */
public class TaskCompletedEvent {
    private final Task task;
    private final User user;

    public TaskCompletedEvent(Task task, User user) {
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
