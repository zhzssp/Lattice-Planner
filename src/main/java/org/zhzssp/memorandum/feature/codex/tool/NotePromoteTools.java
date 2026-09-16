package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.sediment.NotePromoteService;

import java.util.Map;

/**
 * 把 MySQL 随手记晋升为 Git 仓库笔记。原文不删。
 */
@Component
public class NotePromoteTools {

    private final NotePromoteService promote;

    public NotePromoteTools(NotePromoteService promote) {
        this.promote = promote;
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
        return promote.promote(u, noteId, pointId, guidePath, anchor, repoName);
    }
}
