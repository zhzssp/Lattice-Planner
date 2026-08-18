package org.zhzssp.memorandum.agenteval.cassette;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制盒：一个评测用例在一次完整运行中产生的全部 LLM 交互记录。
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
 * <p>本实现取两者之长：<b>用 (caseId, callIndex) 作为主键回放</b>，
 * 同时记录每次请求的 {@code fingerprint}。回放时若 fingerprint 不匹配，
 * <b>不报错、但发出漂移警告</b>，并在评测报告里标注该用例"录制可能过期"。
 *
 * <p>这样做的效果是：改 prompt 后测试仍能跑（不阻塞开发），
 * 但你会明确知道"有 3 个用例的录制已漂移，建议重新录制"。
 */
public class Cassette {

    private String caseId;
    private String recordedAt;
    /** 录制时使用的模型，回放时不校验，仅供人工检视 */
    private String recordedModel;
    private List<LlmInteraction> interactions = new ArrayList<>();

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
        this.interactions = interactions == null ? new ArrayList<>() : interactions;
    }

    public void add(LlmInteraction interaction) {
        this.interactions.add(interaction);
    }

    public int size() {
        return interactions.size();
    }

    /** 按调用序号取记录；越界返回 null（由回放层决定如何处理）。 */
    public LlmInteraction at(int index) {
        if (index < 0 || index >= interactions.size()) return null;
        return interactions.get(index);
    }

    /**
     * 单次 LLM 交互记录。
     *
     * @param index               本用例内的调用序号（从 0 起）
     * @param purpose             TEXT / CHAT，便于人工检视时区分是 ReAct 主循环还是摘要/改写
     * @param fingerprint         请求指纹（模型 + 温度 + messages 规范化后的 SHA-256 前 16 位）
     * @param messagesDigest      messages 的可读摘要（截断），便于人工核对录制内容
     * @param responseContent     上游返回的文本内容（回放时原样返回）
     * @param responseUsageJson   原始 usage JSON 字符串；回放时一并返回，
     *                            使 prompt cache 统计链路也能被测试覆盖。null 表示无
     */
    public record LlmInteraction(
            int index,
            String purpose,
            String fingerprint,
            String messagesDigest,
            String responseContent,
            String responseUsageJson
    ) {}
}
