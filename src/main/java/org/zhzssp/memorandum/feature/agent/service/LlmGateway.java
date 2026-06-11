package org.zhzssp.memorandum.feature.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 单一 LLM 网关：所有 Agent 统一从这里调用。
 *
 * 当前适配：DeepSeek Chat Completions API（OpenAI-Compatible）
 */
@Component
public class LlmGateway {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    @Value("${agent.llm.model:" + DEFAULT_MODEL + "}")
    private String model;

    /**
     * 多轮对话专用模型；默认强制使用 deepseek-chat，避免 deepseek-reasoner
     * 输出 reasoning_content / &lt;think&gt; 段干扰工具调用 JSON 解析。
     */
    @Value("${agent.chat.model:deepseek-chat}")
    private String chatModelOverride;

    @Value("${agent.llm.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    /**
     * 在 application.properties 中配置：
     * agent.llm.api-key=YOUR_DEEPSEEK_API_KEY
     */
    @Value("${agent.llm.api-key:}")
    private String configuredApiKey;

    /**
     * Embedding 独立配置：DeepSeek 不提供 embedding，必须接入 OpenAI-Compatible
     * 兼容端点（硅基流动 / 智谱 / 通义 / 本地 Ollama nomic-embed-text 等）。
     * 留空时 base-url 回落到 chat 的 baseUrl，api-key 回落到 resolveApiKey()。
     */
    @Value("${agent.embedding.model:bge-m3}")
    private String embeddingModel;

    @Value("${agent.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${agent.embedding.api-key:}")
    private String embeddingApiKey;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
     * 多轮对话调用：直接传完整 messages 列表（含 system / user / assistant）。
     * 使用 chatModelOverride（默认 deepseek-chat）避免 reasoner 推理段干扰。
     * 与 generateText 的差异：① 不强制非空 ② 调用方自行拼 messages ③ 模型独立可配。
     */
    public String generateChat(java.util.List<java.util.Map<String, String>> messages) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Agent LLM API key is missing. Please set agent.llm.api-key or DEEPSEEK_API_KEY.");
        }
        try {
            String endpoint = normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
            String chatModel = (chatModelOverride == null || chatModelOverride.isBlank())
                    ? DEFAULT_MODEL : chatModelOverride;
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", chatModel,
                    "temperature", 0.2,
                    "messages", messages
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DeepSeek Chat API failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode contentNode = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            return contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText("").trim();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call DeepSeek Chat API.", ex);
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
