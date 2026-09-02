package org.zhzssp.memorandum.agenteval.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RetrievalMetrics} 的算术验证。
 *
 * <p>指标本身出错是最难发现的一类 bug：报告照常生成、数字看着合理，
 * 但所有基于它的结论都是错的。所以指标必须是<b>可独立验证的纯函数</b>。
 */
@DisplayName("检索指标算术")
class RetrievalMetricsTest {

    @Nested
    @DisplayName("召回率与精确率")
    class RecallPrecision {

        @Test
        @DisplayName("全部命中且无噪声：两者都是 1")
        void perfect() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(1L, 2L), Set.of(1L, 2L), 2);
            assertThat(m.recallAtK()).isEqualTo(1.0);
            assertThat(m.precisionAtK()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("召回全但夹带噪声：召回 1、精确率被稀释")
        void recallFullPrecisionDiluted() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(1L, 8L, 9L, 7L), Set.of(1L), 4);
            assertThat(m.recallAtK()).isEqualTo(1.0);
            assertThat(m.precisionAtK()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("相关文档排在 k 之外就等于没召回")
        void beyondKIsMissed() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(8L, 9L, 7L, 1L), Set.of(1L), 3);
            assertThat(m.recallAtK())
                    .as("截断到 top-3，第 4 位的相关文档根本进不了上下文")
                    .isEqualTo(0.0);
            assertThat(m.firstHitRank()).isEqualTo(-1);
        }

        @Test
        @DisplayName("多跳只召回一半：召回率 0.5")
        void partialMultiHop() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(1L, 8L), Set.of(1L, 2L), 6);
            assertThat(m.recallAtK()).isEqualTo(0.5);
            assertThat(m.hitCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("检索返回为空：召回率 0，精确率按 0 处理而不是 NaN")
        void emptyRetrieval() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(), Set.of(1L), 6);
            assertThat(m.recallAtK()).isEqualTo(0.0);
            assertThat(m.precisionAtK())
                    .as("0/0 若返回 NaN，会污染整个平均值")
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("倒数排名")
    class ReciprocalRank {

        @Test
        @DisplayName("排第一是 1，排第三是 1/3")
        void ranks() {
            assertThat(RetrievalMetrics.of(List.of(1L, 8L, 9L), Set.of(1L), 3).reciprocalRank())
                    .isEqualTo(1.0);
            assertThat(RetrievalMetrics.of(List.of(8L, 9L, 1L), Set.of(1L), 3).reciprocalRank())
                    .isEqualTo(round4(1.0 / 3));
        }

        @Test
        @DisplayName("召回率相同但排序不同，MRR 能区分出来")
        void mrrSeesWhatRecallCannot() {
            RetrievalMetrics good = RetrievalMetrics.of(List.of(1L, 8L, 9L, 7L, 6L, 5L), Set.of(1L), 6);
            RetrievalMetrics bad = RetrievalMetrics.of(List.of(8L, 9L, 7L, 6L, 5L, 1L), Set.of(1L), 6);

            assertThat(good.recallAtK()).isEqualTo(bad.recallAtK());
            assertThat(good.precisionAtK()).isEqualTo(bad.precisionAtK());
            assertThat(good.reciprocalRank())
                    .as("排第 1 和排第 6 的实际效果天差地别，只有 MRR 反映得出来")
                    .isGreaterThan(bad.reciprocalRank());
        }

        @Test
        @DisplayName("一条都没命中：倒数排名 0")
        void noHit() {
            RetrievalMetrics m = RetrievalMetrics.of(List.of(8L, 9L), Set.of(1L), 6);
            assertThat(m.reciprocalRank()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("不可回答类必须被挡在门外")
    class Unanswerable {

        /**
         * 相关集为空时不该退化成"满分"或 NaN，而应当直接拒绝计算。
         *
         * <p>这不是吹毛求疵：若让它返回 1.0，一个<b>从不召回任何东西</b>的系统
         * 会在全部不可答题上拿满分，把平均召回率拉高。
         * 那类问题该度量的是"有没有正确降级"，是另一个维度。
         */
        @Test
        @DisplayName("相关集为空时拒绝计算召回率")
        void refusesEmptyRelevantSet() {
            assertThatThrownBy(() -> RetrievalMetrics.of(List.of(8L, 9L), Set.of(), 6))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("正确降级");
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
