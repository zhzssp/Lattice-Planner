package org.zhzssp.memorandum.feature.agent.llm.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生产实现：基于 Java 21 {@link HttpClient} 的 OpenAI-Compatible 传输层。
 *
 * <p>只做四件事：拼请求体、发 HTTP、校验状态码、抽取响应字段。
 * 不含模型路由、不含 usage 统计上报、不含重试策略——那些都属于
 * {@code LlmGateway} 的业务职责。
 */
@Component
public class HttpLlmTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmTransport.class);
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private final ObjectMapper om;
    private final HttpClient httpClient;

    public HttpLlmTransport(ObjectMapper om) {
        this.om = om;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String endpoint = normalizeBaseUrl(request.baseUrl()) + "/v1/chat/completions";
        try {
            String body = om.writeValueAsString(Map.of(
                    "model", request.model(),
                    "temperature", request.temperature(),
                    "messages", request.messages()
            ));
            String raw = send(endpoint, request.apiKey(), body, request.timeoutSeconds());

            JsonNode root = om.readTree(raw);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = (contentNode.isMissingNode() || contentNode.isNull())
                    ? "" : contentNode.asText("").trim();

            JsonNode usage = root.path("usage");
            return new ChatResponse(content, usage.isMissingNode() ? null : usage);
        } catch (LlmHttpException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmTransportException("调用 Chat API 失败：" + endpoint, e);
        } catch (Exception e) {
            throw new LlmTransportException("解析 Chat 响应失败：" + endpoint, e);
        }
    }

    @Override
    public List<float[]> embed(EmbedRequest request) {
        if (request.inputs() == null || request.inputs().isEmpty()) return List.of();
        String endpoint = normalizeBaseUrl(request.baseUrl()) + "/v1/embeddings";
        try {
            String body = om.writeValueAsString(Map.of(
                    "model", request.model(),
                    "input", request.inputs()
            ));
            String raw = send(endpoint, request.apiKey(), body, request.timeoutSeconds());

            JsonNode arr = om.readTree(raw).path("data");
            List<float[]> out = new ArrayList<>(request.inputs().size());
            for (JsonNode n : arr) {
                JsonNode emb = n.path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                out.add(v);
            }
            return out;
        } catch (LlmHttpException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmTransportException("调用 Embedding API 失败：" + endpoint, e);
        } catch (Exception e) {
            throw new LlmTransportException("解析 Embedding 响应失败：" + endpoint, e);
        }
    }

    /* ---- 内部 ---- */

    private String send(String endpoint, String apiKey, String body, int timeoutSeconds)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("[LLM] 上游返回非 2xx：{} status={}", endpoint, resp.statusCode());
            throw new LlmHttpException(resp.statusCode(), resp.body());
        }
        return resp.body();
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BASE_URL;
        String t = raw.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }
}
