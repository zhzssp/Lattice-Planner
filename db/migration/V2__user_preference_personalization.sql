-- 用户偏好个性化：规划模式默认视图、模糊任务阈值、各区块显示开关
-- 若某列已存在可跳过对应语句
ALTER TABLE user_preference ADD COLUMN default_task_view VARCHAR(20) NULL;
ALTER TABLE user_preference ADD COLUMN fuzzy_task_days_threshold INT NULL;
ALTER TABLE user_preference ADD COLUMN show_goals BOOLEAN NULL;
ALTER TABLE user_preference ADD COLUMN show_fuzzy_hint BOOLEAN NULL;
ALTER TABLE user_preference ADD COLUMN show_archived_section BOOLEAN NULL;
ALTER TABLE user_preference ADD COLUMN show_score_section BOOLEAN NULL;
