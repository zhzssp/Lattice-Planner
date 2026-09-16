package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.sediment.NoteTemplate;
import org.zhzssp.memorandum.feature.codex.sediment.SedimentService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 MySQL 随手记晋升为 Git 仓库笔记。原文不删。
 */
@Component
public class NotePromoteTools {

    private final NoteService notes;
    private final SedimentService sediment;
    private final NoteTemplate template;

    public NotePromoteTools(NoteService notes, SedimentService sediment, NoteTemplate template) {
        this.notes = notes;
        this.sediment = sediment;
        this.template = template;
    }

    @AgentTool(name = "note.promote", tags = {"note", "write", "codex"}, requiresConfirm = true,
            description = "把一条个人笔记（MySQL）晋升为知识仓库 docs/notes 下的文件。"
                    + "原文不删除，只记下 promoted_path。未晋升的随手记不得写进 learning-path.md。"
                    + "可带 pointId 把速记挂到路径要点（还需 guidePath+anchor）。")
    public Map<String, Object> promote(
            @ToolParam(value = "noteId", desc = "个人笔记 id", required = true) Long noteId,
            @ToolParam(value = "pointId", desc = "可选，路径要点 id，如 s1.p1") String pointId,
            @ToolParam(value = "guidePath", desc = "挂靠的知识文档路径；与 anchor 一起才插入速记")
            String guidePath,
            @ToolParam(value = "anchor", desc = "章节 anchor") String anchor,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        Note n = notes.findByIdForUser(noteId, u).orElse(null);
        if (n == null) {
            return Map.of("ok", false, "error", "NOTE_NOT_FOUND", "message", "没有这条个人笔记。");
        }
        if (n.getPromotedPath() != null && !n.getPromotedPath().isBlank()) {
            return Map.of("ok", true, "alreadyPromoted", true,
                    "promotedPath", n.getPromotedPath(),
                    "message", "已经晋升过，原文仍在个人笔记里。");
        }
        String title = (n.getTitle() == null || n.getTitle().isBlank()) ? "未命名" : n.getTitle();
        String body = n.getContent() == null ? "" : n.getContent();
        boolean backref = guidePath != null && !guidePath.isBlank()
                && anchor != null && !anchor.isBlank();
        String notePath = "docs/notes/" + template.slug(title) + ".md";
        SedimentService.Request req = new SedimentService.Request(
                repoName, title, body, title,
                backref ? guidePath : null,
                backref ? anchor : null,
                null, notePath, body, AgentContext.sessionId(),
                SedimentService.WriteMode.CREATE, true, backref, pointId);
        SedimentService.Result r = sediment.sediment(u.getId(), req);
        Map<String, Object> m = new LinkedHashMap<>();
        if (!r.ok()) {
            m.put("ok", false);
            m.put("error", r.code());
            m.put("message", r.message());
            return m;
        }
        n.setPromotedPath(r.notePath());
        notes.update(n, null, null, null, n.getTags());
        m.put("ok", true);
        m.put("promotedPath", r.notePath());
        m.put("pkmNoteId", n.getId());
        m.put("pointId", pointId);
        m.put("branch", r.branch());
        m.put("message", "已写入仓库笔记，个人笔记原文保留。尚未 commit。");
        m.put("nextStep", r.nextStep());
        return m;
    }
}
