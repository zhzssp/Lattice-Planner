package org.zhzssp.memorandum.feature.agent.tool;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 单个工具的运行时元信息。由 ToolRegistry 在 @PostConstruct 阶段构建。
 */
public record ToolDefinition(
        String name,
        String description,
        boolean requiresConfirm,
        List<String> tags,
        Object bean,
        Method method,
        List<ParamDef> params
) {
    public record ParamDef(String name, String desc, boolean required, Class<?> javaType) {
    }
}
