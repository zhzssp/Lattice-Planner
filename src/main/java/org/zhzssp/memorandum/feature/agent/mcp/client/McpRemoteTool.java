package org.zhzssp.memorandum.feature.agent.mcp.client;

import java.util.List;
import java.util.Map;

/**
 * 从远程 MCP Server 发现的工具元数据。
 * 对标本地 ToolDefinition，但不含 bean/method（无法反射调用）。
 */
public record McpRemoteTool(
        /** 注册全名，如 mcp.brave-search.web_search */
        String fullName,
        /** 远程原始名，如 web_search */
        String originalName,
        /** 工具描述 */
        String description,
        /** JSON Schema（inputSchema） */
        Map<String, Object> inputSchema,
        /** 来源 Server 名 */
        String serverName
) {
    /** 统一 tag，供 PromptBuilder 按模式过滤 */
    public List<String> tags() {
        return List.of("mcp");
    }
}
