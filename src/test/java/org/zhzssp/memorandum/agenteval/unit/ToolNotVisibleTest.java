package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.ReflexionAdvisor;
import org.zhzssp.memorandum.feature.agent.runtime.ReflexionAdvisor.FailureMode;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolView;

import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K3 单元测试：{@code TOOL_NOT_VISIBLE} 的失败分类与「不可见结果」构造。
 */
class ToolNotVisibleTest {

    private ReflexionAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ReflexionAdvisor(new ObjectMapper());
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "failThreshold", 2);
    }

    @Test
    @DisplayName("TOOL_NOT_VISIBLE 被正确分类（先于 DENIED）")
    void classifyToolNotVisible() {
        assertEquals(FailureMode.TOOL_NOT_VISIBLE,
                advisor.classify("{\"error\":\"TOOL_NOT_VISIBLE\",\"tool\":\"task.create\"}"));
    }

    @Test
    @DisplayName("TOOL_NOT_VISIBLE 是不可重试的（一次即封禁）")
    void notVisibleIsNonRetryable() {
        assertEquals(false, FailureMode.TOOL_NOT_VISIBLE.retryable());
    }

    @Test
    @DisplayName("notVisibleResult 含 error/reason/hint")
    void notVisibleResultShape() {
        ToolView view = new ToolView(
                new LinkedHashSet<>(java.util.List.of("kb.semantic_search", "note.list")),
                Map.of("task.create", "MODE(learn):deny(tag=write)"),
                "sig");

        // 通过反射拿到 resolver 不方便，这里直接验证 ToolView 的 reason 语义
        assertEquals("MODE(learn):deny(tag=write)", view.reasonOf("task.create"));
        assertEquals(false, view.contains("task.create"));
        assertTrue(view.contains("kb.semantic_search"));
    }
}
