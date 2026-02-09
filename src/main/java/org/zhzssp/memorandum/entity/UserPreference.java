package org.zhzssp.memorandum.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 用户偏好设置：认知负载控制。
 */
@Entity
@Table(name = "user_preference")
@Data
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** 每屏最多显示任务数（null 表示不限制） */
    @Column(name = "max_visible_tasks", nullable = true)
    private Integer maxVisibleTasks;

    /** 是否显示未来任务 */
    @Column(name = "show_future_tasks", nullable = false)
    private Boolean showFutureTasks = true;

    /** 是否显示统计信息（完成率等） */
    @Column(name = "show_statistics", nullable = false)
    private Boolean showStatistics = false;

    /** 默认思维模式 */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_mindset_mode", nullable = true)
    private MindsetMode defaultMindsetMode;

    public enum MindsetMode {
        EXECUTE,  // 执行模式
        LEARN,    // 学习模式
        PLAN      // 规划模式
    }
}
