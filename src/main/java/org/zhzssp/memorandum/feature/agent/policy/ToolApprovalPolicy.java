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
 */
@Component
public class ToolApprovalPolicy {

    private final UserPreferenceRepository preferenceRepository;

    public ToolApprovalPolicy(UserPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * 判断某工具对给定用户是否需要弹窗确认。
     *
     * @return true = 需要弹窗确认；false = 自动允许（不弹窗）
     */
    public boolean needsConfirm(User user, ToolDefinition def) {
        if (user == null || def == null) return true;
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
                    tools.stream().filter(t -> t != null && !t.isBlank())
                            .collect(Collectors.joining(","))
            );
        }
        preferenceRepository.save(pref);
    }
}
