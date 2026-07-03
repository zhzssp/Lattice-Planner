package org.zhzssp.memorandum.feature.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 可选模型目录：从 {@link LlmProperties} 读取并校验，提供查询入口。
 */
@Component
public class ModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

    private final LlmProperties props;
    private final Map<String, LlmProperties.Provider> providerMap;
    private final List<LlmProperties.ModelDef> availableModels;
    private final String defaultModelId;

    public ModelCatalog(LlmProperties props) {
        this.props = props;
        this.providerMap = props.getProviders().stream()
                .collect(Collectors.toMap(
                        LlmProperties.Provider::getId,
                        p -> p,
                        (a, b) -> a));

        // 校验并过滤 model：providerId 必须存在，enabled=true 才上架
        this.availableModels = props.getModels().stream()
                .filter(m -> {
                    if (!providerMap.containsKey(m.getProviderId())) {
                        log.warn("[ModelCatalog] 模型 {} 的 providerId={} 不存在，已跳过",
                                m.getId(), m.getProviderId());
                        return false;
                    }
                    return true;
                })
                .filter(LlmProperties.ModelDef::isEnabled)
                .toList();

        this.defaultModelId = ensureDefaultModel();
    }

    private String ensureDefaultModel() {
        String def = props.getDefaultModel();
        if (def == null || def.isBlank()) {
            def = "deepseek-chat";
        }
        // 检查默认模型是否存在于可用列表中
        final String defFinal = def;
        boolean exists = availableModels.stream().anyMatch(m -> m.getId().equals(defFinal));
        if (!exists && !availableModels.isEmpty()) {
            def = availableModels.get(0).getId();
            log.info("[ModelCatalog] 默认模型 {} 不在可用列表中，回落为 {}", props.getDefaultModel(), def);
        }
        if (availableModels.isEmpty()) {
            log.warn("[ModelCatalog] 没有可用模型！请检查 agent.llm.models 配置");
        }
        return def;
    }

    /** 所有上架的可用模型（给前端下拉用）。 */
    public List<LlmProperties.ModelDef> availableModels() {
        return availableModels;
    }

    /** 按 modelId 查找模型。 */
    public Optional<LlmProperties.ModelDef> find(String modelId) {
        return availableModels.stream()
                .filter(m -> m.getId().equals(modelId))
                .findFirst();
    }

    /** 模型归属的提供方。 */
    public LlmProperties.Provider providerOf(String modelId) {
        return find(modelId)
                .map(m -> providerMap.get(m.getProviderId()))
                .orElse(null);
    }

    /** 系统默认模型 id（用户未选择时回落）。 */
    public String defaultModelId() {
        return defaultModelId;
    }

    /** 提供方显示名（给前端展示），不存在返回 id 本身。 */
    public String providerDisplayName(String providerId) {
        LlmProperties.Provider p = providerMap.get(providerId);
        return (p != null && p.getDisplayName() != null) ? p.getDisplayName() : providerId;
    }
}
