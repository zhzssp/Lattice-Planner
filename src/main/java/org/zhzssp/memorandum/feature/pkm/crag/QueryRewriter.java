package org.zhzssp.memorandum.feature.pkm.crag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写器（C2）。
 *
 * <p>当检索结果被判为 AMBIGUOUS / INCORRECT 时，用一次 LLM 调用生成 1~3 个语义等价/拆解的改写查询，
 * 再合并检索以提高召回。LLM 不可用时回退原 query（不劣化可用性）。
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final LlmGateway llm;
    private final ObjectMapper om;

    public QueryRewriter(LlmGateway llm, ObjectMapper om) {
        this.llm = llm;
        this.om = om;
    }

    /**
     * 生成 n 个（1~3）语义改写的查询。
     * LLM 超时/异常/解析失败时返回仅含原 query 的列表。
     */
    public List<String> rewrite(String query, int n) {
        if (query == null || query.isBlank()) return List.of("");
        try {
            String prompt = """
                    将下面的检索问题改写成 %d 个语义等价或不同侧面的表述（同义改写、拆解为子问题、补充上下文均可）。
                    只输出一个 JSON 字符串数组，例如 ["表述1","表述2"]。不要输出任何额外文字。

                    原始问题：%s
                    """.formatted(Math.max(1, Math.min(n, 3)), query);

            String raw = llm.generateText(prompt);
            if (raw == null || raw.isBlank()) return List.of(query);

            JsonNode arr = om.readTree(raw);
            if (!arr.isArray() || arr.size() == 0) return List.of(query);

            List<String> out = new ArrayList<>();
            out.add(query); // 原 query 始终保留
            for (JsonNode item : arr) {
                String t = item.asText("").trim();
                if (!t.isBlank() && !t.equals(query)) out.add(t);
            }
            log.debug("[CRAG] 改写查询：{} → {}", query, out.subList(1, Math.min(out.size(), 4)));
            return out.size() == 1 ? out : out.stream().distinct().limit(n + 1).toList();
        } catch (Exception e) {
            log.warn("[CRAG] 查询改写失败，回退原 query：{}", e.getMessage());
            return List.of(query);
        }
    }
}
