package org.zhzssp.memorandum.agenteval.cost;

import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.TokenUsage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个用例内的 LLM 用量累计器，由录制/回放两个传输层共同持有。
 *
 * <h3>为什么记在传输层，而不是给 AgentTraceListener 加方法</h3>
 * 给 {@code AgentTraceListener} 加一个 {@code onLlmUsage} 会改<b>生产接口</b>，
 * 所有实现类都得跟着动，而这个需求纯粹是评测侧的。
 * 传输层本来就是唯一看得见完整响应体的地方，放这里 blast radius 最小——
 * 这是「把可替换点设在 HTTP 边界」那条思路的直接延续。
 *
 * <h3>★ 它<b>刻意</b>记两套数，因为其中一套在回放时是假的</h3>
 * 这是整个 P5 成立与否的关键，必须说清楚：
 *
 * <table>
 *   <tr><th></th><th>来源</th><th>回放时是否可信</th></tr>
 *   <tr><td><b>上游 token</b>（{@code promptTokens} 等）</td>
 *       <td>响应体里的 {@code usage}</td>
 *       <td><b>否</b>——回放时它来自<b>录制盒</b>，是当初录制那一刻的值</td></tr>
 *   <tr><td><b>活体字符数</b>（{@code requestChars}）</td>
 *       <td>本次<b>真实发出</b>的 messages</td>
 *       <td><b>是</b>——它由当前代码算出，与录制无关</td></tr>
 * </table>
 *
 * <p>为什么这个区分要紧：假设有人往 system prompt 里塞了 2000 token 的新指令。
 * 回放时 {@code usage} 仍然原样返回<b>录制当天</b>的 token 数，
 * 于是一个建立在上游 token 上的成本门禁<b>结构上就不可能</b>发现这次膨胀——
 * 它会一路绿到线上账单变了才被发现。
 * 那正是这套评测体系一直在猎杀的那种假绿，只不过换到了成本维度。
 *
 * <p>所以门禁跑在<b>活体字符数</b>上（见 {@link BudgetGate}），
 * 上游 token 只用于换算真实成本与校准字符/token 比值。
 *
 * <h3>为什么用「字符数」而不是自己估 token</h3>
 * 自己估 token 需要一个系数（中文约 0.6 token/字、英文约 0.25），
 * 而系数会随分词器变化，估出来的数字有<b>虚假的精确感</b>。
 * 字符数是<b>可精确测量</b>的，且门禁比的是"相对基线涨了多少"——
 * 只要前后用同一把尺，系数根本不进入判定。
 * 真实 token 数则由录制时的 {@code usage} 提供，两者一比就得到本项目自己的
 * 字符/token 实测比值（见 {@link UsageSnapshot#charsPerPromptToken()}），
 * 于是这把尺是<b>被标定过</b>的，而不是拍脑袋的。
 *
 * <h3>并发</h3>
 * 子代理 fan-out 会并行发起调用，因此全部用原子量累加。
 */
public final class UsageAccumulator {

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong callsWithUsage = new AtomicLong();

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cacheHitTokens = new AtomicLong();
    private final AtomicLong cacheMissTokens = new AtomicLong();

    private final AtomicLong requestChars = new AtomicLong();
    private final AtomicLong responseChars = new AtomicLong();

    private final AtomicLong upstreamLatencyMs = new AtomicLong();
    private final AtomicLong latencySamples = new AtomicLong();

    /**
     * 观测一次 LLM 调用。
     *
     * @param request     本次<b>真实发出</b>的请求（活体尺寸由它算）
     * @param usage       上游返回的 usage（回放时来自录制盒，可能为 {@link TokenUsage#ABSENT}）
     * @param content     响应正文
     * @param latencyMs   上游耗时；<b>回放时应传 null</b>，见 {@link UsageSnapshot#upstreamLatencyMs()}
     */
    public void observe(LlmTransport.ChatRequest request, TokenUsage usage,
                        String content, Long latencyMs) {
        calls.incrementAndGet();

        if (usage != null && usage.present()) {
            callsWithUsage.incrementAndGet();
            promptTokens.addAndGet(usage.promptTokens());
            completionTokens.addAndGet(usage.completionTokens());
            cacheHitTokens.addAndGet(usage.cacheHitTokens());
            cacheMissTokens.addAndGet(usage.cacheMissTokens());
        }

        requestChars.addAndGet(measureRequestChars(request));
        responseChars.addAndGet(content == null ? 0 : content.length());

        if (latencyMs != null && latencyMs >= 0) {
            upstreamLatencyMs.addAndGet(latencyMs);
            latencySamples.incrementAndGet();
        }
    }

    /**
     * 本次请求的活体尺寸 = 所有 message 的 {@code content} 字符数之和。
     *
     * <p>刻意<b>只数 content，不数 JSON 结构</b>（role 字段、括号、引号）：
     * 那部分是每条消息一份的近似常量，把它算进来只会让"prompt 涨了多少"
     * 掺进"消息条数变了多少"，两件事应当分开看——后者已由 {@code llmCalls} 与步数覆盖。
     *
     * <p>工具目录不需要单独处理：它被拼进 system prompt 的正文里，
     * 所以工具描述写长了，这里立刻能看出来。
     */
    static long measureRequestChars(LlmTransport.ChatRequest request) {
        if (request == null || request.messages() == null) return 0;
        long sum = 0;
        for (Object m : request.messages()) {
            if (m instanceof Map<?, ?> map) {
                Object c = map.get("content");
                if (c != null) sum += String.valueOf(c).length();
            } else if (m != null) {
                sum += String.valueOf(m).length();
            }
        }
        return sum;
    }

    /** 清零。每个用例开始前调用，否则用量会跨用例累加。 */
    public void reset() {
        for (AtomicLong a : List.of(calls, callsWithUsage, promptTokens, completionTokens,
                cacheHitTokens, cacheMissTokens, requestChars, responseChars,
                upstreamLatencyMs, latencySamples)) {
            a.set(0);
        }
    }

    public UsageSnapshot snapshot() {
        return new UsageSnapshot(
                calls.get(), callsWithUsage.get(),
                promptTokens.get(), completionTokens.get(),
                cacheHitTokens.get(), cacheMissTokens.get(),
                requestChars.get(), responseChars.get(),
                latencySamples.get() == 0 ? null : upstreamLatencyMs.get()
        );
    }
}
