package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.policy.ToolApprovalPolicy;
import org.zhzssp.memorandum.feature.agent.runtime.AgentMode;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;
import org.zhzssp.memorandum.feature.codex.tool.CheckpointTools;
import org.zhzssp.memorandum.repository.UserPreferenceRepository;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * L1 单元测试：验证闭环的两条不可退让约束。
 *
 * <p>这两条都不是「功能」而是「纪律」，且都靠<strong>结构</strong>而非提示保证。
 * 测试的作用是：将来若有人为了方便把它们打开，这里会先红。</p>
 *
 * <table>
 *   <tr><th>约束</th><th>为什么必须结构性保证</th></tr>
 *   <tr><td>Agent 不得代填预测</td>
 *       <td>模型一定会"贴心地"帮用户预测——从它的视角这是在帮忙。
 *           但「先预测再动手」的全部价值在于暴露<em>用户自己</em>的心智模型，
 *           代填等于把机制掏空。所以做法是：<strong>根本不提供这个工具</strong>。</td></tr>
 *   <tr><td>checkpoint.run 不可免确认</td>
 *       <td>它在用户机器上执行真实命令。若允许 auto-approve，
 *           「每次执行都经用户过目」这道闸门形同虚设。</td></tr>
 * </table>
 */
class VerifyDisciplineTest {

    /* ================= 约束一：Agent 无法代填预测 ================= */

    @Nested
    @DisplayName("Agent 不得代填预测（靠「不提供工具」保证）")
    class NoPredictTool {

        @Test
        @DisplayName("CheckpointTools 中不存在任何写入预测的工具")
        void noPredictToolExists() {
            for (Method m : CheckpointTools.class.getDeclaredMethods()) {
                var ann = m.getAnnotation(
                        org.zhzssp.memorandum.feature.agent.tool.AgentTool.class);
                if (ann == null) continue;
                String name = ann.name().toLowerCase();
                assertNotEquals("checkpoint.predict", name,
                        "checkpoint.predict 必须不存在：若给 Agent 提交预测的能力，"
                                + "它会代替用户预测，整个「先预测再动手」机制立刻失效");
                assertFalse(name.contains("predict") && !name.equals("checkpoint.stats"),
                        "不得出现任何名含 predict 的写入工具，实际：" + ann.name());
            }
        }

        @Test
        @DisplayName("checkpoint.grade 存在但只记判定，不写预测本身")
        void gradeToolExistsButOnlyJudges() {
            boolean found = false;
            for (Method m : CheckpointTools.class.getDeclaredMethods()) {
                var ann = m.getAnnotation(
                        org.zhzssp.memorandum.feature.agent.tool.AgentTool.class);
                if (ann != null && "checkpoint.grade".equals(ann.name())) {
                    found = true;
                    // 参数里不得有 prediction —— 只能有 correct / divergence
                    for (var p : m.getParameters()) {
                        var pa = p.getAnnotation(
                                org.zhzssp.memorandum.feature.agent.tool.ToolParam.class);
                        if (pa == null) continue;
                        assertNotEquals("prediction", pa.value(),
                                "grade 工具不得接收 prediction 参数");
                    }
                }
            }
            assertTrue(found, "checkpoint.grade 应当存在——判定预测一致性是 Agent 该做的活");
        }

        @Test
        @DisplayName("predictionSatisfied 正确反映门禁状态")
        void predictionGateLogic() {
            KbCheckpoint cp = new KbCheckpoint();
            cp.setPredictRequired(true);
            assertFalse(cp.predictionSatisfied(), "未填预测时门禁不满足");

            cp.setPrediction("   ");
            assertFalse(cp.predictionSatisfied(), "空白预测不算填写");

            cp.setPrediction("我认为 toy.mul 会消失，%a 直接变成 %x");
            assertTrue(cp.predictionSatisfied());

            KbCheckpoint noReq = new KbCheckpoint();
            noReq.setPredictRequired(false);
            assertTrue(noReq.predictionSatisfied(), "不要求预测时门禁默认满足");
        }
    }

    /* ================= 约束二：受限执行不可免确认 ================= */

    @Nested
    @DisplayName("checkpoint.run 永不免确认（执行层强制，非 UI 提示）")
    class NeverAutoApprove {

        private ToolApprovalPolicy policy(String whitelist) {
            UserPreferenceRepository repo = mock(UserPreferenceRepository.class);
            UserPreference pref = new UserPreference();
            pref.setAgentAutoApproveTools(whitelist);
            when(repo.findByUser(any())).thenReturn(Optional.of(pref));
            return new ToolApprovalPolicy(repo);
        }

        private ToolDefinition def(String name, boolean requiresConfirm) {
            return new ToolDefinition(name, name, requiresConfirm,
                    List.of(), null, null, List.of());
        }

        @Test
        @DisplayName("即便用户把 checkpoint.run 加进白名单，仍然弹窗")
        void stillConfirmsEvenIfWhitelisted() {
            ToolApprovalPolicy p = policy("task.create,checkpoint.run");
            User u = new User();
            assertTrue(p.needsConfirm(u, def("checkpoint.run", true)),
                    "受限执行必须逐次确认——否则安全闸门 ④ 形同虚设");
        }

        @Test
        @DisplayName("普通写工具可以被免确认（用户偏好应被尊重）")
        void normalToolCanBeAutoApproved() {
            ToolApprovalPolicy p = policy("task.create");
            User u = new User();
            assertFalse(p.needsConfirm(u, def("task.create", true)),
                    "建任务只影响自己的库，免确认是合理偏好");
        }

        @Test
        @DisplayName("写入层剔除硬例外——UI 禁用只是提示层，可被绕过")
        void strippedOnWrite() {
            UserPreferenceRepository repo = mock(UserPreferenceRepository.class);
            UserPreference pref = new UserPreference();
            when(repo.findByUser(any())).thenReturn(Optional.of(pref));
            ToolApprovalPolicy p = new ToolApprovalPolicy(repo);

            p.updateAutoApproved(new User(), Set.of("task.create", "checkpoint.run"));

            verify(repo).save(argThat(saved -> {
                String v = saved.getAgentAutoApproveTools();
                return v != null && v.contains("task.create")
                        && !v.contains("checkpoint.run");
            }));
        }

        @Test
        @DisplayName("静态判定方法可供 UI 禁用勾选框")
        void staticCheckForUi() {
            assertTrue(ToolApprovalPolicy.neverAutoApprove("checkpoint.run"));
            assertTrue(ToolApprovalPolicy.neverAutoApprove("lab.run_script"));
            assertFalse(ToolApprovalPolicy.neverAutoApprove("task.create"));
            assertFalse(ToolApprovalPolicy.neverAutoApprove("checkpoint.list"));
            assertFalse(ToolApprovalPolicy.neverAutoApprove(null));
        }
    }

    /* ================= 约束三：exec 权限收窄到单一模式 ================= */

    @Nested
    @DisplayName("exec 权限只在 VERIFY 模式开放")
    class ExecIsolation {

        @Test
        @DisplayName("只有 VERIFY 的 allowTags 含 exec")
        void onlyVerifyAllowsExec() {
            for (AgentMode m : AgentMode.values()) {
                if (m == AgentMode.VERIFY) {
                    assertTrue(m.allowTags().contains("exec"),
                            "VERIFY 必须放行 exec，否则验证闭环无法工作");
                } else {
                    assertFalse(m.allowTags().contains("exec"),
                            m.label() + " 不得放行 exec tag");
                }
            }
        }

        @Test
        @DisplayName("CHAT 显式 deny exec/checkpoint（它的 allowTags 是空集=不收窄）")
        void chatDeniesExplicitly() {
            assertTrue(AgentMode.CHAT.allowTags().isEmpty(),
                    "CHAT 保持「不收窄」语义");
            assertTrue(AgentMode.CHAT.denyTags().contains("exec"),
                    "CHAT 不收窄，因此必须显式 deny，否则 exec 工具会自动可见");
            assertTrue(AgentMode.CHAT.denyTags().contains("checkpoint"));
        }

        @Test
        @DisplayName("study/curate 显式 deny exec")
        void studyAndCurateDenyExec() {
            assertTrue(AgentMode.STUDY.denyTags().contains("exec"),
                    "研读模式不该能执行命令");
            assertTrue(AgentMode.CURATE.denyTags().contains("exec"),
                    "策展模式不该能执行命令");
        }

        @Test
        @DisplayName("VERIFY 禁写：验证过程不应修改仓库内容")
        void verifyDeniesWrite() {
            assertTrue(AgentMode.VERIFY.denyTags().contains("write"));
            assertTrue(AgentMode.VERIFY.denyTags().contains("task"));
            assertTrue(AgentMode.VERIFY.denyTags().contains("goal"));
        }
    }

    /* ================= 状态机 ================= */

    @Nested
    @DisplayName("状态与级别语义")
    class StatusSemantics {

        @Test
        @DisplayName("Level 顺序即难度顺序，L2 是主判据")
        void levelOrdering() {
            assertTrue(KbCheckpoint.Level.L0.ordinal() < KbCheckpoint.Level.L1.ordinal());
            assertTrue(KbCheckpoint.Level.L1.ordinal() < KbCheckpoint.Level.L2.ordinal());
            assertTrue(KbCheckpoint.Level.L2.ordinal() < KbCheckpoint.Level.L3.ordinal());
        }

        @Test
        @DisplayName("Level 解析容错")
        void levelParsing() {
            assertEquals(KbCheckpoint.Level.L2, KbCheckpoint.Level.of("L2"));
            assertEquals(KbCheckpoint.Level.L2, KbCheckpoint.Level.of("l2"));
            assertEquals(KbCheckpoint.Level.L0, KbCheckpoint.Level.of("bogus"));
            assertEquals(KbCheckpoint.Level.L0, KbCheckpoint.Level.of(null));
        }

        @Test
        @DisplayName("DEGRADED 独立于 PASSED —— 输出被截断时判定强度更低")
        void degradedIsDistinct() {
            assertNotEquals(KbCheckpoint.Status.PASSED, KbCheckpoint.Status.DEGRADED);
            // 二者都算「通过」，但 DEGRADED 要让用户知道判定是在数据不完整时做的
            assertNotNull(KbCheckpoint.Status.valueOf("DEGRADED"));
        }

        @Test
        @DisplayName("默认 verifySource 是 PARSED —— 如实标注判据较弱")
        void defaultVerifySourceIsParsed() {
            KbCheckpoint cp = new KbCheckpoint();
            assertEquals(KbCheckpoint.VerifySource.PARSED, cp.getVerifySource(),
                    "从 Markdown 解析出的判据只能判退出码，不可假装精确");
        }

        @Test
        @DisplayName("裁判来源必须可区分 —— 否则准确率没有解释力")
        void judgeSourceDistinguishable() {
            assertNotEquals(KbCheckpoint.PredictionJudge.AI,
                    KbCheckpoint.PredictionJudge.USER);
        }
    }
}
