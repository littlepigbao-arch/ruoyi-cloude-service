package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.controller.BatchWriteController;
import com.ruoyi.ai.util.ContentHashUtils;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C2 batch-ledger acceptance points A/B/C/E/F plus fault injections D1 (lost receipt response) and
 * D2 (duplicate request against an already-stored batch) against real isolated MySQL.
 * D3 (restart recovery via checkpoint+write_batch) lives in {@link BatchWriteRecoveryIT} because it needs
 * fresh service/scheduler instances. setUp mirrors the C1 replay pattern (tolerating already-migrated
 * objects) and re-applies the effect ledger plus the C2 batch tables.
 */
public class BatchWriteServiceMysqlTest extends TestCase {
    private A1TestFixture fixture; private BatchWriteService svc; private BatchWriteController controller;
    private long workbook; private long branch; private String original;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C2 schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        for(String file:new String[]{"ai_agent_controlled_write_v2.sql","ai_agent_batch_write_v2.sql"}){
            String sql=new String(Files.readAllBytes(Paths.get(module,"sql",file)),StandardCharsets.UTF_8);
            for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
                String trimmed=statement.trim();if(trimmed.isEmpty())continue;
                try{fixture.db.execute(trimmed);}
                catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate"))throw alreadyMigrated;}
            }
        }
        ObjectNode snapshot=(ObjectNode)parse("{\"id\":\"c2\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"S\",\"rowCount\":1300,\"columnCount\":4,\"cellData\":{}}}}");
        original=snapshot.toString();workbook=fixture.seed(original);
        branch=fixture.request(workbook,-1,null,original).getBranchId();
        svc=new BatchWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        A1TestFixture.inject(svc,"controlledEnabled",true);A1TestFixture.inject(svc,"enabled",true);
        controller=new BatchWriteController(svc);
    }
    @Override protected void tearDown(){fixture.close();}
    private ObjectNode batchedRequest(int rows,int width,String overwrite){
        ObjectNode ws=fixture.runs.workspace(workbook,1);
        ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong())
            .put("activeSheetId","s").put("overwrite",overwrite);
        req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1:B1300")));
        req.set("target",obj().put("sheetId","s").put("startRow",0).put("startColumn",0));
        ArrayNode values=arr();for(int i=0;i<rows;i++){ArrayNode row=arr();for(int c=0;c<width;c++)row.add("v"+i+"-"+c);values.add(row);}
        req.set("values",values);return req;
    }
    private ObjectNode claim(String effectId,int index,long fence){return svc.claimBatch(effectId,index,1,obj().put("transportVersion",2).put("clientId","c2-test").put("fencingToken",fence));}
    private ObjectNode receiptOf(ObjectNode contract,boolean ok,ArrayNode cells){
        ObjectNode r=obj().put("transportVersion",2).put("effectId",contract.path("effectId").asText())
            .put("batchId",contract.path("batchId").asText()).put("batchIndex",contract.path("batchIndex").asInt())
            .put("claimToken",contract.path("claimToken").asText()).put("paramsHash",contract.path("paramsHash").asText())
            .put("fencingToken",contract.path("fencingToken").asLong()).put("ok",ok);
        if(cells!=null)r.set("cells",cells);else r.put("error","client setter failed");
        return r;
    }
    /** Faithful readback of one batch contract: exactly the manifest cells and values. */
    private ArrayNode readbackOf(ObjectNode contract){
        ArrayNode cells=arr();
        for(JsonNode cell:contract.path("manifest").path("cells"))
            cells.add(obj().put("sheetId",cell.path("sheetId").asText()).put("row",cell.path("row").asInt()).put("column",cell.path("column").asInt()).put("formula","").set("value",cell.path("value").deepCopy()));
        return cells;
    }
    private String statusOf(String effectId,int index){return fixture.db.queryForObject("select status from ai_agent_write_batch where effect_id=? and batch_index=?",String.class,effectId,index);}
    private Long draftVersion(){List<Long> v=fixture.db.queryForList("select version from ai_workbook_working_state where workbook_id=?",Long.class,workbook);return v.isEmpty()?null:v.get(0);}
    // ---------- C2 终审整改（2×P1 + 2×P2）回归辅助 ----------
    private long fenceOf(long wb){return fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,wb);}
    private Long draftVersionOf(long wb){List<Long> v=fixture.db.queryForList("select version from ai_workbook_working_state where workbook_id=?",Long.class,wb);return v.isEmpty()?null:v.get(0);}
    private int draftRowsOf(long wb){return fixture.db.queryForObject("select count(*) from ai_workbook_working_state where workbook_id=?",Integer.class,wb).intValue();}
    private int leaseOwners(long wb){return fixture.db.queryForObject("select count(*) from ai_agent_lease where workbook_id=? and run_id is not null",Integer.class,wb).intValue();}
    private long leaseExpiry(long wb){return fixture.db.queryForObject("select max(expires_at) from ai_agent_lease where workbook_id=?",Long.class,wb);}
    private String effectStatus(String effectId){return fixture.db.queryForObject("select status from ai_agent_effect where effect_id=?",String.class,effectId);}
    private long checkpointVersion(String effectId,int index){return fixture.db.queryForObject("select max(saved_draft_version) from ai_agent_checkpoint where effect_id=? and batch_index=?",Long.class,effectId,index);}
    private int checkpointCount(String effectId,int index){return fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=? and batch_index=?",Integer.class,effectId,index).intValue();}
    /** 以 [sameFrom,sameTo) 行已预填 same-i-c 的快照种入全新工作簿，并生成把这些行按原值重写的请求；返回 {请求, 快照}。 */
    private ObjectNode[] noChangePlan(int totalRows,int sameFrom,int sameTo){
        ObjectNode snapshot=(ObjectNode)parse("{\"id\":\"c2-nc\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"S\",\"rowCount\":1300,\"columnCount\":4,\"cellData\":{}}}}");
        ObjectNode cellData=(ObjectNode)snapshot.path("sheets").path("s").path("cellData");
        for(int i=sameFrom;i<sameTo;i++){ObjectNode row=obj();for(int c=0;c<2;c++)row.set(String.valueOf(c),obj().put("v","same-"+i+"-"+c).put("t",1));cellData.set(String.valueOf(i),row);}
        long wb=fixture.seed(snapshot.toString());
        ObjectNode ws=fixture.runs.workspace(wb,1);
        ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",wb)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong())
            .put("activeSheetId","s").put("overwrite","confirmed");
        req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1:B1300")));
        req.set("target",obj().put("sheetId","s").put("startRow",0).put("startColumn",0));
        ArrayNode values=arr();
        for(int i=0;i<totalRows;i++){ArrayNode row=arr();for(int c=0;c<2;c++)row.add(i>=sameFrom&&i<sameTo?"same-"+i+"-"+c:"chg-"+i+"-"+c);values.add(row);}
        req.set("values",values);return new ObjectNode[]{req,snapshot};
    }
    /** 直接种入与快照完全一致的真实草稿行（无变化批次的“真实保存过”证据：草稿行 + content_hash）。 */
    private void seedRealDraft(long wb,String content){
        Map<String,Object> seed=new HashMap<>();seed.put("workbookId",wb);
        seed.put("branchId",fixture.db.queryForObject("select current_branch_id from ai_workbook where workbook_id=?",Long.class,wb));
        seed.put("ownerUserId",1L);seed.put("baseCommitId",null);seed.put("draftActionsJson","[]");seed.put("executedChangesJson","[]");
        seed.put("content",content.getBytes(StandardCharsets.UTF_8));seed.put("contentHash", ContentHashUtils.sha256(content));
        seed.put("expectedDraftVersion",-1L);seed.put("expectedContentHash", ContentHashUtils.sha256(content));
        assertEquals("预置真实草稿行必须成功",1,fixture.tx.execute(s->fixture.versions.insertWorkingState(seed)).intValue());
    }

    /** 验收前置：服务端先落库全部批次清单与初始检查点，才允许下发第一批；契约 v1 字段齐全；批次必须按序领取。 */
    public void testRecordBeforeDispatchAndContractFields(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        assertEquals(3,created.path("totalBatches").asInt());
        assertEquals("清单必须先全部落库（3 行批次 + 初始检查点）",3,fixture.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=?",Integer.class,effectId).intValue());
        assertEquals("初始检查点 batch_index=-1",-1,fixture.db.queryForObject("select batch_index from ai_agent_checkpoint where effect_id=? order by checkpoint_seq limit 1",Integer.class,effectId).intValue());
        assertEquals("PREPARED",fixture.db.queryForObject("select status from ai_agent_effect where effect_id=?",String.class,effectId));
        assertEquals("未物化批次只显示检查点已保存","CHECKPOINT_SAVED",created.path("batches").get(0).path("journal").asText());
        long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        try{claim(effectId,1,fence);fail("out-of-order dispatch accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_ORDER_INVALID"));}
        ObjectNode contract=claim(effectId,0,fence);
        assertEquals(1,contract.path("batchContractVersion").asInt());
        assertTrue("batchId 必须是服务端生成的不重复标识",contract.path("batchId").isTextual()&&contract.path("batchId").asText().length()==36);
        assertEquals(0,contract.path("batchIndex").asInt());
        assertTrue("chunkRef 必须指向 C1 分块制品",contract.path("chunkRef").asText().matches("^[0-9a-f-]{36}#0$"));
        assertEquals(500,contract.path("chunkRows").asInt());
        assertEquals(0,contract.path("rowStart").asInt());
        assertTrue("verificationSpecId 必须是 64 位十六进制摘要",contract.path("verificationSpecId").asText().matches("^[0-9a-f]{64}$"));
        assertTrue(contract.path("paramsHash").asText().matches("^[0-9a-f]{64}$"));
        assertTrue(contract.path("claimToken").isTextual());
        assertEquals(1000,contract.path("manifest").path("cells").size());
        assertEquals("CLAIMED",fixture.db.queryForObject("select status from ai_agent_effect where effect_id=?",String.class,effectId));
        ObjectNode repeat=claim(effectId,0,fence);
        assertFalse("重复领取不得再次发参数或令牌",repeat.path("replayAllowed").asBoolean(true));
        assertFalse(repeat.has("claimToken"));
        assertEquals("重复领取不得产生第二行",3,fixture.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=?",Integer.class,effectId).intValue());
    }
    /** 验收 A：已确认（PERSISTED）批次不重跑；重复回执幂等且不二次应用。 */
    public void testPersistedBatchNeverReRuns(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ObjectNode saved=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        assertEquals(Long.valueOf(0L),draftVersion());
        assertEquals(0,saved.path("batches").get(0).path("batchIndex").asInt());
        try{claim(effectId,0,fence);fail("persisted batch re-dispatched");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_ALREADY_PERSISTED"));}
        String content=String.valueOf(fixture.status(workbook).get("content"));
        ObjectNode again=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("重复回执必须幂等返回","PERSISTED",again.path("batches").get(0).path("status").asText());
        assertEquals("重复回执不得二次应用（版本不变）",Long.valueOf(0L),draftVersion());
        assertEquals("重复回执不得改写草稿",content,fixture.status(workbook).get("content"));
        assertEquals("每批次只允许一行",3,fixture.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=?",Integer.class,effectId).intValue());
        ObjectNode c1=claim(effectId,1,fence);svc.receiptBatch(effectId,1,1,receiptOf(c1,true,readbackOf(c1)));
        ObjectNode c2=claim(effectId,2,fence);ObjectNode done=svc.receiptBatch(effectId,2,1,receiptOf(c2,true,readbackOf(c2)));
        assertEquals("COMPLETED",done.path("status").asText());
        assertEquals("PERSISTED",fixture.db.queryForObject("select status from ai_agent_effect where effect_id=?",String.class,effectId));
        assertEquals(Long.valueOf(2L),draftVersion());
        JsonNode sheet=parse(String.valueOf(fixture.status(workbook).get("content"))).path("sheets").path("s");
        assertEquals("最后一批数据必须落盘","v1249-1",sheet.path("cellData").path("1249").path("1").path("v").asText());
        assertEquals("检查点序列应为 初始+3批",4,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue());
    }
    /** 验收 C：逐批验证失败不进 PERSISTED、草稿不变；验证通过才 CAS 保存并读回一致。 */
    public void testPerBatchVerificationFailureKeepsBatchUnpersisted(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ArrayNode forged=readbackOf(c0);((ObjectNode)forged.get(0)).put("value","tampered");
        try{svc.receiptBatch(effectId,0,1,receiptOf(c0,true,forged));fail("forged readback accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_READBACK_INVALID"));}
        assertEquals("验证不过不得进入 PERSISTED","DISPATCHED",statusOf(effectId,0));
        assertNull("验证不过不得产生草稿",draftVersion());
        ObjectNode wrongToken=receiptOf(c0,true,readbackOf(c0));wrongToken.put("claimToken","stolen");
        try{svc.receiptBatch(effectId,0,1,wrongToken);fail("stolen claim token accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_RECEIPT_IDENTITY_INVALID"));}
        assertEquals("DISPATCHED",statusOf(effectId,0));
        ObjectNode ok=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        assertEquals(Long.valueOf(0L),draftVersion());
        String expectedAfter=fixture.db.queryForObject("select expected_after from ai_agent_write_batch where effect_id=? and batch_index=0",String.class,effectId);
        assertEquals("CAS 保存的草稿哈希必须等于落库 expected_after",expectedAfter,fixture.db.queryForObject("select content_hash from ai_workbook_working_state where workbook_id=?",String.class,workbook));
        assertEquals(1,ok.path("nextBatchIndex").asInt());
        assertEquals("实际进度按 PERSISTED 批次计算",33,ok.path("actualProgress").asInt());
    }
    /** 验收 F：批次未物化时 journal 只显示“检查点已保存”，不得渲染完成/可回滚语义字段。 */
    public void testJournalShowsOnlyCheckpointForUnmaterializedBatch(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        fixture.identity();
        com.ruoyi.common.core.domain.R<?> view=controller.batches(effectId);
        assertEquals(200,view.getCode());
        ObjectNode data=(ObjectNode)view.getData();
        JsonNode pending=data.path("batches").get(1);
        assertEquals("CHECKPOINT_SAVED",pending.path("journal").asText());
        assertEquals("检查点已保存",pending.path("journalText").asText());
        for(String forbidden:new String[]{"afterRevision","persisted","completed","rollback","reversible","receiptHash","draftVersion"})
            assertFalse("未物化批次不得携带完成或回滚语义字段 "+forbidden,pending.has(forbidden));
        assertFalse("未物化批次 JSON 不得出现可回滚措辞",pending.toString().contains("rollback"));
        JsonNode done=data.path("batches").get(0);
        assertEquals("BATCH_PERSISTED",done.path("journal").asText());
        assertTrue(done.path("persisted").asBoolean());
        assertTrue(done.path("afterRevision").asText().matches("^[0-9a-f]{64}$"));
        assertEquals(1,data.path("nextBatchIndex").asInt());
        assertEquals(1,data.path("persistedCount").asInt());
    }
    /** reconcile 只认服务端核实：客户端声明字段一律拒绝；UNKNOWN 批次按草稿哈希核实回 PLANNED 或保持 UNKNOWN。 */
    public void testReconcileRejectsClientDeclarationsAndVerifiesServerSide(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ObjectNode failed=svc.receiptBatch(effectId,0,1,receiptOf(c0,false,null));
        assertEquals("UNKNOWN",statusOf(effectId,0));
        assertEquals("PAUSED",failed.path("status").asText());
        fixture.identity();
        try{controller.reconcile(effectId,obj().put("transportVersion",2).put("rollback",true));fail("client rollback declaration accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("RECONCILE_INVALID"));}
        try{controller.reconcile(effectId,obj().put("transportVersion",2).put("declareStatus","NOT_APPLIED"));fail("client status declaration accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("RECONCILE_INVALID"));}
        com.ruoyi.common.core.domain.R<?> verified=controller.reconcile(effectId,obj().put("transportVersion",2));
        assertEquals(200,verified.getCode());
        assertEquals("服务端核实未落盘 → 批次回到 PLANNED","PLANNED",statusOf(effectId,0));
        assertEquals("EXECUTING",((ObjectNode)verified.getData()).path("status").asText());
        assertFalse("客户端声明一律不被接受",((ObjectNode)verified.getData()).path("reconciledBy").path("clientDeclarationsAccepted").asBoolean());
        // 反例：草稿与该批 expected_before/expected_after 均不符 → 无法核实，保持 UNKNOWN 且任务 PAUSED。
        ObjectNode c0b=claim(effectId,0,fence);
        svc.receiptBatch(effectId,0,1,receiptOf(c0b,false,null));
        Map<String,Object> seed=new HashMap<>();seed.put("workbookId",workbook);seed.put("branchId",branch);seed.put("ownerUserId",1L);
        seed.put("baseCommitId",null);seed.put("draftActionsJson","[]");seed.put("executedChangesJson","[]");
        String foreign="{\"id\":\"c2\",\"tampered\":true}";seed.put("content",foreign.getBytes(StandardCharsets.UTF_8));seed.put("contentHash", ContentHashUtils.sha256(foreign));
        seed.put("expectedDraftVersion",-1L);seed.put("expectedContentHash",fixture.runs.workspace(workbook,1).path("loadedRevision").asText());
        assertEquals("直接种入外源草稿必须成功",1,fixture.tx.execute(s->fixture.versions.insertWorkingState(seed)).intValue());
        com.ruoyi.common.core.domain.R<?> unresolved=controller.reconcile(effectId,obj().put("transportVersion",2));
        assertEquals("无法核实必须保持 UNKNOWN","UNKNOWN",statusOf(effectId,0));
        assertEquals("PAUSED",((ObjectNode)unresolved.getData()).path("status").asText());
    }
    /** 验收 E（R6 负向证据）：分块物化链路上，验证范围之外的数据不得被保存；旧 Snapshot 阻断不变。 */
    public void testSaveScopeNeverExceedsVerificationScope(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ArrayNode beyond=readbackOf(c0);
        beyond.add(obj().put("sheetId","s").put("row",1250).put("column",0).put("formula","").put("value","outside-verification-scope"));
        try{svc.receiptBatch(effectId,0,1,receiptOf(c0,true,beyond));fail("out-of-scope cell saved");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_"));}
        assertEquals("越界回执不得进入 PERSISTED","DISPATCHED",statusOf(effectId,0));
        assertNull("越界回执不得保存任何草稿",draftVersion());
        // 记录期负例（R6 范围上限）：超出总预算的请求在物化前即被拒绝，不产生批次清单，也不残留任务行。
        // （writeArtifact 每次写入全新制品，运行外的 row_count 篡改无法被新 create 读到，故以可达的预算上限为记录期负例。）
        ObjectNode oversized=batchedRequest(1250,2,"confirmed");
        ArrayNode huge=arr();for(int i=0;i<=AgentTools.MAX_CELLS/2;i++)huge.add(arr().add("x").add("y"));
        oversized.set("values",huge);
        try{svc.create(oversized,1);fail("over-budget manifest generated");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_BUDGET_EXCEEDED"));}
        assertEquals("超预算请求不得产生批次清单",3,fixture.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=?",Integer.class,effectId).intValue());
        assertEquals("超预算请求不得残留任务行",0,fixture.db.queryForObject("select count(*) from ai_agent_run where request_id=?",Integer.class,oversized.path("requestId").asText()).intValue());
        // 旧 Snapshot 阻断保持不变。
        try{fixture.runs.claim("any",1,obj());fail("legacy snapshot claim reopened");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("SNAPSHOT_WRITE_BLOCKED"));}
        ObjectNode ok=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("合法范围内回执正常保存","PERSISTED",statusOf(effectId,0));
        JsonNode sheet=parse(String.valueOf(fixture.status(workbook).get("content"))).path("sheets").path("s");
        assertFalse("验证范围之外的数据不得进入草稿",sheet.path("cellData").has("1250"));
    }
    /** 验收 B：同一 requestId 重复提交与已派发批次参数不可变。 */
    public void testDispatchedParametersImmutableAndCreateIdempotent(){
        ObjectNode request=batchedRequest(1250,2,"confirmed");
        ObjectNode created=svc.create(request,1);
        String effectId=created.path("effectId").asText();
        List<String> paramsBefore=fixture.db.queryForList("select params_hash from ai_agent_write_batch where effect_id=? order by batch_index",String.class,effectId);
        ObjectNode replay=svc.create(request,1);
        assertEquals("重复提交必须幂等回到同一效果",effectId,replay.path("effectId").asText());
        assertEquals("重复提交不得产生第二行",3,fixture.db.queryForObject("select count(*) from ai_agent_write_batch where effect_id=?",Integer.class,effectId).intValue());
        assertEquals(paramsBefore,fixture.db.queryForList("select params_hash from ai_agent_write_batch where effect_id=? order by batch_index",String.class,effectId));
        ObjectNode changed=request.deepCopy();((ArrayNode)changed.path("values").get(0)).set(0,parse("\"different\""));
        try{svc.create(changed,1);fail("changed payload under same requestId accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_IDEMPOTENCY_CONFLICT"));}
        long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("派发、回执、核实全程不得改写参数列",paramsBefore,fixture.db.queryForList("select params_hash from ai_agent_write_batch where effect_id=? order by batch_index",String.class,effectId));
    }
    /** 验收 D①②：回执丢失（客户端未收到响应）后重发 → 恰好一次；后端已存时重复/冲突请求 → 幂等或拒绝，均不二次应用。 */
    public void testLostReceiptAndBackendStoredDuplicateAreExactlyOnce(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();long fence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ObjectNode first=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0))); // 提交成功但“响应丢失”
        assertEquals("PERSISTED",statusOf(effectId,0));
        ObjectNode retry=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0))); // 客户端重发同一回执
        assertEquals("PERSISTED",statusOf(effectId,0));
        assertEquals("重发不得二次应用（版本仍为 0）",Long.valueOf(0L),draftVersion());
        assertEquals("重发不得写第二个检查点",1,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=? and batch_index=0",Integer.class,effectId).intValue());
        ArrayNode forged=readbackOf(c0);((ObjectNode)forged.get(1)).put("value","conflicting");
        try{svc.receiptBatch(effectId,0,1,receiptOf(c0,true,forged));fail("conflicting duplicate accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_IDEMPOTENCY_CONFLICT"));}
        assertEquals(Long.valueOf(0L),draftVersion());
    }

    // ---------- C2 终审整改回归：四个独立探针反例，失败先行 ----------

    /** 终审 P1①（探针 NOOP_*）：无变化批次（before==after）且从未保存草稿时，哈希命中不得伪造 PERSISTED/SAVED 检查点/草稿版本。 */
    public void testNoChangeUnsavedBatchSettlesNotAppliedWithoutForgedSave(){
        ObjectNode[] plan=noChangePlan(1,0,1);
        ObjectNode req=plan[0];
        ObjectNode created=svc.create(req,1);
        String effectId=created.path("effectId").asText();long wb=req.path("workbookId").asLong();
        Map<String,Object> hashes=fixture.db.queryForMap("select expected_before,expected_after from ai_agent_write_batch where effect_id=? and batch_index=0",effectId);
        assertEquals("前置：该批次必须是无变化批次（写入值与原值相同）",hashes.get("expected_before"),hashes.get("expected_after"));
        assertEquals("前置：从未保存草稿",0,draftRowsOf(wb));
        long fence=fenceOf(wb);
        ObjectNode c0=claim(effectId,0,fence);
        ObjectNode failed=svc.receiptBatch(effectId,0,1,receiptOf(c0,false,null));
        assertEquals("UNKNOWN",statusOf(effectId,0));
        assertEquals("PAUSED",failed.path("status").asText());
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("未保存的无变化批次不得凭哈希命中完成任务","EXECUTING",verdict.path("status").asText());
        assertEquals("未保存的无变化批次必须核实为未落盘（回 PLANNED）","PLANNED",statusOf(effectId,0));
        assertEquals("不得伪造草稿行",0,draftRowsOf(wb));
        assertEquals("不得伪造 SAVED 检查点",0,checkpointCount(effectId,0));
        assertEquals("检查点版本必须保持真实初始值（-1），不得推算",-1L,verdict.path("checkpoint").path("savedDraftVersion").asLong());
        // 真实路径不受影响：重新领取并成功回执，草稿才真实落盘。
        ObjectNode c0b=claim(effectId,0,fenceOf(wb));
        ObjectNode done=svc.receiptBatch(effectId,0,1,receiptOf(c0b,true,readbackOf(c0b)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        assertEquals("COMPLETED",done.path("status").asText());
        assertEquals("成功回执后草稿真实落盘 v0",Long.valueOf(0L),draftVersionOf(wb));
    }
    /** 终审 P1①（已保存情形）：无变化批次 + 真实草稿行（content_hash 恰好相同）必须仍核实为 SAVED，且版本取自草稿行而非推算。 */
    public void testNoChangeBatchWithRealDraftRowVerifiesSavedWithRealVersion(){
        ObjectNode[] plan=noChangePlan(1,0,1);ObjectNode req=plan[0],snapshot=plan[1];
        long wb=req.path("workbookId").asLong();
        seedRealDraft(wb,snapshot.toString());
        // 草稿真实保存后客户端重新读取工作区绑定（loadedRevision 不变，draftVersion 0→绑定刷新）。
        ObjectNode ws=fixture.runs.workspace(wb,1);
        req.put("expectedDraftVersion",ws.path("draftVersion").asLong());
        ObjectNode created=svc.create(req,1);
        String effectId=created.path("effectId").asText();
        long fence=fenceOf(wb);
        ObjectNode c0=claim(effectId,0,fence);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,false,null));
        assertEquals("UNKNOWN",statusOf(effectId,0));
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("真实草稿行存在且 content_hash 命中 → 仍核实为 PERSISTED","PERSISTED",statusOf(effectId,0));
        assertEquals("COMPLETED",verdict.path("status").asText());
        assertEquals("SAVED 检查点版本必须取自真实草稿行（0），不得推算为 1",0L,checkpointVersion(effectId,0));
        assertEquals("草稿行本身保持不变",Long.valueOf(0L),draftVersionOf(wb));
    }
    /** 终审 P1①（多批次中间位置）：中间无变化批次按草稿行真实存在与否结算，且整链路可继续到完成。 */
    public void testNoChangeBatchMidPlanSettlesHonestlyAndChainCompletes(){
        ObjectNode[] plan=noChangePlan(1250,500,1000);ObjectNode req=plan[0];
        ObjectNode created=svc.create(req,1);
        String effectId=created.path("effectId").asText();long wb=req.path("workbookId").asLong();
        assertEquals(3,created.path("totalBatches").asInt());
        Map<String,Object> hashes=fixture.db.queryForMap("select expected_before,expected_after from ai_agent_write_batch where effect_id=? and batch_index=1",effectId);
        assertEquals("前置：批次 1 必须是无变化批次",hashes.get("expected_before"),hashes.get("expected_after"));
        long fence=fenceOf(wb);
        // 批次 0（变化批次）真实保存 → 草稿 v0
        ObjectNode c0=claim(effectId,0,fence);svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        // 批次 1（中间无变化批次）执行方报失败 → UNKNOWN → 服务端核实为已保存（草稿行真实存在且哈希命中）
        ObjectNode c1=claim(effectId,1,fence);svc.receiptBatch(effectId,1,1,receiptOf(c1,false,null));
        assertEquals("UNKNOWN",statusOf(effectId,1));
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("中间无变化批次凭真实草稿行核实为已保存","PERSISTED",statusOf(effectId,1));
        assertEquals("版本必须取自真实草稿行（0），不得推算 1",0L,checkpointVersion(effectId,1));
        assertEquals("EXECUTING",verdict.path("status").asText());
        // 链路继续到完成
        ObjectNode c2=claim(effectId,2,fenceOf(wb));
        ObjectNode done=svc.receiptBatch(effectId,2,1,receiptOf(c2,true,readbackOf(c2)));
        assertEquals("COMPLETED",done.path("status").asText());
        assertEquals("最终草稿版本 0→1（批次 0 与批次 2 各恰好一次 CAS）",Long.valueOf(1L),draftVersionOf(wb));
        JsonNode sheet=parse(String.valueOf(fixture.status(wb).get("content"))).path("sheets").path("s");
        assertEquals("最后一批数据必须落盘","chg-1249-1",sheet.path("cellData").path("1249").path("1").path("v").asText());
        assertEquals("无变化批次的原值保持不变","same-700-1",sheet.path("cellData").path("700").path("1").path("v").asText());
    }
    /** 终审 P1②（探针 CANCEL_*，DISPATCHED 态）：取消必须先隔离在途批次（UNKNOWN+栅栏提升）并保留租约，经 reconcile 核实后才终结。 */
    public void testCancelIsolatesInFlightBatchThenSettlesThroughReconcile() throws Exception {
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText(),runId=created.path("runId").asText();
        long fence0=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence0);
        assertEquals("DISPATCHED",statusOf(effectId,0));
        A1TestFixture.inject(fixture.runs,"batched",svc);
        ObjectNode cancelled=fixture.runs.cancel(runId,1);
        assertEquals("在途批次未核实时不得直接终结任务","CANCELLING",cancelled.path("status").asText());
        assertEquals("在途批次必须隔离为 UNKNOWN","UNKNOWN",statusOf(effectId,0));
        assertTrue("隔离栅必须提升以阻断旧执行者",fenceOf(workbook)>fence0);
        assertEquals("租约保护必须保留到核实完成",1,leaseOwners(workbook));
        try{svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));fail("isolated batch receipt accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("RESULT_UNKNOWN"));}
        assertEquals("UNKNOWN",statusOf(effectId,0));
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("核实后取消任务收敛为 CANCELLED","CANCELLED",verdict.path("status").asText());
        assertEquals("服务端核实未落盘 → 批次结算为 PLANNED，不再悬空","PLANNED",statusOf(effectId,0));
        assertEquals("效果账本同步终结","CANCELLED",effectStatus(effectId));
        assertEquals("核实完成后才释放租约",0,leaseOwners(workbook));
    }
    /** 终审 P1②（UNKNOWN 态）：批次已 UNKNOWN 时取消保持 CANCELLING 待核实，reconcile 结算后才终结释放。 */
    public void testCancelWithUnknownBatchVerifiesThenFinalizes() throws Exception {
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText(),runId=created.path("runId").asText();
        long fence=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,false,null));
        assertEquals("UNKNOWN",statusOf(effectId,0));
        A1TestFixture.inject(fixture.runs,"batched",svc);
        ObjectNode cancelled=fixture.runs.cancel(runId,1);
        assertEquals("UNKNOWN 事实未结算不得直接终结","CANCELLING",cancelled.path("status").asText());
        assertEquals("UNKNOWN",statusOf(effectId,0));
        assertEquals("租约保护必须保留",1,leaseOwners(workbook));
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("核实未落盘 → 结算 PLANNED 并完成取消","PLANNED",statusOf(effectId,0));
        assertEquals("CANCELLED",verdict.path("status").asText());
        assertEquals("CANCELLED",effectStatus(effectId));
        assertEquals(0,leaseOwners(workbook));
    }
    /** 终审 P1②（部分 PERSISTED 态）：取消保留已保存批次事实；无在途批次时立即终结且效果账本同步取消。 */
    public void testCancelWithPartiallyPersistedBatchesRetainsSavedFacts() throws Exception {
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText(),runId=created.path("runId").asText();
        long fence=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence);svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        ObjectNode c1=claim(effectId,1,fence); // 批次 1 在途
        A1TestFixture.inject(fixture.runs,"batched",svc);
        ObjectNode cancelled=fixture.runs.cancel(runId,1);
        assertEquals("CANCELLING",cancelled.path("status").asText());
        assertEquals("UNKNOWN",statusOf(effectId,1));
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("CANCELLED",verdict.path("status").asText());
        assertEquals("批次 1 核实未落盘 → PLANNED","PLANNED",statusOf(effectId,1));
        assertEquals("已保存批次 0 事实保留","PERSISTED",statusOf(effectId,0));
        assertEquals("草稿事实保留",Long.valueOf(0L),draftVersion());
        assertEquals("CANCELLED",effectStatus(effectId));
        assertEquals(0,leaseOwners(workbook));
        assertEquals("journal 保留已保存事实",1,verdict.path("persistedCount").asInt());
        assertEquals("BATCH_PERSISTED",verdict.path("batches").get(0).path("journal").asText());
        // 变体：部分 PERSISTED 且无在途批次 → 取消立即终结，不悬空等核实
        ObjectNode created2=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId2=created2.path("effectId").asText(),runId2=created2.path("runId").asText();
        long fence2=fenceOf(workbook);
        ObjectNode d0=claim(effectId2,0,fence2);svc.receiptBatch(effectId2,0,1,receiptOf(d0,true,readbackOf(d0)));
        assertEquals("PERSISTED",statusOf(effectId2,0));
        ObjectNode cancelled2=fixture.runs.cancel(runId2,1);
        assertEquals("无在途批次时取消立即终结","CANCELLED",cancelled2.path("status").asText());
        assertEquals("未下发批次保持 PLANNED","PLANNED",statusOf(effectId2,1));
        assertEquals("已保存事实保留","PERSISTED",statusOf(effectId2,0));
        assertEquals("效果账本同步取消，不得悬空 CLAIMED","CANCELLED",effectStatus(effectId2));
        assertEquals(0,leaseOwners(workbook));
    }
    /** 终审 P2③（探针 OVERFLOW_TARGET）：目标坐标必须是无损整数（类型/范围/终点溢出），非法请求在物化前拒绝、零残留。 */
    public void testTargetCoordinatesAreLosslesslyValidatedBeforeMaterialization(){
        int artifactsBefore=fixture.db.queryForObject("select count(*) from ai_analysis_artifact",Integer.class).intValue();
        String[] field=new String[]{"startRow","startColumn"};
        for(String f:field){
            ObjectNode overflow=batchedRequest(1250,2,"confirmed");
            ((ObjectNode)overflow.path("target")).put(f,4294967296L);
            try{svc.create(overflow,1);fail(f+"=4294967296 被 asInt 截断接纳");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_WRITE_INVALID"));}
            ObjectNode negative=batchedRequest(1250,2,"confirmed");
            ((ObjectNode)negative.path("target")).put(f,-1);
            try{svc.create(negative,1);fail(f+"=-1 accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_WRITE_INVALID"));}
            ObjectNode textual=batchedRequest(1250,2,"confirmed");
            ((ObjectNode)textual.path("target")).put(f,"0");
            try{svc.create(textual,1);fail("非整数 "+f+" accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_WRITE_INVALID"));}
            for(ObjectNode bad:new ObjectNode[]{overflow,negative,textual})
                assertEquals("非法坐标不得残留任务行",0,fixture.db.queryForObject("select count(*) from ai_agent_run where request_id=?",Integer.class,bad.path("requestId").asText()).intValue());
        }
        // 终点溢出：起点为合法上界、但 startRow+行数-1 越出 int 范围，同样必须在物化前拒绝。
        ObjectNode edge=batchedRequest(2,1,"confirmed");
        ((ObjectNode)edge.path("target")).put("startRow",Integer.MAX_VALUE);
        try{svc.create(edge,1);fail("startRow+rows 终点溢出 accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_WRITE_INVALID"));}
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_run where request_id=?",Integer.class,edge.path("requestId").asText()).intValue());
        assertEquals("非法坐标不得物化分块制品",artifactsBefore,fixture.db.queryForObject("select count(*) from ai_analysis_artifact",Integer.class).intValue());
        // 合法上界内的正常请求不受影响（沿用既有链路回归）。
        ObjectNode ok=svc.create(batchedRequest(1250,2,"confirmed"),1);
        assertEquals(3,ok.path("totalBatches").asInt());
    }
    /** 终审 P2③（探针 OVERFLOW_RECEIPT）：回执 batchIndex 必须无损精确匹配（4294967296 不得窄化冒充批次 0）。 */
    public void testReceiptBatchIndexIsLosslesslyChecked(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        long fence=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence);
        ObjectNode forged=receiptOf(c0,true,readbackOf(c0));
        forged.put("batchIndex",4294967296L);
        try{svc.receiptBatch(effectId,0,1,forged);fail("回执 batchIndex=4294967296 双方截断比较恒过");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_READBACK_INVALID"));}
        assertEquals("越界回执不得改变批次状态","DISPATCHED",statusOf(effectId,0));
        assertNull("越界回执不得保存草稿",draftVersion());
        ObjectNode negative=receiptOf(c0,true,readbackOf(c0));
        negative.put("batchIndex",-1L);
        try{svc.receiptBatch(effectId,0,1,negative);fail("negative receipt index accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_READBACK_INVALID"));}
        assertEquals("DISPATCHED",statusOf(effectId,0));
        // 正常回执不受影响
        ObjectNode ok=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",ok.path("batches").get(0).path("status").asText());
        assertEquals(Long.valueOf(0L),draftVersion());
    }
    /** 终审 P2④（探针 EXPIRED_PLANNED，首次领取前）：全部批次 PLANNED+租约过期+任务 EXECUTING 时，reconcile 必须经服务端核实恢复租约（栅栏提升）。 */
    public void testExpiredLeaseRecoversBeforeFirstClaimThroughReconcile(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        long fence0=fenceOf(workbook);
        fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=?",workbook);
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("任务保持可领取","EXECUTING",verdict.path("status").asText());
        long recovered=fenceOf(workbook);
        assertTrue("租约恢复必须提升栅栏（阻断旧执行者）",recovered>fence0);
        assertTrue("租约必须重新武装",leaseExpiry(workbook)>System.currentTimeMillis());
        try{claim(effectId,0,fence0);fail("旧栅栏领取未被拒绝");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_FENCE_STALE"));}
        ObjectNode c0=claim(effectId,0,recovered);
        assertEquals("恢复后首批必须可领取",0,c0.path("batchIndex").asInt());
        assertEquals("DISPATCHED",statusOf(effectId,0));
    }
    // ---------- 复审新增 P2 回归：租约恢复提栅栏后，已保存批次原始回执必须可幂等重放，篡改仍拒 ----------

    /** 复审 P2 回归A（已保存→租约恢复提栅栏→原回执重试）：原样重发已 PERSISTED 批次的成功回执必须幂等返回既有结果，不要求回执栅栏等于当前任务栅栏；草稿、检查点、批次事实、receipt_hash 全部不变。 */
    public void testPersistedReceiptReplaysAfterLeaseRecoveryFenceBump(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        long fence0=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence0);
        ObjectNode original=receiptOf(c0,true,readbackOf(c0));
        svc.receiptBatch(effectId,0,1,original);
        assertEquals("PERSISTED",statusOf(effectId,0));
        Long draftV0=draftVersion();String content0=String.valueOf(fixture.status(workbook).get("content"));
        String receiptHash0=fixture.db.queryForObject("select receipt_hash from ai_agent_write_batch where effect_id=? and batch_index=0",String.class,effectId);
        int checkpoints0=fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue();
        // 租约过期 → reconcile（服务端核实）→ 栅栏提升，原执行者回执摘要仍绑定旧栅栏
        fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=?",workbook);
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("EXECUTING",verdict.path("status").asText());
        long fence1=fenceOf(workbook);
        assertTrue("前置：租约恢复必须提升栅栏",fence1>fence0);
        // 原样重发批次 0 成功回执（栅栏仍为原值 fence0）→ 必须幂等返回既有结果
        ObjectNode replay=svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("原始回执重试必须幂等返回已保存结果","PERSISTED",replay.path("batches").get(0).path("status").asText());
        assertEquals("草稿版本不得变化",draftV0,draftVersion());
        assertEquals("草稿内容不得变化",content0,String.valueOf(fixture.status(workbook).get("content")));
        assertEquals("receipt_hash 不得变化",receiptHash0,fixture.db.queryForObject("select receipt_hash from ai_agent_write_batch where effect_id=? and batch_index=0",String.class,effectId));
        assertEquals("检查点不得新增",checkpoints0,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue());
        assertEquals("批次事实保持 PERSISTED","PERSISTED",statusOf(effectId,0));
        assertEquals("幂等重放不得推进检查点序号",0,replay.path("nextBatchIndex").asInt()-verdict.path("nextBatchIndex").asInt());
        // 恢复能力不受影响：批次 1 用提升后的栅栏正常领取续跑
        ObjectNode c1=claim(effectId,1,fence1);
        assertEquals("恢复后续跑必须可用新栅栏领取下一批次","DISPATCHED",statusOf(effectId,1));
    }
    /** 复审 P2 回归B（同场景篡改仍拒）：栅栏提升后，改回执栅栏为当前值或改 cells 任一字节 → 摘要变化，必须 WRITE_IDEMPOTENCY_CONFLICT，不得以幂等名义放行篡改。 */
    public void testTamperedReceiptStillRejectedAfterLeaseRecoveryFenceBump(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        long fence0=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence0);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        int checkpoints0=fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue();
        fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=?",workbook);
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("EXECUTING",verdict.path("status").asText());
        long fence1=fenceOf(workbook);
        assertTrue(fence1>fence0);
        // 变体1：把回执栅栏改为当前栅栏 → 摘要随之变化 → 篡改拒绝
        ObjectNode refenced=receiptOf(c0,true,readbackOf(c0));
        refenced.put("fencingToken",fence1);
        try{svc.receiptBatch(effectId,0,1,refenced);fail("re-fenced receipt accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_IDEMPOTENCY_CONFLICT"));}
        // 变体2：改 cells 任一字节 → 摘要变化 → 篡改拒绝
        ArrayNode tampered=readbackOf(c0);((ObjectNode)tampered.get(0)).put("value","tampered-after-persist");
        try{svc.receiptBatch(effectId,0,1,receiptOf(c0,true,tampered));fail("tampered cells accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("WRITE_IDEMPOTENCY_CONFLICT"));}
        // 两次篡改都不得改写任何事实
        assertEquals("篡改回执不得改变批次状态","PERSISTED",statusOf(effectId,0));
        assertEquals("篡改回执不得改写草稿",Long.valueOf(0L),draftVersion());
        assertEquals("篡改回执不得新增检查点",checkpoints0,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue());
    }
    /** 终审 P2④（批次间过期）：上一批已保存、余批 PLANNED、租约过期 → reconcile 恢复租约后续跑到完成。 */
    public void testExpiredLeaseRecoversBetweenBatchesThroughReconcile(){
        ObjectNode created=svc.create(batchedRequest(1250,2,"confirmed"),1);
        String effectId=created.path("effectId").asText();
        long fence0=fenceOf(workbook);
        ObjectNode c0=claim(effectId,0,fence0);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",statusOf(effectId,0));
        fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=?",workbook);
        try{claim(effectId,1,fence0);fail("过期租约领取未被拒绝");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("RESULT_UNKNOWN"));}
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("EXECUTING",verdict.path("status").asText());
        long recovered=fenceOf(workbook);
        assertTrue("批次间恢复同样必须提升栅栏",recovered>fence0);
        ObjectNode c1=claim(effectId,1,recovered);
        svc.receiptBatch(effectId,1,1,receiptOf(c1,true,readbackOf(c1)));
        assertEquals("PERSISTED",statusOf(effectId,1));
        ObjectNode c2=claim(effectId,2,recovered);
        ObjectNode done=svc.receiptBatch(effectId,2,1,receiptOf(c2,true,readbackOf(c2)));
        assertEquals("恢复后续跑至完成","COMPLETED",done.path("status").asText());
        assertEquals(Long.valueOf(2L),draftVersion());
    }
}
