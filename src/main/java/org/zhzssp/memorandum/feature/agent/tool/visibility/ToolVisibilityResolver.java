package org.zhzssp.memorandum.feature.agent.tool.visibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.feature.agent.runtime.AgentMode;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具可见性解析器（方案 K 核心）。
 *
 * <p>按 scope 链实时计算「当前能看到哪些工具」，支持显式 {@code deny} 与 {@code pin}，
 * 并产出每个决策的<strong>可解释原因</strong>。</p>
 *
 * <h3>K1 阶段范围</h3>
 * <p>仅落地 {@code MODE} 层（GLOBAL 打底 + MODE 收窄/剔除）。ROLE/SESSION 层在
 * K4/K5 阶段接入，本类的方法签名已为其预留。</p>
 *
 * <h3>遮蔽算法（四步）</h3>
 * <ol>
 *   <li><strong>GLOBAL 打底</strong>：本地工具全量，MCP 工具追加；同名时本地优先，
 *       MCP 被遮蔽不重复导出（修「隐患 B」：schema 重复但 invoke 永远走本地）。</li>
 *   <li><strong>allow 收窄</strong>：每层（从远到近）若声明 allow，非命中的剔除。</li>
 *   <li><strong>deny 剔除</strong>：每层若声明 deny，命中的剔除（跨层累积）。</li>
 *   <li><strong>pin 破例</strong>：当前层 pin 的工具加回（除结构性保留）。</li>
 * </ol>
 */
@Component
public class ToolVisibilityResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolVisibilityResolver.class);

    private final ToolRegistry registry;

    @Value("${agent.tool.visibility.enabled:true}")
    private boolean enabled;

    @Value("${agent.tool.visibility.enforce:true}")
    private boolean enforce;

    public ToolVisibilityResolver(ToolRegistry registry) {
        this.registry = registry;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 执行层强制是否开启（K3）：不可见工具是否被短路拦截。 */
    public boolean enforce() {
        return enforce;
    }

    /**
     * 解析指定模式的可见工具视图（MODE 层 + GLOBAL 层）。
     *
     * @param mode 思维模式；null 回退 CHAT
     */
    public ToolView resolveMode(String mode) {
        AgentMode m = AgentMode.of(mode);
        ScopeChain chain = new ScopeChain(List.of(m.toLayer()));
        return resolve(chain);
    }

    /**
     * K4：解析「模式 + 会话」的可见工具视图。
     *
     * <p>链从近到远为 [SESSION, MODE]。SESSION 层由 {@link SessionToolMask} 提供，
     * 无规则时是空层（不产生任何收窄/剔除）。</p>
     *
     * @param mode      思维模式
     * @param sessionMask 会话层规则；null 则仅 MODE 层
     */
    public ToolView resolveModeWithSession(String mode, SessionToolMask.Mask sessionMask) {
        AgentMode m = AgentMode.of(mode);
        List<ToolLayer> layers = new ArrayList<>();
        // 近 → 远：SESSION 在前，MODE 在后
        if (sessionMask != null && !sessionMask.isEmpty()) {
            layers.add(new ToolLayer(
                    ToolLayer.ScopeKind.SESSION,
                    "SESSION",
                    Set.of(),
                    Set.of(),
                    sessionMask.denyTags(),
                    sessionMask.denyTools(),
                    sessionMask.pinnedTools()));
        }
        layers.add(m.toLayer());
        return resolve(new ScopeChain(layers));
    }

    /**
     * K3：构造「工具不可见」的结果 Map，回灌给 LLM。
     *
     * <p>延续方案 E 的「为模型设计错误信息」原则——错误必须可操作：
     * 给出原因、决策链、可用的替代工具，而不是笼统的拒绝。</p>
     */
    public Map<String, Object> notVisibleResult(ToolView view, String toolName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "TOOL_NOT_VISIBLE");
        m.put("tool", toolName);
        m.put("reason", view.reasonOf(toolName));
        // 给模型可用的替代方向（同域可见工具，最多 5 个）
        List<String> alternatives = view.names().stream()
                .filter(n -> !n.equals(toolName))
                .limit(5)
                .toList();
        if (!alternatives.isEmpty()) {
            m.put("visibleAlternatives", alternatives);
        }
        m.put("hint", "该工具在当前模式/角色下不可见，本次调用未执行，不会产生任何副作用。"
                + "请从 visibleAlternatives 或【可用工具】列表中选择可见工具完成目标；"
                + "若确需该操作，请用自然语言告知用户切换到相应模式。");
        return m;
    }

    /**
     * K5：解析子代理的可见工具视图。
     *
     * <p>链从近到远为 [ROLE, MODE(父对话)]：
     * <ul>
     *   <li>ROLE 层用角色 {@code toolTags} 做 allow 收窄（最小职责集）；</li>
     *   <li>MODE 层继承父对话的 deny（如 learn 禁写）——子代理不得绕过模式边界；</li>
     *   <li><strong>结构性保留</strong>：{@code subagent.*} 无条件剔除，子代理不可再委派。</li>
     * </ul></p>
     *
     * @param role       子代理角色
     * @param parentMode 父对话模式；null 表示不继承 mode 层
     */
    public ToolView resolveSubAgent(org.zhzssp.memorandum.feature.agent.subagent.SubAgentRole role,
                                    String parentMode) {
        List<ToolLayer> layers = new ArrayList<>();
        // 近 → 远：ROLE 在前，MODE 在后
        layers.add(ToolLayer.allowOnly(ToolLayer.ScopeKind.ROLE, "ROLE(" + role.name() + ")", role.toolTags()));
        if (parentMode != null && !parentMode.isBlank()) {
            layers.add(AgentMode.of(parentMode).toLayer());
        }
        ToolView view = resolve(new ScopeChain(layers));

        // 结构性保留：ROLE 层存在时，subagent.* 无条件剔除（不可递归委派）
        if (!view.names().stream().noneMatch(n -> n.startsWith("subagent."))) {
            Set<String> filtered = new LinkedHashSet<>();
            for (String n : view.names()) {
                if (n.startsWith("subagent.")) {
                    continue;
                }
                filtered.add(n);
            }
            return new ToolView(filtered, view.reasons(), view.signature());
        }
        return view;
    }

    /**
     * 按 scope 链解析可见工具视图。
     *
     * <p>K1 仅使用链中的 MODE 层；ROLE/SESSION 层在后续阶段接入。</p>
     */
    public ToolView resolve(ScopeChain chain) {
        // ① GLOBAL 打底：本地全量，MCP 追加，同名本地优先（修隐患 B）
        Map<String, String> baseNames = new LinkedHashMap<>();
        List<String> localSorted = new ArrayList<>(registry.all().stream()
                .map(ToolDefinition::name).toList());
        localSorted.sort(String::compareTo);
        for (String n : localSorted) baseNames.put(n, n);

        for (var rt : registry.mcpToolsAll()) {
            String fn = rt.fullName();
            if (baseNames.containsKey(fn)) {
                // 同名 MCP 被本地遮蔽，不重复导出（决策链记录，供 explain）
                continue;
            }
            baseNames.put(fn, fn);
        }

        LinkedHashSet<String> visible = new LinkedHashSet<>(baseNames.keySet());
        Map<String, String> reasons = new LinkedHashMap<>();

        // ② allow 收窄（从远到近）
        List<ToolLayer> layers = chain.layers();
        for (int i = layers.size() - 1; i >= 0; i--) {
            ToolLayer layer = layers.get(i);
            if (layer.allowTags().isEmpty() && layer.allowTools().isEmpty()) continue;
            visible.removeIf(name -> {
                boolean hit = layer.allowTools().contains(name)
                        || tagsOf(name).stream().anyMatch(layer.allowTags()::contains);
                if (!hit) {
                    reasons.put(name, layer.label() + ":not-in-allow");
                }
                return !hit;
            });
        }

        // ③ deny 剔除（跨层累积，任一层命中即剔除）
        for (ToolLayer layer : layers) {
            visible.removeIf(name -> {
                boolean denied = layer.denyTools().contains(name)
                        || tagsOf(name).stream().anyMatch(layer.denyTags()::contains);
                if (denied) {
                    reasons.put(name, layer.label() + ":deny(" + describeDeny(layer, name) + ")");
                }
                return denied;
            });
        }

        // ④ pin 破例（当前层，K1 无 SESSION 层故通常无 pin）
        ToolLayer current = chain.current();
        if (current != null) {
            for (String name : current.pinnedTools()) {
                if (!baseNames.containsKey(name)) continue;   // 不存在的名字不 pin
                visible.add(name);
                reasons.remove(name);
                reasons.put(name, current.label() + ":pin");
            }
        }

        return new ToolView(visible, reasons, chain.signature());
    }

    /** 工具名 → tag 集合；未知名返回空集（MCP 工具统一视为 mcp）。 */
    private Set<String> tagsOf(String name) {
        ToolDefinition def = registry.get(name);
        if (def == null) return Set.of();
        return def.tags() == null ? Set.of() : new LinkedHashSet<>(def.tags());
    }

    private String describeDeny(ToolLayer layer, String name) {
        if (layer.denyTools().contains(name)) return "tool";
        // 找出命中的 deny tag
        for (String tag : layer.denyTags()) {
            if (tagsOf(name).contains(tag)) return "tag=" + tag;
        }
        return "rule";
    }
}
