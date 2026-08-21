package org.zhzssp.memorandum.feature.agent.policy;

import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.repository.UserPreferenceRepository;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Agent 工具授权策略：判定「某工具对某用户是否需要弹窗确认」。
 *
 * <p>核心规则：工具本身的 {@code requiresConfirm()} 为 true
 * <strong>且</strong>未被用户加入 auto-approve 白名单时，才弹窗。
 *
 * <p>auto-approve 数据从 {@link UserPreference#getAgentAutoApproveTools()} 读取，
 * 逗号分隔，默认空集合（全部需确认）。
 *
 * <h3>V4 新增：不可免确认工具（硬例外）</h3>
 * <p>{@link #NEVER_AUTO_APPROVE} 中的工具<strong>永远弹窗</strong>，即便用户把它加进白名单。
 * 目前只有受限执行类工具（{@code checkpoint.run}）：它在用户机器上执行真实命令，
 * 一旦免确认，「每次执行都经用户过目」这道闸门就形同虚设。</p>
 *
 * <p>这类硬例外的判断标准是：<em>副作用是否发生在本应用数据之外</em>。
 * 建任务、写笔记都只影响自己的库，用户想免确认是合理的偏好；
 * 而执行 shell 命令会触及文件系统与工具链，不可逆且难以归因。</p>
 */
@Component
public class ToolApprovalPolicy {

    /**
     * 永不允许 auto-approve 的工具名。
     *
     * <p>用前缀而非全名匹配，避免将来新增 {@code lab.run_script} 等同类工具时漏配。</p>
     */
    private static final Set<String> NEVER_AUTO_APPROVE_PREFIXES = Set.of(
            "checkpoint.run",
            "lab.run_script"
    );

    private final UserPreferenceRepository preferenceRepository;

    public ToolApprovalPolicy(UserPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * 该工具是否属于「不可免确认」的硬例外。
     *
     * <p>供设置页在渲染 auto-approve 勾选框时禁用对应项，
     * 让约束在 UI 上也可见，而不是让用户勾了却发现不生效。</p>
     */
    public static boolean neverAutoApprove(String toolName) {
        if (toolName == null) return false;
        return NEVER_AUTO_APPROVE_PREFIXES.stream().anyMatch(toolName::startsWith);
    }

    /**
     * 判断某工具对给定用户是否需要弹窗确认。
     *
     * @return true = 需要弹窗确认；false = 自动允许（不弹窗）
     */
    public boolean needsConfirm(User user, ToolDefinition def) {
        if (user == null || def == null) return true;
        // 硬例外优先于一切用户偏好：受限执行必须逐次确认
        if (neverAutoApprove(def.name())) return true;
        // 工具本身不需要确认 → 无需弹窗
        if (!def.requiresConfirm()) return false;
        // 查询用户 auto-approve 白名单
        Set<String> autoApproved = autoApprovedTools(user);
        return !autoApproved.contains(def.name());
    }

    /**
     * 读取用户已勾选的 auto-approve 工具名集合。
     * 结果按原始顺序去重，大小写敏感。
     */
    public Set<String> autoApprovedTools(User user) {
        if (user == null) return Collections.emptySet();
        UserPreference pref = preferenceRepository.findByUser(user).orElse(null);
        if (pref == null) return Collections.emptySet();
        String raw = pref.getAgentAutoApproveTools();
        if (raw == null || raw.isBlank()) return Collections.emptySet();
        return Stream.of(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 覆盖写入用户 auto-approve 工具白名单。
     *
     * <p>硬例外工具会被静默剔除——即便前端绕过 UI 直接提交也无效。
     * 「只在 UI 禁用勾选」是提示层约束，模型/脚本完全可以无视；
     * 在写入层剔除才是执行层强制（与方案 D/K 同一立场）。</p>
     *
     * @param tools 工具名集合（null 或空集合清空白名单）
     */
    public void updateAutoApproved(User user, Set<String> tools) {
        if (user == null) return;
        UserPreference pref = preferenceRepository.findByUser(user).orElseGet(() -> {
            UserPreference p = new UserPreference();
            p.setUser(user);
            return p;
        });
        if (tools == null || tools.isEmpty()) {
            pref.setAgentAutoApproveTools(null);
        } else {
            pref.setAgentAutoApproveTools(
                    tools.stream()
                            .filter(t -> t != null && !t.isBlank())
                            .filter(t -> !neverAutoApprove(t))
                            .collect(Collectors.joining(","))
            );
        }
        preferenceRepository.save(pref);
    }
}
