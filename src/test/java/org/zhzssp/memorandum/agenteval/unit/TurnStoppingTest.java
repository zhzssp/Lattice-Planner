package org.zhzssp.memorandum.agenteval.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnEndReason;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnOutcome;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnStoppingAdvisor;
import org.zhzssp.memorandum.feature.agent.runtime.turn.TurnStoppingBus;
import org.zhzssp.memorandum.feature.agent.runtime.turn.advisor.DegradeDisclosureAdvisor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3+L4 单元测试：{@link TurnStoppingBus} 与 {@link DegradeDisclosureAdvisor}（方案 L）。
 *
 * <p>重点验证：失控防护（steer 硬上限 + 剩余步数保护）、降级明示的触发条件、
 * 顾问异常隔离。</p>
 */
class TurnStoppingTest {

    @Nested
    @DisplayName("TurnStoppingBus 失控防护")
    class BusSafety {

        private TurnStoppingBus bus;
        /** 永远返回非空的恶意顾问，模拟「每次都 steer」的 bug 实现。 */
        private TurnStoppingAdvisor alwaysSteer = new TurnStoppingAdvisor() {
            public String name() { return "always-steer"; }
            public Optional<String> onTurnStopping(TurnOutcome o) { return Optional.of("再来一次"); }
        };

        @BeforeEach
        void setUp() {
            bus = new TurnStoppingBus(List.of(alwaysSteer));
            ReflectionTestUtils.setField(bus, "enabled", true);
            ReflectionTestUtils.setField(bus, "maxSteer", 1);
        }

        @Test
        @DisplayName("单轮 steer 硬上限：第一次返回，第二次强制收尾")
        void maxSteerEnforced() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 0);

            Optional<String> first = bus.consult(o, 8);
            assertTrue(first.isPresent(), "首次应返回 steer");
            assertEquals(1, o.steerCount());

            // 第二次：steer 已达上限，强制收尾
            Optional<String> second = bus.consult(o, 8);
            assertFalse(second.isPresent(), "steer 达上限后应强制收尾");
        }

        @Test
        @DisplayName("剩余步数保护：最后一步不 steer")
        void remainingStepsGuard() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 7); // maxSteps=8，usedSteps=7 = 最后一步

            Optional<String> r = bus.consult(o, 8);
            assertFalse(r.isPresent(), "剩余步数不足时应拒绝 steer");
        }

        @Test
        @DisplayName("关闭时完全不询问")
        void disabledBypasses() {
            ReflectionTestUtils.setField(bus, "enabled", false);
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 0);
            assertFalse(bus.consult(o, 8).isPresent());
        }
    }

    @Nested
    @DisplayName("TurnStoppingBus 顾问调度")
    class BusDispatch {

        @Test
        @DisplayName("首个返回非空的顾问胜出，其余不再询问")
        void firstNonEmptyWins() {
            var a = new NamedAdvisor("a", 1, Optional.of("steer-a"));
            var b = new NamedAdvisor("b", 2, Optional.of("steer-b"));
            TurnStoppingBus bus = new TurnStoppingBus(List.of(b, a)); // 乱序传入，应按 order 排序
            ReflectionTestUtils.setField(bus, "enabled", true);
            ReflectionTestUtils.setField(bus, "maxSteer", 2);

            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 0);
            Optional<String> r = bus.consult(o, 8);

            assertEquals("steer-a", r.orElse(null), "order 小的 a 应胜出");
            assertEquals("a", o.lastAdvisorName());
        }

        @Test
        @DisplayName("顾问抛异常被隔离，不影响其他顾问")
        void advisorExceptionIsolated() {
            TurnStoppingAdvisor throwing = new TurnStoppingAdvisor() {
                public String name() { return "throwing"; }
                public int order() { return 1; }
                public Optional<String> onTurnStopping(TurnOutcome o) {
                    throw new RuntimeException("boom");
                }
            };
            var good = new NamedAdvisor("good", 2, Optional.of("steer-good"));
            TurnStoppingBus bus = new TurnStoppingBus(List.of(throwing, good));
            ReflectionTestUtils.setField(bus, "enabled", true);
            ReflectionTestUtils.setField(bus, "maxSteer", 2);

            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 0);
            Optional<String> r = bus.consult(o, 8);

            assertEquals("steer-good", r.orElse(null), "抛异常的顾问被隔离后，good 应被询问");
        }
    }

    @Nested
    @DisplayName("DegradeDisclosureAdvisor 降级明示")
    class DegradeDisclosure {

        private DegradeDisclosureAdvisor advisor;

        @BeforeEach
        void setUp() {
            advisor = new DegradeDisclosureAdvisor();
            ReflectionTestUtils.setField(advisor, "enabled", true);
        }

        @Test
        @DisplayName("降级且未明示 → 注入 steer")
        void degradedWithoutDisclosure() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.propose(TurnEndReason.FINAL_ANSWER, "今天完成了 3 个任务", 2);

            Optional<String> r = advisor.onTurnStopping(o);
            assertTrue(r.isPresent());
            assertTrue(r.get().contains("TRUNCATED"));
        }

        @Test
        @DisplayName("降级但已明示 → 放行")
        void degradedWithDisclosure() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.propose(TurnEndReason.FINAL_ANSWER, "部分内容因过长被截断，以下基于已有信息", 2);

            assertFalse(advisor.onTurnStopping(o).isPresent());
        }

        @Test
        @DisplayName("未降级 → 不注入")
        void notDegraded() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.propose(TurnEndReason.FINAL_ANSWER, "正常答复", 2);
            assertFalse(advisor.onTurnStopping(o).isPresent());
        }

        /**
         * 伪造归属一票否决——<b>哪怕答复里挂着免责声明</b>。
         *
         * <p>这曾经是判据上唯一的正确性漏洞：只要尾部补一句"基于通用知识"，
         * 一条把编造内容安到用户笔记名下的答复就能被放行。
         * 危害在于用户看到"根据你的笔记"会默认这是自己写过的，从而放弃核实——
         * <b>比什么都不说更有害</b>。
         */
        @Test
        @DisplayName("伪造归属 + 免责声明 → 仍注入 steer")
        void fabricatedAttributionOverridesDisclaimer() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.markDegraded(TurnOutcome.CAUSE_CRAG_DEGRADED);
            o.propose(TurnEndReason.FINAL_ANSWER,
                    "根据你的笔记，Redis 的持久化有 RDB 和 AOF 两种。以上部分内容基于通用知识补充。", 2);

            Optional<String> r = advisor.onTurnStopping(o);
            assertTrue(r.isPresent(),
                    "尾部挂一句免责声明不该洗白伪造归属");
            assertTrue(r.get().contains("根据你的笔记"),
                    "steer 里应当点明是哪句话伪造了归属，否则模型不知道要改什么");
        }

        /**
         * 明示措辞不止那几个固定词——诚实的说法是个开放集。
         *
         * <p>词表按人工校准集补齐后，这类最自然的口语表达不再被误伤。
         * 误伤的代价只是多一次 LLM 调用，但它发生在<b>模型已经做对了</b>的时候，
         * 白花钱之外还有把好答复改坏的风险。
         */
        @Test
        @DisplayName("换一种说法的诚实答复 → 放行，不再误伤")
        void naturalPhrasingIsRecognised() {
            for (String honest : List.of(
                    "我在你的记录里没搜到贴近的内容，先按我自己的理解讲。",
                    "翻了一遍你的笔记，这个话题是空白。",
                    "库里没有这方面的积累。基于我的训练数据，我的理解是这样。")) {
                TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
                o.markDegraded(TurnOutcome.CAUSE_CRAG_DEGRADED);
                o.propose(TurnEndReason.FINAL_ANSWER, honest, 2);

                assertFalse(advisor.onTurnStopping(o).isPresent(),
                        "这条答复是诚实的，不该要求重答：" + honest);
            }
        }

        @Test
        @DisplayName("步数耗尽（非正常收敛）→ 不注入")
        void notFinalAnswer() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.propose(TurnEndReason.STEPS_EXHAUSTED, "已达最大步数", 8);
            assertFalse(advisor.onTurnStopping(o).isPresent());
        }

        @Test
        @DisplayName("已 steer 过 → 不重复注入（幂等）")
        void alreadySteered() {
            TurnOutcome o = new TurnOutcome("sid", "chat", "hi");
            o.markDegraded(TurnOutcome.CAUSE_TRUNCATED);
            o.propose(TurnEndReason.FINAL_ANSWER, "答复", 2);
            o.recordSteer("degrade-disclosure");

            assertFalse(advisor.onTurnStopping(o).isPresent());
        }
    }

    /** 测试用的具名顾问。 */
    private record NamedAdvisor(String name, int order, Optional<String> result)
            implements TurnStoppingAdvisor {
        @Override
        public Optional<String> onTurnStopping(TurnOutcome outcome) {
            return result;
        }
    }
}
