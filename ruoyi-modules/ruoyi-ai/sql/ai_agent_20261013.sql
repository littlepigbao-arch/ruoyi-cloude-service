-- Plan-and-Execute V1. Execute after the workbook Git and conversation migrations.
-- Additive migration; existing workbooks and commits are preserved.
CREATE TABLE IF NOT EXISTS ai_agent_run (
 run_id varchar(36) PRIMARY KEY, user_id bigint NOT NULL, workbook_id bigint NOT NULL,
 branch_id bigint NOT NULL, request_id varchar(80) NOT NULL, request_hash varchar(64) NOT NULL,
 status varchar(24) NOT NULL, state_json longtext NOT NULL, row_version bigint NOT NULL DEFAULT 0,
 updated_at datetime(3) NOT NULL, created_at datetime(3) NOT NULL,
 UNIQUE KEY uk_agent_request(user_id, request_id), KEY ix_agent_schedule(status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_lease (
 workbook_id bigint PRIMARY KEY, run_id varchar(36), fencing_token bigint NOT NULL DEFAULT 0,
 expires_at bigint NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_plan (
 run_id varchar(36) NOT NULL, plan_version int NOT NULL, definition_json longtext NOT NULL,
 created_at datetime(3) NOT NULL, PRIMARY KEY(run_id,plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_step (
 run_id varchar(36) NOT NULL, plan_version int NOT NULL, step_id varchar(80) NOT NULL,
 status varchar(24) NOT NULL, definition_json longtext NOT NULL,
 PRIMARY KEY(run_id,plan_version,step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_tool_call (
 run_id varchar(36) NOT NULL, call_id varchar(80) NOT NULL, status varchar(24) NOT NULL,
 args_hash varchar(64) NOT NULL, call_json longtext NOT NULL,
 PRIMARY KEY(run_id,call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_event (
 run_id varchar(36) NOT NULL, sequence_no bigint NOT NULL, event_json longtext NOT NULL,
 created_at datetime(3) NOT NULL, PRIMARY KEY(run_id,sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_analysis_artifact (
 artifact_id varchar(36) PRIMARY KEY, run_id varchar(36) NOT NULL, user_id bigint NOT NULL,
 source_revision varchar(64) NOT NULL, content_json longtext NOT NULL,
 created_at datetime(3) NOT NULL, KEY ix_artifact_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
