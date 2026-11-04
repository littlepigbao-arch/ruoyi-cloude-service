-- B8a additive effect ledger for MySQL 5.7. Apply only to a backed-up, isolated database first.
-- No existing table is rebuilt or altered. Existing binaries ignore this table.
CREATE TABLE IF NOT EXISTS ai_agent_effect (
 effect_id varchar(36) NOT NULL,
 run_id varchar(36) NOT NULL,
 semantic_key char(64) NOT NULL,
 args_hash char(64) NOT NULL,
 target_json longtext NOT NULL,
 status varchar(24) NOT NULL,
 call_id varchar(80) DEFAULT NULL,
 before_revision char(64) DEFAULT NULL,
 after_revision char(64) DEFAULT NULL,
 receipt_hash char(64) DEFAULT NULL,
 result_json longtext DEFAULT NULL,
 created_at datetime(3) NOT NULL,
 updated_at datetime(3) NOT NULL,
 PRIMARY KEY (effect_id),
 UNIQUE KEY uk_effect_run_semantic (run_id,semantic_key),
 UNIQUE KEY uk_effect_run_call (run_id,call_id),
 KEY ix_effect_run_status (run_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
