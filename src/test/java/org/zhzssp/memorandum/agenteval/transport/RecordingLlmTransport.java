package org.zhzssp.memorandum.agenteval.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zhzssp.memorandum.agenteval.cassette.Cassette;
import org.zhzssp.memorandum.agenteval.cassette.CassetteStore;
import org.zhzssp.memorandum.agenteval.cost.UsageAccumulator;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.TokenUsage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 录制模式传输层：装饰真实 {@code HttpLlmTransport}，透传请求并把交互落盘。
 *
 * <p>启用方式：{@code -Dagent.eval.mode=record}。会产生真实 API 调用与费用。
 *
 * <p>一次录制运行结束后，{@link #flush()} 把整盒写入
 * {@code src/test/resources/agent-eval/cassettes/<caseId>.json}，
 * 之后所有 CI 运行都走回放，不再联网。
 */
public class RecordingLlmTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(RecordingLlmTransport.class);

    private final LlmTransport delegate;
    private final ObjectMapper om;
    private final UsageAccumulator usage;
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile Cassette current;
    private volatile int currentTrial;

    public RecordingLlmTransport(LlmTransport delegate, ObjectMapper om, UsageAccumulator usage) {
        this.delegate = delegate;
        this.om = om;
        this.usage = usage;
    }

    /**
     * 开始录制某用例的第 {@code trial} 次试验。
     *
     * <p>同一用例的多次试验必须<b>累积进同一个盒子</b>：若每次试验都新建 Cassette，
     * 后一次试验写盘时会覆盖前一次，最终文件里只剩最后一条轨迹。
     * 因此这里仅在 caseId 变化时才新建。
     */
    public void beginCase(String caseId, int trial) {
        Cassette c = this.current;
        if (c == null || !caseId.equals(c.getCaseId())) {
            c = new Cassette(caseId, LocalDateTime.now().toString(), null);
            this.current = c;
        }
        this.currentTrial = trial;
        this.counter.set(0);
        log.info("[AgentEval] 开始录制用例：{}（第 {} 次试验）", caseId, trial);
    }

    /**
     * 结束当前试验并写盘（整盒重写，含已录完的全部试次）。
     *
     * <h4>★ 空录制绝不写盘</h4>
     * 因为写盘是<b>整盒重写</b>。一次上游调用失败（余额不足、限流、网络断）
     * 会让本次试验录到 0 条交互，此时若照常写盘，就等于用一个空盒子
     * <b>覆盖掉之前辛苦录好的全部试次</b>——而且是静默的：
     * 测试早已因为"LLM 调用失败"红了，没人会想到红的背后还顺手毁了数据。
     *
     * <p>这个坑是真踩出来的：一次 HTTP 402 之后，盒子里只剩下
     * {@code caseId} 和 {@code recordedAt} 两个字段。
     * 那次恰好是新用例、没有存量可毁；换成任一既有用例就是<b>无法挽回的丢失</b>
     * （录制要花真金白银，而且模型行为不可复现，重录也回不到原样）。
     *
     * <p>所以这里的规则是：<b>本次试验一条都没录到，就当无事发生。</b>
     * 宁可少一次录制，不可毁一盒历史。
     */
    public void flush() {
        Cassette c = this.current;
        if (c == null) return;

        if (c.size(currentTrial) == 0) {
            log.warn("[AgentEval] ★放弃写盘：{} 第 {} 次试验一条交互都没录到"
                            + "（通常是上游调用失败）。写盘是整盒重写，"
                            + "此时落盘会用空盒覆盖既有录制，故跳过。",
                    c.getCaseId(), currentTrial);
            return;
        }

        CassetteStore.save(c);
        log.info("[AgentEval] 录制完成：{}（{} 次试验，本次 {} 条交互）→ {}",
                c.getCaseId(), c.trialCount(), c.size(currentTrial),
                CassetteStore.pathFor(c.getCaseId()));
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // 录制模式是<b>唯一</b>能测到真实上游耗时的时机：回放不联网。
        // 因此这里把耗时一并存进盒子，让延迟成为可长期追踪的量而不是一次性观察。
        long startNs = System.nanoTime();
        ChatResponse resp = delegate.chat(request);
        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;

        Cassette c = this.current;
        if (c != null) {
            if (c.getRecordedModel() == null) {
                c.setRecordedModel(request.model());
            }
            String usageJson = null;
            if (resp.usage() != null && !resp.usage().isMissingNode()) {
                try {
                    usageJson = om.writeValueAsString(resp.usage());
                } catch (Exception ignore) {
                    // usage 序列化失败不影响录制主体
                }
            }
            c.add(this.currentTrial, new Cassette.LlmInteraction(
                    counter.getAndIncrement(),
                    request.purpose().name(),
                    CassetteStore.fingerprint(request),
                    CassetteStore.digest(request),
                    resp.content(),
                    usageJson,
                    latencyMs));
        }
        usage.observe(request, TokenUsage.parse(resp.usage()), resp.content(), latencyMs);
        return resp;
    }

    @Override
    public List<float[]> embed(EmbedRequest request) {
        // Embedding 不参与录制：评测用例中的向量检索一律 mock 掉
        // （真实向量依赖 MySQL 数据与外部 embedding 服务，不属于 Agent 决策质量的评测范围）
        return delegate.embed(request);
    }
}
