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

    /** 默认思维模式（用于选择页预选、切换模式后默认选项） */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_mindset_mode", nullable = true)
    private MindsetMode defaultMindsetMode;

    /** 规划模式下的默认任务视图：today / slot / energy */
    @Column(name = "default_task_view", length = 20, nullable = true)
    private String defaultTaskView;

    /** 模糊任务存在超过 N 天则提示拆分；0 表示不提示 */
    @Column(name = "fuzzy_task_days_threshold", nullable = true)
    private Integer fuzzyTaskDaysThreshold;

    /** 规划模式下是否显示目标管理区块 */
    @Column(name = "show_goals", nullable = true)
    private Boolean showGoals;

    /** 是否显示「需要拆分的模糊任务」提示 */
    @Column(name = "show_fuzzy_hint", nullable = true)
    private Boolean showFuzzyHint;

    /** 是否显示归档/搁置任务区块 */
    @Column(name = "show_archived_section", nullable = true)
    private Boolean showArchivedSection;

    /** 是否显示规划完成得分曲线区块 */
    @Column(name = "show_score_section", nullable = true)
    private Boolean showScoreSection;

    /** 背景亮/暗：light / dark，默认亮色 */
    @Column(name = "theme", length = 10, nullable = true)
    private String theme;

    public enum MindsetMode {
        EXECUTE,  // 执行模式
        LEARN,    // 学习模式
        PLAN      // 规划模式
    }
}
