package org.zhzssp.memorandum.feature.codex.path;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.codex.entity.KbPoint;
import org.zhzssp.memorandum.feature.codex.index.FrontMatterParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 窄口径解析 {@code docs/learning-path.md}（及蒸馏 PATH_DELTA 片段）。
 *
 * <p>只认固定标题、列表键和三列表格。解析失败必须带上原因，禁止静默丢行。</p>
 */
@Component
public class LearningPathParser {

    public static final String DEFAULT_PATH = "docs/learning-path.md";

    private static final Pattern STATION_HEAD = Pattern.compile(
            "^##\\s+([A-Za-z][A-Za-z0-9._-]*)\\s*(?:[·•.\\-—]|\\s)+\\s*(.+?)\\s*$");

    private static final Pattern KEY_LINE = Pattern.compile(
            "^\\s*[-*]\\s*(sources|lab|next)\\s*[：:]\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_SEP = Pattern.compile(
            "^\\s*\\|?\\s*:?-{2,}");

    private final FrontMatterParser frontMatter;

    public LearningPathParser(FrontMatterParser frontMatter) {
        this.frontMatter = frontMatter;
    }

    public record ParsedPoint(String id, KbPoint.Level level, String statement) {}

    public record ParsedStation(String id, String title, List<String> sources,
                                String lab, List<String> next, List<ParsedPoint> points) {}

    public record ParsedPath(boolean ok, String error, int version, String cursor,
                             List<ParsedStation> stations) {

        public static ParsedPath fail(String error) {
            return new ParsedPath(false, error, 0, null, List.of());
        }

        public int mustCount() {
            int n = 0;
            for (ParsedStation s : stations) {
                for (ParsedPoint p : s.points()) {
                    if (p.level() == KbPoint.Level.MUST) n++;
                }
            }
            return n;
        }

        public int skipCount() {
            int n = 0;
            for (ParsedStation s : stations) {
                for (ParsedPoint p : s.points()) {
                    if (p.level() == KbPoint.Level.SKIP) n++;
                }
            }
            return n;
        }
    }

    /**
     * 解析完整路径文件。
     *
     * @param requireKind {@code true} 时 front-matter 必须声明 {@code kind: learning-path}
     */
    public ParsedPath parseDocument(String content, boolean requireKind) {
        if (content == null || content.isBlank()) {
            return ParsedPath.fail("文件为空。");
        }
        FrontMatterParser.Result fm = frontMatter.parse(content);
        String kind = fm.str("kind");
        if (requireKind) {
            if (kind == null || kind.isBlank()) {
                return ParsedPath.fail("缺少 front-matter kind: learning-path。");
            }
            if (!"learning-path".equalsIgnoreCase(kind.trim())
                    && !"LEARNING_PATH".equalsIgnoreCase(kind.trim().replace('-', '_'))) {
                return ParsedPath.fail("kind 不是 learning-path：" + kind);
            }
        }
        int version = parseInt(fm.str("version"), 1);
        String cursor = blankToNull(fm.str("cursor"));
        String body = content.substring(Math.min(fm.bodyStart(), content.length()));
        return parseBody(body, version, cursor);
    }

    /** 蒸馏 PATH_DELTA：无 front-matter，version=0。 */
    public ParsedPath parseDelta(String body) {
        if (body == null || body.isBlank()) {
            return ParsedPath.fail("PATH_DELTA 为空。");
        }
        return parseBody(body, 0, null);
    }

    private ParsedPath parseBody(String body, int version, String cursor) {
        List<String> lines = body.replace("\r\n", "\n").replace("\r", "\n").lines().toList();
        List<ParsedStation> stations = new ArrayList<>();
        Set<String> stationIds = new LinkedHashSet<>();
        Set<String> pointIds = new LinkedHashSet<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher head = STATION_HEAD.matcher(line);
            if (!head.matches()) {
                i++;
                continue;
            }
            String id = head.group(1);
            String title = head.group(2).strip();
            if (title.isEmpty()) {
                return ParsedPath.fail("站 " + id + " 缺少标题。");
            }
            if (!stationIds.add(id)) {
                return ParsedPath.fail("重复的站 id：" + id);
            }
            i++;
            List<String> sources = new ArrayList<>();
            String lab = null;
            List<String> next = new ArrayList<>();
            List<String> tableLines = new ArrayList<>();
            boolean inTable = false;
            while (i < lines.size()) {
                String row = lines.get(i);
                if (STATION_HEAD.matcher(row).matches()) break;
                Matcher key = KEY_LINE.matcher(row);
                if (key.matches()) {
                    String k = key.group(1).toLowerCase(Locale.ROOT);
                    String v = key.group(2).strip();
                    switch (k) {
                        case "sources" -> sources.addAll(splitCsv(v));
                        case "lab" -> {
                            String labVal = v.replace("`", "").strip();
                            lab = labVal.isEmpty() ? null : labVal;
                        }
                        case "next" -> next.addAll(splitCsv(v));
                        default -> { }
                    }
                    inTable = false;
                    i++;
                    continue;
                }
                if (looksLikeTableHeader(row)) {
                    inTable = true;
                    tableLines.add(row);
                    i++;
                    continue;
                }
                if (inTable && (row.contains("|") || TABLE_SEP.matcher(row).find())) {
                    tableLines.add(row);
                    i++;
                    continue;
                }
                inTable = false;
                i++;
            }
            List<ParsedPoint> points;
            try {
                points = parseTable(tableLines, id, pointIds);
            } catch (IllegalArgumentException ex) {
                return ParsedPath.fail(ex.getMessage());
            }
            stations.add(new ParsedStation(id, title, sources, lab, next, points));
        }

        if (stations.isEmpty()) {
            return ParsedPath.fail("没有解析到任何 ## <id> · 标题 形式的站。");
        }
        if (cursor != null && stations.stream().noneMatch(s -> s.id().equals(cursor))) {
            return ParsedPath.fail("cursor 指向不存在的站：" + cursor);
        }
        return new ParsedPath(true, null, version, cursor, stations);
    }

    private List<ParsedPoint> parseTable(List<String> tableLines, String stationId,
                                         Set<String> pointIds) {
        List<ParsedPoint> out = new ArrayList<>();
        if (tableLines.isEmpty()) return out;
        int headerIdx = -1;
        int idCol = -1, levelCol = -1, stmtCol = -1;
        for (int i = 0; i < tableLines.size(); i++) {
            List<String> cells = splitRow(tableLines.get(i));
            if (cells.isEmpty()) continue;
            int id = indexOf(cells, "id");
            int lv = indexOf(cells, "级别", "level");
            int st = indexOf(cells, "要点", "point", "statement");
            if (id >= 0 && lv >= 0 && st >= 0) {
                headerIdx = i;
                idCol = id;
                levelCol = lv;
                stmtCol = st;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new IllegalArgumentException("站 " + stationId + " 的表格缺少 id / 级别 / 要点 三列。");
        }
        for (int i = headerIdx + 1; i < tableLines.size(); i++) {
            String raw = tableLines.get(i);
            if (TABLE_SEP.matcher(raw.replace("|", " | ")).find() && !raw.contains("MUST")
                    && !raw.contains("SKIP")) {
                continue;
            }
            List<String> cells = splitRow(raw);
            if (cells.size() <= Math.max(idCol, Math.max(levelCol, stmtCol))) continue;
            String pid = cells.get(idCol).strip();
            String lv = cells.get(levelCol).strip();
            String stmt = cells.get(stmtCol).strip();
            if (pid.isEmpty() || stmt.isEmpty()) continue;
            if (!pointIds.add(pid)) {
                throw new IllegalArgumentException("重复的要点 id：" + pid);
            }
            KbPoint.Level level = parseLevel(lv);
            if (level == null) {
                throw new IllegalArgumentException("要点 " + pid + " 的级别不是 MUST/SKIP：" + lv);
            }
            out.add(new ParsedPoint(pid, level, stmt));
        }
        return out;
    }

    private static KbPoint.Level parseLevel(String raw) {
        if (raw == null) return null;
        String s = raw.strip().toUpperCase(Locale.ROOT);
        if (s.equals("MUST") || s.equals("必学") || s.equals("必须")) return KbPoint.Level.MUST;
        if (s.equals("SKIP") || s.equals("跳过") || s.equals("先跳过")) return KbPoint.Level.SKIP;
        return null;
    }

    private static boolean looksLikeTableHeader(String row) {
        String n = row.toLowerCase(Locale.ROOT);
        return n.contains("|") && n.contains("id") && (n.contains("级别") || n.contains("level"));
    }

    private static List<String> splitRow(String row) {
        String s = row.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : s.split("\\|", -1)) {
            out.add(c.strip());
        }
        return out;
    }

    private static int indexOf(List<String> cells, String... names) {
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i).strip().toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (c.equals(n.toLowerCase(Locale.ROOT))) return i;
            }
        }
        return -1;
    }

    private static List<String> splitCsv(String v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isBlank()) return out;
        for (String p : v.split("[,，]")) {
            String s = p.strip().replace("`", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
