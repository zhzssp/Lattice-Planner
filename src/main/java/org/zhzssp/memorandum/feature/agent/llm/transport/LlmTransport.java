package org.zhzssp.memorandum.feature.agent.llm.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * LLM 传输层抽象：只负责「把请求发出去、把响应拿回来」，不含任何业务语义。
 *
 * <p><strong>为什么要抽这一层</strong>：Agent 评测的核心矛盾是 LLM 的非确定性——
 * 同样的输入两次运行可能走出不同的工具调用路径，无法写稳定断言。
 * 把可替换点设在 HTTP 边界（而不是替换整个 {@code LlmGateway}），
 * 好处是测试时<strong>只有网络调用被固定</strong>，而模型路由（{@code LlmRouter}）、
 * usage 解析、前缀缓存、ReAct 循环、工具反射调用、CRAG 分支全部真实执行。
 * 这样测出来的轨迹才有意义。
 *
 * <p>实现：
 * <ul>
 *   <li>{@code HttpLlmTransport}——生产实现，真实 HTTP</li>
 *   <li>{@code RecordingLlmTransport}（测试）——装饰生产实现，透传并把交互落盘</li>
 *   <li>{@code ReplayLlmTransport}（测试）——不联网，按录制内容回放</li>
 * </ul>
 */
public interface LlmTransport {

    /** 投递一次 Chat Completions 请求。 */
    ChatResponse chat(ChatRequest request);

    /** 投递一次 Embeddings 请求。 */
    List<float[]> embed(EmbedRequest request);

    /**
     * Chat 请求参数。
     *
     * @param baseUrl        上游地址（不含路径）
     * @param apiKey         凭据
     * @param model          模型 id
     * @param messages       消息列表（原样序列化，不做加工）
     * @param temperature    采样温度
     * @param timeoutSeconds 单次请求超时
     * @param purpose        调用用途，仅用于错误消息与录制归档，不影响请求内容
     */
    record ChatRequest(
            String baseUrl,
            String apiKey,
            String model,
            List<?> messages,
            double temperature,
            int timeoutSeconds,
            Purpose purpose
    ) {}

    /**
     * Chat 响应。
     *
     * @param content 抽取出的文本内容（可能为空串）
     * @param usage   原始 usage 节点，供上层解析 token / prompt cache 统计；无则为 null
     */
    record ChatResponse(String content, JsonNode usage) {
        public static ChatResponse of(String content) {
            return new ChatResponse(content, null);
        }
    }

    /** Embedding 请求参数。 */
    record EmbedRequest(
            String baseUrl,
            String apiKey,
            String model,
            List<String> inputs,
            int timeoutSeconds
    ) {}

    /**
     * 调用用途。用于区分错误消息措辞（保持与重构前一致），
     * 并在录制时作为归档维度（便于人工检视录制内容）。
     */
    enum Purpose {
        /** 单轮文本生成：摘要 / 改写 / rerank / 规划草稿等 */
        TEXT,
        /** 多轮对话：ReAct 主循环与子代理 */
        CHAT
    }

    /**
     * 上游返回非 2xx 时抛出。
     *
     * <p>刻意携带原始 status 与 body：上层需要按 status 区分处理
     * （例如 402 余额不足要给用户明确提示，而非笼统的"调用失败"）。
     */
    class LlmHttpException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public LlmHttpException(int statusCode, String responseBody) {
            super("HTTP " + statusCode + " - " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public String responseBody() {
            return responseBody;
        }
    }

    /** 网络层失败（连接超时 / IO 中断等），与「上游明确返回错误码」区分开。 */
    class LlmTransportException extends RuntimeException {
        public LlmTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
