-- C3 workbook import job ledger for MySQL 5.7. Apply only to a backed-up, isolated database first.
-- This migration only adds one new table. No existing table is rebuilt, altered or dropped.
-- ai_workbook_import_job: one row per XLSX import job. Authorization is content-addressed: the job is
-- owned by user_id and follow-up data access (preview) must present the sha256 of the uploaded bytes
-- (source_content_hash) - no endpoint ever accepts a file path or filesystem reference as authorization.
-- State machine: PARSING -> MATERIALIZING -> SUCCEEDED, and PARSING/MATERIALIZING -> FAILED on any
-- refusal (budgets are enforced BEFORE any materialization, so a FAILED job has no artifact rows).
-- state_history_json records every transition with its epoch millis for traceability; the fail_* columns
-- keep the refusal code, phase, message and machine-readable detail (budget kind, observed, limit).
-- Parameter columns (request_id, request_hash, source_name, source_content_hash, source_byte_size) are
-- written once at creation and never rewritten - idempotent replay only compares them.
-- NOTE: COMMENT literals below must stay semicolon-free - the test setup splits this file on ';'.
CREATE TABLE IF NOT EXISTS ai_workbook_import_job (
 job_id varchar(36) NOT NULL,
 user_id bigint NOT NULL,
 request_id varchar(80) NOT NULL,
 request_hash char(64) NOT NULL,
 source_name varchar(255) NOT NULL,
 source_content_hash char(64) NOT NULL,
 source_byte_size bigint NOT NULL,
 status varchar(24) NOT NULL,
 state_history_json longtext NOT NULL,
 artifact_id varchar(36) DEFAULT NULL,
 sheet_count int DEFAULT NULL,
 total_cells int DEFAULT NULL,
 parse_stats_json longtext DEFAULT NULL,
 fail_code varchar(64) DEFAULT NULL,
 fail_phase varchar(24) DEFAULT NULL,
 fail_message varchar(500) DEFAULT NULL,
 fail_detail_json longtext DEFAULT NULL,
 created_at datetime(3) NOT NULL,
 updated_at datetime(3) NOT NULL,
 PRIMARY KEY (job_id),
 UNIQUE KEY uk_import_user_request (user_id,request_id),
 KEY ix_import_user_created (user_id,created_at),
 KEY ix_import_source_hash (source_content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C3: hash-authorized workbook import job with traceable failures';
