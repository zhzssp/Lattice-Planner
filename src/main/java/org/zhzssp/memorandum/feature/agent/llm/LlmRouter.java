package org.zhzssp.memorandum.feature.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.feature.agent.runtime.AgentContext;
import org.zhzssp.memorandum.service.UserPreferenceService;

/**
 * 模型路由器：按当前用户偏好解析目标 provider / endpoint / modelId。
 *
 * <p>调用链路：<br>
 * {@code AgentContext.requireUser()} → {@code UserPreference.agentChatModelId}
 * → {@code ModelCatalog} → {@code EndpointBalancer}</p>
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final ModelCatalog catalog;
    private final EndpointBalancer balancer;
    private final UserPreferenceService prefService;

    public LlmRouter(ModelCatalog catalog,
                     EndpointBalancer balancer,
                     UserPreferenceService prefService) {
        this.catalog = catalog;
        this.balancer = balancer;
        this.prefService = prefService;
    }

    /**
     * 解析当前 Agent 调用所需的目标模型与端点。
     *
     * <p>优先使用用户选中的模型；无效/为空时回落系统默认。
     * 所有调用方无须关心 provider/endpoint 细节。</p>
     */
    public ResolvedTarget resolveForCurrentUser() {
        String modelId;
        try {
            User u = AgentContext.requireUser();
            UserPreference pref = prefService.getOrCreatePreference(u);
            modelId = (pref.getAgentChatModelId() != null && !pref.getAgentChatModelId().isBlank())
                    ? pref.getAgentChatModelId() : catalog.defaultModelId();
            // 校验用户选中的模型仍存在且启用
            if (catalog.find(modelId).isEmpty()) {
                log.info("[LlmRouter] 用户 {} 选中的模型 {} 已不可用，回落默认 {}",
                        u.getId(), modelId, catalog.defaultModelId());
                modelId = catalog.defaultModelId();
            }
        } catch (Exception ex) {
            // AgentContext 未初始化（非 Agent 线程），回落默认
            log.debug("[LlmRouter] 无法获取 AgentContext.user，使用默认模型：{}", ex.getMessage());
            modelId = catalog.defaultModelId();
        }

        LlmProperties.Provider provider = catalog.providerOf(modelId);
        if (provider == null) {
            throw new IllegalStateException(
                    "[LlmRouter] 模型 " + modelId + " 没有对应的 provider，请检查配置");
        }
        LlmProperties.Endpoint endpoint = balancer.pick(provider);

        String baseUrl = endpoint.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "[LlmRouter] provider=" + provider.getId() + " model=" + modelId
                            + " 的 endpoint 未配置 base-url");
        }

        String apiKey = endpoint.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = envKeyFor(provider.getId());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "[LlmRouter] provider=" + provider.getId() + " model=" + modelId + " API key 未配置");
        }

        return new ResolvedTarget(modelId, provider.getId(), baseUrl, apiKey);
    }

    /**
     * 按 provider 选择环境变量回落来源。
     *
     * <p>不能对所有 provider 都回落 {@code DEEPSEEK_API_KEY}——那会让 openai-compat 等
     * 非 DeepSeek 提供方误用 DeepSeek 的 key，导致难以定位的 401。</p>
     */
    private String envKeyFor(String providerId) {
        if (providerId == null) return null;
        return switch (providerId) {
            case "deepseek" -> System.getenv("DEEPSEEK_API_KEY");
            case "openai-compat" -> System.getenv("OPENAI_COMPAT_API_KEY");
            // 未知 provider：按约定尝试 <PROVIDER_ID>_API_KEY（连字符转下划线并大写）
            default -> System.getenv(providerId.toUpperCase().replace('-', '_') + "_API_KEY");
        };
    }

    /** 当前 AgentContext 用户选中的模型 id（供前端展示），无法解析时返回默认值。 */
    public String currentModelId() {
        try {
            User u = AgentContext.requireUser();
            UserPreference pref = prefService.getOrCreatePreference(u);
            return (pref.getAgentChatModelId() != null && !pref.getAgentChatModelId().isBlank()
                    && catalog.find(pref.getAgentChatModelId()).isPresent())
                    ? pref.getAgentChatModelId() : catalog.defaultModelId();
        } catch (Exception ex) {
            return catalog.defaultModelId();
        }
    }

    /* ==================== 解析结果 ==================== */

    public record ResolvedTarget(
            String modelId,
            String providerId,
            String baseUrl,
            String apiKey
    ) {}
}
