-- 手动迁移脚本（若使用 ddl-auto=update 可跳过，Hibernate 会自动执行）
-- 若某列已存在会报错，可跳过该句继续执行其余语句
ALTER TABLE memo ADD COLUMN status VARCHAR(20) NULL;
ALTER TABLE memo ADD COLUMN granularity VARCHAR(20) NULL;
ALTER TABLE memo ADD COLUMN energy_requirement VARCHAR(20) NULL;
ALTER TABLE memo ADD COLUMN mental_load VARCHAR(20) NULL;
ALTER TABLE memo ADD COLUMN preferred_slot VARCHAR(20) NULL;
ALTER TABLE memo ADD COLUMN estimated_minutes INT NULL;
ALTER TABLE memo ADD COLUMN created_at DATETIME NULL;
ALTER TABLE memo ADD COLUMN shelved_at DATETIME NULL;

CREATE TABLE link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(20) NOT NULL,
    source_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    created_at DATETIME NULL
);

CREATE TABLE goal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    goal_type VARCHAR(20) NULL,
    created_at DATETIME NULL,
    archived_at DATETIME NULL,
    user_id BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user(id)
);

ALTER TABLE note ADD COLUMN type VARCHAR(20) NULL;

-- 用户偏好表
CREATE TABLE IF NOT EXISTS user_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    max_visible_tasks INT NULL,
    show_future_tasks BOOLEAN NOT NULL DEFAULT TRUE,
    show_statistics BOOLEAN NOT NULL DEFAULT FALSE,
    default_mindset_mode VARCHAR(20) NULL,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
