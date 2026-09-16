package org.zhzssp.memorandum.feature.codex.path;

import org.springframework.stereotype.Component;

/**
 * 把解析结果写回人可扫读的 {@code docs/learning-path.md}。
 */
@Component
public class LearningPathRenderer {

    public String render(LearningPathParser.ParsedPath path) {
        StringBuilder sb = new StringBuilder();
        int ver = path.version() <= 0 ? 1 : path.version();
        sb.append("---\n");
        sb.append("kind: learning-path\n");
        sb.append("version: ").append(ver).append('\n');
        if (path.cursor() != null && !path.cursor().isBlank()) {
            sb.append("cursor: ").append(path.cursor()).append('\n');
        } else if (!path.stations().isEmpty()) {
            sb.append("cursor: ").append(path.stations().get(0).id()).append('\n');
        }
        sb.append("---\n\n");
        for (LearningPathParser.ParsedStation s : path.stations()) {
            sb.append(renderStation(s));
        }
        return sb.toString();
    }

    /** 单站 Markdown，可直接作为 PATH_DELTA。 */
    public String renderStation(LearningPathParser.ParsedStation s) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(s.id()).append(" · ").append(s.title()).append("\n");
        if (!s.sources().isEmpty()) {
            sb.append("- sources: ").append(String.join(", ", s.sources())).append('\n');
        }
        if (s.lab() != null && !s.lab().isBlank()) {
            sb.append("- lab: ").append(s.lab()).append('\n');
        }
        if (!s.next().isEmpty()) {
            sb.append("- next: ").append(String.join(", ", s.next())).append('\n');
        }
        sb.append('\n');
        sb.append("| id | 级别 | 要点 |\n");
        sb.append("|----|------|------|\n");
        for (LearningPathParser.ParsedPoint p : s.points()) {
            sb.append("| ").append(p.id()).append(" | ").append(p.level().name())
                    .append(" | ").append(p.statement()).append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 已有路径与补丁按 station_id 合并：补丁里出现的站整站替换，其余保留；新站追加。
     */
    public LearningPathParser.ParsedPath merge(LearningPathParser.ParsedPath existing,
                                               LearningPathParser.ParsedPath delta) {
        java.util.LinkedHashMap<String, LearningPathParser.ParsedStation> map =
                new java.util.LinkedHashMap<>();
        if (existing != null && existing.ok()) {
            for (var s : existing.stations()) map.put(s.id(), s);
        }
        for (var s : delta.stations()) map.put(s.id(), s);
        String cursor = delta.cursor() != null ? delta.cursor()
                : (existing != null ? existing.cursor() : null);
        int ver = (existing != null && existing.ok() ? existing.version() : 0) + 1;
        return new LearningPathParser.ParsedPath(true, null, ver, cursor,
                new java.util.ArrayList<>(map.values()));
    }
}
