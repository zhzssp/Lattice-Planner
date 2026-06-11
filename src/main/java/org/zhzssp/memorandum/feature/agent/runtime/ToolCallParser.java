package org.zhzssp.memorandum.feature.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM 输出，识别两种状态：
 * 1) 包含 {"tool":"...","arguments":{...}} -> 工具调用
 * 2) 否则 -> 终态自然语言回答
 *
 * 兼容 deepseek-reasoner 输出包含 &lt;think&gt;...&lt;/think&gt; 推理段的情况。
 */
@Component
public class ToolCallParser {

    private static final Pattern THINK = Pattern.compile(
            "<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper om;

    public ToolCallParser(ObjectMapper om) {
        this.om = om;
    }

    public record ToolCall(String name, JsonNode arguments) {
    }

    public ToolCall parse(String raw) {
        if (raw == null) return null;
        String s = THINK.matcher(raw).replaceAll("").trim();
        if (s.isEmpty()) return null;

        // 优先匹配围栏块内 JSON
        Matcher fm = FENCE.matcher(s);
        String candidate = fm.find() ? fm.group(1).trim() : s;

        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = om.readTree(candidate.substring(start, end + 1));
            if (!node.has("tool") || !node.get("tool").isTextual()) return null;
            String name = node.get("tool").asText();
            if (name.isBlank()) return null;
            JsonNode args = node.path("arguments");
            return new ToolCall(name, args);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 给最终自然语言回答用：剥离 think 段，避免泄漏推理过程 */
    public String stripThinking(String raw) {
        return raw == null ? "" : THINK.matcher(raw).replaceAll("").trim();
    }

    /**
     * 给最终回答展示用的全面清洗：
     *  1) 去除 &lt;think&gt;...&lt;/think&gt;
     *  2) 去除围栏代码块里残留的 tool-call JSON（含 "tool" 字段的 JSON）
     *  3) 去除独立段落形式的 tool-call JSON（行首 { ... } 且包含 "tool"）
     *  其余内容（普通 markdown / 普通围栏代码块）保留，交给前端渲染。
     */
    public String cleanForDisplay(String raw) {
        if (raw == null) return "";
        String s = THINK.matcher(raw).replaceAll("");

        // 删掉围栏块里疑似 tool-call 的 JSON 块（保留普通代码块）
        Matcher fm = FENCE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (fm.find()) {
            String inner = fm.group(1).trim();
            if (looksLikeToolCallJson(inner)) {
                fm.appendReplacement(sb, "");
            } else {
                fm.appendReplacement(sb, Matcher.quoteReplacement(fm.group(0)));
            }
        }
        fm.appendTail(sb);
        s = sb.toString();

        // 删掉裸露的 tool-call JSON 段落
        s = TOOL_JSON_BLOCK.matcher(s).replaceAll("");

        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static final Pattern TOOL_JSON_BLOCK = Pattern.compile(
            "(?m)^\\s*\\{[\\s\\S]*?\"tool\"\\s*:\\s*\"[^\"]+\"[\\s\\S]*?\\}\\s*$");

    private boolean looksLikeToolCallJson(String inner) {
        if (inner == null || inner.isBlank()) return false;
        int s = inner.indexOf('{');
        int e = inner.lastIndexOf('}');
        if (s < 0 || e <= s) return false;
        try {
            JsonNode n = om.readTree(inner.substring(s, e + 1));
            return n.has("tool") && n.get("tool").isTextual();
        } catch (Exception ex) {
            return false;
        }
    }
}
