package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.entity.KnowledgeRepo;
import org.zhzssp.memorandum.feature.codex.path.PathApplyService;
import org.zhzssp.memorandum.feature.codex.path.PathQueryService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学习路径工具（G5）。Agent 只提议，人确认才改权威文件。
 *
 * <p>不在这里把自然语言「猜」成车站：整站替换一旦猜错会抹掉该站既有要点。
 * 模型应先 {@code path.read}，再给出可解析的 PATH_DELTA。</p>
 */
@Component
public class PathTools {

    private final PathQueryService query;
    private final PathApplyService apply;

    public PathTools(PathQueryService query, PathApplyService apply) {
        this.query = query;
        this.apply = apply;
    }

    @AgentTool(name = "path.read", tags = {"codex", "read", "path"},
            description = "读当前学习路径：车站、MUST/SKIP 要点、cursor、投影任务状态，以及权威文件原文。"
                    + "用户问「我学到哪了/路径是什么/当前要点」时调用。"
                    + "改路径之前必须先读，补丁按站整站替换，漏读会覆盖既有要点。")
    public Map<String, Object> read(
            @ToolParam(value = "repoName", desc = "仓库名；省略则用第一个启用的仓库") String repoName
    ) {
        User u = AgentContext.requireUser();
        KnowledgeRepo repo = query.resolve(u.getId(), repoName);
        if (repo == null) {
            return Map.of("ok", false, "error", "REPO_NOT_FOUND",
                    "message", "未找到知识仓库"
                            + (repoName == null ? "" : "：" + repoName));
        }
        Map<String, Object> m = new LinkedHashMap<>(query.view(repo));
        m.put("ok", true);
        return m;
    }

    @AgentTool(name = "path.propose", tags = {"codex", "write", "path"},
            description = "起草路径补丁，★不写盘。入参必须是 PATH_DELTA（## 站id · 标题 + 三列表格）"
                    + "或带 kind: learning-path 的完整路径文件。"
                    + "自然语言请先 path.read，再自己写成上述格式后重试——"
                    + "本工具不会猜站，因为猜错会整站覆盖。"
                    + "把预览原样念给用户，他确认后再 path.apply。")
    public Map<String, Object> propose(
            @ToolParam(value = "delta", desc = "PATH_DELTA 或完整路径文件", required = true)
            String delta,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (delta == null || delta.isBlank()) {
            return Map.of("ok", false, "error", "EMPTY_DELTA", "message", "路径补丁为空。");
        }
        PathApplyService.Proposal p = apply.preview(u.getId(), repoName, delta);
        Map<String, Object> m = apply.toMap(p);
        if (!p.ok()) {
            KnowledgeRepo repo = query.resolve(u.getId(), repoName);
            if (repo != null) {
                m.put("current", query.view(repo));
            }
            m.put("hint", "按 PATH_DELTA 重写后再调用。格式示例：\n"
                    + "## s1 · 站标题\n"
                    + "- sources: docs/learning-guides/x.md\n"
                    + "- lab: lab/\n\n"
                    + "| id | 级别 | 要点 |\n"
                    + "|----|------|------|\n"
                    + "| s1.p1 | MUST | 能…… |\n"
                    + "补丁按 station_id 整站替换：该站既有要点必须一并写出，否则会被覆盖。");
        }
        return m;
    }

    @AgentTool(name = "path.apply", tags = {"codex", "write", "path"}, requiresConfirm = true,
            description = "把已预览的路径补丁写入 docs/learning-path.md，重建索引并对账投影任务。"
                    + "必须传入 path.propose 返回的 delta（或同等 PATH_DELTA）。"
                    + "解析失败只写 docs/learning-path.md.draft，不改权威文件。"
                    + "写入后不 commit。不要用 task.create 另建待办——投影就是那一份。")
    public Map<String, Object> apply(
            @ToolParam(value = "delta", desc = "path.propose 返回的 delta", required = true)
            String delta,
            @ToolParam(value = "repoName", desc = "仓库名") String repoName
    ) {
        User u = AgentContext.requireUser();
        if (delta == null || delta.isBlank()) {
            return Map.of("ok", false, "error", "EMPTY_DELTA", "message", "路径补丁为空。");
        }
        PathApplyService.ApplyResult r = apply.apply(u.getId(), repoName, delta, true);
        return apply.toMap(r);
    }
}
