package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.tool.ToolArgumentValidator;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：{@link ToolArgumentValidator}（方案 E）。
 *
 * <p>这套测试的重点<strong>不是「能不能拦住错误」，而是「会不会拦错」</strong>。
 * 校验器一旦产生假阳性（拒掉 Jackson 本来能成功反序列化的调用），
 * 就凭空制造了一次工具失败，比完全不校验更糟——所以「宽容度」用例
 * 比「拦截」用例更重要，放在 {@link Tolerance} 里单独成组。
 */
class ToolArgumentValidatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ToolArgumentValidator validator = enabled(new ToolArgumentValidator(), true);

    private ToolArgumentValidator enabled(ToolArgumentValidator v, boolean on) {
        ReflectionTestUtils.setField(v, "enabled", on);
        return v;
    }

    /** 构造一个仿 task.create 的工具定义：title 必填 string，priority 可选 int。 */
    private ToolDefinition taskCreate() {
        return new ToolDefinition("task.create", "创建任务", false, List.of("task"), null, null,
                List.of(
                        new ToolDefinition.ParamDef("title", "任务标题", true, String.class),
                        new ToolDefinition.ParamDef("priority", "优先级 1-5", false, Integer.class),
                        new ToolDefinition.ParamDef("tags", "标签数组", false, List.class)
                ));
    }

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /* ================= 拦截能力 ================= */

    @Test
    @DisplayName("缺少必填参数被拦截，并给出 expected 类型")
    void rejectsMissingRequired() {
        Map<String, Object> err = validator.validateLocal(taskCreate(), json("{\"priority\":3}"));
        assertNotNull(err, "缺 title 应被拦截");
        assertEquals(ToolArgumentValidator.ERROR_CODE, err.get("error"));
        assertEquals("task.create", err.get("tool"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) err.get("issues");
        assertEquals(1, issues.size());
        assertEquals("title", issues.get(0).get("param"));
        assertEquals("缺少必填参数", issues.get(0).get("problem"));
        assertEquals("string", issues.get(0).get("expected"));
    }

    @Test
    @DisplayName("错误信息自带完整参数表，让模型无需回看系统提示即可修复")
    void carriesExpectedParams() {
        Map<String, Object> err = validator.validateLocal(taskCreate(), json("{}"));
        assertNotNull(err);
        @SuppressWarnings("unchecked")
        Map<String, String> expected = (Map<String, String>) err.get("expectedParams");
        assertNotNull(expected, "必须回灌参数表——系统提示里的 schema 距离当前上下文很远");
        assertTrue(expected.get("title").contains("必填"));
        assertTrue(expected.get("priority").contains("可选"));
        assertTrue(expected.get("title").contains("任务标题"), "应带上参数描述");
        assertNotNull(err.get("hint"));
        assertTrue(((String) err.get("hint")).contains("未执行"),
                "必须告知模型本次无副作用，否则它可能担心重复创建而不敢重试");
    }

    @Test
    @DisplayName("对象喂给字符串参数被拦截（Jackson 必然失败的形状错误）")
    void rejectsObjectForStringParam() {
        Map<String, Object> err = validator.validateLocal(
                taskCreate(), json("{\"title\":{\"text\":\"写周报\"}}"));
        assertNotNull(err);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) err.get("issues");
        assertEquals("类型错误", issues.get(0).get("problem"));
        assertEquals("object", issues.get(0).get("got"));
    }

    @Test
    @DisplayName("非数字字符串喂给整数参数被拦截")
    void rejectsNonNumericTextForIntParam() {
        Map<String, Object> err = validator.validateLocal(
                taskCreate(), json("{\"title\":\"x\",\"priority\":\"很高\"}"));
        assertNotNull(err);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) err.get("issues");
        assertEquals("priority", issues.get(0).get("param"));
        assertEquals("integer", issues.get(0).get("expected"));
    }

    @Test
    @DisplayName("字符串喂给数组参数被拦截")
    void rejectsStringForArrayParam() {
        Map<String, Object> err = validator.validateLocal(
                taskCreate(), json("{\"title\":\"x\",\"tags\":\"a,b\"}"));
        assertNotNull(err);
    }

    /* ================= 宽容度（防假阳性，比拦截更重要） ================= */

    @Nested
    @DisplayName("宽容度：不得拒掉 Jackson 本来能成功的调用")
    class Tolerance {

        @Test
        @DisplayName("合法调用通过")
        void acceptsValid() {
            assertNull(validator.validateLocal(taskCreate(),
                    json("{\"title\":\"写周报\",\"priority\":3,\"tags\":[\"work\"]}")));
        }

        @Test
        @DisplayName("只给必填参数即可通过")
        void acceptsRequiredOnly() {
            assertNull(validator.validateLocal(taskCreate(), json("{\"title\":\"写周报\"}")));
        }

        @Test
        @DisplayName("可选参数显式为 null 视为未提供，不算类型错误")
        void acceptsExplicitNullOptional() {
            assertNull(validator.validateLocal(taskCreate(),
                    json("{\"title\":\"x\",\"priority\":null}")));
        }

        @Test
        @DisplayName("数字字符串喂给整数参数通过（Jackson 会强转）")
        void acceptsNumericTextForInt() {
            assertNull(validator.validateLocal(taskCreate(),
                    json("{\"title\":\"x\",\"priority\":\"3\"}")));
        }

        @Test
        @DisplayName("数字喂给字符串参数通过（Jackson 会转文本）")
        void acceptsNumberForString() {
            assertNull(validator.validateLocal(taskCreate(), json("{\"title\":2026}")));
        }

        @Test
        @DisplayName("多传一个未声明的无害参数不判失败")
        void acceptsHarmlessUnknownParam() {
            assertNull(validator.validateLocal(taskCreate(),
                            json("{\"title\":\"x\",\"reason\":\"用户要求\"}")),
                    "历史行为是静默忽略；若因此判失败会破坏大量本可工作的调用");
        }

        @Test
        @DisplayName("开关关闭时完全旁路")
        void bypassedWhenDisabled() {
            ToolArgumentValidator off = enabled(new ToolArgumentValidator(), false);
            assertNull(off.validateLocal(taskCreate(), json("{}")),
                    "关闭后行为必须与改造前一致（可降级原则）");
        }
    }

    /* ================= 参数名写错这一最常见故障 ================= */

    @Test
    @DisplayName("参数名写错时，unknownParams 指出真实原因")
    void diagnosesWrongParamName() {
        // 模型把 title 写成了 name：必填缺失触发失败，unknownParams 揭示根因
        Map<String, Object> err = validator.validateLocal(taskCreate(), json("{\"name\":\"写周报\"}"));
        assertNotNull(err);
        @SuppressWarnings("unchecked")
        List<String> unknown = (List<String>) err.get("unknownParams");
        assertNotNull(unknown, "应附上未知参数作为诊断线索");
        assertTrue(unknown.contains("name"));
        assertTrue(((String) err.get("hint")).contains("参数名"),
                "hint 应引导模型怀疑参数名而不是以为漏传");
    }

    /* ================= MCP 工具（inputSchema 驱动） ================= */

    @Test
    @DisplayName("MCP 工具缺 required 参数被拦截")
    void rejectsMissingMcpRequired() {
        JsonNode schema = json("""
                {"type":"object",
                 "properties":{"path":{"type":"string","description":"绝对路径"}},
                 "required":["path"]}""");
        Map<String, Object> err = validator.validateMcp(
                "mcp.loopback.local.read_document", schema, json("{}"));
        assertNotNull(err, "本地文档读取缺 path 是高频故障，必须拦住");
        @SuppressWarnings("unchecked")
        Map<String, String> expected = (Map<String, String>) err.get("expectedParams");
        assertTrue(expected.get("path").contains("必填"));
    }

    @Test
    @DisplayName("MCP required 参数给空串等同缺失")
    void rejectsBlankMcpRequired() {
        JsonNode schema = json("""
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""");
        assertNotNull(validator.validateMcp("mcp.x.read", schema, json("{\"path\":\"  \"}")));
    }

    @Test
    @DisplayName("MCP 合法调用通过；无 schema 时不做判断")
    void acceptsValidMcp() {
        JsonNode schema = json("""
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""");
        assertNull(validator.validateMcp("mcp.x.read", schema, json("{\"path\":\"C:/a.md\"}")));
        assertNull(validator.validateMcp("mcp.x.read", json("{}"), json("{}")),
                "远端 schema 缺失时不应自行发明约束");
    }

    /* ================= 与 schema 导出的一致性 ================= */

    @Test
    @DisplayName("类型映射覆盖常见 Java 类型（与 exportSchemas 共用同一套）")
    void jsonTypeMapping() {
        assertEquals("string", ToolArgumentValidator.jsonTypeOf(String.class));
        assertEquals("integer", ToolArgumentValidator.jsonTypeOf(Long.class));
        assertEquals("integer", ToolArgumentValidator.jsonTypeOf(int.class));
        assertEquals("number", ToolArgumentValidator.jsonTypeOf(Double.class));
        assertEquals("boolean", ToolArgumentValidator.jsonTypeOf(boolean.class));
        assertEquals("array", ToolArgumentValidator.jsonTypeOf(List.class));
        assertEquals("array", ToolArgumentValidator.jsonTypeOf(String[].class));
        assertEquals("object", ToolArgumentValidator.jsonTypeOf(Map.class));
        // 枚举按字符串导出，否则校验期望与 LLM 看到的 schema 会不一致
        assertEquals("string", ToolArgumentValidator.jsonTypeOf(java.time.DayOfWeek.class));
    }
}
