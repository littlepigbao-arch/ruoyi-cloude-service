-- 智能工作簿 Git-like 线性版本与私有分支基础表
CREATE TABLE IF NOT EXISTS ai_workbook_branch (
  branch_id       BIGINT       NOT NULL AUTO_INCREMENT,
  workbook_id     BIGINT       NOT NULL,
  branch_name     VARCHAR(128) NOT NULL,
  head_commit_id  CHAR(36)     DEFAULT NULL,
  owner_user_id   BIGINT       NOT NULL,
  visibility      VARCHAR(16)  NOT NULL DEFAULT 'private',
  status          VARCHAR(16)  NOT NULL DEFAULT 'active',
  version         BIGINT       NOT NULL DEFAULT 0,
  created_time    DATETIME(3)  NOT NULL,
  update_time     DATETIME(3)  NOT NULL,
  PRIMARY KEY (branch_id),
  UNIQUE KEY uk_branch_name (workbook_id, owner_user_id, branch_name),
  KEY idx_branch_workbook (workbook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作簿版本分支';

CREATE TABLE IF NOT EXISTS ai_workbook_commit (
  commit_id              CHAR(36)     NOT NULL,
  workbook_id            BIGINT       NOT NULL,
  branch_id              BIGINT       NOT NULL,
  parent_commit_id       CHAR(36)     DEFAULT NULL,
  second_parent_id       CHAR(36)     DEFAULT NULL,
  commit_no              BIGINT       NOT NULL,
  commit_message         VARCHAR(500) NOT NULL,
  source                 VARCHAR(32)  NOT NULL,
  author_user_id         BIGINT       NOT NULL,
  author_name            VARCHAR(64)  DEFAULT NULL,
  operation_id           VARCHAR(64)  NOT NULL,
  action_schema_version  VARCHAR(32)  NOT NULL,
  planned_actions_json   LONGTEXT     DEFAULT NULL,
  executed_changes_json  LONGTEXT     NOT NULL,
  inverse_changes_json   LONGTEXT     DEFAULT NULL,
  diff_summary_json      LONGTEXT     DEFAULT NULL,
  knowledge_refs_json    LONGTEXT     DEFAULT NULL,
  data_context_json      LONGTEXT     DEFAULT NULL,
  execution_context_json LONGTEXT     DEFAULT NULL,
  before_hash            CHAR(64)     NOT NULL,
  after_hash             CHAR(64)     NOT NULL,
  validation_json        LONGTEXT     DEFAULT NULL,
  snapshot_id            CHAR(36)     DEFAULT NULL,
  created_time           DATETIME(3)  NOT NULL,
  PRIMARY KEY (commit_id),
  UNIQUE KEY uk_commit_operation (workbook_id, operation_id),
  UNIQUE KEY uk_commit_no (workbook_id, commit_no),
  KEY idx_commit_parent (parent_commit_id),
  KEY idx_commit_branch_time (branch_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作簿不可变提交';

CREATE TABLE IF NOT EXISTS ai_workbook_snapshot (
  snapshot_id     CHAR(36)    NOT NULL,
  workbook_id     BIGINT      NOT NULL,
  commit_id       CHAR(36)    NOT NULL,
  snapshot_type   VARCHAR(16) NOT NULL,
  content_blob    LONGBLOB    NOT NULL,
  content_hash    CHAR(64)    NOT NULL,
  codec           VARCHAR(16) NOT NULL DEFAULT 'plain',
  created_time    DATETIME(3) NOT NULL,
  PRIMARY KEY (snapshot_id),
  UNIQUE KEY uk_snapshot_commit (commit_id),
  KEY idx_snapshot_workbook (workbook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作簿完整快照';

CREATE TABLE IF NOT EXISTS ai_workbook_working_state (
  workbook_id          BIGINT      NOT NULL,
  branch_id            BIGINT      NOT NULL,
  owner_user_id        BIGINT      NOT NULL,
  base_commit_id       CHAR(36)    DEFAULT NULL,
  draft_actions_json   LONGTEXT    DEFAULT NULL,
  executed_changes_json LONGTEXT   DEFAULT NULL,
  draft_snapshot       LONGBLOB    DEFAULT NULL,
  content_hash         CHAR(64)    DEFAULT NULL,
  version              BIGINT      NOT NULL DEFAULT 0,
  update_time          DATETIME(3) NOT NULL,
  PRIMARY KEY (workbook_id, branch_id, owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作簿分支草稿';

CREATE TABLE IF NOT EXISTS ai_workbook_tag (
  tag_id          BIGINT       NOT NULL AUTO_INCREMENT,
  workbook_id     BIGINT       NOT NULL,
  tag_name        VARCHAR(128) NOT NULL,
  commit_id       CHAR(36)     NOT NULL,
  message         VARCHAR(500) DEFAULT NULL,
  creator_user_id BIGINT       NOT NULL,
  created_time    DATETIME(3)  NOT NULL,
  PRIMARY KEY (tag_id),
  UNIQUE KEY uk_workbook_tag (workbook_id, tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作簿版本标签';
