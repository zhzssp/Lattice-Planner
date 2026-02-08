package org.zhzssp.memorandum.entity;

/**
 * 任务状态枚举，支持「静默搁置」「自然过期」等非惩罚式设计。
 */
public enum TaskStatus {
    /** 待办 */
    PENDING,
    /** 已完成 */
    DONE,
    /** 已归档 */
    ARCHIVED,
    /** 已搁置（静默搁置，不显示在默认视图） */
    SHELVED
}
