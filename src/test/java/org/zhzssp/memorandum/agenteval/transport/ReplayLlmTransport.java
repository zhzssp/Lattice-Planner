package org.zhzssp.memorandum.agenteval.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zhzssp.memorandum.agenteval.cassette.Cassette;
import org.zhzssp.memorandum.agenteval.cassette.CassetteStore;
import org.zhzssp.memorandum.agenteval.trial.EvalTrialExtension;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回放模式传输层：<b>完全不联网</b>，按录制盒顺序返回预录响应。
 *
 * <p>这是评测体系的核心组件。它使得：
 * <ul>
 *   <li>ReAct 循环、工具反射调用、CRAG 分支、前缀缓存全部<b>真实执行</b></li>
 *   <li>唯一被固定的是"模型说了什么"，于是轨迹变得<b>确定、可断言</b></li>
 *   <li>CI 无需 API Key、无需网络、零成本、毫秒级完成</li>
 * </ul>
 *
 * <h3>三种异常情形的处理</h3>
 * <ol>
 *   <li><b>指纹漂移</b>（prompt 变了但仍能按序回放）：记录警告，继续回放。
 *       报告里标注"录制可能过期"，提示重新录制。</li>
 *   <li><b>录制耗尽</b>（Agent 比录制时多调了 LLM）：这通常意味着行为已改变
 *       （比如多绕了一步），抛异常并明确指出，这是<b>需要人关注的信号</b>而非静默失败。</li>
 *   <li><b>Embedding 调用</b>：直接抛异常。评测用例必须 mock 掉向量检索层，
 *       不允许穿透到真实 embedding 服务。</li>
 * </ol>
 */
public class ReplayLlmTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(ReplayLlmTransport.class);

    private final ObjectMapper om;
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile Cassette current;
    private volatile int currentTrial;
    private final List<String> driftWarnings = new ArrayList<>();

    public ReplayLlmTransport(ObjectMapper om) {
        this.om = om;
    }

    /**
     * 载入某用例第 {@code trial} 次试验的录制，准备回放。
     *
     * <p>若该试次没有录制，<b>直接抛错而不是回退到 trial 0</b>。
     * 回退看似"容错"，实则是最坏的选择：它会把同一条轨迹重放 k 遍，
     * 于是 k 次试验结果完全相同、方差恒为 0，{@code pass^k} 退化成 {@code pass^1}。
     * 报告上会出现一个漂亮且完全虚假的可靠性数字——这比测试直接失败危险得多。
     */
    public void beginCase(String caseId, int trial) {
        Cassette c = CassetteStore.load(caseId);
        if (c.trial(trial) == null) {
            int wanted = EvalTrialExtension.trialCount();
            throw new AssertionError(String.format(
                    "用例 %s 只录制了 %d 次试验，无法回放第 %d 次。%n"
                            + "pass^k 要求每次试验都是独立录制的轨迹，不能靠重放同一条来凑数。%n"
                            + "请以录制模式补录（PowerShell 下参数要加引号）：%n"
                            + "  gradlew agentEval \"-Dagent.eval.mode=record\" \"-Dagent.eval.trials=%d\"%n"
                            + "需 DEEPSEEK_API_KEY，会产生真实 API 调用与费用。",
                    caseId, c.trialCount(), trial + 1, wanted));
        }
        this.current = c;
        this.currentTrial = trial;
        this.counter.set(0);
        this.driftWarnings.clear();
    }

    /** 本次回放中检测到的指纹漂移警告（供报告汇总）。 */
    public List<String> driftWarnings() {
        return List.copyOf(driftWarnings);
    }

    /** 实际消耗的录制条数，可用于断言"LLM 调用次数符合预期"。 */
    public int consumedInteractions() {
        return counter.get();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Cassette c = this.current;
        if (c == null) {
            throw new IllegalStateException("ReplayLlmTransport 未初始化：请先调用 beginCase(caseId)");
        }
        int idx = counter.getAndIncrement();
        int t = this.currentTrial;
        Cassette.LlmInteraction rec = c.at(t, idx);

        if (rec == null) {
            throw new AssertionError(String.format(
                    "录制耗尽：用例 %s（第 %d 次试验）只录了 %d 次 LLM 调用，但 Agent 请求了第 %d 次。%n"
                            + "这通常说明 Agent 行为已改变（多绕了推理步骤）。%n"
                            + "若这是预期的改动，请重新录制：-Dagent.eval.mode=record%n"
                            + "本次请求摘要：%s",
                    c.getCaseId(), t, c.size(t), idx + 1, CassetteStore.digest(request)));
        }

        // 指纹漂移检测：不阻断，但要让人知道
        String actual = CassetteStore.fingerprint(request);
        if (rec.fingerprint() != null && !rec.fingerprint().equals(actual)) {
            String w = String.format("第 %d 次调用指纹漂移（录制=%s，当前=%s）",
                    idx, rec.fingerprint(), actual);
            driftWarnings.add(w);
            log.warn("[AgentEval] {} - 用例 {}：{}", "PROMPT DRIFT", c.getCaseId(), w);
        }

        com.fasterxml.jackson.databind.JsonNode usage = null;
        if (rec.responseUsageJson() != null && !rec.responseUsageJson().isBlank()) {
            try {
                usage = om.readTree(rec.responseUsageJson());
            } catch (Exception ignore) {
                // 回放 usage 解析失败不影响主断言
            }
        }
        return new ChatResponse(rec.responseContent(), usage);
    }

    @Override
    public List<float[]> embed(EmbedRequest request) {
        throw new AssertionError(
                "评测用例不应触发真实 Embedding 调用。请用 @MockBean 替换 RagSearchService / "
                        + "CorrectiveRetriever / EmbeddingClient——向量检索的正确性不属于 Agent "
                        + "决策质量的评测范围，且会引入 MySQL 与外部服务依赖。");
    }
}
