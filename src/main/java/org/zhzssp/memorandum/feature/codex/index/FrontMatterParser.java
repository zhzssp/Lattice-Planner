package org.zhzssp.memorandum.feature.codex.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML front-matter 解析器（极简实现，无 SnakeYAML 依赖）。
 *
 * <h3>为什么全部字段可选</h3>
 * <p>目标仓库现有 61 篇 Markdown <strong>一篇都没有 front-matter</strong>。
 * 若把元数据设为必填，第一步就得先给 61 个文件补齐 schema——
 * 这种「先交作业才能用」的设计会让方案永远停在纸面。</p>
 *
 * <p>因此：<strong>缺失只降级，不报错</strong>。kind 从路径推断、title 取首个
 * {@code # } 标题、maturity 默认 stable。先把索引跑起来产生价值，
 * 再由 Agent 在后续 PR 里逐步回填元数据。</p>
 *
 * <h3>为什么自己写解析而不引 SnakeYAML</h3>
 * <p>front-matter 只用到「标量 / 字符串数组 / 一层嵌套对象」这三种形状，
 * 用不到 YAML 的锚点、多文档、复杂类型。为三种形状引一个能执行任意类型构造的
 * 库（SnakeYAML 历史上有反序列化 RCE），在「解析用户本地文件」这个场景里不划算。</p>
 */
@Component
public class FrontMatterParser {

    private static final Logger log = LoggerFactory.getLogger(FrontMatterParser.class);

    /** 校验问题的严重度。 */
    public enum Severity {
        /** 语法错 / 枚举非法：会导致元数据不可用。 */
        ERROR,
        /** 缺推荐字段：可用但不完整。 */
        WARN,
        /** 可补充项。 */
        INFO
    }

    public record Issue(Severity severity, String field, String message) {}

    /**
     * 解析结果。
     *
     * @param data      解析出的键值（无 front-matter 时为空 Map，不是 null）
     * @param bodyStart 正文起始偏移（front-matter 之后），用于章节偏移计算
     * @param issues    校验问题
     */
    public record Result(Map<String, Object> data, int bodyStart, List<Issue> issues) {

        public boolean present() {
            return !data.isEmpty();
        }

        public boolean hasError() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        }

        public String firstError() {
            return issues.stream()
                    .filter(i -> i.severity() == Severity.ERROR)
                    .map(i -> i.field() + ": " + i.message())
                    .findFirst().orElse(null);
        }

        public String str(String key) {
            Object v = data.get(key);
            return v == null ? null : String.valueOf(v);
        }

        @SuppressWarnings("unchecked")
        public List<String> list(String key) {
            Object v = data.get(key);
            if (v == null) return List.of();
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>(l.size());
                for (Object o : l) if (o != null) out.add(String.valueOf(o));
                return out;
            }
            return List.of(String.valueOf(v));
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = data.get(key);
            if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of();
        }
    }

    private static final Result EMPTY = new Result(Map.of(), 0, List.of());

    private final ObjectMapper om;

    public FrontMatterParser(ObjectMapper om) {
        this.om = om;
    }

    /**
     * 解析文档开头的 {@code ---} 包裹块。
     *
     * <p>无 front-matter 时返回空结果且 {@code issues} 为空——
     * 「没有元数据」是完全合法的状态，不是问题。</p>
     *
     * <p><strong>{@code bodyStart} 始终以传入的原始 {@code content} 为基准</strong>：
     * 调用方（章节解析、链接抽取、切片）都用它对原文做 {@code substring}，
     * 若这里因剥离 BOM 而产生 1 字符偏移，会让所有章节区间整体错位——
     * 这种 off-by-one 只在带 BOM 的文件上出现，极难定位。</p>
     */
    public Result parse(String content) {
        if (content == null || content.isBlank()) return EMPTY;

        // BOM 容忍：记录偏移量而非改写字符串，保证返回的 bodyStart 相对原文有效
        int bomOffset = content.startsWith("\uFEFF") ? 1 : 0;
        String text = content.substring(bomOffset);

        int idx = 0;
        while (idx < text.length() && (text.charAt(idx) == '\n' || text.charAt(idx) == '\r')) idx++;
        if (!text.startsWith("---", idx)) return EMPTY;

        // 定位结束分隔符：必须独占一行
        int firstNl = text.indexOf('\n', idx);
        if (firstNl < 0) return EMPTY;
        int lineStart = firstNl + 1;

        int end = -1;
        int scan = lineStart;
        int bodyStart = -1;
        while (scan < text.length()) {
            int nl = text.indexOf('\n', scan);
            String line = (nl < 0 ? text.substring(scan) : text.substring(scan, nl));
            String trimmed = line.strip();
            if (trimmed.equals("---") || trimmed.equals("...")) {
                end = scan;
                bodyStart = (nl < 0 ? text.length() : nl + 1);
                break;
            }
            if (nl < 0) break;
            scan = nl + 1;
        }
        if (end < 0) {
            // 有开头没结尾 → 这是真正的语法错误，须报出来，否则整个正文会被当元数据
            return new Result(Map.of(), 0, List.of(new Issue(Severity.ERROR, "front-matter",
                    "存在起始 --- 但缺少结束分隔符")));
        }

        String yaml = text.substring(firstNl + 1, end);
        List<Issue> issues = new ArrayList<>();
        Map<String, Object> data = parseSimpleYaml(yaml, issues);
        validate(data, issues);
        return new Result(data, bodyStart + bomOffset, issues);
    }

    /** 元数据转 JSON 串落库。 */
    public String toJson(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return om.writeValueAsString(data);
        } catch (Exception e) {
            log.debug("[Codex] front-matter 转 JSON 失败：{}", e.getMessage());
            return null;
        }
    }

    /* ---------------- 极简 YAML 子集 ---------------- */

    /**
     * 支持的形状：
     * <pre>
     * key: value                 标量
     * key: [a, b, c]             行内数组
     * key:                       块数组
     *   - a
     *   - b
     * key:                       一层嵌套对象
     *   sub: v
     * key:                       对象数组（每项一层键值）
     *   - {kind: paper, ref: "x"}
     * </pre>
     */
    private Map<String, Object> parseSimpleYaml(String yaml, List<Issue> issues) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = yaml.split("\\R", -1);

        String pendingKey = null;
        List<Object> pendingList = null;
        Map<String, Object> pendingMap = null;

        for (String raw : lines) {
            if (raw == null) continue;
            String noComment = stripComment(raw);
            if (noComment.isBlank()) continue;

            int indent = indentOf(noComment);
            String line = noComment.strip();

            // 缩进的列表项
            if (line.startsWith("- ") || line.equals("-")) {
                if (pendingKey == null) continue;   // 顶层裸列表：本 schema 不使用
                if (pendingList == null) pendingList = new ArrayList<>();
                String item = line.length() > 1 ? line.substring(1).strip() : "";
                pendingList.add(parseInlineValue(item));
                continue;
            }

            // 缩进的子键（嵌套对象）
            if (indent > 0 && pendingKey != null && line.contains(":")) {
                if (pendingList != null) {
                    // 形如 "- kind: paper" 之后跟随的 "  ref: x"：并入最后一项
                    Object last = pendingList.isEmpty() ? null : pendingList.get(pendingList.size() - 1);
                    if (last instanceof Map<?, ?> lm) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) lm;
                        int c = line.indexOf(':');
                        m.put(line.substring(0, c).strip(), parseInlineValue(line.substring(c + 1).strip()));
                        continue;
                    }
                }
                if (pendingMap == null) pendingMap = new LinkedHashMap<>();
                int c = line.indexOf(':');
                pendingMap.put(line.substring(0, c).strip(), parseInlineValue(line.substring(c + 1).strip()));
                continue;
            }

            // 顶层键
            int colon = line.indexOf(':');
            if (colon < 0) {
                issues.add(new Issue(Severity.WARN, "front-matter", "无法解析的行：" + truncate(line)));
                continue;
            }
            // 收束上一个 key 的块值
            flushPending(out, pendingKey, pendingList, pendingMap);
            pendingList = null;
            pendingMap = null;

            String key = line.substring(0, colon).strip();
            String val = line.substring(colon + 1).strip();
            if (val.isEmpty()) {
                pendingKey = key;              // 块值在后续缩进行
            } else {
                out.put(key, parseInlineValue(val));
                pendingKey = null;
            }
        }
        flushPending(out, pendingKey, pendingList, pendingMap);
        return out;
    }

    private void flushPending(Map<String, Object> out, String key,
                              List<Object> list, Map<String, Object> map) {
        if (key == null) return;
        if (list != null) {
            out.put(key, list);
        } else if (map != null) {
            out.put(key, map);
        } else {
            out.putIfAbsent(key, "");
        }
    }

    /** 行内值：数组 / 对象 / 引号串 / 裸标量。 */
    private Object parseInlineValue(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.startsWith("[") && v.endsWith("]")) {
            String inner = v.substring(1, v.length() - 1).strip();
            if (inner.isEmpty()) return new ArrayList<>();
            List<Object> out = new ArrayList<>();
            for (String part : splitTopLevel(inner)) out.add(unquote(part.strip()));
            return out;
        }
        if (v.startsWith("{") && v.endsWith("}")) {
            String inner = v.substring(1, v.length() - 1).strip();
            Map<String, Object> m = new LinkedHashMap<>();
            if (inner.isEmpty()) return m;
            for (String part : splitTopLevel(inner)) {
                int c = part.indexOf(':');
                if (c < 0) continue;
                m.put(part.substring(0, c).strip(), unquote(part.substring(c + 1).strip()));
            }
            return m;
        }
        return unquote(v);
    }

    /** 按逗号切分，但跳过引号与括号内部的逗号。 */
    private List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> { quote = c; cur.append(c); }
                case '[', '{' -> { depth++; cur.append(c); }
                case ']', '}' -> { depth--; cur.append(c); }
                case ',' -> {
                    if (depth == 0) { out.add(cur.toString()); cur.setLength(0); }
                    else cur.append(c);
                }
                default -> cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private Object unquote(String v) {
        if (v.length() >= 2) {
            char f = v.charAt(0);
            char l = v.charAt(v.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        if (v.equals("true")) return Boolean.TRUE;
        if (v.equals("false")) return Boolean.FALSE;
        return v;
    }

    private String stripComment(String line) {
        // 只处理行首 # 与「空格 + #」，避免误伤 anchor（如 path#anchor）与引号内的 #
        String t = line.strip();
        if (t.startsWith("#")) return "";
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '#' && i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    /* ---------------- 校验（只报告，不阻塞索引） ---------------- */

    private static final List<String> KNOWN_MATURITY =
            List.of("seed", "draft", "reviewed", "stable", "deprecated");
    private static final List<String> KNOWN_LEVELS = List.of("L0", "L1", "L2", "L3");

    private void validate(Map<String, Object> data, List<Issue> issues) {
        if (data.isEmpty()) return;

        Object schema = data.get("schema");
        if (schema != null && !"1".equals(String.valueOf(schema))) {
            issues.add(new Issue(Severity.WARN, "schema",
                    "未知 schema 版本 " + schema + "，按 v1 解析"));
        }

        Object maturity = data.get("maturity");
        if (maturity != null && !KNOWN_MATURITY.contains(String.valueOf(maturity).toLowerCase())) {
            issues.add(new Issue(Severity.ERROR, "maturity",
                    "非法取值 " + maturity + "，应为 " + KNOWN_MATURITY));
        }

        Object level = data.get("level");
        if (level != null && !KNOWN_LEVELS.contains(String.valueOf(level).toUpperCase())) {
            issues.add(new Issue(Severity.ERROR, "level",
                    "非法取值 " + level + "，应为 " + KNOWN_LEVELS));
        }

        if (data.containsKey("kind") && String.valueOf(data.get("kind")).isBlank()) {
            issues.add(new Issue(Severity.WARN, "kind", "声明了 kind 但为空，将按路径推断"));
        }
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
