package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.zhzssp.memorandum.entity.Goal;
import org.zhzssp.memorandum.entity.User;

import java.util.List;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findByUser(User user);

    /** 有效目标（未归档） */
    List<Goal> findByUserAndArchivedAtIsNull(User user);
}
