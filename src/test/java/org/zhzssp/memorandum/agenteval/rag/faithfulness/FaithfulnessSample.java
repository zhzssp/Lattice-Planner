package org.zhzssp.memorandum.agenteval.rag.faithfulness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/**
 * 一条忠实度校准样本：问题 + 检索到的 context + 答复 + 人工标注。
 *
 * @param context   检索片段原文，取自 {@code golden-set.json} 的语料，保证与检索评测同源
 * @param label     人工标注的忠实度
 * @param note      标注理由。<b>必须写</b>——理由缺失的标注无法被复核，
 *                  过两周连标注者自己都说不清当初为什么这么标
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaithfulnessSample(String id, String question, List<String> context,
                                 String answer, Faithfulness label, String note) {

    private static final String RESOURCE = "/agent-eval/rag/faithfulness-calibration.json";

    /**
     * 加载校准集。
     *
     * <h3>★ 这份数据的三条局限（不写出来就会被当成基准集用）</h3>
     * <ol>
     *   <li><b>是构造的，不是抽样的。</b>样本按"想考察的失败模式"编写，
     *       刻意加大了编造类的比例。因此这里的准确率<b>不能外推到真实分布</b></li>
     *   <li><b>单人标注，没有第二标注者。</b>没有人际一致率打底，
     *       所谓"与人工一致"其实是"与我一致"</li>
     *   <li><b>N 很小。</b>十几条样本上的 κ 置信区间宽得几乎不能用来做判定，
     *       只够用来<b>排除明显不可用的判分器</b></li>
     * </ol>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Set(List<FaithfulnessSample> samples) {

        public static Set load() {
            try (InputStream in = Set.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IllegalStateException("找不到忠实度校准集：" + RESOURCE);
                return new ObjectMapper().readValue(in, Set.class);
            } catch (Exception e) {
                throw new IllegalStateException("加载忠实度校准集失败：" + RESOURCE, e);
            }
        }

        public List<FaithfulnessSample> withLabel(Faithfulness label) {
            return samples.stream().filter(s -> s.label() == label).toList();
        }
    }
}
