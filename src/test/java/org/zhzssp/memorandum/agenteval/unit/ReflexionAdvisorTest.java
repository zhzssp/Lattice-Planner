package org.zhzssp.memorandum.agenteval.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.ReflexionAdvisor;
import org.zhzssp.memorandum.feature.agent.runtime.ReflexionAdvisor.FailureMode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 单元测试：{@link ReflexionAdvisor}（方案 D）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li><strong>失败分类</strong>——分错了就会给出反向的建议
 *       （把确定性失败当偶发的去重试、把可修复的当死路的去放弃）；</li>
 *   <li><strong>可重试性语义</strong>——不可重试的失败必须一次即封禁，
 *       不能白烧一步去证明它还会失败；</li>
 *   <li><strong>不误伤成功响应</strong>——{@code {"status":"ok"}} 这类响应
 *       绝不能被当成失败，否则会给成功调用注入"禁止再调用"的错误引导。</li>
 * </ol>
 */
class ReflexionAdvisorTest {

    private ReflexionAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ReflexionAdvisor(new ObjectMapper());
        ReflectionTestUtils.setField(advisor, "enabled", true);
        ReflectionTestUtils.setField(advisor, "failThreshold", 2);
    }

    /* ================= 失败分类 ================= */

    @Nested
    @DisplayName("失败分类")
    class Classification {

        @Test
        @DisplayName("参数校验错误 → INVALID_ARGUMENTS")
        void invalidArguments() {
            assertEquals(FailureMode.INVALID_ARGUMENTS,
                    advisor.classify("{\"error\":\"INVALID_ARGUMENTS\",\"tool\":\"task.create\"}"));
            assertEquals(FailureMode.INVALID_ARGUMENTS,
                    advisor.classify("{\"error\":\"IllegalArgumentException\","
                            + "\"message\":\"缺少必填参数：title\"}"));
        }

        @Test
        @DisplayName("资源不存在 → RESOURCE_NOT_FOUND（多为编造 id）")
        void resourceNotFound() {
            assertEquals(FailureMode.RESOURCE_NOT_FOUND,
                    advisor.classify("{\"error\":\"NOT_FOUND\",\"message\":\"任务 999 不存在\"}"));
            assertEquals(FailureMode.RESOURCE_NOT_FOUND,
                    advisor.classify("{\"error\":\"NoSuchElementException\",\"message\":\"No value\"}"));
        }

        @Test
        @DisplayName("权限/功能禁用 → DENIED")
        void denied() {
            assertEquals(FailureMode.DENIED,
                    advisor.classify("{\"status\":\"WRITE_DISABLED\",\"message\":\"该能力已禁用\"}"));
            assertEquals(FailureMode.DENIED,
                    advisor.classify("{\"error\":\"ACCESS_DENIED\",\"message\":\"不在白名单内\"}"));
        }

        @Test
        @DisplayName("用户拒绝授权 → USER_REJECTED")
        void userRejected() {
            assertEquals(FailureMode.USER_REJECTED, advisor.classify("{\"status\":\"USER_REJECTED\"}"));
        }

        @Test
        @DisplayName("工具不存在 → UNKNOWN_TOOL")
        void unknownTool() {
            assertEquals(FailureMode.UNKNOWN_TOOL,
                    advisor.classify("{\"error\":\"UNKNOWN_TOOL\",\"tool\":\"local.read_file\"}"));
        }

        @Test
        @DisplayName("超时/网络 → TRANSIENT")
        void transientFailure() {
            assertEquals(FailureMode.TRANSIENT,
                    advisor.classify("{\"error\":\"SocketTimeoutException\",\"message\":\"read timeout\"}"));
            assertEquals(FailureMode.TRANSIENT,
                    advisor.classify("{\"error\":\"MCP_PROXY_NOT_READY\",\"message\":\"尚未初始化\"}"));
        }

        @Test
        @DisplayName("未知错误 → OTHER（仍可重试一次）")
        void other() {
            assertEquals(FailureMode.OTHER,
                    advisor.classify("{\"error\":\"SomeWeirdException\",\"message\":\"?\"}"));
        }
    }

    @Nested
    @DisplayName("不得把成功响应误判为失败")
    class NoFalsePositive {

        @Test
        @DisplayName("{\"status\":\"ok\"} 不是失败")
        void statusOk() {
            assertEquals(FailureMode.NONE, advisor.classify("{\"status\":\"ok\",\"id\":12}"));
        }

        @Test
        @DisplayName("普通业务响应不是失败")
        void plainSuccess() {
            assertEquals(FailureMode.NONE, advisor.classify("{\"id\":7,\"title\":\"写周报\"}"));
        }

        @Test
        @DisplayName("检索结果数组不是失败（含 CRAG _meta 行）")
        void searchArray() {
            assertEquals(FailureMode.NONE,
                    advisor.classify("[{\"_meta\":\"crag\",\"grade\":\"INCORRECT\",\"degraded\":true}]"),
                    "grade=INCORRECT 是检索质量信号，不是工具失败——误判会禁掉检索工具");
        }

        @Test
        @DisplayName("空/非法输入不是失败")
        void blankOrBroken() {
            assertEquals(FailureMode.NONE, advisor.classify(null));
            assertEquals(FailureMode.NONE, advisor.classify(""));
            assertEquals(FailureMode.NONE, advisor.classify("not a json"));
            assertEquals(FailureMode.NONE, advisor.classify("null"));
        }
    }

    /* ================= 封禁语义 ================= */

    @Test
    @DisplayName("可重试失败：第一次给修复指引，达到阈值才封禁")
    void retryableBansAtThreshold() {
        var st = advisor.newTurn();

        String first = advisor.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);
        assertNotNull(first);
        assertTrue(first.contains("expectedParams"), "参数错误应引导对照参数表修正");
        assertFalse(st.isBanned("task.create"), "第一次失败不应封禁——参数错误是可修复的");

        String second = advisor.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);
        assertNotNull(second);
        assertTrue(st.isBanned("task.create"), "达到阈值 2 应封禁");
        assertTrue(second.contains("禁止"));
    }

    @Test
    @DisplayName("不可重试失败：一次即封禁，不浪费步数去证明它还会失败")
    void nonRetryableBansImmediately() {
        for (FailureMode mode : new FailureMode[]{
                FailureMode.DENIED, FailureMode.USER_REJECTED,
                FailureMode.UNKNOWN_TOOL, FailureMode.RESOURCE_NOT_FOUND}) {
            var st = advisor.newTurn();
            String hint = advisor.onFailure(st, "some.tool", mode);
            assertNotNull(hint, mode + " 应给出提示");
            assertTrue(st.isBanned("some.tool"), mode + " 应立即封禁（retryable=false）");
        }
    }

    @Test
    @DisplayName("用户拒绝后禁止绕过——包括换用等效工具")
    void userRejectionForbidsWorkaround() {
        var st = advisor.newTurn();
        String hint = advisor.onFailure(st, "task.delete", FailureMode.USER_REJECTED);
        assertTrue(hint.contains("绕过"), "必须显式禁止绕过，否则模型可能改用等效工具执行");
    }

    @Test
    @DisplayName("资源不存在时引导先检索取真实 id，而不是空喊换工具")
    void notFoundGuidesToSearch() {
        var st = advisor.newTurn();
        String hint = advisor.onFailure(st, "task.update", FailureMode.RESOURCE_NOT_FOUND);
        assertTrue(hint.contains("检索") || hint.contains("查询"));
        assertTrue(hint.contains("id"));
    }

    @Test
    @DisplayName("成功后清零失败计数（自修复完成）")
    void successResetsCounter() {
        var st = advisor.newTurn();
        advisor.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);
        assertEquals(1, st.failures("task.create"));
        assertTrue(st.isRepairAttempt("task.create"));

        advisor.onSuccess(st, "task.create");
        assertEquals(0, st.failures("task.create"));
        assertFalse(st.isRepairAttempt("task.create"),
                "已自修复成功，后续调用不应再计入修复尝试");
        assertFalse(st.isBanned("task.create"));
    }

    @Test
    @DisplayName("失败计数按工具独立，不会互相牵连")
    void countersAreIsolatedPerTool() {
        var st = advisor.newTurn();
        advisor.onFailure(st, "a.tool", FailureMode.INVALID_ARGUMENTS);
        advisor.onFailure(st, "a.tool", FailureMode.INVALID_ARGUMENTS);
        assertTrue(st.isBanned("a.tool"));
        assertFalse(st.isBanned("b.tool"), "b 未失败过，不应被牵连封禁");
    }

    @Test
    @DisplayName("封禁结果 JSON 含 error code、失败次数与换路建议")
    void bannedResultShape() {
        var st = advisor.newTurn();
        advisor.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);
        advisor.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);

        Map<String, Object> r = advisor.bannedResult(st, "task.create");
        assertEquals(ReflexionAdvisor.BANNED_ERROR, r.get("error"));
        assertEquals("task.create", r.get("tool"));
        assertEquals(2, r.get("failures"));
        assertTrue(((String) r.get("hint")).contains("禁止再次调用"));
        assertTrue(((String) r.get("hint")).contains("向用户说明"),
                "必须给出兜底出口，否则模型可能空转到步数耗尽");
    }

    /* ================= 可降级 ================= */

    @Test
    @DisplayName("开关关闭时完全旁路：不封禁、不注入提示")
    void disabledIsFullyBypassed() {
        ReflexionAdvisor off = new ReflexionAdvisor(new ObjectMapper());
        ReflectionTestUtils.setField(off, "enabled", false);
        ReflectionTestUtils.setField(off, "failThreshold", 2);

        var st = off.newTurn();
        assertNull(off.onFailure(st, "task.create", FailureMode.DENIED));
        assertFalse(st.isBanned("task.create"));
        assertFalse(st.isRepairAttempt("task.create"));
        // 关闭后行为应与改造前的隐式 Reflexion 完全一致
        assertNull(off.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS));
    }

    @Test
    @DisplayName("阈值可配置为 1（更激进地封禁）")
    void thresholdIsConfigurable() {
        ReflexionAdvisor strict = new ReflexionAdvisor(new ObjectMapper());
        ReflectionTestUtils.setField(strict, "enabled", true);
        ReflectionTestUtils.setField(strict, "failThreshold", 1);

        var st = strict.newTurn();
        strict.onFailure(st, "task.create", FailureMode.INVALID_ARGUMENTS);
        assertTrue(st.isBanned("task.create"), "阈值 1 时首次失败即封禁");
    }

    @Test
    @DisplayName("状态是单轮作用域：新一轮不继承上一轮的封禁")
    void stateIsPerTurn() {
        var turn1 = advisor.newTurn();
        advisor.onFailure(turn1, "task.create", FailureMode.DENIED);
        assertTrue(turn1.isBanned("task.create"));

        var turn2 = advisor.newTurn();
        assertFalse(turn2.isBanned("task.create"),
                "用户下一轮可能已补齐信息或调整了权限，继承封禁会误伤");
    }
}
