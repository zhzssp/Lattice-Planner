package org.zhzssp.memorandum.feature.codex.tool;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.feature.agent.tool.AgentTool;
import org.zhzssp.memorandum.feature.agent.tool.ToolParam;
import org.zhzssp.memorandum.feature.codex.service.CodexSearchService;
import org.zhzssp.memorandum.feature.pkm.crag.CorrectiveRetriever;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联合检索：Git 仓库 + 个人笔记，命中带来源标签。不改旧两个工具的 schema。
 */
@Component
public class SearchBothTools {

    private final CodexSearchService gitSearch;
    private final CorrectiveRetriever pkm;
    private final NoteRepository noteRepo;

    public SearchBothTools(CodexSearchService gitSearch,
                           CorrectiveRetriever pkm,
                           NoteRepository noteRepo) {
        this.gitSearch = gitSearch;
        this.pkm = pkm;
        this.noteRepo = noteRepo;
    }

    @AgentTool(name = "search.both", tags = {"read", "codex", "kb"},
            description = "同时检索知识仓库（Git）与个人随手记（MySQL）。"
                    + "每条命中带 source=GIT 或 PKM，不要混权威：路径与认可笔记以 GIT 为准。"
                    + "study/iterate 下用户说「搜一下我学过的」时优先用本工具，而不是只搜一边。"
                    + "不替代 kb.semantic_search / doc.search 的既有返回格式。")
    public Map<String, Object> both(
            @ToolParam(value = "query", desc = "自然语言查询", required = true) String query,
            @ToolParam(value = "topK", desc = "每一侧返回条数，默认 4") Integer topK
    ) {
        User u = AgentContext.requireUser();
        int k = (topK == null || topK <= 0) ? 4 : Math.min(topK, 10);
        List<Map<String, Object>> hits = new ArrayList<>();

        if (gitSearch.searchable(u.getId())) {
            for (CodexSearchService.GitHit h : gitSearch.search(u.getId(), query, k)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "GIT");
                row.put("title", h.title());
                row.put("path", h.path());
                row.put("locator", h.locator());
                if (h.headingPath() != null) row.put("section", h.headingPath());
                row.put("score", Math.round(h.score() * 1000.0) / 1000.0);
                row.put("content", h.content());
                hits.add(row);
            }
        }

        try {
            CorrectiveRetriever.CragResult cr = pkm.retrieve(u, query, k);
            for (RagSearchService.Hit h : cr.hits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "PKM");
                row.put("kind", h.source());
                row.put("score", h.score());
                if ("NOTE".equals(h.source()) && h.noteId() != null) {
                    row.put("noteId", h.noteId());
                    noteRepo.findById(h.noteId()).ifPresent(n -> {
                        row.put("title", n.getTitle());
                        if (n.getPromotedPath() != null) {
                            row.put("promotedPath", n.getPromotedPath());
                        }
                    });
                } else if (h.sourcePath() != null) {
                    row.put("sourcePath", h.sourcePath());
                }
                row.put("content", h.content());
                hits.add(row);
            }
        } catch (Exception ignored) {
            // PKM 检索失败不挡 Git 侧
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("query", query);
        m.put("hitCount", hits.size());
        m.put("hits", hits);
        m.put("_caveat", "GIT 是知识仓库权威源；PKM 是随手记。未晋升的 PKM 不进路径文件。"
                + (gitSearch.searchable(u.getId()) ? ""
                : " 未接入仓库且 pkm.rag.git.enabled=false，GIT 侧为空。"));
        return m;
    }
}
