package org.zhzssp.memorandum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.mapper.TaskMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskService {

    @Autowired
    private TaskMapper taskMapper;

    public List<Task> searchTasks(Long userId, String keyword, LocalDateTime start, LocalDateTime end) {
        return taskMapper.searchTasks(userId, keyword, start, end);
    }
}
