package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.AgentMode;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolView;
import org.zhzssp.memorandum.feature.agent.tool.visibility.ToolVisibilityResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 单元测试：V4 新增的三个模式（study / curate / verify）与 CHAT 的 deny 收敛。
 *
 * <h3>最关键的一组断言在 {@link ChatByteStability}</h3>
 * <p>CHAT 的 {@code allowTags} 是空集（语义为「不收窄」），因此新增任何工具都会
 * 自动出现在它的工具列表里，进而改变 {@code exportSchemas} 的输出字节，
 * 让方案 A 的评测 cassette（按 messages_hash 命中）全部失效——
 * 那是本项目最有价值的工程资产。</p>
 *
 * <p>所以「CHAT 看不到 codex/exec/checkpoint 工具」不是产品偏好，而是<strong>技术约束</strong>，
 * 必须有测试守住。若将来有人给 CHAT 放开这些 tag，这组测试会先红。</p>
 */
class CodexModeVisibilityTest {

    private ToolRegistry registry;
    private ToolVisibilityResolver resolver;

    private static ToolDefinition def(String name, String... tags) {
        return new ToolDefinition(name, name + " 描述", false, List.of(tags), null, null, List.of());
    }

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);

        // 既有工具 + V4 新增 Codex 工具，tag 分布与真实实现一致
        var tools = List.of(
                def("task.create", "task", "write"),
                def("task.search", "task", "read"),
                def("goal.create", "goal", "write"),
                def("note.create", "note", "write"),
                def("kb.semantic_search", "kb", "read"),
                def("insight.daily_scores", "insight", "read"),
                def("subagent.plan", "subagent"),
                // ---- V4 Codex ----
                def("repo.list", "codex", "read"),
                def("repo.status", "codex", "read"),
                def("repo.sync", "codex", "write"),
                def("doc.search", "codex", "read"),
                def("doc.read", "codex", "read"),
                def("doc.outline", "codex", "read"),
                def("doc.backlinks", "codex", "read"),
                // ---- 受限执行（P1 预留 tag，用于验证隔离已生效）----
                def("checkpoint.list", "checkpoint", "read"),
                def("checkpoint.run", "checkpoint", "exec")
        );
        when(registry.all()).thenReturn(tools);
        when(registry.mcpToolsAll()).thenReturn(List.of());
        for (ToolDefinition t : tools) {
            when(registry.get(t.name())).thenReturn(t);
        }

        resolver = new ToolVisibilityResolver(registry);
        ReflectionTestUtils.setField(resolver, "enabled", true);
        ReflectionTestUtils.setField(resolver, "enforce", true);
    }

    /* ================= 最关键：保护评测录制的字节稳定 ================= */

    @Nested
    @DisplayName("CHAT 模式必须排除 Codex 工具（保护评测 cassette）")
    class ChatByteStability {

        @Test
        @DisplayName("chat 看不到任何 codex 工具")
        void chatExcludesCodex() {
            ToolView view = resolver.resolveMode("chat");
            assertFalse(view.contains("repo.list"),
                    "chat 若能看到 codex 工具，V3 的 cassette 将全部失效");
            assertFalse(view.contains("doc.search"));
            assertFalse(view.contains("repo.sync"));
        }

        @Test
        @DisplayName("chat 看不到 exec / checkpoint 工具")
        void chatExcludesExecAndCheckpoint() {
            ToolView view = resolver.resolveMode("chat");
            assertFalse(view.contains("checkpoint.run"));
            assertFalse(view.contains("checkpoint.list"));
        }

        @Test
        @DisplayName("chat 仍能看到全部既有工具（V3 行为不变）")
        void chatKeepsLegacyTools() {
            ToolView view = resolver.resolveMode("chat");
            assertTrue(view.contains("task.create"));
            assertTrue(view.contains("note.create"));
            assertTrue(view.contains("kb.semantic_search"));
            assertTrue(view.contains("subagent.plan"));
            assertTrue(view.contains("insight.daily_scores"));
        }

        @Test
        @DisplayName("既有四模式的 allowTags 一字未改")
        void legacyModesUnchanged() {
            // 这四个模式的 allow 集合是 V3 的原值；改动会破坏降级路径的逐字节一致性
            assertEquals(java.util.Set.of("task", "goal", "planner", "kb",
                            "read", "write", "subagent", "mcp"),
                    AgentMode.PLAN.allowTags());
            assertEquals(java.util.Set.of("task", "goal", "insight", "note", "kb",
                            "read", "subagent", "mcp"),
                    AgentMode.REFLECT.allowTags());
            assertEquals(java.util.Set.of("kb", "note", "read", "subagent", "mcp"),
                    AgentMode.LEARN.allowTags());
            assertTrue(AgentMode.CHAT.allowTags().isEmpty(),
                    "CHAT 必须保持「不收窄」语义，靠 deny 排除新工具");
        }

        @Test
        @DisplayName("plan/reflect/learn 也看不到 codex 工具（未放行该 tag）")
        void legacyModesExcludeCodexNaturally() {
            for (String mode : List.of("plan", "reflect", "learn")) {
                ToolView view = resolver.resolveMode(mode);
                assertFalse(view.contains("doc.search"),
                        mode + " 模式不应看到 codex 工具");
                assertFalse(view.contains("checkpoint.run"),
                        mode + " 模式不应看到 exec 工具");
            }
        }
    }

    /* ================= study：只读知识仓库 ================= */

    @Nested
    @DisplayName("study 模式（知识仓库检索问答，禁写禁执行）")
    class StudyMode {

        @Test
        @DisplayName("可见 codex 读工具与 kb 检索")
        void seesReadTools() {
            ToolView view = resolver.resolveMode("study");
            assertTrue(view.contains("doc.search"));
            assertTrue(view.contains("doc.read"));
            assertTrue(view.contains("doc.outline"));
            assertTrue(view.contains("repo.list"));
            assertTrue(view.contains("kb.semantic_search"));
        }

        @Test
        @DisplayName("禁写：repo.sync / note.create 不可见")
        void deniesWrite() {
            ToolView view = resolver.resolveMode("study");
            assertFalse(view.contains("repo.sync"), "repo.sync 带 write tag，应被 deny");
            assertFalse(view.contains("note.create"));
            assertNotNull(view.reasonOf("repo.sync"));
            assertTrue(view.reasonOf("repo.sync").contains("tag=write"));
        }

        @Test
        @DisplayName("禁执行：checkpoint.run 不可见")
        void deniesExec() {
            ToolView view = resolver.resolveMode("study");
            assertFalse(view.contains("checkpoint.run"));
        }

        @Test
        @DisplayName("禁任务/目标：不会在研读时误改任务体系")
        void deniesTaskAndGoal() {
            ToolView view = resolver.resolveMode("study");
            assertFalse(view.contains("task.create"));
            assertFalse(view.contains("task.search"));
            assertFalse(view.contains("goal.create"));
        }
    }

    /* ================= curate：可写仓库，不动任务 ================= */

    @Nested
    @DisplayName("curate 模式（整理知识仓库）")
    class CurateMode {

        @Test
        @DisplayName("可写仓库：repo.sync 可见")
        void allowsRepoWrite() {
            ToolView view = resolver.resolveMode("curate");
            assertTrue(view.contains("repo.sync"));
            assertTrue(view.contains("doc.search"));
        }

        @Test
        @DisplayName("不动任务体系：task/goal/insight 全部不可见")
        void deniesTaskGoalInsight() {
            ToolView view = resolver.resolveMode("curate");
            assertFalse(view.contains("task.create"),
                    "整理知识时不应能改任务——避免「让它整理笔记结果动了我的任务」");
            assertFalse(view.contains("goal.create"));
            assertFalse(view.contains("insight.daily_scores"));
        }

        @Test
        @DisplayName("禁执行：策展不需要跑命令")
        void deniesExec() {
            ToolView view = resolver.resolveMode("curate");
            assertFalse(view.contains("checkpoint.run"));
        }
    }

    /* ================= verify：唯一可执行的模式 ================= */

    @Nested
    @DisplayName("verify 模式（唯一开放受限执行）")
    class VerifyMode {

        @Test
        @DisplayName("checkpoint.run 仅在此模式可见")
        void onlyModeWithExec() {
            ToolView view = resolver.resolveMode("verify");
            assertTrue(view.contains("checkpoint.run"),
                    "verify 是唯一允许 exec 的模式");
            assertTrue(view.contains("checkpoint.list"));
        }

        @Test
        @DisplayName("其余全部模式都看不到 exec 工具")
        void allOtherModesDenyExec() {
            for (String mode : List.of("chat", "plan", "reflect", "learn", "study", "curate")) {
                ToolView view = resolver.resolveMode(mode);
                assertFalse(view.contains("checkpoint.run"),
                        mode + " 模式绝不应看到受限执行工具");
            }
        }

        @Test
        @DisplayName("禁写：验证过程不应修改仓库内容")
        void deniesWrite() {
            ToolView view = resolver.resolveMode("verify");
            assertFalse(view.contains("repo.sync"));
            assertFalse(view.contains("note.create"));
        }
    }

    /* ================= 模式解析 ================= */

    @Nested
    @DisplayName("AgentMode 解析")
    class ModeParsing {

        @Test
        @DisplayName("新模式 label 可被正确解析")
        void parsesNewModes() {
            assertEquals(AgentMode.STUDY, AgentMode.of("study"));
            assertEquals(AgentMode.CURATE, AgentMode.of("curate"));
            assertEquals(AgentMode.VERIFY, AgentMode.of("verify"));
            assertEquals(AgentMode.STUDY, AgentMode.of("STUDY"), "解析应大小写不敏感");
        }

        @Test
        @DisplayName("未知模式仍回退 CHAT（V3 行为不变）")
        void unknownFallsBackToChat() {
            assertEquals(AgentMode.CHAT, AgentMode.of("nonexistent"));
            assertEquals(AgentMode.CHAT, AgentMode.of(null));
            assertEquals(AgentMode.CHAT, AgentMode.of(""));
        }

        @Test
        @DisplayName("exec tag 只出现在 VERIFY 的 allow 中")
        void execOnlyInVerify() {
            for (AgentMode m : AgentMode.values()) {
                if (m == AgentMode.VERIFY) {
                    assertTrue(m.allowTags().contains("exec"));
                } else {
                    assertFalse(m.allowTags().contains("exec"),
                            m.label() + " 不应放行 exec tag");
                }
            }
        }
    }
}
