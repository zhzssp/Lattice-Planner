-- MCP Token 表
CREATE TABLE mcp_token (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    label       VARCHAR(100),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP NULL,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_mcp_token_hash ON mcp_token(token_hash);
