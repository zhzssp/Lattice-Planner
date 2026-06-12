package org.zhzssp.memorandum.feature.agent.mcp.client;

import java.util.List;
import java.util.Map;

/**
 * MCP 远程工具调用的返回结果。
 */
public record McpToolResult(
        /** content 列表 */
        List<Map<String, Object>> content,
        /** 是否出错 */
        boolean isError
) {
    /** 从结果中提取文本内容（拼接所有 text 类型 content） */
    public String extractText() {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : content) {
            if ("text".equals(c.get("type")) && c.get("text") != null) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(c.get("text"));
            }
        }
        return sb.toString();
    }

    /** 构造一个错误结果 */
    public static McpToolResult error(String message) {
        return new McpToolResult(
                List.of(Map.of("type", "text", "text", message)),
                true
        );
    }
}
