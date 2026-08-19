package org.zhzssp.memorandum.feature.agent.tool.visibility;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次可见性解析的结果（方案 K）。
 *
 * <p><strong>只存可见工具名集合 + 决策链，不存工具定义本身</strong>——schema 序列化
 * 逻辑必须单一地留在 {@code ToolRegistry}（本地与 MCP 的序列化方式不同，拆两处必然漂移）。
 * {@code ToolRegistry} 按名字集合查表导出，避免重复实现。</p>
 *
 * <p>决策链是<strong>可解释性</strong>的落点——「弱类型 tag 关联」无法靠编译器发现
 * 配置错误，只能靠可观测的决策链暴露。</p>
 */
public final class ToolView {

    /** 可见工具名（有序，保证 schema 导出字节稳定）。 */
    private final Set<String> visible;

    /** 每个被 deny 的工具名 → 最近一次生效的原因（供 explain 使用）。 */
    private final Map<String, String> reasons;

    /** 视图签名（来自 {@link ScopeChain#signature()}），用于缓存。 */
    private final String signature;

    public ToolView(Set<String> visible, Map<String, String> reasons, String signature) {
        this.visible = new LinkedHashSet<>(visible);
        this.reasons = new LinkedHashMap<>(reasons);
        this.signature = signature;
    }

    /** 可见工具名集合（有序，只读视图）。 */
    public Set<String> names() {
        return visible;
    }

    public boolean contains(String name) {
        return visible.contains(name);
    }

    /** 某工具不可见时的原因；可见或未知返回 {@code null}。 */
    public String reasonOf(String name) {
        return reasons.get(name);
    }

    /** 完整决策链副本（用于 explain 端点）。 */
    public Map<String, String> reasons() {
        return new LinkedHashMap<>(reasons);
    }

    public String signature() {
        return signature;
    }

    public int size() {
        return visible.size();
    }
}
