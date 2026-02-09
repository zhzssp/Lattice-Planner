package org.zhzssp.memorandum.feature.goal.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.event.TaskCompletedEvent;
import org.zhzssp.memorandum.core.event.TaskCreatedEvent;
import org.zhzssp.memorandum.entity.Link;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.feature.goal.entity.Goal;
import org.zhzssp.memorandum.feature.goal.repository.GoalRepository;
import org.zhzssp.memorandum.repository.LinkRepository;

import java.util.List;

/**
 * 目标功能的事件监听器（插件层）。
 * 通过监听核心事件来扩展目标相关行为，而不直接修改核心代码。
 */
@Component
public class GoalEventListener {

    private final GoalRepository goalRepository;
    private final LinkRepository linkRepository;

    public GoalEventListener(GoalRepository goalRepository, LinkRepository linkRepository) {
        this.goalRepository = goalRepository;
        this.linkRepository = linkRepository;
    }

    /**
     * 监听任务创建事件。
     * 可以用于：目标聚类、任务分析等（未来扩展）。
     */
    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        // 未来可以在这里实现：自动聚类任务到目标、分析任务类型等
        // 目前保持空实现，展示事件驱动的扩展点
    }

    /**
     * 监听任务完成事件。
     * 可以用于：更新目标进度、推荐相关笔记等（未来扩展）。
     */
    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        // 未来可以在这里实现：更新目标完成度、推荐学习笔记等
        // 目前保持空实现，展示事件驱动的扩展点
    }
}
