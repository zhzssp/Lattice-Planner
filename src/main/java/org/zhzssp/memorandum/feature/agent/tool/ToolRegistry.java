package org.zhzssp.memorandum.feature.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.zhzssp.memorandum.feature.agent.mcp.client.McpRemoteTool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动时扫描所有 @AgentTool 方法，构建工具注册表。
 *
 * - exportSchemas：把工具描述导出为给 LLM 读的 JSON Schema 列表
 * - invoke：根据 LLM 给出的 tool name + arguments(JsonNode) 反射调用
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext ctx;
    private final ObjectMapper om;
    private final ToolArgumentValidator validator;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    // MCP 远程工具注册表（fullName → McpRemoteTool）
    private final Map<String, McpRemoteTool> mcpTools = new ConcurrentHashMap<>();

    // MCP 工具代理（延迟注入，避免循环依赖）
    private volatile Object mcpProxy;

    public ToolRegistry(ApplicationContext ctx, ObjectMapper om, ToolArgumentValidator validator) {
        this.ctx = ctx;
        this.om = om;
        this.validator = validator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        Map<String, Object> beans = new HashMap<>();
        beans.putAll(ctx.getBeansWithAnnotation(org.springframework.stereotype.Component.class));
        beans.putAll(ctx.getBeansWithAnnotation(org.springframework.stereotype.Service.class));
        for (Object bean : beans.values()) {
            Class<?> userClass = AopUtils.getTargetClass(bean);
            for (Method m : userClass.getDeclaredMethods()) {
                AgentTool ann = m.getAnnotation(AgentTool.class);
                if (ann == null) continue;
                m.setAccessible(true);
                List<ToolDefinition.ParamDef> params = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    ToolParam pa = p.getAnnotation(ToolParam.class);
                    if (pa == null) {
                        throw new IllegalStateException(
                                "@AgentTool 方法 " + userClass.getSimpleName() + "#" + m.getName()
                                        + " 的参数 " + p.getName() + " 缺少 @ToolParam");
                    }
                    params.add(new ToolDefinition.ParamDef(pa.value(), pa.desc(), pa.required(), p.getType()));
                }
                tools.put(ann.name(), new ToolDefinition(
                        ann.name(), ann.description(), ann.requiresConfirm(),
                        List.of(ann.tags()), bean, m, params));
            }
        }
        log.info("[Agent] Registered {} local tools: {}", tools.size(), tools.keySet());
    }

    public Collection<ToolDefinition> all() {
        return tools.values();
    }

    /** 获取全部工具定义（含 MCP 远程工具的 ToolDefinition 视图）。 */
    public Collection<ToolDefinition> allWithMcp() {
        Collection<ToolDefinition> result = new ArrayList<>(tools.values());
        for (McpRemoteTool rt : mcpTools.values()) {
            result.add(rtToToolDef(rt));
        }
        return result;
    }

    public ToolDefinition get(String name) {
        ToolDefinition local = tools.get(name);
        if (local != null) return local;
        McpRemoteTool mcp = mcpTools.get(name);
        return mcp != null ? rtToToolDef(mcp) : null;
    }

    /** 注册 MCP 远程工具。 */
    public void registerMcpTool(McpRemoteTool rt) {
        mcpTools.put(rt.fullName(), rt);
        log.info("[Agent] 注册 MCP 远程工具：{} (from {})", rt.fullName(), rt.serverName());
    }

    /** 移除指定 Server 的所有 MCP 远程工具。 */
    public void unregisterMcpTools(String serverName) {
        mcpTools.entrySet().removeIf(e -> {
            if (e.getValue().serverName().equals(serverName)) {
                log.info("[Agent] 移除 MCP 远程工具：{} (from {})", e.getKey(), serverName);
                return true;
            }
            return false;
        });
    }

    /** 设置 MCP 工具代理（由 McpToolProxy 调用，避免循环依赖）。 */
    public void setMcpProxy(Object proxy) {
        this.mcpProxy = proxy;
    }

    /** 获取 MCP 工具名列表。 */
    public Set<String> mcpToolNames() {
        return mcpTools.keySet();
    }

    /** 获取已注册的 MCP 远程工具。 */
    public Collection<McpRemoteTool> mcpToolsAll() {
        return mcpTools.values();
    }

    /**
     * 导出 OpenAI function-calling 风格的 schema 列表，给 LLM 看。
     * tagFilter == null 或空 -> 全部工具；否则保留至少一个 tag 命中的工具。
     */
    public List<Map<String, Object>> exportSchemas(Set<String> tagFilter) {
        List<Map<String, Object>> list = new ArrayList<>();
        // 按工具名排序（ConcurrentHashMap 迭代序不保证，排序确保前缀字节稳定）
        List<ToolDefinition> sorted = new ArrayList<>(tools.values());
        sorted.sort(java.util.Comparator.comparing(ToolDefinition::name));
        for (ToolDefinition t : sorted) {
            if (tagFilter != null && !tagFilter.isEmpty()
                    && t.tags().stream().noneMatch(tagFilter::contains)) {
                continue;
            }
            ObjectNode props = om.createObjectNode();
            List<String> required = new ArrayList<>();
            for (ToolDefinition.ParamDef p : t.params()) {
                ObjectNode pn = om.createObjectNode();
                pn.put("type", jsonType(p.javaType()));
                pn.put("description", p.desc());
                props.set(p.name(), pn);
                if (p.required()) required.add(p.name());
            }
            ObjectNode parameters = om.createObjectNode();
            parameters.put("type", "object");
            parameters.set("properties", props);
            parameters.set("required", om.valueToTree(required));
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("description", t.description() + (t.requiresConfirm() ? "（需用户确认）" : ""));
            entry.put("parameters", parameters);
            list.add(entry);
        }
        // MCP 远程工具（按 fullName 排序）
        if (tagFilter == null || tagFilter.isEmpty() || tagFilter.contains("mcp")) {
            List<McpRemoteTool> sortedMcp = new ArrayList<>(mcpTools.values());
            sortedMcp.sort(java.util.Comparator.comparing(McpRemoteTool::fullName));
            for (McpRemoteTool rt : sortedMcp) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("name", rt.fullName());
                entry.put("description", "[MCP/" + rt.serverName() + "] " + rt.description());
                entry.put("parameters", rt.inputSchema() != null ? rt.inputSchema() : Map.of("type", "object", "properties", Map.of()));
                list.add(entry);
            }
        }
        return list;
    }

    /**
     * 根据 LLM 给出的 arguments(JsonNode) 反射调用工具方法。
     * 支持本地 @AgentTool 和 MCP 远程工具。
     *
     * <p>E：调用前先做参数校验。校验失败时<strong>返回</strong>错误 Map 而非抛异常，
     * 目的是让它走与工具业务错误一致的回灌通道（{@code {"error":...}}），
     * 从而能被 {@code ReflexionAdvisor} 分类并附加修复策略。
     * 抛异常则会被上层包装成笼统的 {@code IllegalArgumentException}，丢掉全部细节。</p>
     */
    public Object invoke(String name, JsonNode args) throws Exception {
        // 优先匹配本地工具
        ToolDefinition t = tools.get(name);
        if (t != null) {
            Map<String, Object> invalid = validator.validateLocal(t, args);
            if (invalid != null) {
                log.debug("[Agent] 参数校验拒绝 tool={} issues={}", name, invalid.get("issues"));
                return invalid;
            }
            return invokeLocal(t, args);
        }
        // MCP 远程工具
        McpRemoteTool mcp = mcpTools.get(name);
        if (mcp != null) {
            Map<String, Object> invalid = validateMcpArgs(mcp, args);
            if (invalid != null) {
                log.debug("[Agent] 参数校验拒绝(MCP) tool={} issues={}", name, invalid.get("issues"));
                return invalid;
            }
            return invokeMcp(name, args);
        }
        throw new IllegalArgumentException("未知工具：" + name);
    }

    /** MCP 工具的 inputSchema 是 Map，转成 JsonNode 后交给校验器。 */
    private Map<String, Object> validateMcpArgs(McpRemoteTool mcp, JsonNode args) {
        if (mcp.inputSchema() == null || mcp.inputSchema().isEmpty()) return null;
        try {
            return validator.validateMcp(mcp.fullName(), om.valueToTree(mcp.inputSchema()), args);
        } catch (Exception e) {
            // 远端 schema 形状不可控，校验器自身异常绝不能阻断真实调用
            log.debug("[Agent] MCP 参数校验跳过 tool={}：{}", mcp.fullName(), e.getMessage());
            return null;
        }
    }

    /** 本地工具反射调用。 */
    private Object invokeLocal(ToolDefinition t, JsonNode args) throws Exception {
        Object[] real = new Object[t.params().size()];
        for (int i = 0; i < t.params().size(); i++) {
            ToolDefinition.ParamDef p = t.params().get(i);
            JsonNode v = args == null ? null : args.get(p.name());
            if (v == null || v.isNull()) {
                if (p.required()) {
                    throw new IllegalArgumentException("缺少必填参数：" + p.name());
                }
                real[i] = defaultValueFor(p.javaType());
            } else {
                real[i] = om.treeToValue(v, p.javaType());
            }
        }
        try {
            return t.method().invoke(t.bean(), real);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getTargetException();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    /** MCP 远程工具代理调用。 */
    private Object invokeMcp(String name, JsonNode args) throws Exception {
        if (mcpProxy == null) {
            return Map.of("error", "MCP_PROXY_NOT_READY", "message", "MCP 工具代理尚未初始化");
        }
        // 反射调用 McpToolProxy.invoke(String, JsonNode)
        java.lang.reflect.Method invokeMethod = mcpProxy.getClass().getMethod("invoke", String.class, JsonNode.class);
        return invokeMethod.invoke(mcpProxy, name, args);
    }

    /** 将 McpRemoteTool 转换为 ToolDefinition 视图。 */
    private ToolDefinition rtToToolDef(McpRemoteTool rt) {
        return new ToolDefinition(
                rt.fullName(),
                "[MCP/" + rt.serverName() + "] " + rt.description(),
                false,  // MCP 工具不需要确认
                rt.tags(),
                null,   // 无 bean
                null,   // 无 method
                List.of()  // 无本地参数定义（使用 inputSchema）
        );
    }

    private Object defaultValueFor(Class<?> c) {
        if (c == int.class || c == long.class || c == short.class || c == byte.class) return 0;
        if (c == double.class || c == float.class) return 0.0;
        if (c == boolean.class) return false;
        if (c == char.class) return '\0';
        return null;
    }

    private String jsonType(Class<?> c) {
        // 委托给校验器的同一套映射：保证 LLM 看到的 schema 类型
        // 与校验时的期望类型永远一致（两处各写一份必然会漂移）
        return ToolArgumentValidator.jsonTypeOf(c);
    }
}
