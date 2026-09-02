package org.zhzssp.memorandum.agenteval.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.rag.GoldenSet.QuestionType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 · 降级阈值标定检查——<b>本类跑的是生产默认配置，刻意不覆盖任何参数</b>。
 *
 * <h3>它曾经查出什么</h3>
 * {@code pkm.rag.alpha=0.4} × {@code pkm.crag.lower=0.4} 这对参数，
 * 在<b>关键字通路缺席</b>时会让检索得分的上限正好压在降级阈值上。
 * 于是相关笔记明明排在第 1、2 位，系统却告诉用户"没找到，以下基于通用知识"——
 * 4 道多跳题<b>全部</b>中招。
 *
 * <p>这就是所谓<b>假降级</b>。它比漏召更难发现：
 * 漏召至少答案是空的，假降级则会给出一个<b>看起来合理、但主动放弃了你的笔记</b>的答案。
 * 用户不会去投诉，只会觉得"这个知识库好像没什么用"。
 *
 * <h3>修法与本类现在的职责</h3>
 * 根因是<b>量纲错配</b>：{@code score} 是排序分数（含 alpha 权重、随命中通路浮动），
 * 却被拿去比一个表达"语义相关度"的固定阈值。修法是让
 * {@link org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator} 改判
 * {@code relevance}（余弦，与 alpha 和通路都无关），而不是去挪 alpha 或 lower——
 * 挪参数只能让这一组数据碰巧不出事，换个 topK 或换个模型又会复发。
 *
 * <p>断言已按原计划<b>反转</b>：从"证明缺陷存在"变成"守住缺陷不复发"。
 * 它守的那条线一直都在，所以不删。<b>任何让判级重新依赖 alpha 的改动，
 * 都会在这里变红。</b>
 */
@DisplayName("P4 · 降级阈值标定（生产默认配置）")
class RagDegradeCalibrationTest extends RagEvalBase {

    @Test
    @DisplayName("生产配置下多跳问题不再被假降级")
    void multiHopIsNotFalselyDegradedUnderProductionConfig() {
        RagGoldenReport report = runGoldenSet();
        System.out.println(report.render());

        // 前提：召回本身是好的。假降级不是"没找到"，是"找到了却说没找到"
        assertThat(report.meanRecall(QuestionType.MULTI_HOP))
                .as("相关笔记确实都召回来了——这曾经正是问题所在")
                .isEqualTo(1.0);

        List<RagGoldenReport.Row> multiHop = report.byType(QuestionType.MULTI_HOP);
        long degraded = multiHop.stream().filter(RagGoldenReport.Row::degraded).count();

        assertThat(degraded)
                .as("判级已改用与 alpha 无关的 relevance。若这里重新变成 %d，"
                        + "说明判级又和排序分数耦合上了——查 RetrievalEvaluator.grade "
                        + "是不是被改回读 score()", multiHop.size())
                .isZero();

        assertThat(report.falseDegradeRate())
                .as("可回答的题一道都不该被判降级")
                .isEqualTo(0.0);
    }

    /**
     * 收紧降级不能以放过"不可答"为代价。
     *
     * <p>这两个指标必须配着看，就像精确率之于召回率：
     * 只看正确降级率，一个"永远降级"的系统满分；
     * 只看假降级率，一个"从不降级"的系统满分。
     * 修假降级最容易的走偏方式，就是把阈值调松到什么都不降级——
     * <b>这条断言就是拦这个的。</b>
     */
    @Test
    @DisplayName("收紧降级没有放过『库里根本没有』的问题")
    void correctDegradeRateStillHolds() {
        RagGoldenReport report = runGoldenSet();

        assertThat(report.correctDegradeRate())
                .as("库里没有的问题必须照样被降级。这个数掉下来，"
                        + "说明假降级是靠『把阈值调到什么都不降级』换来的")
                .isEqualTo(1.0);
        assertThat(report.falseDegradeRate())
                .as("而同一次运行里，该召回的题一道都没被误伤")
                .isEqualTo(0.0);
    }
}
