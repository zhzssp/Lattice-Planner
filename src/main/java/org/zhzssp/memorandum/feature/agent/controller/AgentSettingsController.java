package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.feature.agent.llm.LlmProperties;
import org.zhzssp.memorandum.feature.agent.llm.ModelCatalog;
import org.zhzssp.memorandum.feature.agent.policy.ToolApprovalPolicy;
import org.zhzssp.memorandum.feature.agent.tool.ToolDefinition;
import org.zhzssp.memorandum.feature.agent.tool.ToolRegistry;
import org.zhzssp.memorandum.repository.UserRepository;
import org.zhzssp.memorandum.service.UserPreferenceService;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 设置接口：工具授权白名单 + 多模型切换。
 *
 * <ul>
 *   <li>GET  /agent/settings/tools   — 可确认工具清单 + 已勾选集合</li>
 *   <li>PUT  /agent/settings/auto-approve — 覆盖保存用户勾选的工具名数组</li>
 *   <li>GET  /agent/settings/models  — 可用模型列表 + 当前用户选中模型</li>
 *   <li>PUT  /agent/settings/model   — 切换当前用户 Agent 使用的模型</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/settings")
public class AgentSettingsController {

    private final ToolRegistry registry;
    private final ToolApprovalPolicy approvalPolicy;
    private final ModelCatalog modelCatalog;
    private final UserRepository userRepository;
    private final UserPreferenceService prefService;

    public AgentSettingsController(ToolRegistry registry,
                                   ToolApprovalPolicy approvalPolicy,
                                   ModelCatalog modelCatalog,
                                   UserRepository userRepository,
                                   UserPreferenceService prefService) {
        this.registry = registry;
        this.approvalPolicy = approvalPolicy;
        this.modelCatalog = modelCatalog;
        this.userRepository = userRepository;
        this.prefService = prefService;
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
        // 过滤出 requiresConfirm=true 的工具。
        // 用 allWithMcp() 而非 all()：AgentOrchestrator 判定确认时走的是 registry.get(name)，
        // 能命中 MCP 远程工具；若此处只列本地工具，需确认的 MCP 工具将无法在 UI 中勾选免确认。
        List<Map<String, String>> confirmable = registry.allWithMcp().stream()
                .filter(ToolDefinition::requiresConfirm)
                .sorted(java.util.Comparator.comparing(ToolDefinition::name))
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
        User user = requireUser(principal);
        List<String> tools = body != null ? body.get("tools") : null;
        Set<String> toolSet = (tools != null) ? Set.copyOf(tools) : Set.of();
        approvalPolicy.updateAutoApproved(user, toolSet);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("autoApproved", approvalPolicy.autoApprovedTools(user));
        return result;
    }

    /**
     * 返回可用模型列表 + 当前用户选中的模型。
     *
     * <p>响应格式：<pre>
     * {
     *   "models": [
     *     {"id":"deepseek-chat","displayName":"DeepSeek Chat","provider":"DeepSeek"},
     *     ...
     *   ],
     *   "current": "deepseek-chat"
     * }
     * </pre>
     */
    @GetMapping("/models")
    public Map<String, Object> getModelSettings(Principal principal) {
        User user = resolveUser(principal);
        List<Map<String, String>> models = new ArrayList<>();
        for (LlmProperties.ModelDef m : modelCatalog.availableModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("displayName", m.getDisplayName());
            item.put("provider", modelCatalog.providerDisplayName(m.getProviderId()));
            models.add(item);
        }
        String current;
        if (user != null) {
            UserPreference pref = prefService.getOrCreatePreference(user);
            current = (pref.getAgentChatModelId() != null && !pref.getAgentChatModelId().isBlank()
                    && modelCatalog.find(pref.getAgentChatModelId()).isPresent())
                    ? pref.getAgentChatModelId() : modelCatalog.defaultModelId();
        } else {
            current = modelCatalog.defaultModelId();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("current", current);
        return result;
    }

    /**
     * 切换用户 Agent 使用的模型。
     *
     * <p>请求体：{@code {"modelId":"deepseek-reasoner"}} </p>
     */
    @PutMapping("/model")
    public Map<String, Object> updateModel(
            @RequestBody Map<String, String> body,
            Principal principal) {
        User user = requireUser(principal);
        String modelId = body != null ? body.get("modelId") : null;
        if (modelId == null || modelId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "modelId 不能为空");
        }
        if (modelCatalog.find(modelId).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "无效的模型 id：" + modelId);
        }
        UserPreference pref = prefService.getOrCreatePreference(user);
        pref.setAgentChatModelId(modelId);
        prefService.savePreference(pref);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("modelId", modelId);
        return result;
    }

    private User resolveUser(Principal principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getName()).orElse(null);
    }

    /**
     * 写操作专用：解析不到用户时返回 401，而不是静默成功。
     *
     * <p>背景：{@code /agent/**} 在 WebSecurityConfig 中被 permitAll（因为静态资源
     * {@code /agent/chat-panel.js|css} 也在该前缀下），所以写接口必须自己兜住鉴权，
     * 否则匿名调用会静默返回 {@code {"status":"ok"}} 而实际什么都没保存。</p>
     */
    private User requireUser(Principal principal) {
        User user = resolveUser(principal);
        if (user == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
