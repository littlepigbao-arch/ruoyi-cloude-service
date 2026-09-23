-- 增量迁移：将会话绑定工作簿。旧会话保持 NULL，避免错误归属推断。
ALTER TABLE ai_conversation
  ADD COLUMN workbook_id BIGINT NULL COMMENT '绑定的工作簿ID' AFTER user_id,
  ADD KEY idx_user_workbook (user_id, workbook_id);
