package org.zhzssp.memorandum.feature.agent.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.memory.AgentFact;
import org.zhzssp.memorandum.feature.agent.memory.FactService;
import org.zhzssp.memorandum.repository.UserRepository;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Facts 管理接口（上下文工程 P1）。
 *
 * <ul>
 *   <li>GET  /agent/settings/facts — 当前用户全部事实（含原文片段，供核对）</li>
 *   <li>POST /agent/settings/facts/{id}/reject — 标记为错误（同 key 永不再抽）</li>
 * </ul>
 *
 * <p>存在这个接口的理由与 facts 存 {@code source_quote} 同构：一条 LLM 抽出来的事实
 * 会被注入每一轮，用户必须能核对「软件凭什么说我有这条约束」，并纠正抽错的。
 * 没有纠正入口，错误的 fact 会无限期污染上下文。</p>
 */
@RestController
@RequestMapping("/agent/settings")
public class AgentFactController {

    private final FactService factService;
    private final UserRepository userRepository;

    public AgentFactController(FactService factService, UserRepository userRepository) {
        this.factService = factService;
        this.userRepository = userRepository;
    }

    @GetMapping("/facts")
    public Map<String, Object> listFacts(Principal principal) {
        User user = requireUser(principal);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentFact f : factService.listForUser(user.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("kind", f.getKind().name());
            m.put("key", f.getFactKey());
            m.put("value", f.getFactValue());
            m.put("sourceQuote", f.getSourceQuote());
            m.put("sourceTurn", f.getSourceTurn());
            m.put("confidence", f.getConfidence().name());
            m.put("status", f.getStatus().name());
            m.put("sessionId", f.getSessionId());
            m.put("updatedAt", f.getUpdatedAt());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", factService.enabled());
        out.put("facts", items);
        return out;
    }

    /**
     * 标记一条 fact 为错误。
     *
     * <p>标 {@code REJECTED} 后同 key 永不再抽（用户判定过的东西不该被自动重开）。
     * 幂等：重复调用不报错。</p>
     */
    @PostMapping("/facts/{id}/reject")
    public Map<String, Object> reject(Principal principal, @PathVariable Long id) {
        User user = requireUser(principal);
        factService.reject(user.getId(), id);
        return Map.of("status", "ok", "id", id);
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
    }
}
