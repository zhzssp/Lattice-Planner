package org.zhzssp.memorandum.feature.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.llm.LlmRouter;
import org.zhzssp.memorandum.feature.agent.runtime.PrefixCacheMetrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 网关：统一对外暴露 {@link #generateChat} / {@link #generateText} / {@link #generateEmbedding}。
 *
 * <p>{@code generateChat} 经 {@link LlmRouter} 按当前用户路由到对应 provider/endpoint/model；
 * {@code generateText} 与 {@code generateEmbedding} 保留旧 @Value 路径（避免影响 Scheduler/异步线程）。</p>
 */
@Component
public class LlmGateway {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    @Value("${agent.llm.model:" + DEFAULT_MODEL + "}")
    private String model;

    /**
     * 多轮对话专用模型；保留作为 default-model 未配置时的兜底。
     */
    @Value("${agent.chat.model:deepseek-chat}")
    private String chatModelOverride;

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

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmRouter router;
    private final PrefixCacheMetrics prefixMetrics;

    public LlmGateway(ObjectMapper objectMapper, LlmRouter router, PrefixCacheMetrics prefixMetrics) {
        this.objectMapper = objectMapper;
        this.router = router;
        this.prefixMetrics = prefixMetrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String generateText(String prompt) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Agent LLM API key is missing. Please set agent.llm.api-key or DEEPSEEK_API_KEY.");
        }

        try {
            String endpoint = normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.3,
                    "messages", new Object[]{
                            Map.of("role", "system", "content", "You are a precise planning assistant."),
                            Map.of("role", "user", "content", prompt)
                    }
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DeepSeek API request failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String text = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asText();

            if (text == null || text.isBlank()) {
                throw new IllegalStateException("LLM returned empty response.");
            }
            return text.trim();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to call DeepSeek API.", ex);
        }
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
        return postChat(target.baseUrl(), target.apiKey(), target.modelId(), messages, 0.2, 90);
    }

    /**
     * 通用 Chat Completions 投递（供 generateChat + 可能的后台/fallback 路径复用）。
     *
     * <p>P4：顺带读取响应 {@code usage} 中的 prompt cache token 统计并上报
     * {@link PrefixCacheMetrics}。DeepSeek 的 context caching 是自动的（按前缀命中，
     * 无需显式传 cache_id），因此这里只做「观测」，不改请求协议。</p>
     */
    @SuppressWarnings("unchecked")
    private String postChat(String baseUrl, String apiKey, String modelId,
                            List<?> messages, double temperature, int timeoutSeconds) {
        try {
            String endpoint = normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", modelId,
                    "temperature", temperature,
                    "messages", messages
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Chat API failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            prefixMetrics.recordChatCall();
            recordPromptCacheUsage(root.path("usage"));
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            return contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText("").trim();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call Chat API.", ex);
        }
    }

    /**
     * 从 usage 提取 prompt cache 命中/未命中 token 数，兼容两种上游格式：
     * <ul>
     *   <li>DeepSeek：{@code usage.prompt_cache_hit_tokens} / {@code usage.prompt_cache_miss_tokens}</li>
     *   <li>OpenAI：{@code usage.prompt_tokens_details.cached_tokens}（未命中数用 prompt_tokens 相减）</li>
     * </ul>
     * 任何解析异常都静默忽略——观测不能影响主链路。
     */
    private void recordPromptCacheUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        try {
            long hit = usage.path("prompt_cache_hit_tokens").asLong(-1);
            long miss = usage.path("prompt_cache_miss_tokens").asLong(-1);
            if (hit >= 0 || miss >= 0) {
                prefixMetrics.recordPromptCacheTokens(Math.max(0, hit), Math.max(0, miss));
                return;
            }
            // OpenAI 风格回退
            long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(-1);
            if (cached >= 0) {
                long promptTokens = usage.path("prompt_tokens").asLong(0);
                prefixMetrics.recordPromptCacheTokens(cached, Math.max(0, promptTokens - cached));
            }
        } catch (Exception ignore) {
            // 观测失败不影响主流程
        }
    }

    /**
     * 调用 OpenAI-Compatible /v1/embeddings 接口。
     * 返回与输入顺序对齐的向量数组；不缓存，由调用方（NoteIndexService / RagSearchService）控制频率。
     *
     * 失败时抛 IllegalStateException 给上层，RagSearchService 会捕获并降级到纯关键字。
     */
    public java.util.List<float[]> generateEmbedding(java.util.List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) return java.util.List.of();

        String key = (embeddingApiKey != null && !embeddingApiKey.isBlank())
                ? embeddingApiKey.trim() : resolveApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Embedding API key 未配置：请设置 agent.embedding.api-key（或 agent.llm.api-key 作为回落）");
        }
        String base = (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank())
                ? normalizeBaseUrl(embeddingBaseUrl) : normalizeBaseUrl(baseUrl);

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", embeddingModel,
                    "input", inputs
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Embedding API 请求失败：HTTP " + resp.statusCode() + " - " + resp.body());
            }
            JsonNode arr = objectMapper.readTree(resp.body()).path("data");
            java.util.List<float[]> out = new java.util.ArrayList<>(inputs.size());
            for (JsonNode n : arr) {
                JsonNode emb = n.path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) v[i] = (float) emb.get(i).asDouble();
                out.add(v);
            }
            return out;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Embedding API 失败", ex);
        }
    }

    /** 当前 embedding 模型名（写入 note_embedding.model 字段，便于后续切换模型时识别旧向量）。 */
    public String embeddingModelName() {
        return embeddingModel;
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String resolveApiKey() {
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }
        String env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }
}
