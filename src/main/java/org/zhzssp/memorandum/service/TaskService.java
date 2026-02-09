package org.zhzssp.memorandum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.TaskGranularity;
import org.zhzssp.memorandum.entity.TaskStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.mapper.TaskMapper;
import org.zhzssp.memorandum.repository.TaskRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskRepository taskRepository;

    public List<Task> searchTasks(Long userId, String keyword, LocalDateTime start, LocalDateTime end) {
        return taskMapper.searchTasks(userId, keyword, start, end);
    }

    /**
     * 查找「需要拆分提示」的任务：模糊 + 待办 + 创建时间早于 now - days。
     */
    public List<Task> findFuzzyTasksNeedingSplit(User user, long days) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        return taskRepository.findByUserAndGranularityAndStatusAndCreatedAtBefore(
                user,
                TaskGranularity.FUZZY,
                TaskStatus.PENDING,
                threshold
        );
    }
}
