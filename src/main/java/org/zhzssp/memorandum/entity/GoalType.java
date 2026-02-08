package org.zhzssp.memorandum.entity;

/**
 * 目标类型：长期/中期/短期，弱约束，不强制用户选择。
 */
public enum GoalType {
    /** 长期（季度/学期） */
    LONG_TERM,
    /** 中期（项目） */
    MID_TERM,
    /** 短期（任务/备忘） */
    SHORT_TERM
}
