package org.zhzssp.memorandum.feature.agent.llm;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端点负载均衡器：对同一 provider 下的多个 endpoint 做加权轮询。
 *
 * <p>单 endpoint 直接返回（绝大多数情况）；多 endpoint 按 weight 展开后轮询分摊。</p>
 */
@Component
public class EndpointBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 从 provider 的 endpoints 中按加权轮询挑选一个。
     *
     * @param provider 提供方（含 endpoints 列表）
     * @return 选中的 endpoint
     * @throws IllegalStateException endpoints 为空时抛出。正常情况下不会发生——
     *         {@code ModelCatalog} 已在启动期剔除无可用 endpoint 的 provider 下的模型，
     *         此处仅作为防御性兜底。
     */
    public LlmProperties.Endpoint pick(LlmProperties.Provider provider) {
        List<LlmProperties.Endpoint> endpoints = provider.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException(
                    "[EndpointBalancer] provider " + provider.getId() + " 没有配置 endpoint");
        }
        if (endpoints.size() == 1) {
            return endpoints.get(0);
        }
        // 加权轮询
        AtomicInteger counter = counters.computeIfAbsent(
                provider.getId(), k -> new AtomicInteger(0));
        int totalWeight = endpoints.stream().mapToInt(LlmProperties.Endpoint::getWeight).sum();
        int idx = counter.getAndIncrement() % totalWeight;
        int acc = 0;
        for (LlmProperties.Endpoint ep : endpoints) {
            acc += ep.getWeight();
            if (idx < acc) {
                return ep;
            }
        }
        return endpoints.get(0); // 兜底
    }
}
