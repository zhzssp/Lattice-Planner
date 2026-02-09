package org.zhzssp.memorandum.core.event;

import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;

/**
 * 任务创建事件。用于插件扩展，如目标聚类、任务分析等。
 */
public class TaskCreatedEvent {
    private final Task task;
    private final User user;

    public TaskCreatedEvent(Task task, User user) {
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
