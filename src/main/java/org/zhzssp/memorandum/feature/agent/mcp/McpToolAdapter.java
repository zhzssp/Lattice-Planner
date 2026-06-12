package org.zhzssp.memorandum.feature.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.util.*;

/**
 * ToolRegistry → MCP Tool 适配层。
 * 从 ToolRegistry 导出 MCP tools/list 响应，并委托 invoke 执行。
 */
@Component
public class McpToolAdapter {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper om;

    /** MCP 不暴露的 tag 集合。 */
    private static final Set<String> EXCLUDED_TAGS = Set.of("local", "subagent");

    public McpToolAdapter(ToolRegistry toolRegistry, ObjectMapper om) {
        this.toolRegistry = toolRegistry;
        this.om = om;
    }

    /**
     * 导出 MCP tools/list 格式的工具描述。
     * 排除 local / subagent tag 的工具。
     */
    public List<Map<String, Object>> exportMcpTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition td : toolRegistry.all()) {
            if (td.tags().stream().anyMatch(EXCLUDED_TAGS::contains)) continue;
            tools.add(buildToolEntry(td));
        }
        return tools;
    }

    /**
     * 执行 MCP tools/call：委托给 ToolRegistry.invoke()。
     * 调用方需确保已在 McpSessionCtx.withContext() 内。
     */
    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        try {
            JsonNode argsNode = om.valueToTree(args != null ? args : Collections.emptyMap());
            Object result = toolRegistry.invoke(toolName, argsNode);
            return Map.of("content", List.of(Map.of("type", "text", "text", om.writeValueAsString(result))),
                    "isError", false);
        } catch (IllegalArgumentException e) {
            return errorResult("未知工具：" + toolName);
        } catch (Exception e) {
            return errorResult("工具调用失败：" + e.getMessage());
        }
    }

    /** 获取所有暴露的 MCP 工具名。 */
    public Set<String> exposedToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDefinition td : toolRegistry.all()) {
            if (td.tags().stream().noneMatch(EXCLUDED_TAGS::contains)) {
                names.add(td.name());
            }
        }
        return names;
    }

    /* ---- internal ---- */

    private Map<String, Object> buildToolEntry(ToolDefinition td) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", td.name());
        entry.put("description", td.description() + (td.requiresConfirm() ? "（需用户确认）" : ""));
        entry.put("inputSchema", buildInputSchema(td));
        return entry;
    }

    private Object buildInputSchema(ToolDefinition td) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolDefinition.ParamDef p : td.params()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", jsonTypeToMcp(p.javaType()));
            prop.put("description", p.desc());
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private String jsonTypeToMcp(Class<?> c) {
        if (c == String.class) return "string";
        if (c == Integer.class || c == int.class || c == Long.class || c == long.class) return "integer";
        if (c == Double.class || c == double.class || c == Float.class || c == float.class) return "number";
        if (c == Boolean.class || c == boolean.class) return "boolean";
        if (Collection.class.isAssignableFrom(c) || c.isArray()) return "array";
        return "object";
    }

    private Map<String, Object> errorResult(String msg) {
        return Map.of("content", List.of(Map.of("type", "text", "text", msg)),
                "isError", true);
    }
}
