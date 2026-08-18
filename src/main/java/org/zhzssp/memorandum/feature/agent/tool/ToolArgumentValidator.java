package org.zhzssp.memorandum.feature.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数<strong>前置</strong>校验（方案 E）。
 *
 * <p><strong>动机</strong>：在此之前，参数错误要等到反射调用时 Jackson {@code treeToValue}
 * 抛异常才暴露，回灌给 LLM 的是 {@code {"error":"IllegalArgumentException"}} 这类
 * 笼统信息——<strong>信息量太低，模型很难据此自修复</strong>，往往原样重试或换个工具乱试，
 * 白白烧掉 ReAct 步数。
 *
 * <p>本类在调用前按 {@code @ToolParam} 声明做校验，失败时返回一份
 * <strong>面向模型可操作</strong>的错误：逐参数指出问题、给出期望类型、
 * 并把该工具的完整参数表一并回灌（系统提示里的 schema 距离当前上下文很远，
 * 就近给一份能显著提高一次修复成功率）。
 *
 * <h3>核心设计约束：宁松勿严</h3>
 * <p>校验器的严格程度<strong>必须 ≤ Jackson 的实际容忍度</strong>。
 * 若校验拒掉了一个 Jackson 本来能成功反序列化的调用（假阳性），
 * 就等于凭空制造了一次失败——那比完全不校验更糟。
 * 因此下面的类型判定刻意接受了 Jackson 默认开启的标量强转
 * （如字符串 {@code "5"} → int、数字 {@code 5} → String），
 * 只拦截<strong>必然失败</strong>的形状错误（如对象/数组喂给标量参数）。
 *
 * <h3>未知参数为何不判失败</h3>
 * <p>LLM 偶尔会多传一个无害字段（如 {@code reason}）。历史行为是静默忽略，
 * 调用照样成功。若因此判失败会破坏大量本可工作的调用，所以未知参数
 * <strong>不单独构成失败</strong>，仅在已有其它 issue 时作为诊断线索附上
 * ——这恰好覆盖了最常见的真实故障："参数名写错"（如把 {@code title} 写成
 * {@code name}），此时必填缺失已经触发失败，附带的 {@code unknownParams}
 * 能让模型立刻明白是名字错了而不是漏传了。
 *
 * <p>开关 {@code agent.tool.validate-args=false} 时完全旁路，行为与改造前一致。
 */
@Component
public class ToolArgumentValidator {

    /** 校验失败时统一的 error code，供 LLM 与指标识别。 */
    public static final String ERROR_CODE = "INVALID_ARGUMENTS";

    @Value("${agent.tool.validate-args:true}")
    private boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    /** 单条参数问题。字段全部对模型可读，不含 Java 术语。 */
    public record Issue(String param, String problem, String expected, String got) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("param", param);
            m.put("problem", problem);
            if (expected != null) m.put("expected", expected);
            if (got != null) m.put("got", got);
            return m;
        }
    }

    /**
     * 校验本地工具参数。
     *
     * @return {@code null} 表示通过；否则返回可直接作为工具结果回灌 LLM 的错误 Map
     */
    public Map<String, Object> validateLocal(ToolDefinition def, JsonNode args) {
        if (!enabled || def == null) return null;

        List<Issue> issues = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();

        for (ToolDefinition.ParamDef p : def.params()) {
            declared.add(p.name());
            JsonNode v = (args == null) ? null : args.get(p.name());
            String expected = jsonTypeOf(p.javaType());

            if (v == null || v.isNull()) {
                if (p.required()) {
                    issues.add(new Issue(p.name(), "缺少必填参数", expected, null));
                }
                continue;
            }
            if (!acceptable(expected, v)) {
                issues.add(new Issue(p.name(), "类型错误", expected, describe(v)));
            }
        }

        if (issues.isEmpty()) return null;
        return buildError(def.name(), issues, unknownParams(args, declared), expectedParams(def));
    }

    /**
     * 校验 MCP 远程工具参数（依据 JSON Schema 的 {@code required} 与 {@code properties.*.type}）。
     *
     * <p>MCP 工具没有 {@code @ToolParam} 元信息，但 {@code inputSchema} 提供了等价约束。
     * 之所以值得校验：本地文档读取这条高频链路（{@code mcp.loopback.local.read_document}）
     * 走的就是 MCP，缺 {@code path} 参数时远端只会返回一个笼统错误。
     *
     * <p>比本地校验更宽松——schema 由远端提供，不完全可控，仅拦截
     * {@code required} 缺失与明确的类型冲突。
     */
    public Map<String, Object> validateMcp(String toolName, JsonNode inputSchema, JsonNode args) {
        if (!enabled || inputSchema == null || !inputSchema.isObject()) return null;

        JsonNode props = inputSchema.path("properties");
        JsonNode required = inputSchema.path("required");
        if (!props.isObject() && !required.isArray()) return null;

        List<Issue> issues = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        if (props.isObject()) props.fieldNames().forEachRemaining(declared::add);

        if (required.isArray()) {
            for (JsonNode r : required) {
                String name = r.asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode v = (args == null) ? null : args.get(name);
                if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                    String expected = props.path(name).path("type").asText("string");
                    issues.add(new Issue(name, "缺少必填参数", expected, null));
                }
            }
        }
        if (props.isObject() && args != null) {
            Iterator<String> it = props.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                JsonNode v = args.get(name);
                if (v == null || v.isNull()) continue;
                String expected = props.path(name).path("type").asText(null);
                if (expected == null || expected.isBlank()) continue;
                if (!acceptable(expected, v)) {
                    issues.add(new Issue(name, "类型错误", expected, describe(v)));
                }
            }
        }

        if (issues.isEmpty()) return null;

        Map<String, String> expectedParams = new LinkedHashMap<>();
        if (props.isObject()) {
            Iterator<String> it = props.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                JsonNode p = props.path(name);
                boolean req = containsText(required, name);
                expectedParams.put(name, p.path("type").asText("string")
                        + (req ? "(必填)" : "(可选)")
                        + descSuffix(p.path("description").asText("")));
            }
        }
        return buildError(toolName, issues, unknownParams(args, declared), expectedParams);
    }

    /* ------------------------------------------------------------------ */

    private Map<String, Object> buildError(String tool, List<Issue> issues,
                                           List<String> unknown, Map<String, String> expectedParams) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", ERROR_CODE);
        err.put("tool", tool);
        err.put("issues", issues.stream().map(Issue::toMap).toList());
        if (!unknown.isEmpty()) {
            err.put("unknownParams", unknown);
        }
        if (!expectedParams.isEmpty()) {
            err.put("expectedParams", expectedParams);
        }
        err.put("hint", buildHint(issues, unknown));
        return err;
    }

    /** 提示语必须是<strong>可执行动作</strong>，而不是复述错误。 */
    private String buildHint(List<Issue> issues, List<String> unknown) {
        StringBuilder sb = new StringBuilder();
        if (!unknown.isEmpty()) {
            sb.append("你传入了未声明的参数 ").append(unknown)
                    .append("，很可能是参数名写错了（请对照 expectedParams 的键名）。");
        }
        boolean missing = issues.stream().anyMatch(i -> "缺少必填参数".equals(i.problem()));
        boolean typeErr = issues.stream().anyMatch(i -> "类型错误".equals(i.problem()));
        if (missing) {
            sb.append("请补齐 issues 中标为\"缺少必填参数\"的字段；");
            sb.append("若该值需要从用户输入之外获取，请先调用相应的查询工具拿到真实值，不要编造。");
        }
        if (typeErr) {
            sb.append("请按 expected 指示修正类型后重试。");
        }
        sb.append("本次调用未执行，不会产生任何副作用。");
        return sb.toString();
    }

    private Map<String, String> expectedParams(ToolDefinition def) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ToolDefinition.ParamDef p : def.params()) {
            m.put(p.name(), jsonTypeOf(p.javaType())
                    + (p.required() ? "(必填)" : "(可选)")
                    + descSuffix(p.desc()));
        }
        return m;
    }

    private String descSuffix(String desc) {
        return (desc == null || desc.isBlank()) ? "" : " - " + desc;
    }

    private List<String> unknownParams(JsonNode args, Set<String> declared) {
        List<String> unknown = new ArrayList<>();
        if (args == null || !args.isObject()) return unknown;
        args.fieldNames().forEachRemaining(f -> {
            if (!declared.contains(f)) unknown.add(f);
        });
        return unknown;
    }

    private boolean containsText(JsonNode arr, String value) {
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode n : arr) {
            if (value.equals(n.asText(null))) return true;
        }
        return false;
    }

    /**
     * 判断 JsonNode 是否可被安全反序列化为期望的 JSON 类型。
     *
     * <p>见类注释「宁松勿严」：这里接受 Jackson 默认允许的标量强转，
     * 只拦截形状层面必然失败的组合。
     */
    private boolean acceptable(String expected, JsonNode v) {
        return switch (expected) {
            // 对象/数组喂给字符串参数必然失败；标量（含数字、布尔）Jackson 会强转为文本
            case "string" -> !v.isObject() && !v.isArray();
            case "integer", "number" -> v.isNumber() || isNumericText(v);
            case "boolean" -> v.isBoolean() || isBooleanText(v) || v.isNumber();
            case "array" -> v.isArray();
            case "object" -> v.isObject();
            // 未知/未声明类型：不做判断，交给 Jackson
            default -> true;
        };
    }

    private boolean isNumericText(JsonNode v) {
        if (!v.isTextual()) return false;
        String s = v.asText().trim();
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isBooleanText(JsonNode v) {
        if (!v.isTextual()) return false;
        String s = v.asText().trim();
        return "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
    }

    /** 给模型看的实际类型描述（不用 Jackson 的 NodeType 枚举名，那对模型不友好）。 */
    private String describe(JsonNode v) {
        if (v.isObject()) return "object";
        if (v.isArray()) return "array";
        if (v.isTextual()) return "string";
        if (v.isIntegralNumber()) return "integer";
        if (v.isNumber()) return "number";
        if (v.isBoolean()) return "boolean";
        return "null";
    }

    /**
     * Java 类型 → JSON Schema 类型名。
     *
     * <p>{@code ToolRegistry.exportSchemas} 也用这套映射，抽到这里保证
     * 「给 LLM 看的 schema」与「校验时的期望类型」<strong>永远一致</strong>
     * ——两处各写一份是典型的后期不一致来源。
     */
    public static String jsonTypeOf(Class<?> c) {
        if (c == null) return "object";
        if (c == String.class || c.isEnum() || c == Character.class || c == char.class) return "string";
        if (c == Integer.class || c == int.class || c == Long.class || c == long.class
                || c == Short.class || c == short.class || c == Byte.class || c == byte.class) return "integer";
        if (c == Double.class || c == double.class || c == Float.class || c == float.class) return "number";
        if (c == Boolean.class || c == boolean.class) return "boolean";
        if (Collection.class.isAssignableFrom(c) || c.isArray()) return "array";
        return "object";
    }
}
