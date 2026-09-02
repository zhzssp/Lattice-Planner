package org.zhzssp.memorandum.agenteval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.zhzssp.memorandum.agenteval.rag.GoldenSet.QuestionType;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4 · RAG 检索质量量化评测。
 *
 * <h3>它和 {@code AgentTrajectoryEvalTest} 的分工</h3>
 * 轨迹评测问的是"Agent 选对工具了吗"，本测试问的是"<b>知识库把该给的内容给出来了吗</b>"。
 * 二者必须分开：<b>混成一个"RAG 好不好"的数字，分数掉了你不知道该调检索还是调 prompt。</b>
 *
 * <h3>为什么这里把 alpha 调成 1.0</h3>
 * 见 {@link StructuralLimits#vectorOnlyScoreCeilingSitsOnThreshold}：
 * 关键字通路在 H2 上是死的，保持 {@code alpha=0.4} 会让所有分数恒等于
 * "0.4 × 余弦"，上限正好压在 AMBIGUOUS/INCORRECT 的边界上。
 * 本类要量的是<b>召回与排序</b>，需要一个不被阈值噪声干扰的分数区间；
 * 阈值标定本身由 {@link RagDegradeCalibrationTest} 用<b>生产默认配置</b>单独考察。
 *
 * <p>把 alpha 换成 1.0 <b>不影响召回率与 MRR</b>——它对向量分数是个单调缩放因子，
 * 排序不变。受影响的只有绝对分数，也就是分级结果。
 */
@TestPropertySource(properties = "pkm.rag.alpha=1.0")
@DisplayName("P4 · RAG 检索金标集")
class RagGoldenEvalTest extends RagEvalBase {

    private static final String REPORT_PATH = "build/agent-eval/rag-golden.json";

    private static RagGoldenReport report;

    @Test
    @DisplayName("金标集：单跳/多跳召回率与不可回答降级率")
    void goldenSetScores() {
        report = runGoldenSet();
        System.out.println(report.render());

        // ---- 可回答类：该召的必须召回来 ----
        assertThat(report.meanRecall(QuestionType.SINGLE_HOP))
                .as("单跳题的相关笔记必须全部出现在 top-%d 里", goldenSet.topK())
                .isEqualTo(1.0);
        assertThat(report.meanRecall(QuestionType.MULTI_HOP))
                .as("多跳题需要综合多条笔记，漏掉任何一条都会让生成端无米下炊")
                .isEqualTo(1.0);
        assertThat(report.meanReciprocalRank(QuestionType.SINGLE_HOP))
                .as("单跳题的正确答案应当排第一。MRR 掉下来说明排序在退化，"
                        + "而召回率对此完全不敏感")
                .isEqualTo(1.0);

        // ---- 不可回答类：库里没有时不许硬编 ----
        assertThat(report.correctDegradeRate())
                .as("库里根本没有的问题必须被判降级——这是『不编造』的唯一量化保证。"
                        + "而且这次是靠真实检索打分得出的，不是靠 mock 返回空列表")
                .isEqualTo(1.0);
    }

    /**
     * <b>这套指标不是空转的</b>——抽掉一条相关笔记的向量，召回率立刻掉下来。
     *
     * <h3>为什么非要有这条</h3>
     * 全绿的评测报告本身就是个可疑信号。本项目已经栽过一次：
     * 九个用例长期全绿，实际上所有写工具都因外键缺失而失败，
     * 断言只问"工具被调用过吗"，没人问"它成功了吗"。
     *
     * <p>金标集同样有这个风险：如果指标算错、语料没落库、或者相关性判定写反了，
     * 报告照样会印出一串好看的数字。<b>唯一的破法是让评测自己证明它能变红。</b>
     * 这条用例故意制造一次退化，并要求指标必须捕捉到——
     * 它守的不是产品，是<b>评测本身的有效性</b>。
     */
    @Test
    @DisplayName("金标集能测出退化：抽掉相关笔记的向量后召回率必须下降")
    void detectsRetrievalRegression() {
        double before = runGoldenSet().meanRecall(QuestionType.SINGLE_HOP);
        assertThat(before).isEqualTo(1.0);

        GoldenSet.Question q = goldenSet.byType(QuestionType.SINGLE_HOP).get(0);
        Long target = q.relevantNoteIds().iterator().next();
        embeddingRepository.deleteAll(embeddingRepository.findByUserId(USER_ID).stream()
                .filter(e -> target.equals(e.getNoteId()))
                .toList());
        vectorCache.invalidate(USER_ID);

        RagGoldenReport after = runGoldenSet();
        assertThat(after.meanRecall(QuestionType.SINGLE_HOP))
                .as("笔记 %d 的向量被抽掉后，「%s」就再也召不回来了。"
                        + "召回率若纹丝不动，说明这套指标根本没在量检索",
                        target, q.question())
                .isLessThan(before);

        RagGoldenReport.Row row = after.rows().stream()
                .filter(r -> r.questionId().equals(q.id())).findFirst().orElseThrow();
        assertThat(row.metrics().recallAtK())
                .as("受影响的正是那一题，而不是别处的连带变化")
                .isEqualTo(0.0);
    }

    /* =====================================================================
     * 结构性限制：把产品与评测环境的既有缺陷变成显式断言
     * ===================================================================== */

    @Nested
    @DisplayName("结构性限制")
    class StructuralLimits {

        /**
         * 检索<b>没有分数下限</b>，零相似度的笔记照样会被塞进上下文。
         *
         * <p>{@code RagSearchService} 结尾是无条件的 {@code .limit(k)}：
         * 只要库里有 k 条笔记，就一定返回 k 条，哪怕后几条余弦为 0。
         * CRAG 的分级又只看 {@code hits.get(0)} 的最高分，
         * 于是<b>低质片段一路畅通地进了模型的上下文窗口</b>。
         *
         * <p>代价是双份的：占用上下文预算，还给模型提供了编造的素材。
         * 加一条分数下限就能解决——这条断言正是为了让那个改动有据可依。
         * <b>改完之后本断言会变红，那时该更新的是断言，而不是绕过它。</b>
         */
        @Test
        @DisplayName("没有分数下限：零相似度的笔记也会占满 top-k")
        void noScoreFloor() {
            GoldenSet.Question q = goldenSet.byType(QuestionType.SINGLE_HOP).get(0);
            List<RagSearchService.Hit> hits = rag.search(user, q.question(), goldenSet.topK());

            assertThat(hits)
                    .as("库里有 %d 条笔记，取 top-%d 就一定填满",
                            goldenSet.corpus().size(), goldenSet.topK())
                    .hasSize(goldenSet.topK());

            long zeroScore = hits.stream().filter(h -> h.score() <= 1e-9).count();
            assertThat(zeroScore)
                    .as("单跳题只有 1 条相关笔记，其余位置被余弦为 0 的噪声填满——"
                            + "这些片段会原样进入模型上下文")
                    .isGreaterThan(0);
        }

        /**
         * 关键字通路在 H2 上是死的，而且是<b>静默</b>死的。
         *
         * <p>{@code NoteRepository.fulltextSearch} 用的是 MySQL 专有的
         * {@code MATCH ... AGAINST}，H2 直接抛 SQLException，
         * 被 {@code RagSearchService} 的 try/catch 吞掉后只剩一行 debug 日志。
         *
         * <p>断言它，是为了让"评测环境测不到关键字召回"这件事
         * 从一个没人知道的事实，变成一条写在代码里的约定。
         * 将来接了 Testcontainers MySQL，这条会变红并提示：可以开启真实的双通路评测了。
         */
        @Test
        @DisplayName("关键字通路在 H2 上不可用，且失败是静默的")
        void keywordPathUnavailableOnH2() {
            assertThatThrownBy(() -> noteRepository.fulltextSearch(USER_ID, "Kafka", 10))
                    .as("MATCH ... AGAINST 是 MySQL 专有语法")
                    .isInstanceOf(Exception.class);

            // 而 search() 完全不体现这次失败：调用方拿到的结果里没有任何降级信号
            List<RagSearchService.Hit> hits =
                    rag.search(user, "Kafka 消费者组是怎么分配分区的？", 3);
            assertThat(hits).isNotEmpty();
            assertThat(hits).allSatisfy(h -> assertThat(h.reason())
                    .as("命中理由里只有 vec 没有 kw——整条关键字通路缺席，但没有任何人被告知")
                    .startsWith("vec"));
        }

        /**
         * 生产配置下，纯向量通路<b>永远达不到 CORRECT</b>。
         *
         * <p>{@code pkm.rag.alpha=0.4} 意味着向量分量的上限就是 {@code 0.4 × 1.0 = 0.4}，
         * 而 {@code pkm.crag.upper=0.6}。也就是说：只要关键字通路缺席
         * （H2 评测环境、线上 FULLTEXT 索引未建、或查询被 ngram 切没了），
         * <b>再完美的语义匹配也顶多被判到 AMBIGUOUS</b>。
         *
         * <p>更麻烦的是 0.4 恰好等于 {@code pkm.crag.lower}：
         * 判 AMBIGUOUS 还是 INCORRECT 完全取决于浮点误差往哪边偏。
         * 这不是评测环境的问题，是<b>权重与阈值没有一起标定</b>——
         * 两个参数分别看都合理，乘到一起就穿帮了。后果见 {@link RagDegradeCalibrationTest}。
         */
        @Test
        @DisplayName("alpha=0.4 时纯向量分数上限 0.4，恰好压在降级阈值上")
        void vectorOnlyScoreCeilingSitsOnThreshold() {
            double productionAlpha = 0.4;
            double ceiling = productionAlpha * 1.0;

            assertThat(ceiling)
                    .as("向量通路的分数上限 = alpha × 余弦上限")
                    .isLessThan(evaluator.getUpper());
            assertThat(ceiling)
                    .as("而且正好落在 lower(%.2f) 上：判 AMBIGUOUS 还是 INCORRECT "
                            + "由浮点误差决定，不由检索质量决定", evaluator.getLower())
                    .isEqualTo(evaluator.getLower());
        }
    }

    @AfterAll
    static void dumpReport() {
        if (report == null) return;
        try {
            Path out = Path.of(REPORT_PATH);
            Files.createDirectories(out.getParent());
            Files.writeString(out, new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report.toMap()));
            System.out.println("[RAG] 金标集报告已写入 " + out.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[RAG] 报告落盘失败：" + e.getMessage());
        }
    }
}
