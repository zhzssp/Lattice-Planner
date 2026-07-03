-- Agent 自动允许工具白名单字段（Auto-Approve）
ALTER TABLE user_preference
    ADD COLUMN agent_auto_approve_tools VARCHAR(2000) NULL;
