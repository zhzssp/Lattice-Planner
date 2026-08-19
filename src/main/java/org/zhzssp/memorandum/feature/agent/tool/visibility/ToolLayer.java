package org.zhzssp.memorandum.feature.agent.tool.visibility;

import java.util.Set;

/**
 * 一层工具可见性规则（方案 K）。
 *
 * <p>四个字段的语义<strong>刻意不对称</strong>：
 * <ul>
 *   <li>{@code allowTags/allowTools} 是「收窄」：非空时，只有命中的才能继续可见；</li>
 *   <li>{@code denyTags/denyTools} 是「剔除」：命中即不可见，跨层累积；</li>
 *   <li>{@code pinnedTools} 是「破例」：优先级高于一切 deny（除结构性保留）。</li>
 * </ul>
 *
 * <p>K1 阶段仅落地 {@code GLOBAL} 与 {@code MODE} 两层，其余 {@link ScopeKind}
 * 预留在枚举中，避免后续扩展时改动调用方。</p>
 */
public record ToolLayer(
        ScopeKind kind,
        String label,
        Set<String> allowTags,
        Set<String> allowTools,
        Set<String> denyTags,
        Set<String> denyTools,
        Set<String> pinnedTools
) {

    /** 一层规则的语义类别。 */
    public enum ScopeKind {
        /** 全量注册表打底（含本地遮蔽同名 MCP）。 */
        GLOBAL,
        /** 思维模式（chat/plan/reflect/learn）。 */
        MODE,
        /** 子代理角色（PLANNER/REFLECTION/RESEARCH）。 */
        ROLE,
        /** 单会话临时意愿。 */
        SESSION
    }

    /** 便捷构造：仅声明 allow（无 deny、无 pin）。 */
    public static ToolLayer allowOnly(ScopeKind kind, String label, Set<String> allowTags) {
        return new ToolLayer(kind, label, allowTags, Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 便捷构造：声明 allow + deny。 */
    public static ToolLayer of(ScopeKind kind, String label,
                               Set<String> allowTags, Set<String> denyTags) {
        return new ToolLayer(kind, label, allowTags, Set.of(), denyTags, Set.of(), Set.of());
    }

    /** 便捷构造：空层（无任何约束）。 */
    public static ToolLayer empty(ScopeKind kind, String label) {
        return new ToolLayer(kind, label, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
