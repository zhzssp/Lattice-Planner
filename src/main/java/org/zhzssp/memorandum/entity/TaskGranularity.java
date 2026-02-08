package org.zhzssp.memorandum.entity;

/**
 * 任务粒度：为后续「原子任务 / 标准任务 / 模糊任务」提示预留。
 */
public enum TaskGranularity {
    /** 原子任务（≤15min） */
    ATOMIC,
    /** 标准任务（30–90min） */
    STANDARD,
    /** 模糊任务（如"复习数据库"） */
    FUZZY
}
