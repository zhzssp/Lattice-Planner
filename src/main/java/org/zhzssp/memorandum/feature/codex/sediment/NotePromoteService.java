package org.zhzssp.memorandum.feature.codex.sediment;

import org.springframework.stereotype.Service;
import org.zhzssp.memorandum.core.service.NoteService;
import org.zhzssp.memorandum.entity.Note;
import org.zhzssp.memorandum.entity.User;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 MySQL 随手记晋升为 Git {@code docs/notes}。HTTP 与 Agent 工具共用，避免两套逻辑。
 */
@Service
public class NotePromoteService {

    private final NoteService notes;
    private final SedimentService sediment;
    private final NoteTemplate template;

    public NotePromoteService(NoteService notes, SedimentService sediment, NoteTemplate template) {
        this.notes = notes;
        this.sediment = sediment;
        this.template = template;
    }

    public Map<String, Object> promote(User user, Long noteId, String pointId,
                                       String guidePath, String anchor, String repoName) {
        Note n = notes.findByIdForUser(noteId, user).orElse(null);
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
                null, notePath, body, null,
                SedimentService.WriteMode.CREATE, true, backref, pointId);
        SedimentService.Result r = sediment.sediment(user.getId(), req);
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
