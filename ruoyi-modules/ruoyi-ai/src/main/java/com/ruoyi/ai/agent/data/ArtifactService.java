package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.WriteManifest;
import com.ruoyi.ai.util.ContentHashUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1a chunked artifact storage: one artifact row plus fixed-row chunks written in a single transaction.
 * Reads are owner-only and per-chunk hash-verified. No endpoint ever returns a full concatenation; only
 * {@link #readArtifactForLegacy} assembles chunks for existing single-block consumers.
 * Pin reuses the status column (ACTIVE->PINNED) instead of a new flag column: a PINNED artifact skips the
 * TTL check; pin never exempts the dependency staleness check.
 * C1c adds dataset dependency registration (writeArtifactEnvelope only), stamped with the C1-review F2
 * dual-channel source identity: the commit channel (ai_workbook.version) plus the draft-save channel
 * (ai_workbook_working_state.version of the artifact's own workbook+branch+owner) - a real saveWorkingState
 * advances the draft while the version counter stands still, so both channels are compared; hash lineages
 * (tree hash vs raw-string hash) are deliberately never compared, and the historical-read vs
 * writable-back-source distinction stays with the SOURCE_STALE write path outside this service.
 * C1-review F5 contract (2026-11-05 ledger): the read path is pure comparison and zero mutation - TTL and
 * staleness refusals are decided by fresh SELECTs and can never be rolled back or committed by an outer
 * transaction. The status column materializes EXPIRED/INVALIDATED only inside sweepInvalidated's
 * independent transaction (both kinds) or via the explicit markInvalidated.
 * C1-review round 2 (2026-11-06 ledger): the draft channel is three-state (registered-NULL vs a row that
 * appeared later, registered-value vs a row that vanished or moved - both fail closed, as do a deleted
 * source workbook / branch or a branch whose owner no longer matches the artifact user); dependency
 * registration binds the registered snapshot to the authoritative source content by parsed-identity
 * equality on both the draft and the committed path (round 3 supersedes round 2's raw-hash binding,
 * which only held for compact saves) and refuses the whole write on mismatch; every paging page
 * re-validates the full chunk
 * sequence plus its parsed rows size against the declared row_count; materialization transactions are
 * REQUIRES_NEW so no caller rollback can resurrect ACTIVE status.
 */
@Service
public class ArtifactService {
    public static final int CHUNK_ROWS = 5000;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    /** C1r2-F5: status materialization always commits in its own REQUIRES_NEW transaction. */
    private final TransactionTemplate materializeTx;
    @Autowired
    public ArtifactService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        materializeTx = new TransactionTemplate(manager);
        materializeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        materializeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** For callers holding only a JdbcTemplate (AgentStore, tests); joins outer transactions via the shared DataSource. */
    public ArtifactService(JdbcTemplate db) { this(db, new DataSourceTransactionManager(db.getDataSource())); }
    /** content_hash is sha256 over the chunk blobs concatenated in chunk_index order; byte_size is that string's UTF-8 length. */
    public ObjectNode writeArtifact(String runId, long userId, long workbookId, long branchId, String schemaName, String sourceRevision, ArrayNode rows, Date expiresAt) {
        require(runId != null && !runId.trim().isEmpty(), "ARTIFACT_RUN_REQUIRED: 缺少任务标识");
        require(sourceRevision != null, "ARTIFACT_REVISION_REQUIRED: 缺少来源版本");
        require(rows != null, "ARTIFACT_ROWS_REQUIRED: 缺少行数据");
        String id = UUID.randomUUID().toString();
        return tx.execute(status -> {
            List<String> blobs = chunkBlobs(rows);
            String joined = String.join("", blobs);
            String hash = ContentHashUtils.sha256(joined);
            long byteSize = joined.getBytes(StandardCharsets.UTF_8).length;
            db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,workbook_id,branch_id,schema_name,storage_kind,row_count,byte_size,content_hash,expires_at,status,created_at) values (?,?,?,?, '',?,?,?,?,?,?,?,?,?,now(3))",
                id, runId, userId, sourceRevision, workbookId, branchId, schemaName, "chunked", rows.size(), byteSize, hash, expiresAt, "ACTIVE");
            for (int c = 0; c < blobs.size(); c++)
                db.update("insert into ai_analysis_artifact_chunk(artifact_id,chunk_index,row_start,row_count,content_blob,content_hash) values (?,?,?,?,?,?)",
                    id, c, c * CHUNK_ROWS, Math.min(CHUNK_ROWS, rows.size() - c * CHUNK_ROWS), blobs.get(c), ContentHashUtils.sha256(blobs.get(c)));
            ObjectNode meta = obj().put("id", id).put("runId", runId).put("userId", userId).put("sourceRevision", sourceRevision)
                .put("storageKind", "chunked").put("rowCount", rows.size()).put("byteSize", byteSize).put("contentHash", hash)
                .put("status", "ACTIVE").put("chunkCount", blobs.size());
            if (expiresAt == null) meta.putNull("expiresAt"); else meta.put("expiresAt", expiresAt.getTime());
            return meta;
        });
    }
    /**
     * C1b versioned dataset envelope ({artifactEnvelopeVersion:2, data:{columns,rows}, lineage, dataDigest, lineageDigest}).
     * Data rows beyond one chunk go to ai_analysis_artifact_chunk exactly like writeArtifact; lineage moves to the
     * lineage_json column and content_json keeps only the envelope skeleton (version, digests, columns, empty rows).
     * content_hash stays the transport integrity hash over concatenated chunk blobs; dataDigest/lineageDigest stay in the
     * skeleton and are re-verified by AgentStore.artifactRecord's existing WriteManifest.digest checks - semantics unchanged
     * for inline envelopes. Envelopes fitting in a single chunk stay inline so legacy single-block consumers (and their
     * content_json tamper tests) keep working unchanged.
     */
    public ObjectNode writeArtifactEnvelope(String runId, long userId, long workbookId, long branchId, String schemaName, String sourceRevision, JsonNode data, JsonNode lineage, Date expiresAt) {
        require(runId != null && !runId.trim().isEmpty(), "ARTIFACT_RUN_REQUIRED: 缺少任务标识");
        require(sourceRevision != null, "ARTIFACT_REVISION_REQUIRED: 缺少来源版本");
        require(data != null && data.isObject() && data.path("columns").isArray() && data.path("rows").isArray(), "ARTIFACT_ENVELOPE_INVALID: 数据集封装必须包含columns与rows");
        require(lineage != null && lineage.isArray(), "ARTIFACT_ENVELOPE_INVALID: 数据集封装缺少来源脉络");
        ObjectNode envelope = obj().put("artifactEnvelopeVersion", 2)
            .put("dataDigest", WriteManifest.digest(data)).put("lineageDigest", WriteManifest.digest(lineage));
        envelope.set("data", data.deepCopy()); envelope.set("lineage", lineage.deepCopy());
        ArrayNode rows = (ArrayNode) data.path("rows");
        String id = UUID.randomUUID().toString();
        return tx.execute(status -> {
            if (rows.size() <= CHUNK_ROWS) {
                db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,workbook_id,branch_id,schema_name,storage_kind,expires_at,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,now(3))",
                    id, runId, userId, sourceRevision, envelope.toString(), workbookId, branchId, schemaName, "inline", expiresAt, "ACTIVE");
                registerDatasetDependency(id, userId, workbookId, branchId, schemaName, sourceRevision);
                return obj().put("id", id).put("runId", runId).put("userId", userId).put("sourceRevision", sourceRevision)
                    .put("storageKind", "inline").put("rowCount", rows.size()).put("status", "ACTIVE").put("chunkCount", 0);
            }
            List<String> blobs = chunkBlobs(rows);
            String joined = String.join("", blobs);
            String hash = ContentHashUtils.sha256(joined);
            long byteSize = joined.getBytes(StandardCharsets.UTF_8).length;
            ObjectNode skeleton = envelope.deepCopy();
            ((ObjectNode) skeleton.path("data")).set("rows", arr());
            skeleton.remove("lineage");
            db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,workbook_id,branch_id,schema_name,storage_kind,row_count,byte_size,content_hash,lineage_json,expires_at,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(3))",
                id, runId, userId, sourceRevision, skeleton.toString(), workbookId, branchId, schemaName, "chunked", rows.size(), byteSize, hash, lineage.toString(), expiresAt, "ACTIVE");
            for (int c = 0; c < blobs.size(); c++)
                db.update("insert into ai_analysis_artifact_chunk(artifact_id,chunk_index,row_start,row_count,content_blob,content_hash) values (?,?,?,?,?,?)",
                    id, c, c * CHUNK_ROWS, Math.min(CHUNK_ROWS, rows.size() - c * CHUNK_ROWS), blobs.get(c), ContentHashUtils.sha256(blobs.get(c)));
            registerDatasetDependency(id, userId, workbookId, branchId, schemaName, sourceRevision);
            ObjectNode meta = obj().put("id", id).put("runId", runId).put("userId", userId).put("sourceRevision", sourceRevision)
                .put("storageKind", "chunked").put("rowCount", rows.size()).put("byteSize", byteSize).put("contentHash", hash)
                .put("status", "ACTIVE").put("chunkCount", blobs.size());
            if (expiresAt == null) meta.putNull("expiresAt"); else meta.put("expiresAt", expiresAt.getTime());
            return meta;
        });
    }
    private static List<String> chunkBlobs(ArrayNode rows) {
        List<String> blobs = new ArrayList<>();
        for (int start = 0; start < rows.size() || start == 0; start += CHUNK_ROWS) {
            ArrayNode chunk = arr();
            for (int i = start; i < Math.min(start + CHUNK_ROWS, rows.size()); i++) chunk.add(rows.get(i));
            blobs.add(chunk.toString());
        }
        return blobs;
    }
    /**
     * C1c dependency registration: a successfully written dataset envelope records its upstream workbook
     * revision in ai_analysis_artifact_dependency inside the same transaction, so the artifact row and its
     * dependency row commit or roll back together. Only the envelope path registers; writeArtifact keeps
     * the plain row-array semantics with no envelope lineage and therefore no dependency. Idempotency is
     * skip-if-present (minimal choice): envelope ids are fresh UUIDs per write, so a duplicate registration
     * could only stem from a replayed write, and skipping keeps each artifact's dependency grain at one row.
     * Revision facts kept, with distinct roles: source_revision stays a lineage record of the run-side tree
     * hash and is never compared at read time; source_version stamps the ai_workbook.version counter (the
     * commit-advancement channel) and source_draft_version stamps the ai_workbook_working_state.version of
     * the artifact's own workbook+branch+owner row (the draft-save channel, C1-review F2) - propagation
     * compares these two version channels only. A real draft save (saveWorkingState) advances
     * working_state.version without touching ai_workbook.version, so a single version channel would keep
     * serving artifacts whose source data already changed. The draft channel is three-state (C1-review
     * round 2 F2-a): registered NULL means "no draft existed", and any working-state row appearing later
     * (including the very first save at v0) invalidates; registered with a value, a vanished row or an
     * unequal version invalidates (F2-c fail-closed - the source no longer exists, so refusing prevents
     * forging freshness from a resurrected pointer). The tree hash and ai_workbook.content_hash (a
     * raw-string hash) are two incomparable lineages that legitimately diverge on non-canonical content,
     * so comparing them would falsely invalidate fresh artifacts.
     * C1-review round 3 F2-b registration-time binding: the run-side sourceRevision is produced by exactly
     * one rule - AgentJson.hash(parse(authoritative content)) - where the authoritative content is
     * working_state.draft_snapshot when a draft row exists for the artifact's own workbook+branch+owner and
     * ai_workbook.content otherwise (AgentRunService.workspace/current and ControlledWriteService.current
     * all apply this rule). Registration mirrors that rule on BOTH paths: it reads the authoritative
     * content, parses it and compares the parsed identity. The round-2 binding instead equated
     * sourceRevision with working_state.content_hash, which holds only for compact saves - a pretty-printed
     * draft legitimately diverges (raw-string hash lineage) and was falsely refused - and it skipped
     * verification entirely when no draft row existed, letting stale committed content register under the
     * advanced version counter. content_hash keeps its original raw-content integrity role
     * (WorkbookVersionServiceImpl) and is never an identity here; WriteManifest.digest is a sorted-canonical
     * lineage and likewise never compared. Any mismatch refuses the whole write with SOURCE_STALE (the
     * write-path philosophy of B10: no silent registration on stale identity) and the transaction rolls
     * back leaving zero residue. Version stamps come from the very rows whose content was verified:
     * source_version from that ai_workbook row's version and source_draft_version from that working_state
     * row's version, so both channels always describe the identity that was actually checked.
     */
    private void registerDatasetDependency(String id, long userId, long workbookId, long branchId, String schemaName, String sourceRevision) {
        if (!DatasetService.SCHEMA_NAME.equals(schemaName)) return;
        if (db.queryForObject("select count(*) from ai_analysis_artifact_dependency where artifact_id=?", Integer.class, id) != 0) return;
        List<Map<String, Object>> book = db.queryForList("select version,content from ai_workbook where workbook_id=?", workbookId);
        List<Map<String, Object>> draft = db.queryForList("select version,draft_snapshot from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=?", workbookId, branchId, userId);
        String authoritative = draft.isEmpty() ? (book.isEmpty() ? null : stringValue(book.get(0).get("content"))) : stringValue(draft.get(0).get("draft_snapshot"));
        require(authoritative != null && sourceRevision.equals(hash(parse(authoritative))), "SOURCE_STALE: 登记快照与当前权威来源不一致，拒绝生成依赖制品");
        db.update("insert into ai_analysis_artifact_dependency(artifact_id,source_revision,source_version,source_draft_version,dependency_kind,rule_version,created_at) values (?,?,?,?,?,?,now(3))",
            id, sourceRevision, book.isEmpty() ? null : ((Number) book.get(0).get("version")).longValue(), draft.isEmpty() ? null : ((Number) draft.get(0).get("version")).longValue(), "dataset_source", DatasetService.RULE_VERSION);
    }
    /** draft_snapshot is LONGBLOB and content is LONGTEXT, so plain JdbcTemplate hands back byte[] or String depending on the column - normalize to the UTF-8 string the run side parses. */
    private static String stringValue(Object column) {
        if (column == null) return null;
        return column instanceof byte[] ? new String((byte[]) column, StandardCharsets.UTF_8) : String.valueOf(column);
    }
    /** Metadata only; never carries the artifact content. */
    public ObjectNode readArtifactMetadata(String id, long userId) { return metadataFromRow(readableRow(id, userId)); }
    /**
     * C1-review F1 unified state gate for every artifact consumer lane (inline, chunked and the legacy
     * dual-read lane): ownership, TTL, status and dependency invalidation are decided here, so an
     * INVALIDATED or EXPIRED artifact can no longer be served by parsing content_json directly.
     * Permission is checked twice on the artifactRecord lane (join there + ownedRow here) by design -
     * the duplicate check is harmless and keeps this gate usable standalone.
     */
    public void assertReadable(String id, long userId) { readableRow(id, userId); }
    /** Keyset paging over stable ascending chunk_index: cursor is the previous page's nextCursor (null = first page), one chunk per page. */
    public ObjectNode readChunkPage(String id, long userId, Long cursor) { return readChunkPage(id, userId, cursor, null); }
    /** C3-review F2: precision-preserving variant for consumers whose cell values must survive the read
     *  as exact decimals (import preview). Identical gate, sequence, slice, hash and row-count checks -
     *  only the chunk blob's parse differs: a caller-supplied mapper with floats-as-BigDecimal replaces
     *  the default parse whose DoubleNode materialization silently re-rounded long literals. A null
     *  mapper keeps the historical behavior, so every existing caller is unchanged. */
    public ObjectNode readChunkPage(String id, long userId, Long cursor, com.fasterxml.jackson.databind.ObjectMapper preciseMapper) {
        Map<String, Object> row = readableRow(id, userId);
        require("chunked".equals(String.valueOf(row.get("storage_kind"))), "ARTIFACT_NOT_CHUNKED: 仅分块制品支持逐块读取");
        // C1r-F4: reject a cursor outside the lossless int range before any intValue() narrowing could
        // silently alias it (4294967296L used to narrow to 0 and serve the first chunk again).
        require(cursor == null || (cursor >= 0 && cursor <= Integer.MAX_VALUE), "ARTIFACT_CURSOR_INVALID: 分页游标越界");
        Integer index;
        // C1r2-F3: EVERY page re-validates the whole chunk sequence (the C1r-F3 check ran on the first page
        // only, so deleting the tail chunk after page 1 let later pages end the walk silently short). The
        // check reads chunk_index/row_start/row_count metadata only - never a content blob - so the per-page
        // cost is one bounded metadata query; completeness is deliberately preferred over per-page latency.
        requireChunkSequenceIntact(id, row);
        if (cursor == null) index = queryIndex("select min(chunk_index) from ai_analysis_artifact_chunk where artifact_id=?", id);
        else index = cursor.intValue();
        require(index != null && index >= 0, "ARTIFACT_CURSOR_INVALID: 分页游标越界");
        List<Map<String, Object>> chunks = db.queryForList("select chunk_index,row_start,row_count,content_blob,content_hash from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=?", id, index);
        require(!chunks.isEmpty(), "ARTIFACT_CURSOR_INVALID: 分页游标越界");
        Map<String, Object> chunk = chunks.get(0);
        Integer next = queryIndex("select min(chunk_index) from ai_analysis_artifact_chunk where artifact_id=? and chunk_index>?", id, index);
        // C1r-F3: a later page validates its own row_start/row_count against its chunk index and slice position.
        requireSliceConsistent(row, ((Number) chunk.get("chunk_index")).intValue(), ((Number) chunk.get("row_start")).intValue(), ((Number) chunk.get("row_count")).intValue(), next == null);
        String blob = verifiedBlob(id, String.valueOf(chunk.get("chunk_index")), String.valueOf(chunk.get("content_blob")), chunk.get("content_hash"));
        JsonNode content;
        try { content = preciseMapper == null ? parse(blob) : preciseMapper.readTree(blob); }
        catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("ARTIFACT_INVALID: 分块内容不是有效 JSON", e); }
        require(content.isArray(), "ARTIFACT_INVALID: 分块内容不是行数组");
        // C1r2-F3: content consistency - the parsed rows must be exactly the declared row_count. A blob
        // rewritten to [] with its own content_hash re-synced used to serve a page claiming n rows with 0.
        int declared = ((Number) chunk.get("row_count")).intValue();
        require(content.size() == declared, SEQUENCE_INVALID + "（块 " + index + " 声明 " + declared + " 行实际 " + content.size() + " 行）");
        ObjectNode page = obj().put("chunkIndex", ((Number) chunk.get("chunk_index")).intValue())
            .put("rowStart", ((Number) chunk.get("row_start")).intValue()).put("rowCount", ((Number) chunk.get("row_count")).intValue());
        page.set("rows", content);
        if (next == null) page.putNull("nextCursor"); else page.put("nextCursor", next.longValue());
        return page;
    }
    private static final String SEQUENCE_INVALID = "ARTIFACT_CHUNK_SEQUENCE_INVALID: 制品分块序列不完整";
    /** C1r-F3 full-sequence check (C1r2-F3: re-run on EVERY paging page, not just the first): chunk indexes run 0..n-1 with no gaps, the chunk count matches the artifact row_count, every chunk's row_start/row_count matches its slice, and the per-chunk row counts sum to the artifact row_count. Reads index/row_start/row_count metadata only - never a content blob. */
    private void requireChunkSequenceIntact(String id, Map<String, Object> artifact) {
        List<Map<String, Object>> chunks = db.queryForList("select chunk_index,row_start,row_count from ai_analysis_artifact_chunk where artifact_id=? order by chunk_index", id);
        require(!chunks.isEmpty(), SEQUENCE_INVALID);
        Integer total = artifact.get("row_count") == null ? null : ((Number) artifact.get("row_count")).intValue();
        long sum = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunk = chunks.get(i);
            require(((Number) chunk.get("chunk_index")).intValue() == i, SEQUENCE_INVALID);
            requireSliceConsistent(artifact, i, ((Number) chunk.get("row_start")).intValue(), ((Number) chunk.get("row_count")).intValue(), i == chunks.size() - 1);
            sum += ((Number) chunk.get("row_count")).intValue();
        }
        if (total != null) {
            require((total == 0 ? 1 : (total + CHUNK_ROWS - 1) / CHUNK_ROWS) == chunks.size(), SEQUENCE_INVALID);
            require(sum == total, SEQUENCE_INVALID);
        }
    }
    /** C1r-F3 slice check for one chunk: row_start is the chunk's window origin and row_count is exactly the slice the fixed-row writer produced (a 0-row artifact keeps one empty chunk). */
    private void requireSliceConsistent(Map<String, Object> artifact, int index, int rowStart, int rowCount, boolean last) {
        require(rowStart == index * CHUNK_ROWS, SEQUENCE_INVALID);
        Integer total = artifact.get("row_count") == null ? null : ((Number) artifact.get("row_count")).intValue();
        if (total != null) require(rowCount == Math.max(0, Math.min(CHUNK_ROWS, total - index * CHUNK_ROWS)), SEQUENCE_INVALID);
        else require(last ? rowCount >= 0 && rowCount <= CHUNK_ROWS : rowCount == CHUNK_ROWS, SEQUENCE_INVALID);
    }
    /** Dual read for existing single-block consumers only (never an HTTP endpoint): chunked is assembled chunk by chunk, legacy inline reads content_json. */
    public JsonNode readArtifactForLegacy(String id, long userId) {
        Map<String, Object> row = readableRow(id, userId);
        if (!"chunked".equals(String.valueOf(row.get("storage_kind")))) return parse(String.valueOf(row.get("content_json")));
        List<Map<String, Object>> chunks = db.queryForList("select chunk_index,content_blob,content_hash from ai_analysis_artifact_chunk where artifact_id=? order by chunk_index", id);
        StringBuilder joined = new StringBuilder(); ArrayNode all = arr();
        for (Map<String, Object> chunk : chunks) {
            String blob = verifiedBlob(id, String.valueOf(chunk.get("chunk_index")), String.valueOf(chunk.get("content_blob")), chunk.get("content_hash"));
            JsonNode content = parse(blob);
            require(content.isArray(), "ARTIFACT_INVALID: 分块内容不是行数组");
            joined.append(blob); all.addAll((ArrayNode) content);
        }
        require(ContentHashUtils.sha256(joined.toString()).equals(String.valueOf(row.get("content_hash"))), "ARTIFACT_HASH_MISMATCH: 制品 " + id + " 整体哈希不符，疑似篡改");
        Object lineageJson = row.get("lineage_json");
        if (lineageJson == null) return all;
        // C1b chunked dataset envelope: rebuild the original envelope object from the content_json skeleton
        // plus the reassembled rows and lineage_json, so AgentStore.artifactRecord re-verifies
        // dataDigest/lineageDigest exactly as it does for inline envelopes.
        JsonNode skeleton = parse(String.valueOf(row.get("content_json")));
        require(skeleton.isObject() && skeleton.has("artifactEnvelopeVersion") && skeleton.path("data").isObject(), "ARTIFACT_INVALID: 分块制品封装骨架无效");
        ObjectNode envelope = (ObjectNode) skeleton.deepCopy();
        ((ObjectNode) envelope.path("data")).set("rows", all);
        envelope.set("lineage", parse(String.valueOf(lineageJson)));
        return envelope;
    }
    /** Pin keeps an artifact readable past its TTL; the status semantics ACTIVE->PINNED avoid a dedicated flag column. */
    public ObjectNode pinArtifact(String id, long userId) {
        Map<String, Object> row = ownedRow(id, userId);
        String status = String.valueOf(row.get("status"));
        require("ACTIVE".equals(status) || "PINNED".equals(status), "ARTIFACT_STATE_INVALID: 已过期或已失效的制品不能固定");
        db.update("update ai_analysis_artifact set status='PINNED' where artifact_id=? and user_id=?", id, userId);
        return metadataFromRow(ownedRow(id, userId));
    }
    /**
     * C1c invalidation propagation builds on this state; every read refuses INVALIDATED afterwards.
     * C1-review round 2 F5: the status UPDATE (and the metadata re-read that reports it) run inside the
     * REQUIRES_NEW materialization transaction, so an outer caller's rollback can never resurrect ACTIVE.
     * Constraint: REQUIRES_NEW takes a second physical connection - under a SingleConnectionDataSource
     * (one shared connection) an inner call from an outer transaction would self-block, so this path must
     * only be used with a regular pooled/per-call DataSource (tests use plain new A1TestFixture()).
     */
    public ObjectNode markInvalidated(String id, long userId) {
        ownedRow(id, userId);
        return materializeTx.execute(status -> {
            boundMaterializationLockWait();
            db.update("update ai_analysis_artifact set status='INVALIDATED' where artifact_id=? and user_id=?", id, userId);
            return metadataFromRow(ownedRow(id, userId));
        });
    }
    /**
     * Bounded lock wait for materialization traffic: an outer transaction holding the target row must fail
     * this UPDATE fast instead of hanging the default 50s. The session variable is set on the same physical
     * connection that runs the UPDATE (the transaction-bound connection inside materializeTx). Note: with a
     * pooled DataSource the setting survives the connection's return to the pool - a deliberate bounded-wait
     * policy for whoever borrows that connection next; the test DriverManagerDataSource opens a fresh
     * connection per transaction, so nothing leaks between tests.
     */
    private void boundMaterializationLockWait() { db.execute("set session innodb_lock_wait_timeout=5"); }
    private String verifiedBlob(String id, String chunkIndex, String blob, Object expectedHash) {
        require(ContentHashUtils.sha256(blob).equals(expectedHash == null ? null : String.valueOf(expectedHash)), "ARTIFACT_HASH_MISMATCH: 制品分块 " + id + "#" + chunkIndex + " 哈希不符，疑似篡改");
        return blob;
    }
    private Integer queryIndex(String sql, Object... args) { return db.queryForObject(sql, Integer.class, args); }
    /** Existence and ownership are one check so a foreign id reveals nothing. expires_at is read as epoch millis server-side (same session timezone as writes). */
    private Map<String, Object> ownedRow(String id, long userId) {
        require(id != null && !id.trim().isEmpty(), "ARTIFACT_ID_REQUIRED: 缺少制品标识");
        List<Map<String, Object>> rows = db.queryForList("select artifact_id,run_id,user_id,source_revision,content_json,lineage_json,storage_kind,row_count,byte_size,content_hash,cast(round(unix_timestamp(expires_at)*1000) as signed) as expires_at,status from ai_analysis_artifact where artifact_id=?", id);
        require(!rows.isEmpty() && ((Number) rows.get(0).get("user_id")).longValue() == userId, "ARTIFACT_FORBIDDEN: 制品不存在或仅制品所有者可访问");
        return rows.get(0);
    }
    /**
     * C1r-F5 zero-mutation read gate (Kimi contract, 2026-11-05 review ledger): every verdict below is a
     * fresh comparison over SELECTed state and no read path ever UPDATEs the artifact row - so a refusal
     * cannot be undone by an outer transaction rollback, and cannot be smuggled into an outer commit as a
     * durable side effect either. TTL: an ACTIVE artifact past expires_at is refused with ARTIFACT_EXPIRED
     * (PINNED skips the TTL check - pin exempts TTL only, never the dependency check). Dependency
     * staleness: the F2 dual-channel identity comparison refuses ACTIVE/PINNED artifacts with
     * ARTIFACT_SOURCE_STALE. A materialized EXPIRED/INVALIDATED status is refused with the existing
     * messages. Status materialization happens only in sweepInvalidated's independent transaction (both
     * kinds) or through the explicit markInvalidated.
     */
    private Map<String, Object> readableRow(String id, long userId) {
        Map<String, Object> row = ownedRow(id, userId);
        requireReadableState(id, userId, row);
        return row;
    }
    private void requireReadableState(String id, long userId, Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        if ("ACTIVE".equals(status)) {
            Object expiresAt = row.get("expires_at");
            require(expiresAt == null || ((Number) expiresAt).longValue() >= System.currentTimeMillis(), "ARTIFACT_EXPIRED: 制品已过期");
        }
        if ("ACTIVE".equals(status) || "PINNED".equals(status)) requireSourceFresh(id, userId);
        require("ACTIVE".equals(status) || "PINNED".equals(status), "EXPIRED".equals(status) ? "ARTIFACT_EXPIRED: 制品已过期" : "ARTIFACT_INVALIDATED: 制品已失效");
    }
    /**
     * C1c upstream invalidation, hooked after the permission check in requireReadableState; C1r-F5 makes it
     * a pure SELECT comparison (the read path never mutates the artifact row). Only artifacts carrying a
     * dependency registration are ever refused: the inner join on ai_analysis_artifact_dependency drops
     * every artifact without one, so all B-phase inline/array artifacts keep reading unchanged. Everything
     * else is left-joined and fail-closed (C1-review round 2 F2-c): a deleted source workbook, a deleted
     * branch, or a branch whose owner_user_id no longer equals the artifact user refuses the read - the
     * source no longer exists under the registered identity, so serving the artifact would let a vanished
     * or re-pointed source smuggle in freshness. Staleness compares both stamped version channels against
     * current identity - the commit channel d.source_version vs ai_workbook.version, and (C1-review F2)
     * the draft channel in three states (round 2 F2-a): registered NULL means no draft existed at
     * registration, so ANY working-state row appearing later (including the first save at v0) invalidates;
     * registered with a value, a vanished row or an unequal version invalidates. A real draft save
     * (saveWorkingState) advances working_state.version while ai_workbook.version stands still, so only
     * the dual identity catches changed source data. Hash lineages are never compared at read time:
     * source_revision is a run-side tree hash while ai_workbook.content_hash is a raw-string hash, and the
     * two legitimately diverge on non-canonical content (the registration-time draft binding of F2-b is
     * the only same-lineage equality check and it lives on the write path). Both channels use <> rather
     * than >: versions should advance monotonically, so any inequality - including a regressed pointer -
     * is an anomalous workbook move and fails closed (the distinction between historical result reads and
     * writable-back sources stays with the SOURCE_STALE write path, which is untouched).
     * Permission stays first (ownedRow ran already): a foreign reader gets ARTIFACT_FORBIDDEN and never
     * learns the state. PINNED is checked too: pin exempts TTL only, never upstream invalidation.
     */
    private void requireSourceFresh(String id, long userId) {
        Integer stale = db.queryForObject(
            "select count(*) from ai_analysis_artifact a join ai_analysis_artifact_dependency d on d.artifact_id=a.artifact_id " +
            "left join ai_workbook w on w.workbook_id=a.workbook_id " +
            "left join ai_workbook_branch br on br.branch_id=a.branch_id and br.workbook_id=a.workbook_id and br.owner_user_id=a.user_id " +
            "left join ai_workbook_working_state ws on ws.workbook_id=a.workbook_id and ws.branch_id=a.branch_id and ws.owner_user_id=a.user_id " +
            "where a.artifact_id=? and a.user_id=? and (" + STALE_PREDICATE + ")", Integer.class, id, userId);
        require(stale == 0, "ARTIFACT_SOURCE_STALE: 上游工作簿已推进，制品按依赖登记失效");
    }
    /**
     * The single staleness predicate shared by the read path and sweepInvalidated (round 2: one definition,
     * both consumers). Fail-closed source-existence branches first (missing workbook row, missing branch
     * row, or branch owner no longer the artifact user), then the commit channel, then the three-state
     * draft channel. Everything is left-joined, so a missing row reads as NULL and lands in a refusing
     * branch instead of silently dropping out of an inner join.
     */
    private static final String STALE_PREDICATE =
        "w.workbook_id is null or br.branch_id is null " +
        "or d.source_version is not null and (w.version is null or w.version<>d.source_version) " +
        "or d.source_draft_version is null and ws.version is not null " +
        "or d.source_draft_version is not null and (ws.version is null or ws.version<>d.source_draft_version)";
    /**
     * C1c explicit batch sweep for C5 integration/ops. C1r-F5 dual-semantics contract: this method (name
     * kept) is the only automatic materializer of BOTH refusal kinds, in its own independent transaction -
     * TTL-expired ACTIVE rows are set to EXPIRED (expires_at past now, exactly the read-path TTL
     * predicate) and dual-channel stale ACTIVE/PINNED rows are set to INVALIDATED (exactly the read-path
     * {@link #requireSourceFresh} predicate, shared as {@link #STALE_PREDICATE}). The read path itself
     * never touches status, so a zero-mutation refusal stays observable for ops only after this sweep
     * materializes it. Returns the total number of rows materialized. Deliberately unwired here: no caller
     * in this task.
     * C1-review round 2 F5: the independent transaction is REQUIRES_NEW, so the materialization commits
     * (or rolls back) on its own regardless of any caller's transaction - an outer rollback can no longer
     * resurrect ACTIVE status. The same SingleConnectionDataSource caveat as markInvalidated applies, and
     * the lock wait is bounded ({@link #boundMaterializationLockWait}) so a lock-holding outer transaction
     * fails this sweep fast instead of deadlocking it for the default 50s.
     */
    public int sweepInvalidated(long workbookId) {
        Integer flipped = materializeTx.execute(status -> {
            boundMaterializationLockWait();
            int expired = db.update("update ai_analysis_artifact set status='EXPIRED' where workbook_id=? and status='ACTIVE' and expires_at is not null and expires_at<now(3)", workbookId);
            int invalidated = db.update(
                "update ai_analysis_artifact a join ai_analysis_artifact_dependency d on d.artifact_id=a.artifact_id " +
                "left join ai_workbook w on w.workbook_id=a.workbook_id " +
                "left join ai_workbook_branch br on br.branch_id=a.branch_id and br.workbook_id=a.workbook_id and br.owner_user_id=a.user_id " +
                "left join ai_workbook_working_state ws on ws.workbook_id=a.workbook_id and ws.branch_id=a.branch_id and ws.owner_user_id=a.user_id " +
                "set a.status='INVALIDATED' where a.workbook_id=? and a.status in ('ACTIVE','PINNED') " +
                "and (" + STALE_PREDICATE + ")", workbookId);
            return expired + invalidated;
        });
        return flipped == null ? 0 : flipped;
    }
    private ObjectNode metadataFromRow(Map<String, Object> row) {
        String id = String.valueOf(row.get("artifact_id"));
        ObjectNode meta = obj().put("id", id).put("runId", String.valueOf(row.get("run_id"))).put("userId", ((Number) row.get("user_id")).longValue())
            .put("sourceRevision", String.valueOf(row.get("source_revision"))).put("storageKind", String.valueOf(row.get("storage_kind")))
            .put("status", String.valueOf(row.get("status"))).put("chunkCount", db.queryForObject("select count(*) from ai_analysis_artifact_chunk where artifact_id=?", Integer.class, id));
        Object rowCount = row.get("row_count"); if (rowCount == null) meta.putNull("rowCount"); else meta.put("rowCount", ((Number) rowCount).intValue());
        Object byteSize = row.get("byte_size"); if (byteSize == null) meta.putNull("byteSize"); else meta.put("byteSize", ((Number) byteSize).longValue());
        Object hash = row.get("content_hash"); if (hash == null) meta.putNull("contentHash"); else meta.put("contentHash", String.valueOf(hash));
        Object expiresAt = row.get("expires_at"); if (expiresAt == null) meta.putNull("expiresAt"); else meta.put("expiresAt", ((Number) expiresAt).longValue());
        return meta;
    }
}
