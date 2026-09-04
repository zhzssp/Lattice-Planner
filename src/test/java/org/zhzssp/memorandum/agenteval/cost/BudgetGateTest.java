package org.zhzssp.memorandum.agenteval.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算门禁的判定逻辑（P5）。
 *
 * <p>这道门禁的价值全在<b>它会不会误报</b>：一道天天误报的成本闸门，
 * 三周内必然被加上 {@code @Disabled}，然后 prompt 就可以随便长胖了。
 * 所以这里的用例重点不是"超了要红"，而是"<b>什么情况下不该红</b>"。
 */
@DisplayName("P5 · 预算门禁")
class BudgetGateTest {

    private static BudgetGate.Verdict judge(long baseline, long actual) {
        return BudgetGate.judge("c", "requestChars", baseline, actual);
    }

    @Nested
    @DisplayName("判定")
    class Judgement {

        @Test
        @DisplayName("持平通过")
        void equalPasses() {
            assertEquals(BudgetGate.Status.OK, judge(10_000, 10_000).status());
        }

        @Test
        @DisplayName("★日期串/自增 id 那点抖动通过——但也仅止于此")
        void absoluteAllowanceAbsorbsNoise() {
            // 10000 的 0.5% = 50，不足绝对宽容量 64，取 64
            assertEquals(BudgetGate.Status.OK, judge(10_000, 10_064).status());
            assertEquals(10_064, judge(10_000, 10_064).allowed(),
                    "边界取闭区间，避免恰好踩线时反复横跳");
        }

        @Test
        @DisplayName("大基线按比例给余量，小基线按绝对值给——取两者较大")
        void allowanceIsMaxOfPctAndAbsolute() {
            assertEquals(100_500, judge(100_000, 0).allowed(), "0.5% = 500 > 64，走比例");
            assertEquals(264, judge(200, 0).allowed(), "0.5% = 1 < 64，走绝对值");
        }

        @Test
        @DisplayName("★「往 prompt 加 5 行」这种量级必须当场判红")
        void catchesRealisticBloat() {
            // 实测数据：加 5 行说明 = 每次调用 +149 字符。
            // 单次调用的用例：13280 → 13429，仅 +1.12%。
            // 设计初稿的 20% 容差会连续放过十几次这样的改动，所以砍到了 0.5%。
            var v = judge(13_280, 13_429);
            assertEquals(BudgetGate.Status.OVER, v.status(),
                    "1.1% 的涨幅必须被抓住——prompt 膨胀正是这样一点点攒出来的");
            assertTrue(v.delta() < 0.02, "而这只是 1% 出头，可见门禁得多严才拦得住");
        }

        @Test
        @DisplayName("★下降不判红，但显著下降要提示基线该收——否则预算只涨不跌")
        void dropFlagsStaleBaseline() {
            assertEquals(BudgetGate.Status.OK, judge(10_000, 9_500).status(),
                    "小幅波动不该产生噪声提示");
            assertEquals(BudgetGate.Status.STALE, judge(10_000, 7_000).status(),
                    "优化掉 30% 之后基线仍停在旧值，等于白送了 30% 的膨胀空间");
        }

        @Test
        @DisplayName("基线为 0 时不做除零")
        void zeroBaseline() {
            assertEquals(BudgetGate.Status.OK, judge(0, 0).status());
            assertNull(judge(0, 1).delta(), "基线为 0 时涨幅无定义，不能返回 Infinity");
        }

        @Test
        @DisplayName("★llmCalls 零容忍：多调一次就是行为变了，不该按百分比打折")
        void llmCallsHaveNoTolerance() {
            assertEquals(BudgetGate.Status.OK,
                    BudgetGate.judge("c", "llmCalls", 3, 3).status());
            assertEquals(BudgetGate.Status.OVER,
                    BudgetGate.judge("c", "llmCalls", 3, 4).status(),
                    "3→4 按比例只有 +33%，任何百分比容差都会放过它；"
                            + "但在小整数上算百分比本来就没有意义");
        }
    }

    @Nested
    @DisplayName("覆盖与缺口")
    class Coverage {

        @Test
        @DisplayName("★新用例未登记基线：报 UNTRACKED，不判红")
        void newCaseIsNotAFailure() {
            var verdicts = BudgetGate.check(
                    Map.of(),
                    Map.of("brand_new", Map.of("requestChars", 9_999L)));

            assertTrue(verdicts.stream().allMatch(v -> v.status() == BudgetGate.Status.UNTRACKED));
            assertTrue(BudgetGate.overruns(verdicts).isEmpty(),
                    "新加用例就判红，只会让人为了绿而先去改基线，门禁就废了");
        }

        @Test
        @DisplayName("★基线里有、本次没跑到：报 MISSING，不判红也不静默")
        void skippedCaseIsReportedNotIgnored() {
            var verdicts = BudgetGate.check(
                    Map.of("gone", Map.of("requestChars", 100L)),
                    Map.of());

            assertEquals(BudgetGate.Status.MISSING, verdicts.get(0).status());
            assertTrue(BudgetGate.overruns(verdicts).isEmpty());
        }

        @Test
        @DisplayName("只挑出真正超支的项")
        void overrunsFiltersCorrectly() {
            var verdicts = BudgetGate.check(
                    Map.of("a", Map.of("requestChars", 1_000L),
                            "b", Map.of("requestChars", 1_000L)),
                    Map.of("a", Map.of("requestChars", 5_000L),
                            "b", Map.of("requestChars", 1_000L)));

            var over = BudgetGate.overruns(verdicts);
            assertEquals(1, over.size());
            assertEquals("a", over.get(0).caseId());
        }

        @Test
        @DisplayName("失败信息必须写清怎么更新基线")
        void renderTellsHowToFix() {
            String text = BudgetGate.render(BudgetGate.overruns(BudgetGate.check(
                    Map.of("a", Map.of("requestChars", 1_000L)),
                    Map.of("a", Map.of("requestChars", 5_000L)))));

            assertTrue(text.contains("agent.eval.budget=write"),
                    "不给出更新命令，下一个撞上它的人只会把断言注释掉");
        }
    }

    @Nested
    @DisplayName("基线文件")
    class Baseline {

        @Test
        @DisplayName("★跑局部套件写基线时，未跑到的用例必须原样保留——否则门禁会静默消失")
        void writeMergesInsteadOfReplacing() {
            Map<String, Map<String, Long>> existing = Map.of(
                    "regression_a", Map.of("requestChars", 1000L),
                    "regression_b", Map.of("requestChars", 2000L));
            // 模拟只跑了能力集：实测里一条回归用例都没有
            Map<String, Map<String, Long>> capabilityOnly = Map.of(
                    "capability_x", Map.of("requestChars", 500L));

            var merged = BudgetBaseline.merge(existing, capabilityOnly);

            assertTrue(merged.containsKey("regression_a"),
                    "跑能力集写基线把回归集的门禁抹掉了——此后它们全判 UNTRACKED，"
                            + "也就是不判，而且没有任何提示");
            assertTrue(merged.containsKey("regression_b"));
            assertTrue(merged.containsKey("capability_x"), "本次实测值应写入");
            assertEquals(3, merged.size());
        }

        @Test
        @DisplayName("同名用例以本次实测为准——合并不能变成「基线永远改不动」")
        void writeOverwritesSameCase() {
            var merged = BudgetBaseline.merge(
                    Map.of("a", Map.of("requestChars", 1000L)),
                    Map.of("a", Map.of("requestChars", 1500L)));

            assertEquals(1500L, merged.get("a").get("requestChars"),
                    "保守到连本次实测都覆盖不了的话，基线就再也更新不了了");
        }

        @Test
        @DisplayName("未跑到的用例会被列出来，不静默保留")
        void keptCasesAreReported() {
            var kept = BudgetBaseline.keptFrom(
                    Map.of("a", Map.of("requestChars", 1L), "b", Map.of("requestChars", 1L)),
                    Map.of("a", Map.of("requestChars", 1L)));

            assertEquals(Set.of("b"), kept,
                    "保留是为了不误删门禁，但保留了什么必须说出来——"
                            + "否则删掉的用例会永远滞留在基线里没人发现");
        }
    }

    @Nested
    @DisplayName("活体字符度量")
    class LiveMeasurement {

        @Test
        @DisplayName("★度量的是当前真实发出的 messages，prompt 变长立刻体现")
        void measuresActualRequest() {
            long lean = UsageAccumulator.measureRequestChars(request("你是一个助手"));
            long bloated = UsageAccumulator.measureRequestChars(
                    request("你是一个助手。另外请注意以下二十条补充说明……"));

            assertTrue(bloated > lean,
                    "这是回放模式下唯一能发现 prompt 膨胀的信号——"
                            + "回放返回的 token 数来自录制盒，改 prompt 也不会变");
        }

        @Test
        @DisplayName("累计器在多次调用间累加，reset 后归零")
        void accumulatesAndResets() {
            var acc = new UsageAccumulator();
            acc.observe(request("abcde"), org.zhzssp.memorandum.feature.agent.llm.transport
                    .TokenUsage.ABSENT, "xy", null);
            acc.observe(request("abcde"), org.zhzssp.memorandum.feature.agent.llm.transport
                    .TokenUsage.ABSENT, "xy", null);

            var snap = acc.snapshot();
            assertEquals(2, snap.llmCalls());
            assertEquals(0, snap.callsWithUsage());
            assertEquals(4, snap.responseChars());
            assertTrue(snap.requestChars() >= 10);

            acc.reset();
            assertEquals(0, acc.snapshot().llmCalls());
        }
    }

    private static LlmTransport.ChatRequest request(String system) {
        return new LlmTransport.ChatRequest(
                "http://x", "k", "m",
                List.of(Map.of("role", "system", "content", system)),
                0.0, 30, LlmTransport.Purpose.CHAT);
    }
}
