package org.zhzssp.memorandum.agenteval.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zhzssp.memorandum.agenteval.cassette.Cassette;
import org.zhzssp.memorandum.agenteval.cassette.CassetteStore;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

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
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile Cassette current;

    public RecordingLlmTransport(LlmTransport delegate, ObjectMapper om) {
        this.delegate = delegate;
        this.om = om;
    }

    /** 开始录制一个用例。同一个 Transport 实例可顺序录制多个用例。 */
    public void beginCase(String caseId) {
        this.current = new Cassette(caseId, LocalDateTime.now().toString(), null);
        this.counter.set(0);
        log.info("[AgentEval] 开始录制用例：{}", caseId);
    }

    /** 结束并写盘。 */
    public void flush() {
        Cassette c = this.current;
        if (c == null) return;
        CassetteStore.save(c);
        log.info("[AgentEval] 录制完成：{}（{} 次 LLM 交互）→ {}",
                c.getCaseId(), c.size(), CassetteStore.pathFor(c.getCaseId()));
        this.current = null;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse resp = delegate.chat(request);
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
            c.add(new Cassette.LlmInteraction(
                    counter.getAndIncrement(),
                    request.purpose().name(),
                    CassetteStore.fingerprint(request),
                    CassetteStore.digest(request),
                    resp.content(),
                    usageJson));
        }
        return resp;
    }

    @Override
    public List<float[]> embed(EmbedRequest request) {
        // Embedding 不参与录制：评测用例中的向量检索一律 mock 掉
        // （真实向量依赖 MySQL 数据与外部 embedding 服务，不属于 Agent 决策质量的评测范围）
        return delegate.embed(request);
    }
}
