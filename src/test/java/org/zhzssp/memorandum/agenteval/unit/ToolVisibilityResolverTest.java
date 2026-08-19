package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolView;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolVisibilityResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 单元测试：{@link ToolVisibilityResolver}（方案 K，K1 阶段）。
 *
 * <p>覆盖核心遮蔽算法：GLOBAL 打底 → allow 收窄 → deny 剔除。
 * 重点回归「learn/reflect 模式因 tag OR 语义仍能看到写工具」的 bug。</p>
 */
class ToolVisibilityResolverTest {

    private ToolRegistry registry;
    private ToolVisibilityResolver resolver;

    /** 造工具定义的便捷方法（bean/method/params 无需，解析器只读 name/tags）。 */
    private static ToolDefinition def(String name, String... tags) {
        return new ToolDefinition(name, name + " 描述", false, List.of(tags), null, null, List.of());
    }

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);

        // 构造与真实实现一致的 tag 分布
        var tools = List.of(
                def("task.create", "task", "write"),
                def("task.search", "task", "read"),
                def("goal.create", "goal", "write"),
                def("goal.list", "goal", "read"),
                def("note.create", "note", "write"),
                def("note.list", "note", "read"),
                def("kb.semantic_search", "kb", "read"),
                def("kb.ingest_local_doc", "kb", "write", "local"),
                def("insight.daily_scores", "insight", "read"),
                def("planner.draft_goal_plan", "planner", "read"),
                def("planner.apply_goal_plan", "planner", "write"),
                def("subagent.plan", "subagent")
        );
        when(registry.all()).thenReturn(tools);
        when(registry.mcpToolsAll()).thenReturn(java.util.List.of());
        for (ToolDefinition t : tools) {
            when(registry.get(t.name())).thenReturn(t);
        }

        resolver = new ToolVisibilityResolver(registry);
        ReflectionTestUtils.setField(resolver, "enabled", true);
    }

    /* ================= 核心 bug 回归：deny 语义 ================= */

    @Nested
    @DisplayName("learn 模式（纯检索，禁写）")
    class LearnMode {

        @Test
        @DisplayName("note.create 因 write tag 被 deny（回归：learn 模式能写笔记的 bug）")
        void noteCreateDenied() {
            ToolView view = resolver.resolveMode("learn");
            assertFalse(view.contains("note.create"), "learn 模式不应看到写笔记工具");
            assertTrue(view.reasonOf("note.create").contains("deny"),
                    "原因应为 deny，实际：" + view.reasonOf("note.create"));
        }

        @Test
        @DisplayName("task.create / goal.create 被 deny 或不在 allow")
        void writeToolsHidden() {
            ToolView view = resolver.resolveMode("learn");
            assertFalse(view.contains("task.create"));
            assertFalse(view.contains("goal.create"));
            assertFalse(view.contains("kb.ingest_local_doc"));
        }

        @Test
        @DisplayName("读工具可见")
        void readToolsVisible() {
            ToolView view = resolver.resolveMode("learn");
            assertTrue(view.contains("kb.semantic_search"));
            assertTrue(view.contains("note.list"));
            assertTrue(view.contains("subagent.plan"));
        }
    }

    @Nested
    @DisplayName("reflect 模式（复盘，只读）")
    class ReflectMode {

        @Test
        @DisplayName("写工具被 deny")
        void writeToolsDenied() {
            ToolView view = resolver.resolveMode("reflect");
            assertFalse(view.contains("task.create"), "reflect 模式不应看到写任务工具");
            assertFalse(view.contains("goal.create"));
            assertFalse(view.contains("note.create"));
        }

        @Test
        @DisplayName("只读复盘工具可见")
        void readToolsVisible() {
            ToolView view = resolver.resolveMode("reflect");
            assertTrue(view.contains("insight.daily_scores"));
            assertTrue(view.contains("task.search"));
            assertTrue(view.contains("goal.list"));
        }
    }

    @Nested
    @DisplayName("plan 模式（规划，读写均可）")
    class PlanMode {

        @Test
        @DisplayName("写工具可见（不能过度收窄）")
        void writeToolsVisible() {
            ToolView view = resolver.resolveMode("plan");
            assertTrue(view.contains("task.create"));
            assertTrue(view.contains("note.create"));
            assertTrue(view.contains("planner.apply_goal_plan"));
        }
    }

    @Nested
    @DisplayName("chat 模式（通用，全部可见）")
    class ChatMode {

        @Test
        @DisplayName("全部工具可见，无 deny")
        void allVisible() {
            ToolView view = resolver.resolveMode("chat");
            assertTrue(view.contains("task.create"));
            assertTrue(view.contains("note.create"));
            assertTrue(view.contains("subagent.plan"));
        }

        @Test
        @DisplayName("null mode 回退 chat")
        void nullModeFallsBackToChat() {
            ToolView view = resolver.resolveMode(null);
            assertTrue(view.contains("task.create"));
        }
    }

    /* ================= 决策链可解释性 ================= */

    @Nested
    @DisplayName("决策链")
    class DecisionChain {

        @Test
        @DisplayName("deny 工具带原因；可见工具无原因")
        void denyHasReason() {
            ToolView view = resolver.resolveMode("learn");
            assertNotNull(view.reasonOf("note.create"));
            assertTrue(view.reasonOf("note.create").contains("MODE(learn)"));
            assertTrue(view.reasonOf("note.create").contains("tag=write"),
                    "应定位到具体 deny tag，实际：" + view.reasonOf("note.create"));
        }
    }
}
