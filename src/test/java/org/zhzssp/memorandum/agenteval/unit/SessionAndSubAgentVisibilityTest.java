package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.subagent.SubAgentRole;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;
import org.zhzssp.memorandum.feature.agent.tool.visibility.SessionToolMask;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolView;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolVisibilityResolver;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K4+K5 单元测试：会话层屏蔽（{@link SessionToolMask}）与子代理继承 mode
 * （{@link ToolVisibilityResolver#resolveSubAgent}）。
 */
class SessionAndSubAgentVisibilityTest {

    private ToolRegistry registry;
    private ToolVisibilityResolver resolver;

    private static ToolDefinition def(String name, String... tags) {
        return new ToolDefinition(name, name + " 描述", false, List.of(tags), null, null, List.of());
    }

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        var tools = List.of(
                def("task.create", "task", "write"),
                def("task.search", "task", "read"),
                def("note.create", "note", "write"),
                def("kb.semantic_search", "kb", "read"),
                def("goal.create", "goal", "write"),
                def("subagent.plan", "subagent"),
                def("planner.draft_goal_plan", "planner", "read")
        );
        when(registry.all()).thenReturn(tools);
        when(registry.mcpToolsAll()).thenReturn(java.util.List.of());
        for (ToolDefinition t : tools) {
            when(registry.get(t.name())).thenReturn(t);
        }
        resolver = new ToolVisibilityResolver(registry);
        ReflectionTestUtils.setField(resolver, "enabled", true);
        ReflectionTestUtils.setField(resolver, "enforce", true);
    }

    @Nested
    @DisplayName("K4：会话层屏蔽")
    class SessionMask {

        @Test
        @DisplayName("会话层 deny 特定工具")
        void sessionDenyTool() {
            SessionToolMask mask = new SessionToolMask(30);
            mask.deny("sid-1", Set.of("task.create"), Set.of());

            ToolView view = resolver.resolveModeWithSession("plan",
                    mask.of("sid-1"));
            assertFalse(view.contains("task.create"), "会话 deny 后 task.create 不可见");
            assertTrue(view.contains("task.search"), "同域读工具仍可见");
        }

        @Test
        @DisplayName("会话层 pin 覆盖 mode deny")
        void sessionPinOverridesDeny() {
            SessionToolMask mask = new SessionToolMask(30);
            // learn 模式默认 deny write，但 pin 破例放行 note.create
            mask.pin("sid-1", Set.of("note.create"));

            ToolView view = resolver.resolveModeWithSession("learn",
                    mask.of("sid-1"));
            assertTrue(view.contains("note.create"), "pin 应覆盖 learn 的 deny(write)");
            assertFalse(view.contains("task.create"), "未被 pin 的写工具仍被 deny");
        }

        @Test
        @DisplayName("无会话规则时等价于纯 mode 层")
        void noSessionRule() {
            SessionToolMask mask = new SessionToolMask(30);
            ToolView view = resolver.resolveModeWithSession("learn", mask.of("sid-1"));
            assertFalse(view.contains("note.create"), "无会话规则时 learn 仍禁写");
            assertTrue(view.contains("kb.semantic_search"));
        }
    }

    @Nested
    @DisplayName("K5：子代理继承 mode")
    class SubAgentInheritsMode {

        /**
         * <p>这条用例区分了 mode deny 里混着的两类语义，见
         * {@code AgentMode.inheritableDenyTags()}：
         * {@code write} 是安全边界必须继承，{@code planner} 是范围边界不该继承。
         * 补 LEARN 越界时给它加了域 deny，本用例立刻变红——
         * 是它逼出了这个区分。</p>
         */
        @Test
        @DisplayName("learn 模式委派 PLANNER，写工具仍不可见")
        void plannerInheritsLearnDeny() {
            ToolView view = resolver.resolveSubAgent(SubAgentRole.PLANNER, "learn");
            assertFalse(view.contains("task.create"), "learn 下 PLANNER 不应看到写任务");
            assertFalse(view.contains("goal.create"));
            assertTrue(view.contains("planner.draft_goal_plan"), "PLANNER 的只读规划工具可见");
        }

        /**
         * ★安全边界那一半：委派<b>不得</b>成为提权路径。
         *
         * <p>与上一条正好互补。上一条保证「别管太宽」，这一条保证「别放太松」——
         * 只有两条同时在，{@code inheritableDenyTags} 的取舍才被真正钉住。
         * 只留其一的话，把它实现成「全不继承」或「全继承」都能过测。</p>
         */
        @Test
        @DisplayName("★委派不得绕过父模式的写禁令（安全边界必须继承）")
        void delegationIsNotPrivilegeEscalation() {
            for (String readOnlyMode : List.of("learn", "reflect", "study")) {
                ToolView view = resolver.resolveSubAgent(SubAgentRole.PLANNER, readOnlyMode);
                assertFalse(view.contains("task.create"),
                        readOnlyMode + " 禁写，委派出去的子代理也不得写任务——"
                                + "否则「委派一个子代理」就是一条提权路径");
                assertFalse(view.contains("goal.create"), readOnlyMode + " 下不得写目标");
                assertFalse(view.contains("note.create"), readOnlyMode + " 下不得写笔记");
            }
        }

        /** 范围边界确实没被继承下去（对照组，防止实现退化成「全继承」）。 */
        @Test
        @DisplayName("范围边界不继承：learn 的域 deny 不该让 PLANNER 变成空壳")
        void scopeDenyIsNotInherited() {
            assertFalse(org.zhzssp.memorandum.feature.agent.runtime.AgentMode.LEARN
                            .inheritableDenyTags().contains("planner"),
                    "planner 是范围边界，不该出现在可继承集合里");
            assertTrue(org.zhzssp.memorandum.feature.agent.runtime.AgentMode.LEARN
                            .inheritableDenyTags().contains("write"),
                    "write 是安全边界，必须在可继承集合里");
        }

        @Test
        @DisplayName("plan 模式委派 PLANNER，写工具可见")
        void plannerInPlanMode() {
            ToolView view = resolver.resolveSubAgent(SubAgentRole.PLANNER, "plan");
            assertTrue(view.contains("task.create"), "plan 下 PLANNER 应能写任务");
            assertTrue(view.contains("goal.create"));
        }

        @Test
        @DisplayName("结构性保留：子代理永远看不到 subagent.*")
        void subagentStructurallyReserved() {
            // 即使用一个 allow 含 subagent 的角色（构造一个含 subagent tag 的场景）
            // 直接用 PLANNER 验证：其 toolTags 不含 subagent，本就不该有
            ToolView view = resolver.resolveSubAgent(SubAgentRole.PLANNER, "plan");
            assertFalse(view.contains("subagent.plan"), "子代理不可见 subagent.*");
        }
    }
}
