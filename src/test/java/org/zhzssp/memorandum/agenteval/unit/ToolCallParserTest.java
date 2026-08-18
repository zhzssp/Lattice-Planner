package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.runtime.ToolCallParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：{@link ToolCallParser} 的解析鲁棒性。
 *
 * <p>这是 Agent 链路上<b>最脆弱也最关键</b>的一环——LLM 输出格式不受控，
 * 解析失败会直接导致工具调用丢失或把 JSON 泄漏给用户。
 * 不依赖 Spring 上下文，毫秒级运行。
 */
class ToolCallParserTest {

    private final ToolCallParser parser = new ToolCallParser(new ObjectMapper());

    @Test
    @DisplayName("裸 JSON 工具调用可解析")
    void parsesPlainJson() {
        var call = parser.parse("{\"tool\":\"task.create\",\"arguments\":{\"title\":\"写周报\"}}");
        assertNotNull(call);
        assertEquals("task.create", call.name());
        assertEquals("写周报", call.arguments().path("title").asText());
    }

    @Test
    @DisplayName("Markdown 围栏包裹的工具调用可解析")
    void parsesFencedJson() {
        String raw = "```json\n{\"tool\":\"goal.create\",\"arguments\":{\"title\":\"季度目标\"}}\n```";
        var call = parser.parse(raw);
        assertNotNull(call);
        assertEquals("goal.create", call.name());
    }

    @Test
    @DisplayName("reasoner 的 think 段被剥离后仍能解析工具调用")
    void parsesAfterThinkBlock() {
        String raw = "<think>用户想建任务，我应该调用 task.create</think>\n"
                + "{\"tool\":\"task.create\",\"arguments\":{\"title\":\"复盘\"}}";
        var call = parser.parse(raw);
        assertNotNull(call, "think 段应被剥离且不影响 JSON 解析");
        assertEquals("task.create", call.name());
    }

    @Test
    @DisplayName("纯自然语言判定为终态答复而非工具调用")
    void plainTextIsFinalAnswer() {
        assertNull(parser.parse("已经为你创建了任务，记得周五前完成。"));
    }

    @Test
    @DisplayName("缺少 tool 字段的 JSON 不误判为工具调用")
    void jsonWithoutToolFieldIsNotToolCall() {
        assertNull(parser.parse("{\"result\":\"ok\",\"count\":3}"));
    }

    @Test
    @DisplayName("空输入与 null 安全处理")
    void handlesEmptyInput() {
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("   "));
        assertNull(parser.parse("<think>只有思考没有结论</think>"));
    }

    @Test
    @DisplayName("终态答复清洗：剥离 think 段")
    void cleanForDisplayStripsThinking() {
        String out = parser.cleanForDisplay("<think>内部推理不该泄漏</think>这是给用户的答复");
        assertFalse(out.contains("内部推理"), "think 段必须被剥离，否则泄漏推理过程");
        assertTrue(out.contains("这是给用户的答复"));
    }

    @Test
    @DisplayName("终态答复清洗：移除残留的 tool-call JSON，但保留普通代码块")
    void cleanForDisplayRemovesToolJsonButKeepsCodeBlocks() {
        String withToolJson = "好的。\n```json\n{\"tool\":\"task.create\",\"arguments\":{}}\n```\n已完成。";
        String cleaned = parser.cleanForDisplay(withToolJson);
        assertFalse(cleaned.contains("\"tool\""), "tool-call JSON 必须被清除，否则泄漏给用户");

        String withNormalCode = "参考这段代码：\n```java\nint a = 1;\n```\n以上。";
        String keep = parser.cleanForDisplay(withNormalCode);
        assertTrue(keep.contains("int a = 1;"), "普通代码块应保留");
    }

    @Test
    @DisplayName("工具名为空白时不视为有效调用")
    void blankToolNameRejected() {
        assertNull(parser.parse("{\"tool\":\"   \",\"arguments\":{}}"));
    }
}
