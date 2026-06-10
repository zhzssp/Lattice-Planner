package org.zhzssp.memorandum.feature.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 @AgentTool 方法的参数。每个参数必须显式声明 name + desc。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    /** 参数 JSON 字段名（LLM 输出 JSON 的 arguments.<value>） */
    String value();

    /** 参数说明，进系统 Prompt */
    String desc();

    /** 是否必填，缺失时直接抛错给 LLM 看到，引导其重试 */
    boolean required() default false;
}
