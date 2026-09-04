package org.zhzssp.memorandum.agenteval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.zhzssp.memorandum.agenteval.cost.UsageAccumulator;
import org.zhzssp.memorandum.agenteval.trace.CollectingTraceListener;
import org.zhzssp.memorandum.agenteval.transport.RecordingLlmTransport;
import org.zhzssp.memorandum.agenteval.transport.ReplayLlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.HttpLlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;

/**
 * 评测运行时的 Spring 装配。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>replay</b>（默认）：注入 {@link ReplayLlmTransport}，完全离线，CI 常态运行</li>
 *   <li><b>record</b>（{@code -Dagent.eval.mode=record}）：注入 {@link RecordingLlmTransport}，
 *       真实联网并落盘录制</li>
 * </ul>
 *
 * <p>用 {@code @Primary} 覆盖生产的 {@link HttpLlmTransport}，
 * 这样 {@code LlmGateway} 无需任何改动即可被接管——
 * 这正是把可替换点设在传输层而非 Gateway 的收益。
 */
@TestConfiguration
public class AgentEvalConfig {

    public static final String MODE_PROPERTY = "agent.eval.mode";
    public static final String MODE_RECORD = "record";

    /** 当前是否为录制模式。 */
    public static boolean isRecordMode() {
        return MODE_RECORD.equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "replay"));
    }

    /**
     * token / 字符 / 延迟的累计器（P5）。
     *
     * <p>放在传输层而非 {@code AgentTraceListener}：传输层是唯一看得见完整响应体的地方，
     * 而给生产的监听器接口加方法会波及所有实现类，为一个纯评测需求不值当。
     */
    @Bean
    public UsageAccumulator usageAccumulator() {
        return new UsageAccumulator();
    }

    @Bean
    @Primary
    public LlmTransport evalLlmTransport(ObjectMapper om, HttpLlmTransport httpTransport,
                                         UsageAccumulator usage) {
        return isRecordMode()
                ? new RecordingLlmTransport(httpTransport, om, usage)
                : new ReplayLlmTransport(om, usage);
    }

    /**
     * 轨迹收集器。注册为 Bean 后会被 {@code AgentTraceBus} 自动纳入
     * （它注入的是 {@code List<AgentTraceListener>}），无需额外接线。
     */
    @Bean
    public CollectingTraceListener collectingTraceListener() {
        return new CollectingTraceListener();
    }
}
