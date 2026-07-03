package org.zhzssp.memorandum.feature.agent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 多模型 / 多提供方配置绑定。
 *
 * <p>绑定前缀 {@code agent.llm}，通过 {@code @ConfigurationProperties} 将
 * {@code application.properties} 中的 provider/model/endpoint 列表自动注入。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    /** 用户未选择模型时的默认回退模型 id */
    private String defaultModel = "deepseek-chat";

    /** 提供方列表（每 provider 可配多 endpoint 做负载均衡） */
    private List<Provider> providers = new ArrayList<>();

    /** 上架给用户的可选模型列表 */
    private List<ModelDef> models = new ArrayList<>();

    // ---- getter / setter ----

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String v) { this.defaultModel = v; }

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> v) { this.providers = v; }

    public List<ModelDef> getModels() { return models; }
    public void setModels(List<ModelDef> v) { this.models = v; }

    /* ==================== 内嵌 POJO ==================== */

    public static class Provider {
        private String id;
        private String displayName;
        private String type = "OPENAI_COMPATIBLE";  // 目前只支持 OpenAI 风格
        private List<Endpoint> endpoints = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }

        public List<Endpoint> getEndpoints() { return endpoints; }
        public void setEndpoints(List<Endpoint> v) { this.endpoints = v; }
    }

    public static class Endpoint {
        private String baseUrl;
        private String apiKey;
        private int weight = 1;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }

        public int getWeight() { return weight; }
        public void setWeight(int v) { this.weight = Math.max(1, v); }
    }

    public static class ModelDef {
        private String id;
        private String displayName;
        private String providerId;
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }

        public String getProviderId() { return providerId; }
        public void setProviderId(String v) { this.providerId = v; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
