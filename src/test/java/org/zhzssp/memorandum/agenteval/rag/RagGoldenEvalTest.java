package org.zhzssp.memorandum.agenteval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zhzssp.memorandum.agenteval.rag.GoldenSet.QuestionType;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator;
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
 * <h3>本类跑的是生产默认配置</h3>
 * 早先这里必须覆盖 {@code pkm.rag.alpha=1.0} 才能量出干净的分数：当时判级读的是
 * 排序分数，{@code alpha=0.4} 会把上限压在 AMBIGUOUS/INCORRECT 的边界上。
 * 判级改用与 alpha 无关的 {@code relevance} 之后，这个覆盖就没必要了——
 * <b>能删掉一个"为了让测试好看而加的配置覆盖"，本身就是修对了的信号。</b>
 */
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
         * 分数下限已生效：零相似度的笔记不再被塞进上下文。
         *
         * <p>此前 {@code RagSearchService} 结尾是无条件的 {@code .limit(k)}：
         * 只要库里有 k 条笔记，就一定返回 k 条，哪怕后几条余弦为 0。
         * 代价是双份的——占用上下文预算，还给模型提供了编造的素材。
         *
         * <p>现在由 {@code pkm.rag.min-relevance} 挡掉。<b>断言已反转</b>：
         * 从"证明没有下限"变成"守住下限还在"。
         */
        @Test
        @DisplayName("分数下限生效：余弦为 0 的笔记不进 top-k")
        void scoreFloorDropsNoise() {
            GoldenSet.Question q = goldenSet.byType(QuestionType.SINGLE_HOP).get(0);
            List<RagSearchService.Hit> hits = rag.search(user, q.question(), goldenSet.topK());

            assertThat(hits)
                    .as("单跳题只有少数几条沾边的笔记，不该再被无关内容填满到 top-%d",
                            goldenSet.topK())
                    .isNotEmpty()
                    .hasSizeLessThan(goldenSet.topK());

            assertThat(hits)
                    .as("留下来的每一条都必须过了相关度门槛——"
                            + "余弦为 0 的片段一旦进上下文，就是模型编造的素材")
                    .allSatisfy(h -> assertThat(h.relevance()).isNotNull().isGreaterThan(0.0));
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
         * 排序分数的上限依然压在阈值上——<b>但这已经不再有害</b>。
         *
         * <p>{@code pkm.rag.alpha=0.4} 意味着向量分量的排序分上限就是
         * {@code 0.4 × 1.0 = 0.4}，恰好等于 {@code pkm.crag.lower}。
         * 这个算术事实没变，也不打算改（alpha 本来就该是排序权重）。
         * 变的是<b>没人再拿它去判级了</b>。
         *
         * <p>保留这条断言，是因为它记录了"为什么判级不能读 score"的完整理由：
         * 一旦有人把判级改回读 {@code score()}，这两个数就会重新撞在一起，
         * 而 {@link RagDegradeCalibrationTest} 会立刻变红。
         */
        @Test
        @DisplayName("排序分数上限仍压在阈值上，但判级已不看它")
        void rankingScoreCeilingIsNoLongerUsedForGrading() {
            double productionAlpha = 0.4;
            double ceiling = productionAlpha * 1.0;

            assertThat(ceiling)
                    .as("排序分数的上限 = alpha × 余弦上限，仍然低于 upper")
                    .isLessThan(evaluator.getUpper());
            assertThat(ceiling)
                    .as("而且仍然正好落在 lower(%.2f) 上——这就是当初判级读 score 时，"
                            + "多跳题被 100%% 假降级的算术根因", evaluator.getLower())
                    .isEqualTo(evaluator.getLower());

            // 而同样这批命中，按 relevance 判级能正常到达 CORRECT
            GoldenSet.Question q = goldenSet.byType(QuestionType.SINGLE_HOP).get(0);
            List<RagSearchService.Hit> hits = rag.search(user, q.question(), goldenSet.topK());
            assertThat(evaluator.grade(hits))
                    .as("判级依据换成与 alpha 无关的 relevance 之后，"
                            + "完美语义匹配终于能被判成 CORRECT")
                    .isEqualTo(RetrievalEvaluator.Grade.CORRECT);
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
