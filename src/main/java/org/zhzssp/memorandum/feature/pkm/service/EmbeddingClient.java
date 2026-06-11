package org.zhzssp.memorandum.feature.pkm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.service.LlmGateway;

import java.util.List;

/**
 * Embedding 薄封装：
 * - 把 LlmGateway 的 generateEmbedding 暴露给 PKM 模块；
 * - 提供 float[] ↔ JSON 串行化（用于落库 note_embedding.embedding MEDIUMTEXT 字段）；
 * - 提供静态 cosine 相似度，避免每次 RAG 重建数学函数。
 *
 * 设计取舍：不引入 ND4J / Smile 等向量库——单用户万级 chunk 全表 cosine 实测 ~10ms，
 * 数组运算用 JIT 即可；外部库只会增大启动时间与镜像体积。
 */
@Component
public class EmbeddingClient {

    private final LlmGateway llm;
    private final ObjectMapper om;

    public EmbeddingClient(LlmGateway llm, ObjectMapper om) {
        this.llm = llm;
        this.om = om;
    }

    public List<float[]> embed(List<String> texts) {
        return llm.generateEmbedding(texts);
    }

    public String modelName() {
        return llm.embeddingModelName();
    }

    /** float[] → JSON 数组串。失败抛 RuntimeException。 */
    public String serialize(float[] v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 embedding 失败", e);
        }
    }

    /** JSON 数组串 → float[]。失败抛 RuntimeException。 */
    public float[] deserialize(String json) {
        try {
            List<Double> list = om.readValue(json, new TypeReference<List<Double>>() {});
            float[] v = new float[list.size()];
            for (int i = 0; i < list.size(); i++) v[i] = list.get(i).floatValue();
            return v;
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 embedding 失败", e);
        }
    }

    /** 余弦相似度。任一向量为 null / 长度不一致 / 全零 时返回 0。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
