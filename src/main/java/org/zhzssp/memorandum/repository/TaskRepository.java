package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskGranularity;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByUser(User user);

    List<Task> findByUserAndStatus(User user, TaskStatus status);

    /** 可行动项：PENDING 且未搁置 */
    List<Task> findByUserAndStatusIn(User user, List<TaskStatus> statuses);

    /** 模糊任务 + 待办 + 创建时间早于指定时间（用于“存在 N 天未拆分”提示） */
    List<Task> findByUserAndGranularityAndStatusAndCreatedAtBefore(
            User user,
            TaskGranularity granularity,
            TaskStatus status,
            LocalDateTime createdAtBefore
    );
}
