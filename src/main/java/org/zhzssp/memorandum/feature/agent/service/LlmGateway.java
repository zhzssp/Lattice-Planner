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
