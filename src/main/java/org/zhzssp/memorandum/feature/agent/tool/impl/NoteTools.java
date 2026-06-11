package org.zhzssp.memorandum.feature.agent.tool.impl;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.NoteType;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;

import java.util.List;

/**
 * 笔记相关工具。AGENT_MEMO 由 LongTermMemoryService 内部使用，不暴露给 LLM。
 */
@Component
public class NoteTools {

    private final NoteService noteService;

    public NoteTools(NoteService noteService) {
        this.noteService = noteService;
    }

    @AgentTool(name = "note.list", tags = {"note", "read"},
            description = "列出当前用户的非系统类笔记。无参数。")
    public List<NoteView> list() {
        return noteService.listVisibleByUser(AgentContext.requireUser())
                .stream().map(NoteView::of).toList();
    }

    @AgentTool(name = "note.create", tags = {"note", "write"},
            description = "创建一条笔记。type ∈ SCRATCH/LEARNING/PROJECT/RETROSPECTIVE，可空（默认 SCRATCH）。")
    public NoteView create(
            @ToolParam(value = "title", desc = "笔记标题，可空") String title,
            @ToolParam(value = "content", desc = "笔记内容", required = true) String content,
            @ToolParam(value = "type", desc = "SCRATCH/LEARNING/PROJECT/RETROSPECTIVE，可空") String type
    ) {
        NoteType t = NoteType.SCRATCH;
        if (type != null && !type.isBlank()) {
            try {
                NoteType parsed = NoteType.valueOf(type.trim().toUpperCase());
                // 不允许 LLM 直接写 AGENT_MEMO
                if (parsed != NoteType.AGENT_MEMO) t = parsed;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return NoteView.of(noteService.create(AgentContext.requireUser(), title, content, t));
    }

    public record NoteView(Long id, String title, String type, String createdAt, String preview) {
        static NoteView of(Note n) {
            String content = n.getContent() == null ? "" : n.getContent();
            String preview = content.length() <= 120 ? content : content.substring(0, 120) + "...";
            return new NoteView(
                    n.getId(), n.getTitle(),
                    n.getType() == null ? null : n.getType().name(),
                    n.getCreatedAt() == null ? null : n.getCreatedAt().toLocalDate().toString(),
                    preview);
        }
    }
}
