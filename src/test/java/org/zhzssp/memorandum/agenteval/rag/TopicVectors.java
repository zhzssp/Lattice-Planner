package org.zhzssp.memorandum.agenteval.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把金标集里的<b>命名主题权重</b>编译成确定性向量，让向量检索通路能完全离线运行。
 *
 * <h3>为什么需要它</h3>
 * 评测环境的两条检索通路本来<b>都是死的</b>：
 * <ul>
 *   <li>关键字通路用 MySQL 的 {@code MATCH ... AGAINST}，H2 上直接抛异常</li>
 *   <li>向量通路要调 embedding API，没 key 也抛异常</li>
 * </ul>
 * 两处异常都被 {@code RagSearchService} 吞掉，于是 {@code search()} 恒返回空——
 * <b>检索相关的一切在评测里其实从未被执行过</b>。
 * 桩掉 {@code EmbeddingClient} 之后，向量通路就能跑起来，
 * 而余弦计算、加权融合、排序、top-k 截断、CRAG 分级<b>全都是真实产品代码</b>。
 *
 * <h3>用命名主题而不是裸浮点数</h3>
 * 金标集是要被人 review 的。{@code {"kafka": 0.8, "consumer_group": 0.6}}
 * 能一眼看出想表达什么相关性，一串 1024 维浮点数不能。
 * 维度由全集的主题名排序后确定，因此<b>结果可复现</b>。
 *
 * <p>不可回答类问题的主题（qcd、futures 等）刻意不出现在任何语料里，
 * 因此与全部笔记正交、余弦恒为 0，CRAG 会判 INCORRECT 并降级。
 */
public final class TopicVectors {

    private final Map<String, Integer> dimensions;

    private TopicVectors(Map<String, Integer> dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * 由整个金标集（语料 + 问题）的主题全集建立维度表。
     *
     * <p>维度按主题名<b>排序</b>分配而不是按出现顺序，
     * 这样往金标集中间插一条新记录不会打乱既有维度——
     * 否则每加一个问题，所有历史向量都会变，分数无法跨版本对照。
     */
    public static TopicVectors from(GoldenSet set) {
        List<String> names = new ArrayList<>();
        set.corpus().forEach(n -> names.addAll(n.topics().keySet()));
        set.questions().forEach(q -> names.addAll(q.topics().keySet()));

        Map<String, Integer> dims = new LinkedHashMap<>();
        names.stream().distinct().sorted().forEach(name -> dims.put(name, dims.size()));
        return new TopicVectors(dims);
    }

    public int dimension() {
        return dimensions.size();
    }

    /** 编译成 L2 归一化向量，使余弦相似度等于两组权重的夹角余弦。 */
    public float[] encode(Map<String, Double> topics) {
        float[] v = new float[dimensions.size()];
        if (topics != null) {
            topics.forEach((name, weight) -> {
                Integer idx = dimensions.get(name);
                if (idx != null) v[idx] = weight.floatValue();
            });
        }
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        }
        return v;
    }
}
