package org.zhzssp.memorandum.agenteval.cassette;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制盒：一个评测用例在<b>若干次独立试验</b>中产生的全部 LLM 交互记录。
 *
 * <h3>寻址策略（本设计的关键取舍）</h3>
 * 回放时如何把「当前这次 LLM 调用」对应到「录制里的某条记录」，有两种做法：
 * <ul>
 *   <li><b>内容寻址</b>（对 messages 做 hash）：精确，但极其脆弱——
 *       system prompt 里任何一个字的改动（哪怕只是改了个错别字）都会导致全部录制失效，
 *       必须重新联网录制。而 prompt 微调恰恰是 Agent 开发中最频繁的操作。</li>
 *   <li><b>顺序寻址</b>（按第几次调用）：稳定，但无法感知「prompt 已经变了」。</li>
 * </ul>
 *
 * <p>本实现取两者之长：<b>用 (caseId, trial, callIndex) 作为主键回放</b>，
 * 同时记录每次请求的 {@code fingerprint}。回放时若 fingerprint 不匹配，
 * <b>不报错、但发出漂移警告</b>，并在评测报告里标注该用例"录制可能过期"。
 *
 * <p>这样做的效果是：改 prompt 后测试仍能跑（不阻塞开发），
 * 但你会明确知道"有 3 个用例的录制已漂移，建议重新录制"。
 *
 * <h3>为什么需要「多试次」</h3>
 * 单次录制只能回答"它能不能做到"，回答不了"它稳不稳"。
 * 而对 Agent 来说后者往往更要命：单次成功率 75% 的 Agent，
 * {@code pass@3} 有 98.4%，{@code pass^3} 却只有 42%。
 * 因此同一用例需要录 k 次<b>互相独立</b>的轨迹，见 {@link #trials}。
 *
 * <h3>文件格式的向后兼容</h3>
 * 历史录制盒是扁平的 {@code interactions[]}（无试次概念）。
 * 这里保留该字段<b>仅用于读取旧文件</b>，语义等价于 {@code trials[0]}；
 * 新录制一律写 {@code trials[][]}。于是旧盒子不必作废，也不需要一次性迁移。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Cassette {

    private String caseId;
    private String recordedAt;
    /** 录制时使用的模型，回放时不校验，仅供人工检视 */
    private String recordedModel;

    /**
     * <b>旧格式，仅用于反序列化历史文件</b>，等价于 {@code trials[0]}。
     * 新录制不再写这个字段（为 null 时 Jackson 会整个省略）。
     */
    private List<LlmInteraction> interactions;

    /** 新格式：每个元素是一次独立试验的完整交互序列。 */
    private List<List<LlmInteraction>> trials;

    public Cassette() {
    }

    public Cassette(String caseId, String recordedAt, String recordedModel) {
        this.caseId = caseId;
        this.recordedAt = recordedAt;
        this.recordedModel = recordedModel;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(String recordedAt) {
        this.recordedAt = recordedAt;
    }

    public String getRecordedModel() {
        return recordedModel;
    }

    public void setRecordedModel(String recordedModel) {
        this.recordedModel = recordedModel;
    }

    public List<LlmInteraction> getInteractions() {
        return interactions;
    }

    public void setInteractions(List<LlmInteraction> interactions) {
        this.interactions = interactions;
    }

    public List<List<LlmInteraction>> getTrials() {
        return trials;
    }

    public void setTrials(List<List<LlmInteraction>> trials) {
        this.trials = trials;
    }

    /* ---- 试次访问 ---- */

    /**
     * 取第 {@code i} 次试验的交互序列；不存在则返回 null，由回放层决定如何处理。
     *
     * <p>读旧格式文件时，{@code trials} 为 null，此时 {@code trial(0)}
     * 回退到 {@code interactions}，其余试次返回 null——
     * 于是"拿旧盒子跑 pass^3"会得到一个明确的错误，而不是静默地把同一条轨迹重放三遍
     * （那会让方差恒为 0，pass^k 退化成 pass^1，指标看起来漂亮但毫无意义）。
     */
    public List<LlmInteraction> trial(int i) {
        if (trials != null) {
            return (i >= 0 && i < trials.size()) ? trials.get(i) : null;
        }
        return i == 0 ? interactions : null;
    }

    /** 已录制的试次数。 */
    public int trialCount() {
        if (trials != null) return trials.size();
        return (interactions == null || interactions.isEmpty()) ? 0 : 1;
    }

    /** 取某次试验的第 {@code index} 条交互；越界返回 null。 */
    public LlmInteraction at(int trial, int index) {
        List<LlmInteraction> t = trial(trial);
        if (t == null || index < 0 || index >= t.size()) return null;
        return t.get(index);
    }

    /** 某次试验的交互条数。 */
    public int size(int trial) {
        List<LlmInteraction> t = trial(trial);
        return t == null ? 0 : t.size();
    }

    /**
     * 追加一条交互到指定试次（录制时用）。必要时自动补齐前面的空试次。
     */
    public void add(int trial, LlmInteraction interaction) {
        if (trials == null) {
            trials = new ArrayList<>();
            // 迁移：若本对象是从旧格式读入的，把 interactions 收编为 trial 0
            if (interactions != null && !interactions.isEmpty()) {
                trials.add(new ArrayList<>(interactions));
            }
            interactions = null;
        }
        while (trials.size() <= trial) {
            trials.add(new ArrayList<>());
        }
        trials.get(trial).add(interaction);
    }

    /**
     * 单次 LLM 交互记录。
     *
     * @param index               本次试验内的调用序号（从 0 起）
     * @param purpose             TEXT / CHAT，便于人工检视时区分是 ReAct 主循环还是摘要/改写
     * @param fingerprint         请求指纹（模型 + 温度 + messages 规范化后的 SHA-256 前 16 位）
     * @param messagesDigest      messages 的可读摘要（截断），便于人工核对录制内容
     * @param responseContent     上游返回的文本内容（回放时原样返回）
     * @param responseUsageJson   原始 usage JSON 字符串；回放时一并返回，
     *                            使 prompt cache 统计链路也能被测试覆盖。null 表示无
     * @param upstreamLatencyMs   ★<b>录制时</b>这一次调用的真实往返耗时。
     *                            回放不联网，本机那点毫秒只反映回放速度，与线上延迟无关；
     *                            把真实耗时<b>存进盒子</b>，延迟才成为一个能长期追踪的量。
     *                            旧格式录制盒里没有这个字段，读进来是 null，
     *                            报告应如实标 n/a，而不是当成 0 混进分位数
     */
    public record LlmInteraction(
            int index,
            String purpose,
            String fingerprint,
            String messagesDigest,
            String responseContent,
            String responseUsageJson,
            Long upstreamLatencyMs
    ) {}
}
