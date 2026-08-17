package org.zhzssp.memorandum.feature.pkm.serving;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;
import org.zhzssp.memorandum.feature.pkm.service.RagSearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Ranker（R2）。
 *
 * <p>不引入 cross-encoder 模型，用现有 LLM 对粗排 top-N 做精排，输出相关度降序的编号数组。
 * 任何异常（超时 / 解析失败 / LLM 不可用）都回退到原始融合序，不劣化可用性。</p>
 */
@Component
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final LlmGateway llm;
    private final ObjectMapper om;
    private final RagServingMetrics metrics;

    @Value("${pkm.rag.serving.rerank.enabled:false}")
    private boolean enabled;

    @Value("${pkm.rag.serving.rerank.candidate-n:20}")
    private int candidateN;

    public Reranker(LlmGateway llm, ObjectMapper om, RagServingMetrics metrics) {
        this.llm = llm;
        this.om = om;
        this.metrics = metrics;
    }

    public boolean enabled() { return enabled; }

    /**
     * 对粗排候选精排返回 topK。
     * 输入可为空/过少/全空 content，均回退原序。
     *
     * <p>指标语义：真正按 LLM 返回序重排成功记 {@code recordRerank()}；
     * 任何回退路径（未启用除外）记 {@code recordRerankFallback()}，
     * 便于在 stats 端点观察"rerank 是否在静默降级"。</p>
     */
    public List<RagSearchService.Hit> rerank(String query, List<RagSearchService.Hit> candidates, int topK) {
        if (!enabled || candidates == null || candidates.isEmpty()) return candidates;

        List<RagSearchService.Hit> pool = candidates.size() > candidateN
                ? candidates.subList(0, candidateN) : candidates;
        if (pool.size() <= topK) {
            // 候选不足以重排，退化为直接截断（不计入 fallback：非失败）
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        try {
            // 组装紧凑 prompt：编号 + 截断 content
            StringBuilder sb = new StringBuilder("请根据查询评估以下候选片段的相关性。");
            sb.append("只输出相关度降序的候选编号 JSON 数组，例如 [3,1,5]。不要输出额外文字。\n\n");
            sb.append("查询：").append(query).append("\n\n");
            for (int i = 0; i < pool.size(); i++) {
                String txt = pool.get(i).content();
                String preview = txt == null ? "" : (txt.length() > 200 ? txt.substring(0, 200) + "..." : txt);
                sb.append(i).append(". ").append(preview).append("\n");
            }

            String raw = llm.generateText(sb.toString());
            if (raw == null || raw.isBlank()) {
                metrics.recordRerankFallback();
                return candidates;
            }

            JsonNode arr = om.readTree(raw);
            if (!arr.isArray()) {
                metrics.recordRerankFallback();
                return candidates;
            }

            // 按返回编号重排
            List<RagSearchService.Hit> reranked = new ArrayList<>();
            for (JsonNode idxNode : arr) {
                int idx = idxNode.asInt(-1);
                if (idx >= 0 && idx < pool.size()) {
                    reranked.add(pool.get(idx));
                }
            }
            if (reranked.isEmpty()) {
                // LLM 返回了数组但全是非法编号，视为失败
                metrics.recordRerankFallback();
                return candidates;
            }
            // 未提及的候选按原序追加
            for (int i = 0; i < pool.size(); i++) {
                final int idx = i;
                if (reranked.stream().noneMatch(h -> h.equals(pool.get(idx)))) {
                    reranked.add(pool.get(idx));
                }
            }
            metrics.recordRerank();
            log.debug("[RAG Serving] Rerank: {} 候选 → {} 精排 → {} 结果",
                    candidates.size(), pool.size(), Math.min(topK, reranked.size()));
            return reranked.subList(0, Math.min(topK, reranked.size()));
        } catch (Exception e) {
            metrics.recordRerankFallback();
            log.debug("[RAG Serving] Rerank 失败，回退融合序：{}", e.getMessage());
            return candidates;
        }
    }
}
