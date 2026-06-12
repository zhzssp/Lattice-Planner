package org.zhzssp.memorandum.feature.agent.mcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.zhzssp.memorandum.feature.agent.mcp.entity.McpToken;

import java.util.Optional;

public interface McpTokenRepository extends JpaRepository<McpToken, Long> {
    Optional<McpToken> findByTokenHash(String tokenHash);

    java.util.List<McpToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    void deleteByIdAndUserId(Long id, Long userId);
}
