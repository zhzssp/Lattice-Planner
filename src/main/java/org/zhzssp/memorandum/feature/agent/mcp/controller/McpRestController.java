package org.zhzssp.memorandum.feature.agent.mcp.controller;

import jdk.jshell.spi.ExecutionControlProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.mcp.McpAuthService;
import org.zhzssp.memorandum.feature.agent.mcp.McpSseEndpoint;
import org.zhzssp.memorandum.feature.agent.mcp.entity.McpToken;
import org.zhzssp.memorandum.feature.agent.mcp.repository.McpTokenRepository;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.List;
import java.util.Map;

/**
 * MCP SSE + JSON-RPC 端点控制器。
 */
@RestController
public class McpRestController {

    private final McpSseEndpoint sseEndpoint;
    private final McpAuthService authService;
    private final McpTokenRepository tokenRepo;
    private final UserRepository userRepo;

    @Value("${mcp.server.enabled:true}")
    private boolean mcpEnabled;

    public McpRestController(McpSseEndpoint sseEndpoint,
                             McpAuthService authService,
                             McpTokenRepository tokenRepo,
                             UserRepository userRepo) {
        this.sseEndpoint = sseEndpoint;
        this.authService = authService;
        this.tokenRepo = tokenRepo;
        this.userRepo = userRepo;
    }

    /** SSE 连接端点：GET /sse?token=lattice_xxx */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect(@RequestParam("token") String token) throws Exception {
        if (!mcpEnabled || !sseEndpoint.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emitter = sseEndpoint.connect(token);
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    /** JSON-RPC 消息端点：POST /mcp/message?sid=xxx */
    @PostMapping(value = "/mcp/message", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> message(
            @RequestParam("sid") String sid,
            @RequestBody Map<String, Object> request) throws Exception {
        if (!mcpEnabled || !sseEndpoint.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> response = sseEndpoint.handleMessage(sid, request);
        return ResponseEntity.ok(response);
    }

    /* ---- Token 管理 REST API（S3）---- */

    /** 列出当前用户的所有 MCP Token。 */
    @GetMapping("/api/mcp/tokens")
    public ResponseEntity<?> listTokens(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepo.findByUsername(userDetails.getUsername()).orElseThrow();
        List<McpToken> tokens = tokenRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        // 不返回 tokenHash
        List<Map<String, Object>> result = tokens.stream().map(t -> Map.<String, Object>of(
                "id", t.getId(),
                "label", t.getLabel() != null ? t.getLabel() : "",
                "createdAt", t.getCreatedAt().toString(),
                "lastUsedAt", t.getLastUsedAt() != null ? t.getLastUsedAt().toString() : "从未使用"
        )).toList();
        return ResponseEntity.ok(result);
    }

    /** 生成新 MCP Token。 */
    @PostMapping("/api/mcp/tokens")
    public ResponseEntity<?> generateToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {
        User user = userRepo.findByUsername(userDetails.getUsername()).orElseThrow();
        String label = body.getOrDefault("label", "MCP Client");
        String rawToken = authService.generateToken(user, label);
        // 明文 token 仅在此响应中返回一次
        return ResponseEntity.ok(Map.of("token", rawToken, "label", label));
    }

    /** 吊销 MCP Token。 */
    @DeleteMapping("/api/mcp/tokens/{id}")
    public ResponseEntity<?> revokeToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        User user = userRepo.findByUsername(userDetails.getUsername()).orElseThrow();
        authService.revokeToken(id, user.getId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
