-- C2 additive batch ledger for MySQL 5.7. Apply only to a backed-up, isolated database first.
-- This migration only adds two new tables. No existing table is rebuilt, altered or dropped, and the
-- effect ledger keeps its primary/unique key semantics - one effect may own many batch rows here.
-- ai_agent_write_batch: the server-generated, record-before-dispatch batch manifest of one effect.
-- Parameter columns (chunk_ref, chunk_rows, row_start, verification_spec_id, params_hash, manifest_json,
-- expected_before, expected_after, batch_id) are written once at record time and are never updated by any
-- code path - only status, claim_token, receipt_hash, after_revision, result_json and updated_at move.
-- NOTE: COMMENT literals below must stay semicolon-free - the test setup splits this file on ';'.
CREATE TABLE IF NOT EXISTS ai_agent_write_batch (
 run_id varchar(36) NOT NULL,
 effect_id varchar(36) NOT NULL,
 batch_index int NOT NULL,
 batch_id varchar(36) NOT NULL,
 status varchar(24) NOT NULL,
 chunk_ref varchar(90) NOT NULL,
 chunk_rows int NOT NULL,
 row_start int NOT NULL,
 verification_spec_id char(64) NOT NULL,
 params_hash char(64) NOT NULL,
 manifest_json longtext NOT NULL,
 expected_before char(64) NOT NULL,
 expected_after char(64) NOT NULL,
 claim_token varchar(36) DEFAULT NULL,
 receipt_hash char(64) DEFAULT NULL,
 after_revision char(64) DEFAULT NULL,
 result_json longtext DEFAULT NULL,
 created_at datetime(3) NOT NULL,
 updated_at datetime(3) NOT NULL,
 PRIMARY KEY (batch_id),
 UNIQUE KEY uk_batch_run_effect_index (run_id,effect_id,batch_index),
 KEY ix_batch_effect_status (effect_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C2: one effect many batches, parameters frozen at record time';
-- ai_agent_checkpoint: the recoverable checkpoint written in the same transaction as each batch PERSISTED
-- transition, plus the initial manifest checkpoint (batch_index -1) written before the first dispatch.
CREATE TABLE IF NOT EXISTS ai_agent_checkpoint (
 run_id varchar(36) NOT NULL,
 effect_id varchar(36) NOT NULL,
 checkpoint_seq bigint NOT NULL,
 batch_index int NOT NULL,
 saved_draft_version bigint NOT NULL,
 content_hash char(64) NOT NULL,
 state varchar(24) NOT NULL,
 created_at datetime(3) NOT NULL,
 PRIMARY KEY (run_id,effect_id,checkpoint_seq),
 KEY ix_checkpoint_effect_batch (run_id,effect_id,batch_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C2: durable per-batch checkpoint for restart recovery';
