package org.zhzssp.memorandum.entity;

/**
 * 笔记类型：用于区分结构与展示。
 *
 * AGENT_MEMO 由 Agent 长期记忆归档时写入，不在 UI 主笔记列表展示。
 */
public enum NoteType {
    SCRATCH,        // 临时想法
    LEARNING,       // 学习笔记
    PROJECT,        // 项目笔记
    RETROSPECTIVE,  // 复盘笔记
    AGENT_MEMO      // Agent 长期记忆条目
}
