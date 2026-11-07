package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.agent.data.ArtifactService;
import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.mapper.*;
import com.ruoyi.ai.util.ContentHashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C2 batch ledger on top of the B controlled-write primitives. One {@link StableEffectLedger} effect owns
 * many batch rows ({@code ai_agent_write_batch}); the effect table's key semantics are untouched.
 * Contract v1 invariants, each fail-closed:
 * - Server generates the whole batch manifest and records every batch row plus the initial checkpoint
 *   (batch_index -1) in ONE create transaction; the first dispatch is a separate later call, so a batch can
 *   never be dispatched before its manifest is durably recorded (record-before-dispatch).
 * - Parameter columns (batch_id, chunk_ref, chunk_rows, row_start, verification_spec_id, params_hash,
 *   manifest_json, expected_before, expected_after) are written exactly once at record time. Dispatch,
 *   receipt and reconcile only ever UPDATE status/claim_token/receipt_hash/after_revision/result_json, so
 *   dispatched parameters are immutable on every path; a repeated create with the same requestId replays
 *   the recorded plan and a changed payload is refused (WRITE_IDEMPOTENCY_CONFLICT).
 * - Per batch: the client readback is validated against the batch's own verification spec
 *   ({@link WriteManifest#validateReceipt} over the recorded cells - the save scope equals the verification
 *   scope and nothing beyond it can be saved), the trusted patch is re-applied server-side, the resulting
 *   content hash must equal the pre-recorded expected_after, the draft is CAS-saved and read back
 *   ({@code ControlledWriteService.savedDraftMatches}); only then does the batch enter PERSISTED and a
 *   checkpoint row commit in the same transaction. A failing verification leaves the batch un-PERSISTED
 *   and the draft unchanged.
 * - UNKNOWN batches are settled only by server verification: the authoritative draft hash is compared with
 *   the recorded expected_after/expected_before chain. A client can never declare a rollback or a save -
 *   the reconcile request body carries no state-changing field at all. A no-change batch
 *   (expected_before==expected_after) is additionally PERSISTED only when the real working_state draft row
 *   exists with that content_hash - a bare hash hit proves equally "nothing was applied" and can never
 *   fabricate a SAVED checkpoint or a draft version.
 * - Journal rule: a batch that is not PERSISTED renders exactly "检查点已保存" and carries no
 *   completion/rollback-semantics field (no afterRevision/persisted/receiptHash).
 */
@Service
public class BatchWriteService {
    /** Batch contract version carried by every dispatch and journal view. */
    public static final int BATCH_CONTRACT_VERSION = 1;
    /** Cells per batch stay within the B controlled budget so each batch manifest is a legal B1 manifest. */
    public static final int BATCH_CELLS = WriteManifest.MAX_WRITE_CELLS;
    /** C2 v1 bound: a batched write may not exceed 64 batches. */
    public static final int MAX_BATCHES = 64;
    private final AgentStore store; private final AgentWriteGuard guard; private final StableEffectLedger effects;
    private final ArtifactService artifacts; private final AiWorkbookMapper workbooks; private final WorkbookVersionMapper versions;
    private final TransactionTemplate tx;
    @Value("${ai.agent.controlled-writes-enabled:false}") private boolean controlledEnabled;
    @Value("${ai.agent.c2-batch-enabled:false}") private boolean enabled;
    public BatchWriteService(AgentStore store, AgentWriteGuard guard, AiWorkbookMapper workbooks, WorkbookVersionMapper versions, PlatformTransactionManager manager) {
        this.store = store; this.guard = guard; this.workbooks = workbooks; this.versions = versions;
        this.effects = new StableEffectLedger(store.db); this.artifacts = new ArtifactService(store.db);
        this.tx = new TransactionTemplate(manager); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public boolean enabled() { return controlledEnabled && enabled; }
    public static boolean ownsBatchedLifecycle(JsonNode r) { return "BATCH_WRITE".equals(r.path("kind").asText()); }

    private static String cellName(int row, int column) {
        StringBuilder sb = new StringBuilder(); int c = column; do { sb.insert(0, (char) ('A' + c % 26)); c = c / 26 - 1; } while (c >= 0);
        return sb.append(row + 1).toString();
    }
    /**
     * C2-review P2③ lossless integer gate (B-phase F2 / C1r-F4 precedent): the JSON node must be an
     * integral number inside [0, Integer.MAX_VALUE] before any intValue() narrowing, so 4294967296 can
     * never alias onto coordinate 0 and a missing/textual/negative coordinate is refused outright.
     */
    private static int losslessIndex(JsonNode v, String field) {
        require(v.isIntegralNumber() && v.bigIntegerValue().signum() >= 0
            && v.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0,
            "BATCH_WRITE_INVALID: " + field + " 必须是 0 至 " + Integer.MAX_VALUE + " 的整数");
        return v.intValue();
    }
    /** Same trusted-current tuple as ControlledWriteService.current: workbook+branch+draft all locked for update. */
    @SuppressWarnings("unchecked") private Map<String, Object> current(ObjectNode r) {
        long w = r.path("workbookId").asLong(), b = r.path("branchId").asLong(), u = r.path("userId").asLong();
        AiWorkbook book = workbooks.selectByIdForUpdate(w, u);
        require(book != null && Objects.equals(book.getCurrentBranchId(), b), "WRITE_VERSION_CONFLICT: 工作簿或分支已变化");
        Map<String, Object> branch = versions.selectBranchForUpdate(w, b, u); require(branch != null, "WRITE_FORBIDDEN: 分支无权访问");
        Map<String, Object> draft = versions.selectWorkingStateForUpdate(w, b, u);
        String content = draft == null ? book.getContent() : String.valueOf(draft.get("content")); String loaded = ContentHashUtils.sha256(content);
        require(draft == null || loaded.equals(draft.get("contentHash")), "WRITE_VERSION_CONFLICT: 草稿完整性错误");
        Map<String, Object> state = new HashMap<>(); state.put("content", content); state.put("loadedRevision", loaded);
        state.put("draftVersion", draft == null ? -1L : ((Number) draft.get("version")).longValue()); state.put("branch", branch); state.put("draft", draft);
        return state;
    }
    private void sameVersion(ObjectNode binding, Map<String, Object> current) {
        Map<String, Object> branch = (Map<String, Object>) current.get("branch");
        require(WriteManifest.sameIntegral(binding.path("expectedDraftVersion"), ((Number) current.get("draftVersion")).longValue())
            && text(binding, "loadedRevision").equals(current.get("loadedRevision"))
            && WriteManifest.sameIntegral(binding.path("expectedBranchVersion"), ((Number) branch.get("version")).longValue()), "WRITE_VERSION_CONFLICT: 旧版本拒绝写入");
    }
    /** Authoritative raw draft hash used by server-side UNKNOWN verification (draft row first, committed content otherwise - same rule as the C1 registration channel). */
    private String authoritativeHash(ObjectNode r) {
        List<Map<String, Object>> draft = store.db.queryForList("select cast(draft_snapshot as char character set utf8mb4) content from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=?", r.path("workbookId").asLong(), r.path("branchId").asLong(), r.path("userId").asLong());
        String content = draft.isEmpty() ? workbooks.selectById(r.path("workbookId").asLong(), r.path("userId").asLong()).getContent() : String.valueOf(draft.get(0).get("content"));
        return ContentHashUtils.sha256(content);
    }
    private void lease(ObjectNode r) {
        require(!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?", r.path("workbookId").asLong(), r.path("runId").asText(), r.path("fencingToken").asLong(), System.currentTimeMillis()).isEmpty(), "RESULT_UNKNOWN: 写租约待核实，不得自动重放");
    }
    private ObjectNode mutate(String runId, long user, Function<ObjectNode, ObjectNode> fn) {
        ObjectNode preview = store.get(runId); require(preview.path("userId").asLong() == user, "任务不存在或无权访问");
        return tx.execute(status -> { guard.lock(preview.path("workbookId").asLong(), user); ObjectNode r = store.locked(runId), before = r.deepCopy(); ObjectNode out = fn.apply(r); if (!r.equals(before)) store.save(r); return out; });
    }

    /**
     * Create the batched run: request idempotency, trusted-current check, server-side chunk materialization
     * through the C1 artifact lane, effect reservation, ALL batch rows and the initial checkpoint - one
     * transaction. Returns the recorded plan; nothing is dispatched yet.
     */
    public ObjectNode create(ObjectNode req, long user) {
        WriteManifest.checkTransport(req);
        require(enabled(), "AI_CAPABILITY_BLOCKED: C2 批次写入未开放");
        require(!req.has("snapshot"), "SNAPSHOT_WRITE_BLOCKED");
        require(!req.has("actions"), "BATCH_WRITE_INVALID: 批次写入按行分块，不接收既有动作组");
        String requestId = text(req, "requestId"); require(requestId.length() <= 80, "请求ID过长");
        long workbook = req.path("workbookId").asLong(); String requestHash = WriteManifest.digest(req);
        return tx.execute(status -> {
            guard.lock(workbook, user);
            List<String> ids = store.db.queryForList("select run_id from ai_agent_run where user_id=? and request_id=?", String.class, user, requestId);
            if (!ids.isEmpty()) { ObjectNode existing = store.locked(ids.get(0)); require(ownsBatchedLifecycle(existing) && requestHash.equals(existing.path("requestHash").asText()), "WRITE_IDEMPOTENCY_CONFLICT: 相同请求ID参数不同"); return progressView(existing); }
            ObjectNode r = obj().put("runId", UUID.randomUUID().toString()).put("requestHash", requestHash).put("requestId", requestId).put("userId", user)
                .put("workbookId", workbook).put("branchId", req.path("branchId").asLong()).put("version", 0).put("eventSeq", 0)
                .put("kind", "BATCH_WRITE").put("transportVersion", 2).put("status", "EXECUTING").put("goal", "受控分批写入")
                .put("persistenceStatus", "NOT_SAVED").put("deadline", System.currentTimeMillis() + 900000);
            String entry = req.path("entryPoint").asText("explicit"); require(Arrays.asList("explicit", "ordinary-ai").contains(entry), "WRITE_ENTRY_INVALID"); r.put("entryPoint", entry);
            r.set("constraints", arr()); r.set("outputs", obj()); r.set("completedSteps", arr());
            r.set("writeScopes", req.path("writeScopes").deepCopy()); r.put("activeSheetId", req.path("activeSheetId").asText());
            r.put("allowOverwrite", "confirmed".equals(req.path("overwrite").asText()));
            r.set("expectedDraftVersion", req.path("expectedDraftVersion")); r.set("expectedBranchVersion", req.path("expectedBranchVersion")); r.set("loadedRevision", req.path("loadedRevision"));
            Map<String, Object> state = current(r); sameVersion(r, state);
            JsonNode snapshot = parse(String.valueOf(state.get("content"))); r.put("revision", hash(snapshot));
            JsonNode target = req.path("target"); require(target.isObject(), "BATCH_WRITE_INVALID: 缺少目标区域");
            String sheetId = text(target, "sheetId");
            JsonNode values = req.path("values"); require(values.isArray() && values.size() > 0, "BATCH_WRITE_INVALID: 缺少行数据");
            int width = values.get(0).size(); require(width > 0, "BATCH_WRITE_INVALID: 行数据不能为空行");
            for (JsonNode row : values) require(row.isArray() && row.size() == width, "BATCH_WRITE_INVALID: 行数据宽度不一致");
            require((long) width * values.size() <= AgentTools.MAX_CELLS, "WRITE_BUDGET_EXCEEDED: 分批写入超过总预算");
            // C2-review P2③: lossless target-coordinate validation (integer type, int range, end-of-range
            // overflow) runs BEFORE the payload materializes into the C1 chunk lane - a narrowing asInt()
            // must never turn 4294967296 into a write plan at row 0.
            int startRow = losslessIndex(target.path("startRow"), "startRow"), startColumn = losslessIndex(target.path("startColumn"), "startColumn");
            require((long) startRow + values.size() - 1 <= Integer.MAX_VALUE && (long) startColumn + width - 1 <= Integer.MAX_VALUE,
                "BATCH_WRITE_INVALID: 目标区域终点超出整数范围");
            // C1 chunk lane: the whole payload materializes first, so every batch cites a durable chunkRef.
            ObjectNode artifactMeta = artifacts.writeArtifact(r.path("runId").asText(), user, workbook, r.path("branchId").asLong(), null, r.path("loadedRevision").asText(), (ArrayNode) values, null);
            String artifactId = artifactMeta.path("id").asText();
            int recorded = store.db.queryForObject("select row_count from ai_analysis_artifact where artifact_id=?", Integer.class, artifactId);
            require(recorded == values.size(), "BATCH_SOURCE_INVALID: 分块物化行数与请求不符，拒绝生成批次清单");
            // Server-side split: each batch is one legal setValues manifest within the B budget.
            int rowsPerBatch = Math.max(1, BATCH_CELLS / width);
            List<ObjectNode> batchPlans = new ArrayList<>();
            JsonNode before = snapshot; String beforeHash = ContentHashUtils.sha256(String.valueOf(state.get("content")));
            for (int start = 0; start < values.size(); start += rowsPerBatch) {
                int end = Math.min(start + rowsPerBatch, values.size());
                ObjectNode action = obj().put("schemaVersion", "2.0").put("type", "setValues");
                action.set("target", obj().put("sheetId", sheetId).put("range", cellName(startRow + start, startColumn) + ":" + cellName(startRow + end - 1, startColumn + width - 1)));
                ArrayNode window = arr(); for (int i = start; i < end; i++) window.add(values.get(i).deepCopy()); action.set("values", window);
                ObjectNode manifest = WriteManifest.create(before, arr().add(action), r.path("writeScopes"), r.path("activeSheetId").asText(), text(req, "overwrite"), r.path("allowOverwrite").asBoolean());
                JsonNode after = WriteManifest.apply(before, manifest);
                ObjectNode plan = obj().put("batchIndex", batchPlans.size()).put("expectedBefore", beforeHash).put("expectedAfter", ContentHashUtils.sha256(after.toString()));
                plan.set("manifest", manifest); batchPlans.add(plan);
                before = after; beforeHash = ContentHashUtils.sha256(after.toString());
            }
            require(batchPlans.size() <= MAX_BATCHES, "WRITE_BUDGET_EXCEEDED: 批次数超过上限");
            store.db.update("insert ignore into ai_agent_lease(workbook_id) values(?)", workbook);
            String id = r.path("runId").asText();
            require(store.db.update("update ai_agent_lease set run_id=?,fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id is null", id, System.currentTimeMillis() + 120000, workbook) == 1, "WRITE_LEASE_CONFLICT: 已有活动写入");
            r.put("fencingToken", store.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?", Long.class, workbook));
            // One effect for the whole batched write: identity covers the full target plus all values.
            ObjectNode whole = obj().put("transportVersion", 2).put("loadedRevision", r.path("loadedRevision").asText()).put("overwrite", text(req, "overwrite"));
            ArrayNode allCells = arr(); for (JsonNode plan : batchPlans) allCells.addAll((ArrayNode) plan.path("manifest").path("cells"));
            whole.set("cells", allCells); whole.set("writeScopes", r.path("writeScopes"));
            String callId = UUID.nameUUIDFromBytes((id + ":batch").getBytes(StandardCharsets.UTF_8)).toString();
            String effectId = effects.reserve(id, callId, whole);
            r.put("effectId", effectId).put("effectCallId", callId).put("sourceArtifactId", artifactId).put("totalBatches", batchPlans.size());
            store.db.update("insert into ai_agent_run(run_id,user_id,workbook_id,branch_id,request_id,request_hash,status,state_json,row_version,updated_at,created_at) values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))", id, user, workbook, r.path("branchId").asLong(), requestId, requestHash, r.toString());
            // Record every batch row and the initial checkpoint before anything can be dispatched.
            for (ObjectNode plan : batchPlans) {
                int index = plan.path("batchIndex").asInt(); ObjectNode manifest = (ObjectNode) plan.path("manifest");
                String batchId = UUID.randomUUID().toString();
                // chunkRef cites the C1 chunk that physically holds this batch's first source row.
                String chunkRef = artifactId + "#" + (index * rowsPerBatch / ArtifactService.CHUNK_ROWS);
                ObjectNode params = obj().put("batchContractVersion", BATCH_CONTRACT_VERSION).put("batchId", batchId).put("batchIndex", index)
                    .put("chunkRef", chunkRef).put("rowStart", index * rowsPerBatch).put("chunkRows", Math.min(rowsPerBatch, values.size() - index * rowsPerBatch));
                params.set("manifest", manifest); params.set("verificationSpec", verificationSpec(manifest));
                store.db.update("insert into ai_agent_write_batch(run_id,effect_id,batch_index,batch_id,status,chunk_ref,chunk_rows,row_start,verification_spec_id,params_hash,manifest_json,expected_before,expected_after,created_at,updated_at) values(?,?,?,?,'PLANNED',?,?,?,?,?,?,?,?,now(3),now(3))",
                    id, effectId, index, batchId, chunkRef, Math.min(rowsPerBatch, values.size() - index * rowsPerBatch), index * rowsPerBatch, WriteManifest.digest(verificationSpec(manifest)), WriteManifest.digest(params), manifest.toString(), plan.path("expectedBefore").asText(), plan.path("expectedAfter").asText());
            }
            store.db.update("insert into ai_agent_checkpoint(run_id,effect_id,checkpoint_seq,batch_index,saved_draft_version,content_hash,state,created_at) values(?,?,0,-1,?,?,'SAVED',now(3))",
                id, effectId, ((Number) state.get("draftVersion")).longValue(), r.path("loadedRevision").asText());
            store.event(r, "BATCH_READY", "服务端已生成并落库 " + batchPlans.size() + " 个批次的清单与初始检查点，未下发");
            store.save(r);
            return progressView(r);
        });
    }
    /** The verification spec of one batch: exactly the recorded cells and their expected values - the save scope equals the verification scope. */
    private static ObjectNode verificationSpec(JsonNode manifest) {
        ObjectNode spec = obj().put("specVersion", "v1");
        ArrayNode cells = arr(); for (JsonNode cell : manifest.path("cells")) cells.add(obj().put("sheetId", cell.path("sheetId").asText()).put("row", cell.path("row").asInt()).put("column", cell.path("column").asInt()).set("value", cell.path("value").deepCopy()));
        spec.set("cells", cells); spec.set("readbackFields", arr().add("sheetId").add("row").add("column").add("value").add("formula"));
        return spec;
    }

    /** Dispatch exactly the next batch (checkpoint order). A repeated claim never receives a token again. */
    public ObjectNode claimBatch(String effectId, int batchIndex, long user, ObjectNode req) {
        WriteManifest.checkTransport(req); String runId = runOf(effectId);
        return mutate(runId, user, r -> {
            require(ownsBatchedLifecycle(r), "BATCH_WRITE_INVALID: 非批次任务");
            // The PERSISTED fact is terminal: it refuses re-dispatch before any lease/deadline/fence check,
            // so a confirmed batch can never be re-run even while the run is paused or the lease is dead.
            Map<String, Object> b = batchRow(effectId, batchIndex);
            if ("PERSISTED".equals(String.valueOf(b.get("status")))) throw new IllegalArgumentException("BATCH_ALREADY_PERSISTED: 已确认批次不得重跑");
            require(!r.has("deadline") || System.currentTimeMillis() < r.path("deadline").asLong(), "RESULT_UNKNOWN: 任务超时，不得领取"); lease(r);
            require("EXECUTING".equals(r.path("status").asText()), "RESULT_UNKNOWN: 当前任务不可派发");
            require(WriteManifest.sameIntegral(req.path("fencingToken"), r.path("fencingToken").asLong()), "WRITE_FENCE_STALE");
            if ("DISPATCHED".equals(String.valueOf(b.get("status")))) return obj().put("batchId", String.valueOf(b.get("batch_id"))).put("batchIndex", batchIndex).put("status", "DISPATCHED").put("replayAllowed", false);
            require("PLANNED".equals(String.valueOf(b.get("status"))), "BATCH_STATE_CONFLICT: 批次状态不可派发");
            int next = latestCheckpointBatchIndex(effectId) + 1; require(batchIndex == next, "BATCH_ORDER_INVALID: 批次必须按检查点顺序逐批领取，当前应领取 " + next);
            try { if (batchIndex == 0) effects.transition(r.path("effectId").asText(), runId, r.path("effectCallId").asText(), "PREPARED", "CLAIMED", null, null, null); }
            catch (IllegalArgumentException alreadyClaimed) { /* batch 0 re-claim after server-verified not-applied: the effect already sits in CLAIMED */ }
            effects.assertStatus(r.path("effectId").asText(), runId, r.path("effectCallId").asText(), "CLAIMED");
            // Only lifecycle columns move here; parameter columns are never rewritten on any path.
            require(store.db.update("update ai_agent_write_batch set status='DISPATCHED',claim_token=?,updated_at=now(3) where effect_id=? and batch_index=? and status='PLANNED'", UUID.randomUUID().toString(), effectId, batchIndex) == 1, "BATCH_STATE_CONFLICT: 批次派发竞争失败");
            store.event(r, "BATCH_DISPATCHED", "批次 " + batchIndex + " 已按落库参数下发，参数自此不可变");
            return contract(store.db.queryForMap("select * from ai_agent_write_batch where effect_id=? and batch_index=?", effectId, batchIndex), r.path("fencingToken").asLong());
        });
    }

    /** Apply one batch: readback validation, trusted re-apply, hash-chain check, CAS save, readback, checkpoint - one transaction. */
    public ObjectNode receiptBatch(String effectId, int batchIndex, long user, ObjectNode req) {
        WriteManifest.checkTransport(req); String runId = runOf(effectId);
        return mutate(runId, user, r -> {
            require(ownsBatchedLifecycle(r), "BATCH_WRITE_INVALID: 非批次任务");
            req.fieldNames().forEachRemaining(k -> require(Arrays.asList("transportVersion", "effectId", "batchId", "batchIndex", "claimToken", "paramsHash", "fencingToken", "ok", "cells", "error").contains(k), "WRITE_READBACK_INVALID: 非授权回执字段"));
            // C2-review P2③: exact integral comparison via BigInteger - a body index of 4294967296 must
            // never narrow onto the addressed batch through a double asInt() truncation.
            require(WriteManifest.sameIntegral(req.path("batchIndex"), batchIndex), "WRITE_READBACK_INVALID: 回执批次不符");
            require(req.path("ok").isBoolean() && (!req.path("ok").asBoolean() || !req.has("error")), "WRITE_READBACK_INVALID: 回执状态必须为布尔值，成功回执不能携带错误");
            Map<String, Object> b = batchRow(effectId, batchIndex);
            require(text(req, "claimToken").equals(String.valueOf(b.get("claim_token"))) && text(req, "paramsHash").equals(String.valueOf(b.get("params_hash"))) && text(req, "batchId").equals(String.valueOf(b.get("batch_id"))) && effectId.equals(text(req, "effectId")), "WRITE_RECEIPT_IDENTITY_INVALID");
            String receiptHash = WriteManifest.digest(req);
            if ("PERSISTED".equals(String.valueOf(b.get("status")))) {
                // Backend-already-stored duplicate: only a byte-identical receipt replays, and it never re-applies.
                // C2-re-review P2: no fence-equality gate here. The stored receipt_hash is bound to the fence
                // that was current at save time, so after a lease-recovery bump (reconcileRunLocked /
                // settleRunLocked) the original receipt would die WRITE_FENCE_STALE while re-fencing it would
                // change its digest - the caller could never idempotently recover the existing result. The
                // replay stays safe without the gate: this branch writes nothing and re-applies nothing, the
                // identity quad above (claimToken/paramsHash/batchId/effectId) plus the full-byte digest below
                // (which covers the receipt's own original fencingToken) prove the caller holds the exact
                // recorded receipt; any tampered byte (fence/cells/ok) still dies WRITE_IDEMPOTENCY_CONFLICT.
                require(receiptHash.equals(String.valueOf(b.get("receipt_hash"))), "WRITE_IDEMPOTENCY_CONFLICT: 重复回执不一致");
                return progressView(r);
            }
            // A batch outside DISPATCHED can never accept a receipt at all - the unknown-state verdict
            // ("needs server verification") comes before any fence comparison.
            require("DISPATCHED".equals(String.valueOf(b.get("status"))), "RESULT_UNKNOWN: 批次未在派发态，回执需先核实");
            require(WriteManifest.sameIntegral(req.path("fencingToken"), r.path("fencingToken").asLong()), "WRITE_FENCE_STALE");
            require(!r.has("deadline") || System.currentTimeMillis() < r.path("deadline").asLong(), "RESULT_UNKNOWN: 任务超时，晚到回执不得新增保存"); lease(r);
            require("EXECUTING".equals(r.path("status").asText()), "RESULT_UNKNOWN: 当前任务不可保存");
            ObjectNode manifest = (ObjectNode) parse(String.valueOf(b.get("manifest_json")));
            if (!req.path("ok").asBoolean()) {
                require(store.db.update("update ai_agent_write_batch set status='UNKNOWN',updated_at=now(3) where effect_id=? and batch_index=? and status='DISPATCHED'", effectId, batchIndex) == 1, "BATCH_STATE_CONFLICT");
                r.put("status", "PAUSED").put("persistenceStatus", "NOT_SAVED").put("message", "RESULT_UNKNOWN: 批次执行失败或回读失败，等待服务端核实；不重放");
                store.event(r, "BATCH_UNKNOWN", r.path("message").asText()); return progressView(r);
            }
            // Verification scope: only the batch's own cells; anything beyond it refuses the whole receipt.
            WriteManifest.validateReceipt(manifest, req.path("cells"));
            Map<String, Object> state = current(r);
            require(ContentHashUtils.sha256(String.valueOf(state.get("content"))).equals(String.valueOf(b.get("expected_before"))), "WRITE_VERSION_CONFLICT: 草稿与批次检查点不符");
            ObjectNode next = WriteManifest.apply(parse(String.valueOf(state.get("content"))), manifest);
            String content = next.toString(), loaded = ContentHashUtils.sha256(content);
            require(loaded.equals(String.valueOf(b.get("expected_after"))), "WRITE_VERSION_CONFLICT: 批次应用结果与落库清单不符");
            long expectedVersion = ((Number) state.get("draftVersion")).longValue();
            Map<String, Object> save = appendAndSave(r, state, manifest, content, loaded, user, String.valueOf(b.get("batch_id")));
            require(ControlledWriteService.savedDraftMatches(versions.selectWorkingStateForUpdate(r.path("workbookId").asLong(), r.path("branchId").asLong(), user), save, expectedVersion + 1), "RESULT_UNKNOWN: CAS 后草稿回读与可信补丁不一致");
            ObjectNode result = obj().put("transportVersion", 2).put("batchId", String.valueOf(b.get("batch_id"))).put("batchIndex", batchIndex)
                .put("loadedRevision", loaded).put("draftVersion", expectedVersion + 1)
                .put("validation", "PASSED").put("persistenceStatus", "SAVED");
            require(store.db.update("update ai_agent_write_batch set status='PERSISTED',receipt_hash=?,after_revision=?,result_json=?,updated_at=now(3) where effect_id=? and batch_index=? and status='DISPATCHED'", receiptHash, loaded, result.toString(), effectId, batchIndex) == 1, "BATCH_STATE_CONFLICT");
            store.db.update("insert into ai_agent_checkpoint(run_id,effect_id,checkpoint_seq,batch_index,saved_draft_version,content_hash,state,created_at) values(?,?,?,?,?,?,'SAVED',now(3))",
                runId, effectId, latestCheckpointSeq(effectId) + 1, batchIndex, expectedVersion + 1, loaded);
            r.put("loadedRevision", loaded).put("revision", hash(next)).put("expectedDraftVersion", expectedVersion + 1).put("persistenceStatus", "SAVED");
            if (batchIndex + 1 >= r.path("totalBatches").asInt() && allPersisted(effectId)) {
                effects.transition(r.path("effectId").asText(), runId, r.path("effectCallId").asText(), "CLAIMED", "PERSISTED", loaded, receiptHash, result);
                r.put("status", "COMPLETED");
                store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?", r.path("workbookId").asLong(), runId);
                store.event(r, "BATCH_COMPLETED", "全部批次已逐批 CAS 保存并写检查点，未创建正式 Commit");
            } else store.event(r, "BATCH_SAVED", "批次 " + batchIndex + " 已验证并保存，检查点已推进");
            return progressView(r);
        });
    }
    /** Build the CAS payload, persist it (insert for the first batch, CAS afterwards) appending the batch change/actions; returns the payload used for the readback check. */
    @SuppressWarnings("unchecked") private Map<String, Object> appendAndSave(ObjectNode r, Map<String, Object> state, ObjectNode manifest, String content, String loaded, long user, String operationId) {
        Map<String, Object> branch = (Map<String, Object>) state.get("branch"); Map<String, Object> prior = (Map<String, Object>) state.get("draft");
        ArrayNode changes = prior == null ? arr() : (ArrayNode) parse(String.valueOf(prior.get("executedChangesJson")));
        ArrayNode actions = prior == null ? arr() : (ArrayNode) parse(String.valueOf(prior.get("draftActionsJson")));
        ObjectNode change = obj().put("schemaVersion", "1.0").put("source", "ai").put("kind", "batch-values").put("operationId", operationId).put("runId", r.path("runId").asText())
            .put("beforeHash", String.valueOf(state.get("loadedRevision"))).put("afterHash", loaded).put("needsSnapshotDiff", true);
        change.set("cells", manifest.path("cells")); changes.add(change); actions.addAll((ArrayNode) manifest.path("actions"));
        Map<String, Object> save = new HashMap<>();
        save.put("workbookId", r.path("workbookId").asLong()); save.put("branchId", r.path("branchId").asLong()); save.put("ownerUserId", user);
        save.put("baseCommitId", branch.get("headCommitId")); save.put("draftActionsJson", actions.toString()); save.put("executedChangesJson", changes.toString());
        save.put("content", content.getBytes(StandardCharsets.UTF_8)); save.put("contentHash", loaded);
        long expected = ((Number) state.get("draftVersion")).longValue();
        require(expected >= -1 && expected < Long.MAX_VALUE, "WRITE_VERSION_CONFLICT: 草稿版本无效");
        save.put("expectedDraftVersion", expected); save.put("expectedContentHash", String.valueOf(state.get("loadedRevision")));
        if (expected < 0) require(versions.insertWorkingState(save) == 1, "WRITE_VERSION_CONFLICT: 首次草稿冲突");
        else require(versions.updateWorkingStateCas(save) == 1, "WRITE_VERSION_CONFLICT: CAS 拒绝旧草稿");
        return save;
    }

    /** Endpoint reconcile: the request body carries no state-changing field; verdicts come from server verification only. */
    public ObjectNode reconcile(String effectId, long user, ObjectNode req) {
        WriteManifest.checkTransport(req);
        req.fieldNames().forEachRemaining(k -> require("transportVersion".equals(k), "RECONCILE_INVALID: 核实不接受任何客户端声明字段，结果以服务端核实为准"));
        String runId = runOf(effectId);
        mutate(runId, user, r -> { require(ownsBatchedLifecycle(r), "BATCH_WRITE_INVALID: 非批次任务"); reconcileRunLocked(r); settleRunLocked(r); return null; });
        ObjectNode view = progressView(store.get(runId));
        view.set("reconciledBy", obj().put("basis", "server-verified-draft-hash").put("clientDeclarationsAccepted", false));
        return view;
    }

    /**
     * Restart/isolation reconciliation under the already-held workbook+run locks (AgentRunService.advance hook).
     * Never dispatches and never settles: dead-lease or past-deadline DISPATCHED batches are isolated to
     * UNKNOWN with a fencing bump (the old executor can then neither save nor re-claim) and the run pauses.
     * UNKNOWN batches are settled only by the reconcile endpoint's server-verified verdict
     * ({@link #settleRunLocked}), so a restart tick can never silently decide an in-flight write by itself.
     */
    public boolean reconcileRunLocked(ObjectNode r) {
        if (!ownsBatchedLifecycle(r)) return false;
        if (Arrays.asList("COMPLETED", "FAILED", "CANCELLED").contains(r.path("status").asText())) return true;
        String runId = r.path("runId").asText(), effectId = text(r, "effectId");
        boolean leaseValid = !store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?", r.path("workbookId").asLong(), runId, r.path("fencingToken").asLong(), System.currentTimeMillis()).isEmpty();
        boolean deadlinePassed = r.has("deadline") && System.currentTimeMillis() >= r.path("deadline").asLong();
        for (Map<String, Object> b : store.db.queryForList("select batch_index,status from ai_agent_write_batch where effect_id=? order by batch_index", effectId)) {
            if (!"DISPATCHED".equals(String.valueOf(b.get("status"))) || (leaseValid && !deadlinePassed)) continue;
            store.db.update("update ai_agent_write_batch set status='UNKNOWN',updated_at=now(3) where effect_id=? and batch_index=? and status='DISPATCHED'", effectId, ((Number) b.get("batch_index")).intValue());
            long old = r.path("fencingToken").asLong();
            if (store.db.update("update ai_agent_lease set fencing_token=fencing_token+1 where workbook_id=? and run_id=? and fencing_token=?", r.path("workbookId").asLong(), runId, old) == 1) r.put("fencingToken", old + 1);
            r.put("status", "PAUSED").put("message", "RESULT_UNKNOWN: 在途批次已按隔离栅隔离，等待服务端核实；不重放");
            store.event(r, "BATCH_UNKNOWN", r.path("message").asText());
        }
        return true;
    }

    /**
     * Reconcile-endpoint settlement under the same held locks (the request body itself carries no
     * state-changing field): UNKNOWN batches are settled purely by comparing the authoritative draft hash
     * with the recorded expected_before/expected_after chain; all-PERSISTED completes the effect, otherwise
     * a verified-safe pass re-arms the lease under the (possibly bumped) fence so the next batch can be
     * claimed again - mirrors resume()'s lease re-establishment. Never dispatches.
     * C2-review P1① save proof: for a no-change batch (expected_before==expected_after) a bare hash hit on
     * expected_after proves nothing - the same hash equally proves "nothing was applied". Such a batch is
     * PERSISTED only when the real working_state draft row exists and its content_hash equals
     * expected_after; the SAVED checkpoint then records that row's actual version, never an inferred
     * version+1. Without the draft row the verdict is NOT-APPLIED (back to PLANNED): nothing was ever
     * saved, so no SAVED fact and no fabricated draft version may exist.
     * C2-review P2④: an expired lease (all batches PLANNED, run still EXECUTING, nothing UNKNOWN) is also
     * recovered here - the reconcile endpoint is the server-verified lease recovery path; recovery from a
     * dead lease re-establishes it with a fencing bump (resume() semantics) so any zombie executor stays
     * fenced off, while a live lease is only extended.
     */
    private void settleRunLocked(ObjectNode r) {
        if (Arrays.asList("COMPLETED", "FAILED", "CANCELLED").contains(r.path("status").asText())) return;
        String runId = r.path("runId").asText(), effectId = text(r, "effectId");
        boolean touched = false; // any server-verified settlement in this pass
        String currentHash = authoritativeHash(r); boolean unresolved = false;
        Map<String, Object> savedDraft = savedDraftRow(r);
        boolean leaseAlive = !store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?", r.path("workbookId").asLong(), runId, r.path("fencingToken").asLong(), System.currentTimeMillis()).isEmpty();
        for (Map<String, Object> b : store.db.queryForList("select batch_index,status,expected_before,expected_after from ai_agent_write_batch where effect_id=? order by batch_index", effectId)) {
            int index = ((Number) b.get("batch_index")).intValue();
            if (!"UNKNOWN".equals(String.valueOf(b.get("status")))) continue;
            String expectedBefore = String.valueOf(b.get("expected_before")), expectedAfter = String.valueOf(b.get("expected_after"));
            // A no-change batch needs the real draft row as its save proof; a changed batch proves the
            // save through the hash alone (the committed baseline can never equal expected_after then).
            boolean saveProven = currentHash.equals(expectedAfter)
                && (!expectedBefore.equals(expectedAfter) || (savedDraft != null && currentHash.equals(String.valueOf(savedDraft.get("content_hash")))));
            if (saveProven) {
                touched = true;
                // The version is a recorded fact of the real draft row, not an inference; the checkpoint
                // fallback is unreachable in the C2 lane (content can only diverge from the committed
                // baseline through a saved draft row) and merely keeps the bookkeeping total.
                Map<String, Object> latest = latestCheckpoint(effectId);
                long version = savedDraft != null ? ((Number) savedDraft.get("version")).longValue()
                    : (latest == null ? r.path("expectedDraftVersion").asLong() : ((Number) latest.get("saved_draft_version")).longValue()) + 1;
                ObjectNode result = obj().put("transportVersion", 2).put("batchIndex", index).put("loadedRevision", currentHash).put("draftVersion", version).put("validation", "SERVER_VERIFIED").put("persistenceStatus", "SAVED");
                store.db.update("update ai_agent_write_batch set status='PERSISTED',after_revision=?,result_json=?,updated_at=now(3) where effect_id=? and batch_index=? and status='UNKNOWN'", currentHash, result.toString(), effectId, index);
                store.db.update("insert into ai_agent_checkpoint(run_id,effect_id,checkpoint_seq,batch_index,saved_draft_version,content_hash,state,created_at) values(?,?,?,?,?,?,'SAVED',now(3))", runId, effectId, latestCheckpointSeq(effectId) + 1, index, version, currentHash);
                store.event(r, "BATCH_VERIFIED_PERSISTED", "批次 " + index + " 由服务端按草稿哈希核实为已保存");
            } else if (currentHash.equals(expectedBefore)) {
                touched = true;
                store.db.update("update ai_agent_write_batch set status='PLANNED',claim_token=null,updated_at=now(3) where effect_id=? and batch_index=? and status='UNKNOWN'", effectId, index);
                store.event(r, "BATCH_VERIFIED_NOT_APPLIED", "批次 " + index + " 由服务端核实未落盘（无变化批次无草稿行亦同），回到可重新领取状态");
            } else { unresolved = true; r.put("status", "PAUSED").put("message", "RESULT_UNKNOWN: 批次 " + index + " 无法用服务端草稿哈希核实，保持 UNKNOWN 待人工核实"); store.event(r, "BATCH_UNRESOLVED", r.path("message").asText()); break; }
        }
        if (!unresolved) {
            if (r.path("totalBatches").asInt() > 0 && allPersisted(effectId)) {
                Map<String, Object> last = store.db.queryForMap("select after_revision,receipt_hash from ai_agent_write_batch where effect_id=? order by batch_index desc limit 1", effectId);
                ObjectNode result = obj().put("transportVersion", 2).put("validation", "SERVER_VERIFIED").put("persistenceStatus", "SAVED").put("loadedRevision", String.valueOf(last.get("after_revision")));
                effects.transition(text(r, "effectId"), runId, text(r, "effectCallId"), "CLAIMED", "PERSISTED", String.valueOf(last.get("after_revision")), String.valueOf(last.get("receipt_hash")), result);
                boolean stop = r.path("cancelRequested").asBoolean();
                r.put("status", stop ? "CANCELLED" : "COMPLETED").put("persistenceStatus", "SAVED")
                    .put("message", stop ? "全部批次经核实已保存，用户取消生效；已保存事实保留" : "全部批次经核实已保存，任务完成");
                store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?", r.path("workbookId").asLong(), runId);
                store.event(r, stop ? "CANCELLED" : "BATCH_COMPLETED", r.path("message").asText());
            } else if (r.path("cancelRequested").asBoolean()) {
                // C2-review P1②: a pending cancellation finalizes only after every UNKNOWN fact above was
                // server-verified and settled - a cancelled task's batches always have a settlement path
                // (the run stays non-terminal CANCELLING until then, so the terminal guard above never
                // swallows an unsettled cancelled run).
                finalizeCancelLocked(r);
            } else if (touched || !"EXECUTING".equals(r.path("status").asText()) || !leaseAlive) {
                long old = r.path("fencingToken").asLong();
                String sql = leaseAlive
                    ? "update ai_agent_lease set expires_at=? where workbook_id=? and run_id=? and fencing_token=?"
                    : "update ai_agent_lease set fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id=? and fencing_token=?";
                if (store.db.update(sql, System.currentTimeMillis() + 120000, r.path("workbookId").asLong(), runId, old) == 1 && !leaseAlive) {
                    r.put("fencingToken", old + 1);
                    store.event(r, "BATCH_LEASE_RECOVERED", "过期租约经服务端核实恢复并提升隔离栅，可继续领取批次");
                }
                if (!"EXECUTING".equals(r.path("status").asText())) {
                    r.put("status", "EXECUTING").put("message", "批次账本已按检查点核实，可继续领取下一批次");
                    store.event(r, "BATCH_RECONCILED", r.path("message").asText());
                }
            }
        }
    }

    /** The run's real working_state draft row (version+content_hash), or null when none was ever saved - the only acceptable save proof for a no-change batch. */
    private Map<String, Object> savedDraftRow(ObjectNode r) {
        List<Map<String, Object>> rows = store.db.queryForList("select version,content_hash from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=?", r.path("workbookId").asLong(), r.path("branchId").asLong(), r.path("userId").asLong());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * C2-review P1② cancel integration under the already-held workbook+run locks (AgentRunService.cancel):
     * a batched run never bypasses the batch ledger. Every in-flight DISPATCHED batch is first isolated to
     * UNKNOWN with a fencing bump (same semantics as the restart hook {@link #reconcileRunLocked}) so the
     * old executor can neither save nor re-claim, and the lease protection stays held. With no UNKNOWN
     * fact left the cancellation finalizes immediately; otherwise the run stays CANCELLING and the
     * reconcile endpoint's server verification settles the UNKNOWN batches and then finalizes
     * ({@link #settleRunLocked}) - so a cancelled task's batches always converge, never dangle.
     */
    public boolean cancelRunLocked(ObjectNode r) {
        if (!ownsBatchedLifecycle(r)) return false;
        if (Arrays.asList("COMPLETED", "FAILED", "CANCELLED").contains(r.path("status").asText())) return true;
        String runId = r.path("runId").asText(), effectId = text(r, "effectId");
        for (Map<String, Object> b : store.db.queryForList("select batch_index,status from ai_agent_write_batch where effect_id=? order by batch_index", effectId)) {
            if (!"DISPATCHED".equals(String.valueOf(b.get("status")))) continue;
            store.db.update("update ai_agent_write_batch set status='UNKNOWN',updated_at=now(3) where effect_id=? and batch_index=? and status='DISPATCHED'", effectId, ((Number) b.get("batch_index")).intValue());
            long old = r.path("fencingToken").asLong();
            if (store.db.update("update ai_agent_lease set fencing_token=fencing_token+1 where workbook_id=? and run_id=? and fencing_token=?", r.path("workbookId").asLong(), runId, old) == 1) r.put("fencingToken", old + 1);
        }
        if (store.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=? and status='UNKNOWN'", Integer.class, effectId) > 0) {
            r.put("status", "CANCELLING").put("message", "RESULT_UNKNOWN: 在途批次已隔离并提升栅栏，取消需先经服务端核实（reconcile）；租约保留至核实完成");
            store.event(r, "BATCH_CANCEL_PENDING", r.path("message").asText());
            return true;
        }
        finalizeCancelLocked(r);
        return true;
    }

    /** Finalize a cancellation whose UNKNOWN facts are all settled: effect cancelled (saved batch facts retained), lease protection released only now. */
    private void finalizeCancelLocked(ObjectNode r) {
        String runId = r.path("runId").asText(), effectId = text(r, "effectId");
        String effectState = store.db.queryForObject("select status from ai_agent_effect where effect_id=?", String.class, effectId);
        if ("PREPARED".equals(effectState) || "CLAIMED".equals(effectState))
            effects.transition(effectId, runId, text(r, "effectCallId"), effectState, "CANCELLED", null, null, null);
        store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?", r.path("workbookId").asLong(), runId);
        r.put("status", "CANCELLED").put("message", "批次任务已取消：在途批次经服务端核实结算，已保存批次事实保留，未下发批次不再执行");
        store.event(r, "CANCELLED", r.path("message").asText());
    }

    private String runOf(String effectId) {
        List<String> rows = store.db.queryForList("select run_id from ai_agent_write_batch where effect_id=? limit 1", String.class, effectId);
        require(!rows.isEmpty(), "BATCH_NOT_FOUND: 效果没有批次账本");
        return rows.get(0);
    }
    private Map<String, Object> batchRow(String effectId, int index) {
        List<Map<String, Object>> rows = store.db.queryForList("select * from ai_agent_write_batch where effect_id=? and batch_index=? for update", effectId, index);
        require(rows.size() == 1, "BATCH_NOT_FOUND: 批次不存在");
        return rows.get(0);
    }
    private boolean allPersisted(String effectId) {
        return store.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=? and status<>'PERSISTED'", Integer.class, effectId) == 0;
    }
    private Map<String, Object> latestCheckpoint(String effectId) {
        List<Map<String, Object>> rows = store.db.queryForList("select batch_index,saved_draft_version,content_hash from ai_agent_checkpoint where effect_id=? order by checkpoint_seq desc limit 1", effectId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private int latestCheckpointBatchIndex(String effectId) {
        Map<String, Object> latest = latestCheckpoint(effectId); return latest == null ? -1 : ((Number) latest.get("batch_index")).intValue();
    }
    private long latestCheckpointSeq(String effectId) {
        return store.db.queryForObject("select coalesce(max(checkpoint_seq),0) from ai_agent_checkpoint where effect_id=?", Long.class, effectId);
    }
    /** Batch contract v1 payload for one dispatched batch. */
    private ObjectNode contract(Map<String, Object> b, long fencingToken) {
        ObjectNode manifest = (ObjectNode) parse(String.valueOf(b.get("manifest_json")));
        ObjectNode out = obj().put("batchContractVersion", BATCH_CONTRACT_VERSION)
            .put("batchId", String.valueOf(b.get("batch_id"))).put("batchIndex", ((Number) b.get("batch_index")).intValue())
            .put("effectId", String.valueOf(b.get("effect_id"))).put("runId", String.valueOf(b.get("run_id")))
            .put("chunkRef", String.valueOf(b.get("chunk_ref"))).put("chunkRows", ((Number) b.get("chunk_rows")).intValue())
            .put("rowStart", ((Number) b.get("row_start")).intValue()).put("verificationSpecId", String.valueOf(b.get("verification_spec_id")))
            .put("paramsHash", String.valueOf(b.get("params_hash"))).put("status", String.valueOf(b.get("status")))
            .put("fencingToken", fencingToken).put("argsHash", String.valueOf(b.get("params_hash")));
        out.set("manifest", manifest); out.put("claimToken", String.valueOf(b.get("claim_token")));
        return out;
    }
    /**
     * Journal/progress view. Journal rule: a batch that is not PERSISTED renders exactly "检查点已保存"
     * and never carries a completion/rollback-semantics field (no afterRevision/persisted/receiptHash).
     */
    public ObjectNode progressView(ObjectNode r) {
        String effectId = r.path("effectId").asText();
        ObjectNode out = obj().put("runId", r.path("runId").asText()).put("effectId", effectId)
            .put("workbookId", r.path("workbookId").asLong()).put("branchId", r.path("branchId").asLong())
            .put("status", r.path("status").asText()).put("batchContractVersion", BATCH_CONTRACT_VERSION)
            .put("totalBatches", r.path("totalBatches").asInt());
        List<Map<String, Object>> effectRows = store.db.queryForList("select status from ai_agent_effect where effect_id=?", effectId);
        out.put("effectStatus", effectRows.isEmpty() ? "MISSING" : String.valueOf(effectRows.get(0).get("status")));
        ArrayNode batches = arr(); int persisted = 0;
        for (Map<String, Object> b : store.db.queryForList("select batch_id,batch_index,chunk_ref,chunk_rows,row_start,verification_spec_id,params_hash,status,after_revision,receipt_hash from ai_agent_write_batch where effect_id=? order by batch_index", effectId)) {
            boolean done = "PERSISTED".equals(String.valueOf(b.get("status"))); if (done) persisted++;
            ObjectNode item = obj().put("batchId", String.valueOf(b.get("batch_id"))).put("batchIndex", ((Number) b.get("batch_index")).intValue())
                .put("chunkRef", String.valueOf(b.get("chunk_ref"))).put("chunkRows", ((Number) b.get("chunk_rows")).intValue())
                .put("verificationSpecId", String.valueOf(b.get("verification_spec_id"))).put("status", String.valueOf(b.get("status")));
            // Un-materialized batches expose only the checkpoint-saved fact - never completion or rollback semantics.
            if (done) item.put("journal", "BATCH_PERSISTED").put("journalText", "批次已物化并核实")
                .put("afterRevision", String.valueOf(b.get("after_revision"))).put("receiptHash", String.valueOf(b.get("receipt_hash"))).put("persisted", true);
            else item.put("journal", "CHECKPOINT_SAVED").put("journalText", "检查点已保存");
            batches.add(item);
        }
        Map<String, Object> latest = latestCheckpoint(effectId);
        ObjectNode checkpoint = obj().put("state", "SAVED").put("batchIndex", latest == null ? -1 : ((Number) latest.get("batch_index")).intValue());
        checkpoint.put("savedDraftVersion", latest == null ? r.path("expectedDraftVersion").asLong() : ((Number) latest.get("saved_draft_version")).longValue());
        checkpoint.put("contentHash", latest == null ? r.path("loadedRevision").asText() : String.valueOf(latest.get("content_hash")));
        out.set("batches", batches); out.put("persistedCount", persisted)
            .put("actualProgress", batches.size() == 0 ? 0 : (int) ((persisted * 100L) / batches.size()))
            .put("nextBatchIndex", latestCheckpointBatchIndex(effectId) + 1)
            .put("journalRule", "未物化批次仅显示检查点已保存，不渲染完成或可回滚状态");
        out.set("checkpoint", checkpoint);
        return out;
    }
    public ObjectNode view(String effectId, long user) {
        ObjectNode r = store.get(runOf(effectId)); require(r.path("userId").asLong() == user, "任务不存在或无权访问");
        return progressView(r);
    }
}
