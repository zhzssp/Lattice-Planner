package org.zhzssp.memorandum.agenteval.judge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 人工标注校准集的加载与统计。
 *
 * <h3>★ 这份数据的三条局限，读任何结论前必须知道</h3>
 * <ol>
 *   <li><b>样本是构造的，不是抽样的。</b>它们由作者按已知失败模式编写，
 *       而非从真实模型输出里随机采样。因此在它上面算出的一致率
 *       <b>不是任何判分器的真实准确率估计</b>，只能回答
 *       "这个判分器有没有盲区、盲区在哪"。这是压力集（stress set）的定位，
 *       跟红队测试集同理。</li>
 *   <li><b>单标注者。</b>只有一个人标（郑皓），没有标注者间一致性可算。
 *       如实写出来，比假装有标注团队诚实得多。</li>
 *   <li><b>n = 19，很小。</b>任何据此得出的差异都不该被当成统计显著。</li>
 * </ol>
 *
 * <p>真正的校准需要对<b>真实录制的输出</b>做标注，那依赖 P1 的录制完成。
 * 在此之前，这份集合的价值在于：它能<b>今天就证明</b>某个判分器在哪类输入上会失效。
 *
 * <h3>为什么全部样本都是降级场景</h3>
 * 待替换的字符串断言（{@code finalAnswerContainsAny}）只在检索降级分支运行。
 * 混入命中场景会让基线在它本就不适用的输入上失分，那是<b>把对比做歪</b>——
 * 想证明一个方案不好，得在它自己的主场上证明。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CalibrationSet(String datasetVersion, List<JudgeSample> samples) {

    private static final String RESOURCE = "/agent-eval/judge/honesty-calibration.json";

    public static CalibrationSet load() {
        try (InputStream in = CalibrationSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到校准集：" + RESOURCE);
            }
            return new ObjectMapper().readValue(in, CalibrationSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载校准集失败：" + RESOURCE, e);
        }
    }

    /** 各标签的样本数，用于确认标注没有严重倾斜。 */
    public Map<HonestyScore, Long> labelDistribution() {
        return samples.stream().collect(Collectors.groupingBy(
                JudgeSample::humanLabel, java.util.TreeMap::new, Collectors.counting()));
    }

    public int size() {
        return samples.size();
    }
}
