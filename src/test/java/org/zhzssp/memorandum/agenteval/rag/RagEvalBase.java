package org.zhzssp.memorandum.agenteval.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zhzssp.memorandum.entity.NoteEmbedding;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.pkm.crag.CorrectiveRetriever;
import org.zhzssp.memorandum.feature.pkm.crag.QueryRewriter;
import org.zhzssp.memorandum.feature.pkm.crag.RetrievalEvaluator;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingClient;
import org.zhzssp.memorandum.feature.pkm.service.EmbeddingVectorCache;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;
import org.zhzssp.memorandum.repository.NoteEmbeddingRepository;
import org.zhzssp.memorandum.repository.NoteRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P4 · RAG 检索评测基座：装配一个能<b>离线运行的真实检索通路</b>。
 *
 * <h3>★ 为什么不复用 {@code AgentEvalBase}</h3>
 * 它把 {@link RagSearchService} 整个 mock 掉了，理由写在它的类注释里——
 * "含 MySQL 原生 SQL，H2 无法执行"。这对轨迹评测是对的，
 * 但副作用是<b>检索相关的一切在评测里从未被真正执行过</b>：
 * {@code kb_search_degraded} 之所以通过，是因为 mock 直接返回了空列表，
 * 而不是因为检索真的判断出"库里没有"。
 *
 * <h3>只桩最外层的一颗螺丝</h3>
 * 本基座唯一桩掉的是 {@link EmbeddingClient}（它要调外部 API），于是
 * 余弦计算、加权融合、排序、top-k 截断、CRAG 分级与降级判定
 * <b>全部是真实产品代码在跑</b>。
 *
 * <h3>能得出什么结论、不能得出什么结论</h3>
 * 相关性由人工指定的主题权重编译成向量（见 {@link TopicVectors}），
 * 所以本套测试<b>测得了检索链路、排序融合与阈值标定，测不了嵌入模型的语义质量</b>——
 * 真实场景里"消费者组"和"partition"有多接近由 bge-m3 决定，不由这里的权重决定。
 * <b>这里的 recall 数字不能拿去代表线上检索效果。</b>
 */
@Tag("agent-eval")
@SpringBootTest
@ActiveProfiles("agenteval")
abstract class RagEvalBase {

    protected static final long USER_ID = 1L;

    @Autowired
    protected RagSearchService rag;

    @Autowired
    protected CorrectiveRetriever crag;

    @Autowired
    protected RetrievalEvaluator evaluator;

    @Autowired
    protected EmbeddingVectorCache vectorCache;

    @Autowired
    protected NoteRepository noteRepository;

    @Autowired
    protected NoteEmbeddingRepository embeddingRepository;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 唯一被桩掉的东西：它会调外部 embedding 服务。 */
    @MockitoBean
    protected EmbeddingClient embeddingClient;

    /**
     * 改写器要花一次 LLM 调用。这里桩成它自己的<b>降级行为</b>
     * （其文档写明"LLM 不可用时回退原 query"），使评测度量的是<b>首轮检索</b>。
     *
     * <p>刻意不评测改写效果：改写质量取决于模型，混进来会让召回率的涨跌无法归因
     * ——究竟是检索变好了还是改写变好了？那该是另一组对照实验。
     */
    @MockitoBean
    protected QueryRewriter queryRewriter;

    protected GoldenSet goldenSet;
    protected TopicVectors vectors;
    protected User user;

    @BeforeEach
    void setUpRagEval() {
        goldenSet = GoldenSet.load();
        vectors = TopicVectors.from(goldenSet);

        user = new User();
        user.setId(USER_ID);
        user.setUsername("rag-eval-user");
        jdbc.update("MERGE INTO user (id, username, password) KEY(id) VALUES (?, ?, ?)",
                USER_ID, "rag-eval-user", "eval-not-a-real-credential");

        seedCorpus();
        stubEmbeddingClient();
        stubQueryRewriterFallback();
    }

    /** 跑一遍金标集，产出可断言的报告。 */
    protected RagGoldenReport runGoldenSet() {
        List<RagGoldenReport.Row> rows = new ArrayList<>();
        for (GoldenSet.Question q : goldenSet.questions()) {
            CorrectiveRetriever.CragResult result = crag.retrieve(user, q.question(), goldenSet.topK());
            List<Long> retrieved = result.hits().stream().map(RagSearchService.Hit::noteId).toList();
            double topScore = result.hits().isEmpty() ? 0.0 : result.hits().get(0).score();

            RetrievalMetrics m = q.answerable()
                    ? RetrievalMetrics.of(retrieved, q.relevantNoteIds(), goldenSet.topK())
                    : null;

            rows.add(new RagGoldenReport.Row(q.id(), q.type(), q.question(),
                    m, result.degraded(), retrieved, topScore));
        }
        return new RagGoldenReport(goldenSet.datasetVersion(), goldenSet.topK(), rows);
    }

    /* ---- 环境搭建 ---- */

    private void seedCorpus() {
        vectorCache.invalidate(USER_ID);
        embeddingRepository.deleteAll();
        jdbc.update("delete from note where user_id = ?", USER_ID);

        for (GoldenSet.CorpusNote n : goldenSet.corpus()) {
            jdbc.update("insert into note (id, title, content, user_id, created_at) values (?, ?, ?, ?, ?)",
                    n.id(), n.title(), n.content(), USER_ID, java.time.LocalDateTime.now());

            NoteEmbedding e = new NoteEmbedding();
            e.setUserId(USER_ID);
            e.setNoteId(n.id());
            e.setSource("NOTE");
            e.setChunkIdx(0);
            e.setContent(n.content());
            e.setEmbedding(Arrays.toString(vectors.encode(n.topics())));
            e.setDim(vectors.dimension());
            e.setModel("topic-vectors-offline");
            embeddingRepository.save(e);
        }
        vectorCache.invalidate(USER_ID);
    }

    /**
     * 桩掉 embedding：查询文本 → 该问题声明的主题向量；存储串 → 真实 JSON 解析。
     *
     * <p>{@code deserialize} 特意走真实的 Jackson 解析而不是返回预置对象，
     * 这样落库的向量是货真价实的 JSON 数组，序列化格式出问题时测试会说话。
     */
    private void stubEmbeddingClient() {
        Map<String, float[]> byText = new HashMap<>();
        goldenSet.questions().forEach(q -> byText.put(q.question(), vectors.encode(q.topics())));

        ObjectMapper om = new ObjectMapper();
        Mockito.when(embeddingClient.embed(ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    // 未登记的文本（例如改写后的查询）视作与语料正交，不凭空制造召回
                    .map(t -> byText.getOrDefault(t, new float[vectors.dimension()]))
                    .toList();
        });
        Mockito.when(embeddingClient.deserialize(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            List<Double> list = om.readValue(inv.getArgument(0, String.class),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Double>>() {});
            float[] v = new float[list.size()];
            for (int i = 0; i < v.length; i++) v[i] = list.get(i).floatValue();
            return v;
        });
    }

    private void stubQueryRewriterFallback() {
        Mockito.when(queryRewriter.rewrite(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> List.of(inv.getArgument(0, String.class)));
    }
}
