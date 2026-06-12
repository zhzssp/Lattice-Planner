package org.zhzssp.memorandum.feature.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.mcp.entity.McpToken;
import org.zhzssp.memorandum.feature.agent.mcp.repository.McpTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * MCP Token 认证服务：验证 lattice_ 前缀的 API Token → 返回对应的 User。
 */
@Component
public class McpAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpAuthService.class);
    private static final String TOKEN_PREFIX = "lattice_";

    private final McpTokenRepository tokenRepo;

    public McpAuthService(McpTokenRepository tokenRepo) {
        this.tokenRepo = tokenRepo;
    }

    /**
     * 验证 token → 返回对应 User。
     * @throws McpAuthException 认证失败
     */
    public User authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new McpAuthException("无效的 MCP Token 格式（需 lattice_ 前缀）");
        }
        String hash = sha256(token);
        McpToken t = tokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new McpAuthException("MCP Token 不存在或已吊销"));
        t.setLastUsedAt(LocalDateTime.now());
        tokenRepo.save(t);
        return t.getUser();
    }

    /** 生成新 token（明文，仅展示一次）。 */
    public String generateToken(User user, String label) {
        String raw = TOKEN_PREFIX + generateRandomHex(32);
        String hash = sha256(raw);
        McpToken t = new McpToken();
        t.setUser(user);
        t.setTokenHash(hash);
        t.setLabel(label);
        t.setCreatedAt(LocalDateTime.now());
        tokenRepo.save(t);
        log.info("[MCP] 为用户 {} 生成 Token（label={}）", user.getUsername(), label);
        return raw;
    }

    /** 吊销 token。 */
    public void revokeToken(Long tokenId, Long userId) {
        tokenRepo.deleteByIdAndUserId(tokenId, userId);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String generateRandomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** MCP 认证异常。 */
    public static class McpAuthException extends RuntimeException {
        public McpAuthException(String message) {
            super(message);
        }
    }
}
