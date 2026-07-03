package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.policy.ToolApprovalPolicy;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 设置接口：读写工具授权白名单。
 *
 * <ul>
 *   <li>GET  /agent/settings/tools   — 返回全部「可确认工具」清单 + 当前用户已勾选集合</li>
 *   <li>PUT  /agent/settings/auto-approve — 覆盖保存用户勾选的工具名数组</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/settings")
public class AgentSettingsController {

    private final ToolRegistry registry;
    private final ToolApprovalPolicy approvalPolicy;
    private final UserRepository userRepository;

    public AgentSettingsController(ToolRegistry registry,
                                   ToolApprovalPolicy approvalPolicy,
                                   UserRepository userRepository) {
        this.registry = registry;
        this.approvalPolicy = approvalPolicy;
        this.userRepository = userRepository;
    }

    /**
     * 返回可确认工具清单 + 当前用户的 auto-approve 白名单。
     *
     * <p>响应格式：<pre>
     * {
     *   "confirmableTools": [
     *     {"name":"kb.ingest_local_doc","description":"摄取本地文档进知识库"},
     *     ...
     *   ],
     *   "autoApproved": ["kb.ingest_local_doc"]
     * }
     * </pre>
     */
    @GetMapping("/tools")
    public Map<String, Object> getToolSettings(Principal principal) {
        User user = resolveUser(principal);
        // 过滤出 requiresConfirm=true 的工具
        List<Map<String, String>> confirmable = registry.all().stream()
                .filter(ToolDefinition::requiresConfirm)
                .map(def -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", def.name());
                    m.put("description", def.description());
                    return m;
                })
                .toList();
        Set<String> autoApproved = (user != null)
                ? approvalPolicy.autoApprovedTools(user)
                : Set.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmableTools", confirmable);
        result.put("autoApproved", autoApproved);
        return result;
    }

    /**
     * 覆盖保存用户 auto-approve 白名单。
     *
     * <p>请求体：{@code {"tools":["kb.ingest_local_doc","kb.delete_local_doc"]}} </p>
     */
    @PutMapping("/auto-approve")
    public Map<String, Object> updateAutoApprove(
            @RequestBody Map<String, List<String>> body,
            Principal principal) {
        User user = resolveUser(principal);
        List<String> tools = body != null ? body.get("tools") : null;
        Set<String> toolSet = (tools != null) ? Set.copyOf(tools) : Set.of();
        approvalPolicy.updateAutoApproved(user, toolSet);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("autoApproved", approvalPolicy.autoApprovedTools(user));
        return result;
    }

    private User resolveUser(Principal principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getName()).orElse(null);
    }
}
