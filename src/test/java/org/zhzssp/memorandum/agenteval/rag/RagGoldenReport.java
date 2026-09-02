package org.zhzssp.memorandum.agenteval.rag;

import org.zhzssp.memorandum.agenteval.rag.GoldenSet.QuestionType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 金标集跑分聚合。<b>可回答与不可回答分开报，两者度量的根本不是一回事。</b>
 *
 * <h3>为什么不能合成一个"RAG 总分"</h3>
 * 检索侧的失败（该召的没召回来）和生成侧的失败（召回来了却编造），
 * 修法完全不同——前者调检索，后者调 prompt。<b>合成一个数字等于放弃归因</b>：
 * 分数掉了你不知道该动哪边。
 *
 * <p>同理，不可回答类问题的"正确降级率"和可回答类的"召回率"也不能平均：
 * 一个从不召回任何东西的系统，在不可答题上会拿满分。
 */
public final class RagGoldenReport {

    /**
     * @param metrics  仅可回答类有值；不可回答类为 null
     * @param degraded CRAG 是否判定降级
     */
    public record Row(String questionId, QuestionType type, String question,
                      RetrievalMetrics metrics, boolean degraded,
                      List<Long> retrievedNoteIds, double topScore) {}

    private final String datasetVersion;
    private final int topK;
    private final List<Row> rows;

    public RagGoldenReport(String datasetVersion, int topK, List<Row> rows) {
        this.datasetVersion = datasetVersion;
        this.topK = topK;
        this.rows = rows;
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Row> byType(QuestionType type) {
        return rows.stream().filter(r -> r.type() == type).toList();
    }

    /** 可回答类（单跳 + 多跳）的平均召回率。RAG 里最关键的一个数。 */
    public double meanRecall(QuestionType... types) {
        return mean(types, r -> r.metrics().recallAtK());
    }

    public double meanPrecision(QuestionType... types) {
        return mean(types, r -> r.metrics().precisionAtK());
    }

    /** 平均倒数排名。低于召回率说明"召回来了但排太后"。 */
    public double meanReciprocalRank(QuestionType... types) {
        return mean(types, r -> r.metrics().reciprocalRank());
    }

    /**
     * 不可回答类里被正确判为降级的比例。
     *
     * <p>这是<b>唯一</b>能证明"库里没有时不硬编"的量化指标。
     * 它守的是 {@code kb_search_degraded} 那条线，但用的是真实检索打分，
     * 而不是靠 mock 返回空列表。
     */
    public double correctDegradeRate() {
        List<Row> un = byType(QuestionType.UNANSWERABLE);
        if (un.isEmpty()) return Double.NaN;
        return round4(un.stream().filter(Row::degraded).count() / (double) un.size());
    }

    /**
     * 可回答类里被<b>误判</b>为降级的比例（假降级）。
     *
     * <p>单看正确降级率会被一个"永远降级"的系统骗过去，
     * 必须和这个数配着看——就像精确率之于召回率。
     */
    public double falseDegradeRate() {
        List<Row> ans = rows.stream().filter(r -> r.type() != QuestionType.UNANSWERABLE).toList();
        if (ans.isEmpty()) return Double.NaN;
        return round4(ans.stream().filter(Row::degraded).count() / (double) ans.size());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasetVersion", datasetVersion);
        m.put("topK", topK);
        m.put("questionCount", rows.size());
        m.put("singleHop", typeSummary(QuestionType.SINGLE_HOP));
        m.put("multiHop", typeSummary(QuestionType.MULTI_HOP));
        Map<String, Object> un = new LinkedHashMap<>();
        un.put("count", byType(QuestionType.UNANSWERABLE).size());
        un.put("correctDegradeRate", correctDegradeRate());
        m.put("unanswerable", un);
        m.put("falseDegradeRate", falseDegradeRate());
        return m;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ RAG 检索金标集 ================\n");
        sb.append(String.format("数据集版本 %s   topK=%d   题量 %d%n", datasetVersion, topK, rows.size()));
        sb.append("（跨 datasetVersion 比分数没有意义：题变难了分数就该降）\n\n");

        for (QuestionType t : List.of(QuestionType.SINGLE_HOP, QuestionType.MULTI_HOP)) {
            List<Row> rs = byType(t);
            if (rs.isEmpty()) continue;
            sb.append(String.format("【%s】%d 题  平均 recall@%d=%.3f  precision@%d=%.3f  MRR=%.3f%n",
                    t == QuestionType.SINGLE_HOP ? "单跳" : "多跳", rs.size(),
                    topK, meanRecall(t), topK, meanPrecision(t), meanReciprocalRank(t)));
            for (Row r : rs) {
                sb.append(String.format("   %-6s %s | %s%n", r.questionId(), r.metrics().render(), r.question()));
            }
        }

        List<Row> un = byType(QuestionType.UNANSWERABLE);
        if (!un.isEmpty()) {
            sb.append(String.format("%n【不可回答】%d 题  正确降级率=%.3f%n", un.size(), correctDegradeRate()));
            for (Row r : un) {
                sb.append(String.format("   %-6s %s (最高分 %.3f) | %s%n", r.questionId(),
                        r.degraded() ? "已降级" : "★未降级——会硬编答案", r.topScore(), r.question()));
            }
        }
        sb.append(String.format("%n可回答类的假降级率=%.3f（该召回却被判降级的比例）%n", falseDegradeRate()));
        sb.append("===============================================\n");
        return sb.toString();
    }

    private double mean(QuestionType[] types, java.util.function.ToDoubleFunction<Row> f) {
        List<QuestionType> want = List.of(types);
        List<Row> rs = rows.stream()
                .filter(r -> want.isEmpty() || want.contains(r.type()))
                .filter(r -> r.metrics() != null)
                .toList();
        if (rs.isEmpty()) return Double.NaN;
        return round4(rs.stream().mapToDouble(f).average().orElse(Double.NaN));
    }

    private Map<String, Object> typeSummary(QuestionType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", byType(t).size());
        m.put("meanRecall", meanRecall(t));
        m.put("meanPrecision", meanPrecision(t));
        m.put("meanReciprocalRank", meanReciprocalRank(t));
        return m;
    }

    private static double round4(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 10000.0) / 10000.0;
    }
}
