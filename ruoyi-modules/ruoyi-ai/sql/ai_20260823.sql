-- ----------------------------
-- AI 智能报表服务 - 建表脚本
-- 适用库：ry-cloud（与 system 共库）
-- 作者：ruoyi
-- 日期：2026-08-23
-- ----------------------------

-- ----------------------------
-- 1、AI 会话表
-- ----------------------------
DROP TABLE IF EXISTS ai_conversation;
CREATE TABLE ai_conversation (
  conversation_id   VARCHAR(64)  NOT NULL COMMENT '会话ID（前端传或后端生成）',
  user_id           BIGINT       NOT NULL COMMENT '用户ID',
  workbook_name     VARCHAR(255) DEFAULT NULL COMMENT '工作簿名',
  sheet_name        VARCHAR(255) DEFAULT NULL COMMENT '工作表名',
  status            CHAR(1)      DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by         VARCHAR(64)  DEFAULT '' COMMENT '创建者',
  create_time       DATETIME     DEFAULT NULL COMMENT '创建时间',
  update_by         VARCHAR(64)  DEFAULT '' COMMENT '更新者',
  update_time       DATETIME     DEFAULT NULL COMMENT '更新时间',
  remark            VARCHAR(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (conversation_id),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话表';

-- ----------------------------
-- 2、AI 消息表
-- ----------------------------
DROP TABLE IF EXISTS ai_message;
CREATE TABLE ai_message (
  message_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  conversation_id    VARCHAR(64)  NOT NULL COMMENT '会话ID',
  role               VARCHAR(16)  NOT NULL COMMENT '角色(user/assistant)',
  content            TEXT         COMMENT '消息内容',
  actions            JSON         DEFAULT NULL COMMENT 'assistant 返回的 actions 数组(JSON)',
  prompt_tokens      INT          DEFAULT 0 COMMENT '输入tokens',
  completion_tokens  INT          DEFAULT 0 COMMENT '输出tokens',
  total_tokens       INT          DEFAULT 0 COMMENT '总tokens',
  model              VARCHAR(64)  DEFAULT NULL COMMENT '模型名',
  create_by          VARCHAR(64)  DEFAULT '' COMMENT '创建者',
  create_time        DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (message_id),
  KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息表';
