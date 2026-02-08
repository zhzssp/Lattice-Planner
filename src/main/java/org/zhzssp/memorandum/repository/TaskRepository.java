package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByUser(User user);

    List<Task> findByUserAndStatus(User user, TaskStatus status);

    /** 可行动项：PENDING 且未搁置 */
    List<Task> findByUserAndStatusIn(User user, List<TaskStatus> statuses);
}
