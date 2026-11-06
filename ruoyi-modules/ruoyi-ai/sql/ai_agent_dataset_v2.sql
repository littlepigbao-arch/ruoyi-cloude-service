-- C1a additive chunked artifact storage for MySQL 5.7. Apply only to a backed-up, isolated database first.
-- This migration only adds: ADD COLUMN on ai_analysis_artifact plus two new tables. No existing table is
-- rebuilt, altered beyond the added columns, or dropped. Existing inline artifacts keep content_json and
-- stay readable through the unchanged inline path (dual read by storage_kind).
-- Pin semantics reuse the status column (ACTIVE->PINNED); no extra pinned flag column is introduced.
ALTER TABLE ai_analysis_artifact
 ADD COLUMN workbook_id bigint NULL COMMENT 'C1a: owning workbook snapshot for dataset queries',
 ADD COLUMN branch_id bigint NULL COMMENT 'C1a: owning branch snapshot for dataset queries',
 ADD COLUMN schema_name varchar(64) NULL COMMENT 'C1a: logical dataset schema name',
 ADD COLUMN storage_kind varchar(16) NOT NULL DEFAULT 'inline' COMMENT 'C1a: inline|chunked, legacy rows stay inline',
 ADD COLUMN row_count int NULL COMMENT 'C1a: total dataset rows (chunked only)',
 ADD COLUMN byte_size bigint NULL COMMENT 'C1a: total UTF-8 bytes of concatenated chunk blobs (chunked only)',
 ADD COLUMN content_hash char(64) NULL COMMENT 'C1a: sha256 over concatenated chunk blobs (chunked only)',
 ADD COLUMN expires_at datetime(3) NULL COMMENT 'C1a: TTL, NULL means permanent',
 ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'C1a: ACTIVE|PINNED|EXPIRED|INVALIDATED';
CREATE TABLE IF NOT EXISTS ai_analysis_artifact_chunk (
 artifact_id varchar(36) NOT NULL,
 chunk_index int NOT NULL,
 row_start int NOT NULL,
 row_count int NOT NULL,
 content_blob longtext NOT NULL,
 content_hash char(64) NOT NULL,
 PRIMARY KEY(artifact_id,chunk_index),
 KEY idx_chunk_artifact(artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C1a: fixed-row chunks of one chunked artifact, content_hash guards each chunk';
CREATE TABLE IF NOT EXISTS ai_analysis_artifact_dependency (
 id bigint AUTO_INCREMENT PRIMARY KEY,
 artifact_id varchar(36) NOT NULL,
 source_revision char(64) NULL,
 dependency_kind varchar(16) NULL,
 rule_version varchar(32) NULL,
 created_at datetime(3) NOT NULL,
 KEY idx_dep_artifact(artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C1a skeleton only: invalidation propagation lands in C1c';
-- C1b dataset envelope chunking: chunked dataset envelopes keep their lineage out of content_json;
-- content_json then holds only the envelope skeleton (version, digests, columns, empty rows).
-- Kept as a separate statement so already-migrated C1a databases add exactly this one column
-- (whole-statement duplicate-column failures are tolerated by the test setup loop).
ALTER TABLE ai_analysis_artifact
 ADD COLUMN lineage_json longtext NULL COMMENT 'C1b: dataset envelope lineage, chunked only';
-- C1c dependency registration: the dependency row also stamps the ai_workbook.version counter at
-- registration time; lazy invalidation compares version counters, never hash lineages (source_revision
-- is a run-side tree hash while ai_workbook.content_hash is a raw-string hash - two incomparable lineages).
-- Kept as a separate statement so already-migrated databases add exactly this one column
-- (whole-statement duplicate-column failures are tolerated by the test setup loop).
-- NOTE: the COMMENT literal below must stay semicolon-free - the test setup splits this file on ';'.
ALTER TABLE ai_analysis_artifact_dependency
 ADD COLUMN source_version bigint NULL COMMENT 'C1c: ai_workbook.version at registration - propagation compares version counters, not hash lineages';
-- C1-review F2 dual-channel source identity: registration also stamps the draft channel
-- (ai_workbook_working_state.version of the artifact's own workbook+branch+owner at registration time).
-- Invalidation compares both channels - commit advancement (ai_workbook.version) and real draft saves
-- (saveWorkingState advancing working_state.version without touching ai_workbook.version); NULL on either
-- channel means that channel is exempt (same semantics as a NULL source_version). Hash lineages (tree hash
-- vs raw-string hash) still never participate in the comparison. Kept as a separate statement so already
-- migrated databases add exactly this one column.
-- NOTE: the COMMENT literal below must stay semicolon-free - the test setup splits this file on ';'.
ALTER TABLE ai_analysis_artifact_dependency
 ADD COLUMN source_draft_version bigint NULL COMMENT 'C1r-F2: working_state.version at registration - draft channel of the dual identity, NULL means exempt';
