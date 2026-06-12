package org.zhzssp.memorandum.feature.agent.mcp.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.zhzssp.memorandum.entity.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_token", uniqueConstraints = @UniqueConstraint(columnNames = "token_hash"))
@Data
public class McpToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 of the raw token string. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Human-readable label, e.g. "Claude Desktop", "Cursor". */
    @Column(length = 100)
    private String label;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
