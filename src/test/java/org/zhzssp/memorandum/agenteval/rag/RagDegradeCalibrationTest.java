package org.zhzssp.memorandum.agenteval.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.rag.GoldenSet.QuestionType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 · 降级阈值标定检查——<b>本类跑的是生产默认配置，刻意不覆盖任何参数</b>。
 *
 * <h3>它查出了什么</h3>
 * {@code pkm.rag.alpha=0.4} × {@code pkm.crag.lower=0.4} 这对参数，
 * 在<b>关键字通路缺席</b>时会让检索得分的上限正好压在降级阈值上。
 * 于是相关笔记明明排在第 1、2 位，系统却告诉用户"没找到，以下基于通用知识"。
 *
 * <p>这就是所谓<b>假降级</b>。它比漏召更难发现：
 * 漏召至少答案是空的，假降级则会给出一个<b>看起来合理、但主动放弃了你的笔记</b>的答案。
 * 用户不会去投诉，只会觉得"这个知识库好像没什么用"。
 *
 * <h3>为什么把缺陷写成断言，而不是先修</h3>
 * 修法有好几种（提高 alpha、下调 lower、给纯向量场景单独标定、
 * 或者干脆让关键字通路缺席时走另一套阈值），选哪种得先量清楚代价。
 * 在那之前，<b>这条断言是这个缺陷唯一的书面记录</b>，
 * 而且它是活的：参数一改它就说话。
 *
 * <p><b>修好之后应当把断言反过来</b>（改成 {@code isEqualTo(0.0)}），
 * 而不是删掉——它守的那条线一直都在。
 */
@DisplayName("P4 · 降级阈值标定（生产默认配置）")
class RagDegradeCalibrationTest extends RagEvalBase {

    @Test
    @DisplayName("生产配置下多跳问题会被 100% 假降级")
    void multiHopIsFalselyDegradedUnderProductionConfig() {
        RagGoldenReport report = runGoldenSet();
        System.out.println(report.render());

        // 前提：召回本身是好的。假降级不是"没找到"，是"找到了却说没找到"
        assertThat(report.meanRecall(QuestionType.MULTI_HOP))
                .as("相关笔记确实都召回来了——这正是问题所在")
                .isEqualTo(1.0);

        List<RagGoldenReport.Row> multiHop = report.byType(QuestionType.MULTI_HOP);
        long degraded = multiHop.stream().filter(RagGoldenReport.Row::degraded).count();

        assertThat(degraded)
                .as("多跳题的最高分 = alpha(0.4) × 余弦(<1)，必然小于 lower(%.2f)，"
                        + "于是全部被判 INCORRECT。相关笔记就排在第 1、2 位，"
                        + "系统却会说『我没在你的笔记里找到』",
                        evaluator.getLower())
                .isEqualTo(multiHop.size());

        // 单跳题侥幸逃过，只是因为余弦恰好是 1.0，得分正好落在阈值上——靠的是浮点运气
        assertThat(report.falseDegradeRate())
                .as("假降级率 = %.2f。它不是 1.0 只是因为单跳题的余弦恰好为 1.0，"
                        + "分数不多不少正好等于阈值。这是浮点运气，不是设计",
                        report.falseDegradeRate())
                .isGreaterThan(0.0);
    }

    /**
     * 不可回答类不受这个缺陷影响——它们本来就该降级。
     *
     * <p>单独断言一次，是为了说明假降级<b>不能靠"正确降级率"发现</b>：
     * 这个数在缺陷存在时依然是满分。两个指标必须配着看，
     * 就像精确率之于召回率。
     */
    @Test
    @DisplayName("正确降级率看不出这个缺陷：它在缺陷存在时依然满分")
    void correctDegradeRateHidesTheDefect() {
        RagGoldenReport report = runGoldenSet();

        assertThat(report.correctDegradeRate())
                .as("库里没有的问题照样被正确降级")
                .isEqualTo(1.0);
        assertThat(report.falseDegradeRate())
                .as("而同一次运行里，该召回的题也在被降级。"
                        + "只报正确降级率的话，这块缺陷是完全看不见的")
                .isGreaterThan(0.0);
    }
}
