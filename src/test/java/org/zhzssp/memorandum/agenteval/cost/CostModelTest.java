package org.zhzssp.memorandum.agenteval.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.feature.agent.llm.transport.TokenUsage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本折算的算术验证（P5）。
 *
 * <p>与 {@code ReliabilityMetrics} / {@code TrajectoryMetrics} / {@code RetrievalMetrics} 一致：
 * <b>指标算术必须能脱离 Spring 单测</b>。一个只在完整评测跑起来时才被间接验证的指标，
 * 算错了没人会发现——它只会安静地输出一个看着挺合理的数字。
 *
 * <p>价表<b>一律用测试内构造的</b>，不读真实的 {@code pricing.json}：
 * 否则真实价格一变，这些断言就集体变红，而它们本来要验的是<b>折算逻辑</b>，
 * 不是"今天 DeepSeek 卖多少钱"。
 */
@DisplayName("P5 · 成本折算")
class CostModelTest {

    /** 好记的整数价：输入未命中 $10/M、命中 $1/M、输出 $100/M。 */
    private static final PriceTable PRICES = PriceTable.of(
            Map.of("known-model", new PriceTable.Rate(10.0, 1.0, 100.0)),
            new PriceTable.Rate(1000.0, 100.0, 10000.0));

    private static UsageSnapshot usage(long prompt, long completion, long hit, long miss) {
        return new UsageSnapshot(1, 1, prompt, completion, hit, miss, 0, 0, null);
    }

    @Nested
    @DisplayName("折算")
    class Arithmetic {

        @Test
        @DisplayName("三档分别按各自单价折算")
        void perRateArithmetic() {
            // 命中 1M @ $1、未命中 2M @ $10、输出 0.5M @ $100
            var c = CostModel.estimate(
                    usage(3_000_000, 500_000, 1_000_000, 2_000_000), "known-model", PRICES);

            assertEquals(1.0, c.inputCacheHitUsd(), 1e-9);
            assertEquals(20.0, c.inputCacheMissUsd(), 1e-9);
            assertEquals(50.0, c.outputUsd(), 1e-9);
            assertEquals(71.0, c.totalUsd(), 1e-9);
        }

        @Test
        @DisplayName("★缓存省下的钱 = 命中量 ×（未命中价 − 命中价）")
        void savedByCache() {
            var c = CostModel.estimate(
                    usage(1_000_000, 0, 1_000_000, 0), "known-model", PRICES);

            // 1M token 命中：实付 $1，若未命中要付 $10，省下 $9
            assertEquals(1.0, c.totalUsd(), 1e-9);
            assertEquals(9.0, c.savedByCacheUsd(), 1e-9);
            assertEquals(10.0, c.totalWithoutCacheUsd(), 1e-9);
        }

        @Test
        @DisplayName("零命中时省下的钱为 0，而不是负数")
        void noCacheNoSaving() {
            var c = CostModel.estimate(
                    usage(1_000_000, 0, 0, 1_000_000), "known-model", PRICES);
            assertEquals(0.0, c.savedByCacheUsd(), 1e-9);
            assertEquals(c.totalUsd(), c.totalWithoutCacheUsd(), 1e-9);
        }

        @Test
        @DisplayName("输入按「命中+未命中」算，不按 promptTokens——分项之和必须等于总数")
        void inputDerivedFromSplitNotPromptTokens() {
            // 故意让 promptTokens 与拆分不自洽（上游偶尔会这样）
            var c = CostModel.estimate(
                    usage(999_999_999, 0, 1_000_000, 1_000_000), "known-model", PRICES);
            // 只认拆分：1M×$1 + 1M×$10
            assertEquals(11.0, c.totalUsd(), 1e-9);
        }
    }

    @Nested
    @DisplayName("未知模型")
    class UnknownModel {

        @Test
        @DisplayName("★按 fallback 高估计价，绝不按 0")
        void fallbackNotZero() {
            var c = CostModel.estimate(
                    usage(1_000_000, 0, 0, 1_000_000), "some-new-model", PRICES);

            assertEquals(1000.0, c.inputCacheMissUsd(), 1e-9,
                    "未知模型按 0 计价会让成本门禁静默失效，报告上是一个漂亮的 $0");
            assertTrue(c.pricedByFallback(), "必须能被报告标注出来，否则数字会被当成实价");
        }

        @Test
        @DisplayName("已登记模型不标 fallback")
        void knownModelNotFlagged() {
            assertFalse(CostModel.estimate(usage(1, 1, 1, 0), "known-model", PRICES)
                    .pricedByFallback());
        }

        @Test
        @DisplayName("模型名为 null 也走 fallback，不抛异常")
        void nullModel() {
            var c = CostModel.estimate(usage(1_000_000, 0, 0, 1_000_000), null, PRICES);
            assertTrue(c.pricedByFallback());
        }
    }

    @Nested
    @DisplayName("UsageSnapshot 的口径")
    class SnapshotSemantics {

        @Test
        @DisplayName("★统计不完整时 charsPerPromptToken 返回 null，而不是一个偏大的比值")
        void incompleteUsageYieldsNoRatio() {
            // 3 次调用只有 1 次带回 usage：字符数是 3 次的，token 只有 1 次的
            var partial = new UsageSnapshot(3, 1, 100, 10, 100, 0, 3000, 300, null);
            assertNull(partial.charsPerPromptToken(),
                    "分子分母来自不同的调用集合，算出来的比值是错的，宁可不给");
            assertFalse(partial.usageComplete());
        }

        @Test
        @DisplayName("统计完整时给出实测字符/token 比")
        void completeUsageYieldsRatio() {
            var full = new UsageSnapshot(2, 2, 1000, 100, 1000, 0, 2000, 200, null);
            assertEquals(2.0, full.charsPerPromptToken(), 1e-9);
        }

        @Test
        @DisplayName("★没有 token 数据时命中率返回 null，不是 0")
        void cacheHitRateNullWhenNoData() {
            assertNull(UsageSnapshot.EMPTY.cacheHitRate(),
                    "「没测到」和「命中率是 0」是两回事，混淆会让人以为缓存完全没生效");
            assertEquals(0.75, new UsageSnapshot(1, 1, 100, 0, 75, 25, 0, 0, null)
                    .cacheHitRate(), 1e-9);
        }

        @Test
        @DisplayName("相加：延迟只在两边都有时求和，单边缺失不当 0 处理")
        void plusHandlesLatency() {
            var withLat = new UsageSnapshot(1, 1, 10, 1, 10, 0, 100, 10, 500L);
            var noLat = new UsageSnapshot(1, 1, 10, 1, 10, 0, 100, 10, null);

            assertEquals(500L, withLat.plus(noLat).upstreamLatencyMs());
            assertEquals(1000L, withLat.plus(withLat).upstreamLatencyMs());
            assertNull(noLat.plus(noLat).upstreamLatencyMs());
        }
    }

    @Nested
    @DisplayName("TokenUsage 解析（生产代码，评测与线上共用）")
    class Parsing {

        private static final ObjectMapper OM = new ObjectMapper();

        private static TokenUsage parse(String json) throws Exception {
            return TokenUsage.parse(OM.readTree(json));
        }

        @Test
        @DisplayName("DeepSeek 格式：hit / miss 直接给出")
        void deepseekFormat() throws Exception {
            var u = parse("""
                    {"prompt_tokens":3120,"completion_tokens":26,
                     "prompt_cache_hit_tokens":3072,"prompt_cache_miss_tokens":48}""");
            assertTrue(u.present());
            assertEquals(3072, u.cacheHitTokens());
            assertEquals(48, u.cacheMissTokens());
            assertEquals(26, u.completionTokens());
        }

        @Test
        @DisplayName("OpenAI 格式：只给 cached_tokens，未命中数用相减补出")
        void openAiFormat() throws Exception {
            var u = parse("""
                    {"prompt_tokens":1000,"completion_tokens":50,
                     "prompt_tokens_details":{"cached_tokens":600}}""");
            assertEquals(600, u.cacheHitTokens());
            assertEquals(400, u.cacheMissTokens());
        }

        @Test
        @DisplayName("★只有 token 数、没有缓存信息时，全部记为未命中（成本宁可高估）")
        void noCacheInfoCountsAsMiss() throws Exception {
            var u = parse("{\"prompt_tokens\":800,\"completion_tokens\":20}");
            assertTrue(u.present());
            assertEquals(0, u.cacheHitTokens());
            assertEquals(800, u.cacheMissTokens(),
                    "缓存维度未知时按未命中计价是保守方向；低估的成本指标没有门禁价值");
        }

        @Test
        @DisplayName("★缺 usage 返回 ABSENT，present=false——不能与「全是 0」混为一谈")
        void absentIsNotZero() throws Exception {
            assertFalse(TokenUsage.parse(null).present());
            assertFalse(parse("{}").present());
            assertFalse(TokenUsage.ABSENT.present());
        }

        @Test
        @DisplayName("字段类型异常不抛，退化为 ABSENT——观测不能打断主链路")
        void malformedIsTolerated() throws Exception {
            assertFalse(parse("{\"prompt_tokens\":\"not-a-number\"}").present());
        }
    }
}
