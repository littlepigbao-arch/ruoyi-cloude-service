-- ----------------------------
-- AI 智能报表服务 - 文档表建表脚本
-- 适用库：ry-cloud（与 system 共库）
-- 作者：ruoyi
-- 日期：2026-08-24
-- ----------------------------

-- ----------------------------
-- AI 报表文档表（存储用户创建/导入的表格快照，用于持久化与文档列表展示）
-- ----------------------------
DROP TABLE IF EXISTS ai_workbook;
CREATE TABLE ai_workbook (
  workbook_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
  name           VARCHAR(255) NOT NULL COMMENT '文档名称',
  doc_type       VARCHAR(16)  DEFAULT 'created' COMMENT '类型(created新建/imported导入)',
  content        LONGTEXT     COMMENT '表格快照JSON',
  user_id        BIGINT       NOT NULL COMMENT '用户ID',
  create_by      VARCHAR(64)  DEFAULT '' COMMENT '创建者',
  create_time    DATETIME     DEFAULT NULL COMMENT '创建时间',
  update_by      VARCHAR(64)  DEFAULT '' COMMENT '更新者',
  update_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (workbook_id),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能报表文档表';
