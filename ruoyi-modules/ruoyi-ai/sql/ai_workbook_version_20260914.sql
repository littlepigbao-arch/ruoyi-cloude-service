-- AI 工作簿版本管理增量脚本（执行前请备份数据库）
ALTER TABLE ai_workbook
  ADD COLUMN content_hash CHAR(64) DEFAULT NULL COMMENT '当前表格快照SHA-256' AFTER content,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER content_hash,
  ADD COLUMN current_branch_id BIGINT DEFAULT NULL COMMENT '当前分支ID' AFTER version,
  ADD COLUMN head_commit_id CHAR(36) DEFAULT NULL COMMENT '当前分支HEAD提交' AFTER current_branch_id,
  ADD UNIQUE KEY uk_workbook_user_name (user_id, name);

UPDATE ai_workbook SET content_hash = SHA2(COALESCE(content, ''), 256) WHERE content_hash IS NULL;
