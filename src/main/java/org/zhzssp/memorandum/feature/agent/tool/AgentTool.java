package org.zhzssp.memorandum.feature.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个方法为 Agent 可调用的工具。
 * 类必须是 Spring 容器内的 @Component / @Service。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    /** 工具名（LLM 输出 JSON 中的 tool 字段），形如 "task.create" */
    String name();

    /** 工具描述，会拼到系统 Prompt 中给 LLM 看 */
    String description();

    /** 是否需要弹窗用户确认（写库 / 读本地文件等高危工具置 true） */
    boolean requiresConfirm() default false;

    /** 标签，用于按模式过滤工具集合（plan / reflect 等） */
    String[] tags() default {};
}
