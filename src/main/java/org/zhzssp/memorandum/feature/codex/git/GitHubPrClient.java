package org.zhzssp.memorandum.feature.codex.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub Pull Request 创建（可选能力）。
 *
 * <h3>为什么用 PR 取代自建的「待采纳卡片」</h3>
 * <p>早期方案设计了一张 {@code harvest_candidate} 表加一套卡片流 UI 来审阅 Agent 产出。
 * 换成 PR 之后，这些全部可以删掉，并且白拿四样东西：
 * 逐行 diff 审阅、行级评论、一键回滚、手机上能审。
 * 自己实现其中任何一样都不会比 GitHub 做得更好。</p>
 *
 * <h3>为什么它是可选的</h3>
 * <p>{@code provider=LOCAL} 是一等公民：不连任何远端也要能全功能运行。
 * 没有 token 时流程停在本地分支，UI 提供「查看 diff / 合并 / 丢弃」，
 * 审阅这一环并不缺失，只是少了远端协作。把 GitHub 做成必需会让
 * 「离线也能用自己的知识库」这条承诺失效。</p>
 *
 * <h3>凭证不落库</h3>
 * <p>{@code knowledge_repo.token_ref} 存的是<strong>环境变量名</strong>而非 token 本身。
 * 明文 token 进数据库后，任何一次数据库备份、日志转储、截图都可能把它带出去，
 * 而 GitHub token 的权限通常远超本软件所需。</p>
 */
@Component
public class GitHubPrClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrClient.class);

    private static final String API_VERSION = "2022-11-28";

    /** PR 创建结果。 */
    public record PrResult(boolean ok, String code, String message,
                           Integer number, String url) {

        public static PrResult fail(String code, String message) {
            return new PrResult(false, code, message, null, null);
        }
    }

    /** 从远端地址解析出的仓库坐标。 */
    public record Slug(String owner, String repo) {}

    private final ObjectMapper om;
    private final HttpClient http;

    @Value("${codex.github.token:}")
    private String configuredToken;

    @Value("${codex.github.api-base:https://api.github.com}")
    private String apiBase;

    @Value("${codex.github.timeout-seconds:30}")
    private int timeoutSeconds;

    public GitHubPrClient(ObjectMapper om) {
        this.om = om;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 解析 token。
     *
     * <p>优先级：仓库上配置的环境变量名 → 全局 {@code codex.github.token}。
     * 前者优先是为了支持多个仓库用不同权限的 token。</p>
     */
    public String resolveToken(String tokenRef) {
        if (tokenRef != null && !tokenRef.isBlank()) {
            String v = System.getenv(tokenRef.strip());
            if (v != null && !v.isBlank()) return v.strip();
            // 显式配了却取不到，必须让用户知道——否则会以为是网络问题
            log.warn("[Codex] 仓库配置的 token 环境变量「{}」未设置或为空", tokenRef);
        }
        if (configuredToken != null && !configuredToken.isBlank()) return configuredToken.strip();
        return null;
    }

    public boolean available(String tokenRef) {
        return resolveToken(tokenRef) != null;
    }

    /**
     * 从 git 远端地址解析 owner/repo。
     *
     * <p>支持 {@code https://host/o/r(.git)}、{@code git@host:o/r(.git)}、
     * {@code ssh://git@host/o/r(.git)} 三种常见写法。</p>
     */
    public Slug parseSlug(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String u = remoteUrl.strip();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.toLowerCase().endsWith(".git")) u = u.substring(0, u.length() - 4);

        String path;
        int at = u.indexOf('@');
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ssh://")) {
            int schemeEnd = u.indexOf("://") + 3;
            int slash = u.indexOf('/', schemeEnd);
            if (slash < 0) return null;
            path = u.substring(slash + 1);
        } else if (at > 0 && u.indexOf(':', at) > 0) {
            path = u.substring(u.indexOf(':', at) + 1);
        } else {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length < 2) return null;
        // 取最后两段：兼容自建实例可能带的额外路径前缀
        String owner = parts[parts.length - 2];
        String repo = parts[parts.length - 1];
        if (owner.isBlank() || repo.isBlank()) return null;
        return new Slug(owner, repo);
    }

    /**
     * 创建 PR。
     *
     * <p>任何失败都返回 {@link PrResult} 而<strong>不抛异常</strong>：
     * 此时本地分支已经推送成功，内容不会丢——把远端 API 的失败升级成整个流程失败，
     * 会让用户误以为沉淀白做了。</p>
     */
    public PrResult createPullRequest(String remoteUrl, String tokenRef,
                                      String head, String base,
                                      String title, String body) {
        String token = resolveToken(tokenRef);
        if (token == null) {
            return PrResult.fail("NO_TOKEN",
                    "未配置 GitHub token，无法创建 PR。分支已推送，可在网页端手动开 PR；"
                            + "或设置环境变量后重试（仓库的 tokenRef 指定变量名，"
                            + "或配置 codex.github.token）。");
        }
        Slug slug = parseSlug(remoteUrl);
        if (slug == null) {
            return PrResult.fail("BAD_REMOTE",
                    "无法从远端地址解析 owner/repo：" + remoteUrl);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("head", head);
        payload.put("base", base);
        if (body != null && !body.isBlank()) payload.put("body", body);

        try {
            String json = om.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/repos/" + slug.owner() + "/"
                            + slug.repo() + "/pulls"))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 201) {
                JsonNode n = om.readTree(resp.body());
                int number = n.path("number").asInt();
                String url = n.path("html_url").asText(null);
                log.info("[Codex] 已创建 PR #{}：{}", number, url);
                return new PrResult(true, "CREATED", "PR 已创建", number, url);
            }
            String detail = extractMessage(resp.body());
            if (sc == 422) {
                // 422 最常见的两种：该分支已有 PR、或分支与 base 无差异
                return PrResult.fail("UNPROCESSABLE",
                        "GitHub 拒绝创建（422）：" + detail
                                + "。常见原因是该分支已存在 PR，或与目标分支无差异。");
            }
            if (sc == 401 || sc == 403) {
                return PrResult.fail("UNAUTHORIZED",
                        "GitHub 鉴权失败（" + sc + "）：" + detail
                                + "。请确认 token 未过期且具备该仓库的 pull_request 写权限。");
            }
            return PrResult.fail("HTTP_" + sc, "GitHub 返回 " + sc + "：" + detail);
        } catch (Exception e) {
            return PrResult.fail("REQUEST_FAILED",
                    "调用 GitHub API 失败：" + e.getMessage()
                            + "。分支已推送，内容未丢失，可在网页端手动开 PR。");
        }
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) return "(无响应内容)";
        try {
            JsonNode n = om.readTree(body);
            StringBuilder sb = new StringBuilder(n.path("message").asText(""));
            JsonNode errs = n.get("errors");
            if (errs != null && errs.isArray()) {
                for (JsonNode e : errs) {
                    String m = e.path("message").asText("");
                    if (!m.isBlank()) sb.append(" | ").append(m);
                }
            }
            String s = sb.toString().strip();
            return s.isEmpty() ? body.substring(0, Math.min(200, body.length())) : s;
        } catch (Exception e) {
            return body.substring(0, Math.min(200, body.length()));
        }
    }
}
