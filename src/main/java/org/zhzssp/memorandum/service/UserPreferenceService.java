package org.zhzssp.memorandum.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.entity.Task;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.repository.UserPreferenceRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户偏好服务：根据认知负载设置过滤任务。
 */
@Service
public class UserPreferenceService {

    @Autowired
    private UserPreferenceRepository preferenceRepository;

    public UserPreference getOrCreatePreference(User user) {
        return preferenceRepository.findByUser(user)
                .orElseGet(() -> {
                    UserPreference pref = new UserPreference();
                    pref.setUser(user);
                    pref.setShowFutureTasks(true);
                    pref.setShowStatistics(false);
                    return preferenceRepository.save(pref);
                });
    }

    public UserPreference savePreference(UserPreference preference) {
        return preferenceRepository.save(preference);
    }

    /**
     * 根据用户偏好过滤任务列表。
     */
    public List<Task> applyTaskFilters(List<Task> tasks, User user) {
        UserPreference pref = getOrCreatePreference(user);

        List<Task> filtered = tasks;

        // 是否显示未来任务
        if (!pref.getShowFutureTasks()) {
            LocalDate today = LocalDate.now();
            filtered = filtered.stream()
                    .filter(t -> t.getDeadline() == null || t.getDeadline().toLocalDate().isEqual(today) || t.getDeadline().toLocalDate().isBefore(today))
                    .collect(Collectors.toList());
        }

        // 限制每屏显示数量
        if (pref.getMaxVisibleTasks() != null && pref.getMaxVisibleTasks() > 0) {
            filtered = filtered.stream()
                    .limit(pref.getMaxVisibleTasks())
                    .collect(Collectors.toList());
        }

        return filtered;
    }
}
