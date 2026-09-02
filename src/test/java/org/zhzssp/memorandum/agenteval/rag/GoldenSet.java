package org.zhzssp.memorandum.agenteval.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 检索金标集：一份自带语料的问题集，进版本控制、改动要 review。
 *
 * <h3>三类问题缺一不可</h3>
 * <ul>
 *   <li><b>单跳</b>：库里有直接答案</li>
 *   <li><b>多跳</b>：要综合多条笔记</li>
 *   <li><b>不可回答</b>：库里根本没有 ← <b>最容易被忘、也最重要</b></li>
 * </ul>
 * 第三类正是 {@code kb_search_degraded} 守的那条线。缺了它，
 * 一个"永远编一个答案出来"的系统能在前两类上拿高分。
 *
 * <h3>扩容后必须重建基线</h3>
 * 加了更难的题导致分数下降是<b>预期行为</b>，不该触发回归告警。
 * 所以报告里记 {@code datasetVersion}——跨版本比分数是没有意义的。
 *
 * <h3>★ 这份数据能测什么、不能测什么</h3>
 * 语料与问题的相关性由<b>人工指定的主题权重</b>编译成向量（见 {@link TopicVectors}），
 * 所以它测的是<b>检索链路与融合排序</b>（{@code RagSearchService} 的余弦、加权、
 * top-k 截断，以及 CRAG 的分级与降级判定）——这些都是真实产品代码。
 *
 * <p>它<b>测不了嵌入模型的语义质量</b>：真实场景里"消费者组"和"partition"
 * 有多接近，由 bge-m3 决定，不由这里的权重决定。
 * 那部分需要真实 API 单独量。<b>把这条写清楚，是为了不让人拿这里的
 * recall 数字去代表线上检索效果。</b>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenSet(String datasetVersion, int topK,
                        List<CorpusNote> corpus, List<Question> questions) {

    private static final String RESOURCE = "/agent-eval/rag/golden-set.json";

    public enum QuestionType { SINGLE_HOP, MULTI_HOP, UNANSWERABLE }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorpusNote(Long id, String title, String content,
                             Map<String, Double> topics) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String id, QuestionType type, String question,
                           Map<String, Double> topics, Set<Long> relevantNoteIds) {

        /** 不可回答类不参与 recall/precision，只看是否正确降级。 */
        public boolean answerable() {
            return type != QuestionType.UNANSWERABLE;
        }
    }

    public static GoldenSet load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("找不到金标集：" + RESOURCE);
            return new ObjectMapper().readValue(in, GoldenSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载金标集失败：" + RESOURCE, e);
        }
    }

    public List<Question> byType(QuestionType type) {
        return questions.stream().filter(q -> q.type() == type).toList();
    }
}
