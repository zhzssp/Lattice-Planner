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
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public ToolRegistry(ApplicationContext ctx, ObjectMapper om) {
        this.ctx = ctx;
        this.om = om;
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
        log.info("[Agent] Registered {} tools: {}", tools.size(), tools.keySet());
    }

    public Collection<ToolDefinition> all() {
        return tools.values();
    }

    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /**
     * 导出 OpenAI function-calling 风格的 schema 列表，给 LLM 看。
     * tagFilter == null 或空 -> 全部工具；否则保留至少一个 tag 命中的工具。
     */
    public List<Map<String, Object>> exportSchemas(Set<String> tagFilter) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDefinition t : tools.values()) {
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
        return list;
    }

    /**
     * 根据 LLM 给出的 arguments(JsonNode) 反射调用工具方法。
     */
    public Object invoke(String name, JsonNode args) throws Exception {
        ToolDefinition t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("未知工具：" + name);
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

    private Object defaultValueFor(Class<?> c) {
        if (c == int.class || c == long.class || c == short.class || c == byte.class) return 0;
        if (c == double.class || c == float.class) return 0.0;
        if (c == boolean.class) return false;
        if (c == char.class) return '\0';
        return null;
    }

    private String jsonType(Class<?> c) {
        if (c == String.class) return "string";
        if (c == Integer.class || c == int.class || c == Long.class || c == long.class) return "integer";
        if (c == Double.class || c == double.class || c == Float.class || c == float.class) return "number";
        if (c == Boolean.class || c == boolean.class) return "boolean";
        if (Collection.class.isAssignableFrom(c) || c.isArray()) return "array";
        return "object";
    }
}
