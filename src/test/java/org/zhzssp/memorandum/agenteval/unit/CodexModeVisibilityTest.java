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
                def("task.today", "task", "read"),
                def("goal.create", "goal", "write"),
                def("goal.list", "goal", "read"),
                def("note.create", "note", "write"),
                def("kb.semantic_search", "kb", "read"),
                def("insight.daily_scores", "insight", "read"),
                // 带 read 的规划工具：它是 learn 越界里代价最大的一个
                // （draft_goal_plan 会起子规划器，一次 5~9 次 LLM 调用）
                def("planner.draft_goal_plan", "planner", "read"),
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
                def("checkpoint.run", "checkpoint", "exec"),
                // ---- P2 沉淀与 Git 写入 ----
                def("doc.write", "codex", "doc", "write"),
                def("doc.insert_backref", "codex", "doc", "write"),
                def("repo.commit", "codex", "git", "write"),
                def("repo.open_pr", "codex", "git", "write"),
                def("ci.run_local", "codex", "read"),
                // ---- P3 缺口闭环 ----
                def("gap.list", "codex", "read"),
                def("gap.to_learning_plan", "codex", "write"),
                def("scope.skipped", "codex", "read"),
                def("scope.set", "codex", "write"),
                // ---- P4 蒸馏与定线 ----
                def("distill.draft", "codex", "doc", "read"),
                def("distill.write", "codex", "doc", "write"),
                def("exam.draft", "codex", "doc", "read"),
                def("exam.write", "codex", "doc", "write"),
                def("route.next", "codex", "read"),
                def("route.stages", "codex", "read"),
                def("doc.anchors", "codex", "read")
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

        /**
         * ★ P2 修正的回归测试。
         *
         * <p>P0/P1 曾假设「新工具不带旧 tag，所以旧模式天然看不到」。这个假设是<strong>错的</strong>：
         * tag 过滤是 OR 语义，而 Codex 工具为参与统一治理必须带 {@code read}/{@code write}——
         * {@code doc.search} 带 {@code read} 会命中 plan/reflect/learn 的 allow，
         * {@code repo.sync} 带 {@code write} 会命中 plan 的 allow。
         * 于是三个旧模式的工具 schema 都被悄悄改变，cassette 会静默失效。</p>
         *
         * <p>本测试同时覆盖两件事：结果正确（看不到），以及<strong>机制正确</strong>
         * （是被 deny 剔除的，而不是碰巧没命中 allow）。后者才能防止将来
         * 有人「顺手」给某个 Codex 工具去掉 write tag 时重新出现泄漏。</p>
         */
        @Test
        @DisplayName("plan/reflect/learn 看不到 codex 工具，且必须是被 deny 剔除的")
        void legacyModesExcludeCodexByDeny() {
            for (String mode : List.of("plan", "reflect", "learn")) {
                ToolView view = resolver.resolveMode(mode);
                for (String tool : List.of("doc.search", "repo.list", "repo.sync",
                        "doc.write", "repo.commit", "ci.run_local", "checkpoint.run",
                        "gap.list", "gap.to_learning_plan", "scope.skipped", "scope.set",
                        "distill.draft", "distill.write", "exam.draft", "exam.write",
                        "route.next", "route.stages")) {
                    assertFalse(view.contains(tool),
                            mode + " 模式不应看到 " + tool + "（会改变工具 schema 字节）");
                }
                // 机制校验：doc.search 带 read tag，命中了 allow，只能靠 deny 剔除
                assertTrue(view.reasonOf("doc.search").contains("deny"),
                        mode + " 排除 doc.search 必须是 deny 生效，而非未命中 allow；"
                                + "实际原因：" + view.reasonOf("doc.search"));
            }
        }

        @Test
        @DisplayName("旧模式仍能看到全部既有工具（allow 未动，deny 只针对 V4 tag）")
        void legacyModesKeepLegacyTools() {
            ToolView plan = resolver.resolveMode("plan");
            assertTrue(plan.contains("task.create"));
            assertTrue(plan.contains("goal.create"));
            assertTrue(plan.contains("kb.semantic_search"));

            ToolView learn = resolver.resolveMode("learn");
            assertTrue(learn.contains("kb.semantic_search"));
            assertFalse(learn.contains("note.create"), "learn 仍应禁写（V3 行为）");
        }
    }

    /* ================= read 横切 tag 造成的越界 ================= */

    /**
     * ★由<b>真实录制</b>查出的一类越界，此前从未被任何测试覆盖。
     *
     * <h3>症状</h3>
     * learn 模式下模型成功调到了 {@code goal.list} 与 {@code planner.draft_goal_plan}，
     * 而该模式的承诺是「纯检索问答（kb/note 读）」。
     *
     * <h3>根因</h3>
     * tag 过滤是 OR 语义，而 {@code read} 挂在<b>每个域的每个读工具</b>上。
     * allow 里只要出现 {@code read}，全系统的读工具就全部命中，
     * 除非把每个业务域逐个 deny。LEARN 原先只 deny 了 {@code write}。
     *
     * <h3>为什么以前测不出来</h3>
     * 两层原因叠加，缺一都不会漏这么久：
     * <ul>
     *   <li>单测这边：从没有一条断言检查过 learn 的<b>读</b>侧边界，
     *       只查了「写工具不可见」；</li>
     *   <li>评测那边：{@code mode_isolation_learn} 用的是<b>手写</b>录制盒，
     *       盒子里模型直接答复「做不到」、压根没尝试调用，
     *       于是 {@code didNotCallTool} 恒真——<b>一条没有守护对象的断言</b>。
     *       换成真实录制后模型真的去试了，洞立刻现形。</li>
     * </ul>
     */
    @Nested
    @DisplayName("★read 横切 tag：只读模式不得因此看到别的业务域")
    class ReadTagLeak {

        /** learn 的承诺是「纯检索问答」，任务体系的读工具同样不该出现。 */
        @Test
        @DisplayName("learn 看不到任务/目标/insight 的读工具")
        void learnExcludesTaskDomainReads() {
            ToolView view = resolver.resolveMode("learn");
            for (String tool : List.of("task.search", "task.today",
                    "goal.list", "insight.daily_scores")) {
                assertFalse(view.contains(tool),
                        "learn 是纯检索问答模式，不应看到 " + tool
                                + "（它靠 read tag 命中 allow，只能靠 deny 剔除）");
            }
            // 机制校验：确认是被 deny 掉的，而不是碰巧没命中 allow。
            // 后者将来会因为有人给某个工具补 tag 而重新泄漏。
            assertTrue(view.reasonOf("goal.list").contains("deny"),
                    "必须是 deny 生效；goal.list 带 read，一定命中了 allow");
        }

        /**
         * 代价最大的一条：起草会起子规划器，一次 5~9 次 LLM 调用。
         * 与 STUDY 当初 deny {@code doc} 挡住 {@code distill.draft} 是同一个理由。
         */
        @Test
        @DisplayName("★三个只读模式都看不到 planner.draft_goal_plan（随口一问就会花钱）")
        void readOnlyModesExcludeCostlyPlanner() {
            for (String mode : List.of("learn", "study", "reflect")) {
                ToolView view = resolver.resolveMode(mode);
                assertFalse(view.contains("planner.draft_goal_plan"),
                        mode + " 不该能触发子规划器：一次起草是 5~9 次 LLM 调用，"
                                + "与只读模式的承诺不符");
            }
        }

        /** study 原先只 deny 了 task/goal，insight 是同一个洞里漏掉的第三个域。 */
        @Test
        @DisplayName("study 看不到 insight 读工具")
        void studyExcludesInsight() {
            assertFalse(resolver.resolveMode("study").contains("insight.daily_scores"));
        }

        /** 边界另一侧：修 deny 不能把模式本职的工具一起挡掉。 */
        @Test
        @DisplayName("修复不得误伤：learn 仍看得到知识检索，plan 仍看得到规划工具")
        void fixDoesNotOverReach() {
            ToolView learn = resolver.resolveMode("learn");
            assertTrue(learn.contains("kb.semantic_search"), "learn 的本职就是检索笔记");
            assertTrue(learn.contains("subagent.plan"), "子代理不在收窄范围内");

            ToolView plan = resolver.resolveMode("plan");
            assertTrue(plan.contains("planner.draft_goal_plan"),
                    "plan 模式 allow 里有 planner，本就该看得到");
            assertTrue(plan.contains("task.create"));

            ToolView reflect = resolver.resolveMode("reflect");
            assertTrue(reflect.contains("task.search"), "复盘要读任务，allow 里有 task");
            assertTrue(reflect.contains("insight.daily_scores"), "复盘要读 insight");
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

        @Test
        @DisplayName("可看缺口台账但不可改：研读时问「我还有哪些盲区」不该需要切模式")
        void seesGapReadOnly() {
            ToolView view = resolver.resolveMode("study");
            assertTrue(view.contains("gap.list"));
            assertTrue(view.contains("scope.skipped"));
            assertFalse(view.contains("gap.to_learning_plan"), "转计划会落库，属写操作");
            assertFalse(view.contains("scope.set"), "改判止损线属写操作");
        }

        /**
         * ★P4 补的一条隔离。
         *
         * <p>{@code distill.draft} 与 {@code exam.draft} 确实<strong>不写任何文件</strong>，
         * 所以它们带的是 {@code read} tag，于是天然命中 STUDY 的 allow。
         * 但一次起草是 5~9 次 LLM 调用——在一个「纯研读」的模式里放一个
         * 随口一问就会花钱的工具，与这个模式的承诺不符。</p>
         *
         * <p>处置是给 STUDY deny {@code doc} tag，而不是给这两个工具去掉 {@code read}：
         * 后者是用 tag 调可见性，而 tag 表达的是工具属于哪个能力域。
         * 一旦开始用 tag 当旋钮，可见性规则就再也读不懂了。</p>
         */
        @Test
        @DisplayName("★看得到定线，但看不到蒸馏与出题（起草会花钱，不该出现在纯研读模式）")
        void seesRouteButNotDistill() {
            ToolView view = resolver.resolveMode("study");
            assertTrue(view.contains("route.next"), "「我该学什么」是研读时最常问的问题");
            assertTrue(view.contains("route.stages"));
            assertFalse(view.contains("distill.draft"),
                    "一次起草是 5~9 次 LLM 调用，纯研读模式不该能触发");
            assertFalse(view.contains("exam.draft"));
            assertFalse(view.contains("distill.write"));

            // 检索类 doc 工具不带 doc tag，所以这条 deny 不会伤到研读本身
            assertTrue(view.contains("doc.search"), "deny doc 不应影响检索");
            assertTrue(view.contains("doc.outline"));
            assertTrue(view.contains("doc.anchors"));
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

        @Test
        @DisplayName("P2 沉淀与 Git 写入工具仅在 curate 可见")
        void ownsSedimentTools() {
            ToolView curate = resolver.resolveMode("curate");
            assertTrue(curate.contains("doc.write"));
            assertTrue(curate.contains("doc.insert_backref"));
            assertTrue(curate.contains("repo.commit"));
            assertTrue(curate.contains("repo.open_pr"));
            assertTrue(curate.contains("ci.run_local"));

            // 其余任何模式都不得出现写仓库的能力
            for (String mode : List.of("chat", "plan", "reflect", "learn", "study", "verify")) {
                ToolView v = resolver.resolveMode(mode);
                assertFalse(v.contains("doc.write"), mode + " 不应能写知识仓库文件");
                assertFalse(v.contains("repo.commit"), mode + " 不应能提交 git");
                assertFalse(v.contains("distill.write"), mode + " 不应能写蒸馏产物");
                assertFalse(v.contains("exam.write"), mode + " 不应能写检验册");
            }
        }

        @Test
        @DisplayName("P4 蒸馏与出题工具仅在 curate 可见")
        void ownsDistillTools() {
            ToolView curate = resolver.resolveMode("curate");
            assertTrue(curate.contains("distill.draft"));
            assertTrue(curate.contains("distill.write"));
            assertTrue(curate.contains("exam.draft"));
            assertTrue(curate.contains("exam.write"));
            assertTrue(curate.contains("route.next"), "策展时也需要知道该先整理哪一块");
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
