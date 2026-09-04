package org.zhzssp.memorandum.feature.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.llm.LlmRouter;
import org.zhzssp.memorandum.feature.agent.llm.transport.LlmTransport;
import org.zhzssp.memorandum.feature.agent.llm.transport.TokenUsage;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCacheMetrics;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关：统一对外暴露 {@link #generateChat} / {@link #generateText} / {@link #generateEmbedding}。
 *
 * <p>{@code generateChat} 经 {@link LlmRouter} 按当前用户路由到对应 provider/endpoint/model；
 * {@code generateText} 与 {@code generateEmbedding} 保留旧 @Value 路径（避免影响 Scheduler/异步线程）。</p>
 *
 * <p><strong>分层</strong>：本类负责业务语义（路由、参数默认值、usage 统计上报、空响应校验），
 * 实际 HTTP 投递委托给 {@link LlmTransport}。这个切分使得 Agent 评测可以替换传输层来固定
 * LLM 响应，而本类的路由与统计逻辑仍被真实执行。</p>
 */
@Component
public class LlmGateway {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** generateText 的固定参数（历史行为，不要随意改动） */
    private static final double TEXT_TEMPERATURE = 0.3;
    private static final int TEXT_TIMEOUT_SECONDS = 60;
    private static final String TEXT_SYSTEM_PROMPT = "You are a precise planning assistant.";

    /** generateChat 的固定参数 */
    private static final double CHAT_TEMPERATURE = 0.2;
    private static final int CHAT_TIMEOUT_SECONDS = 90;

    private static final int EMBED_TIMEOUT_SECONDS = 60;

    @Value("${agent.llm.model:" + DEFAULT_MODEL + "}")
    private String model;

    @Value("${agent.llm.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${agent.llm.api-key:}")
    private String configuredApiKey;

    /**
     * Embedding 独立配置：DeepSeek 不提供 embedding，必须接入 OpenAI-Compatible
     * 兼容端点（硅基流动 / 智谱 / 通义 / 本地 Ollama nomic-embed-text 等）。
     */
    @Value("${agent.embedding.model:bge-m3}")
    private String embeddingModel;

    @Value("${agent.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${agent.embedding.api-key:}")
    private String embeddingApiKey;

    private final LlmTransport transport;
    private final LlmRouter router;
    private final PrefixCacheMetrics prefixMetrics;

    public LlmGateway(LlmTransport transport, LlmRouter router, PrefixCacheMetrics prefixMetrics) {
        this.transport = transport;
        this.router = router;
        this.prefixMetrics = prefixMetrics;
    }

    /**
     * 单轮文本生成：摘要 / 查询改写 / rerank / 规划草稿等。
     *
     * <p>不经 LlmRouter——因为它会被 Scheduler、异步索引等无 AgentContext 的线程调用。</p>
     */
    public String generateText(String prompt) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Agent LLM API key is missing. Please set agent.llm.api-key or DEEPSEEK_API_KEY.");
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", TEXT_SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        );

        LlmTransport.ChatResponse resp;
        try {
            resp = transport.chat(new LlmTransport.ChatRequest(
                    baseUrl, apiKey, model, messages,
                    TEXT_TEMPERATURE, TEXT_TIMEOUT_SECONDS, LlmTransport.Purpose.TEXT));
        } catch (LlmTransport.LlmHttpException ex) {
            throw new IllegalStateException(
                    "DeepSeek API request failed: HTTP " + ex.statusCode() + " - " + ex.responseBody());
        } catch (LlmTransport.LlmTransportException ex) {
            throw new IllegalStateException("Failed to call DeepSeek API.", ex);
        }

        String text = resp.content();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM returned empty response.");
        }
        return text.trim();
    }

    /**
     * 多轮对话调用：经 {@link LlmRouter} 路由到当前用户所选模型。
     * 与 generateText 的差异：① 不强制非空 ② 调用方自行拼 messages ③ 按用户偏好选模型。
     */
    public String generateChat(List<Map<String, String>> messages) {
        LlmRouter.ResolvedTarget target;
        try {
            target = router.resolveForCurrentUser();
        } catch (Exception ex) {
            throw new IllegalStateException("LlmRouter 路由失败：" + ex.getMessage(), ex);
        }

        LlmTransport.ChatResponse resp;
        try {
            resp = transport.chat(new LlmTransport.ChatRequest(
                    target.baseUrl(), target.apiKey(), target.modelId(), messages,
                    CHAT_TEMPERATURE, CHAT_TIMEOUT_SECONDS, LlmTransport.Purpose.CHAT));
        } catch (LlmTransport.LlmHttpException ex) {
            throw new IllegalStateException(
                    "Chat API failed: HTTP " + ex.statusCode() + " - " + ex.responseBody());
        } catch (LlmTransport.LlmTransportException ex) {
            throw new IllegalStateException("Failed to call Chat API.", ex);
        }

        prefixMetrics.recordChatCall();
        recordPromptCacheUsage(resp.usage());
        return resp.content();
    }

    /**
     * 调用 OpenAI-Compatible /v1/embeddings 接口。
     * 返回与输入顺序对齐的向量数组；不缓存，由调用方（NoteIndexService / RagSearchService）控制频率。
     *
     * 失败时抛 IllegalStateException 给上层，RagSearchService 会捕获并降级到纯关键字。
     */
    public List<float[]> generateEmbedding(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();

        String key = (embeddingApiKey != null && !embeddingApiKey.isBlank())
                ? embeddingApiKey.trim() : resolveApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Embedding API key 未配置：请设置 agent.embedding.api-key（或 agent.llm.api-key 作为回落）");
        }
        String base = (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank())
                ? embeddingBaseUrl : baseUrl;

        try {
            return transport.embed(new LlmTransport.EmbedRequest(
                    base, key, embeddingModel, inputs, EMBED_TIMEOUT_SECONDS));
        } catch (LlmTransport.LlmHttpException ex) {
            throw new IllegalStateException(
                    "Embedding API 请求失败：HTTP " + ex.statusCode() + " - " + ex.responseBody());
        } catch (LlmTransport.LlmTransportException ex) {
            throw new IllegalStateException("调用 Embedding API 失败", ex);
        }
    }

    /** 当前 embedding 模型名（写入 note_embedding.model 字段，便于后续切换模型时识别旧向量）。 */
    public String embeddingModelName() {
        return embeddingModel;
    }

    /* ---- 内部 ---- */

    /**
     * 从 usage 提取 prompt cache 命中/未命中 token 数。
     *
     * <p>解析本身交给 {@link org.zhzssp.memorandum.feature.agent.llm.transport.TokenUsage}——
     * 评测侧的成本核算读的是同一份实现。两边各写一份的话，
     * 成本报告描述的就不是线上真正在跑的那个东西了。
     */
    private void recordPromptCacheUsage(JsonNode usage) {
        TokenUsage u = TokenUsage.parse(usage);
        if (u.present()) {
            prefixMetrics.recordPromptCacheTokens(u.cacheHitTokens(), u.cacheMissTokens());
        }
    }

    private String resolveApiKey() {
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }
        String env = System.getenv("DEEPSEEK_API_KEY");
        return (env != null && !env.isBlank()) ? env.trim() : null;
    }
}
