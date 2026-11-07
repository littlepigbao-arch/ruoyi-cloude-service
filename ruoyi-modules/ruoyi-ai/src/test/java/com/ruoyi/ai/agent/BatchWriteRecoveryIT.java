package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C2 acceptance point D3 (restart): recovery must run off the persisted checkpoint + write_batch ledger,
 * never replaying an already-confirmed (PERSISTED) batch. Runs like {@link A1IsolationIT} outside the
 * {@code *Test} suite scope (explicit -Dtest=BatchWriteRecoveryIT), on the same isolated MySQL gate.
 * "Restart" is modeled the way B5 modeled it: brand-new service and scheduler instances over the same
 * database; nothing in-memory survives.
 */
public class BatchWriteRecoveryIT extends TestCase {
    private A1TestFixture fixture; private BatchWriteService svc;
    private long workbook; private String original;
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
        ObjectNode snapshot=(ObjectNode)parse("{\"id\":\"c2-it\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"S\",\"rowCount\":1300,\"columnCount\":4,\"cellData\":{}}}}");
        original=snapshot.toString();workbook=fixture.seed(original);
        svc=newService();
    }
    @Override protected void tearDown(){fixture.close();}
    private BatchWriteService newService() throws Exception {
        BatchWriteService s=new BatchWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        A1TestFixture.inject(s,"controlledEnabled",true);A1TestFixture.inject(s,"enabled",true);return s;
    }
    /** A restarted scheduler over the same database, wired with a fresh batch service like production Spring wiring. */
    private AgentRunService restartedScheduler(BatchWriteService batches) throws Exception {
        AgentStore store=new AgentStore(fixture.db);
        AgentRunService runs=new AgentRunService(store,fixture.guard,new AgentTools(store),fixture.workbooks,fixture.versions,null,fixture.tx.getTransactionManager());
        A1TestFixture.inject(runs,"batched",batches);
        return runs;
    }
    private ObjectNode batchedRequest(){
        ObjectNode ws=fixture.runs.workspace(workbook,1);
        ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong())
            .put("activeSheetId","s").put("overwrite","confirmed");
        req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1:B1300")));
        req.set("target",obj().put("sheetId","s").put("startRow",0).put("startColumn",0));
        ArrayNode values=arr();for(int i=0;i<1250;i++)values.add(arr().add("v"+i+"-0").add("v"+i+"-1"));
        req.set("values",values);return req;
    }
    private long fence(){return fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);}
    private ObjectNode claim(BatchWriteService service,String effectId,int index,long token){return service.claimBatch(effectId,index,1,obj().put("transportVersion",2).put("clientId","c2-restart").put("fencingToken",token));}
    private ObjectNode receiptOf(ObjectNode contract,boolean ok,ArrayNode cells){
        ObjectNode r=obj().put("transportVersion",2).put("effectId",contract.path("effectId").asText())
            .put("batchId",contract.path("batchId").asText()).put("batchIndex",contract.path("batchIndex").asInt())
            .put("claimToken",contract.path("claimToken").asText()).put("paramsHash",contract.path("paramsHash").asText())
            .put("fencingToken",contract.path("fencingToken").asLong()).put("ok",ok);
        if(cells!=null)r.set("cells",cells);else r.put("error","connection lost mid-batch");
        return r;
    }
    private ArrayNode readbackOf(ObjectNode contract){
        ArrayNode cells=arr();
        for(JsonNode cell:contract.path("manifest").path("cells"))
            cells.add(obj().put("sheetId",cell.path("sheetId").asText()).put("row",cell.path("row").asInt()).put("column",cell.path("column").asInt()).put("formula","").set("value",cell.path("value").deepCopy()));
        return cells;
    }
    private String statusOf(String effectId,int index){return fixture.db.queryForObject("select status from ai_agent_write_batch where effect_id=? and batch_index=?",String.class,effectId,index);}

    /** D③：服务重启后依据 checkpoint+write_batch 恢复；隔离在途批次、不重放已确认批次、按检查点续跑至完成。 */
    public void testRestartRecoversFromCheckpointWithoutReplayingConfirmedBatch() throws Exception {
        ObjectNode created=svc.create(batchedRequest(),1);
        String effectId=created.path("effectId").asText(),runId=created.path("runId").asText();
        long fence0=fence();
        ObjectNode c0=claim(svc,effectId,0,fence0);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0))); // 批次 0 已确认
        ObjectNode c1=claim(svc,effectId,1,fence0);                        // 批次 1 在途时“宕机”
        assertEquals("DISPATCHED",statusOf(effectId,1));
        // --- 重启：全新服务与调度器实例，仅数据库存活 ---
        BatchWriteService afterRestart=newService();
        AgentRunService scheduler=restartedScheduler(afterRestart);
        try{
            fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=? and run_id=?",workbook,runId);
            String prior=fixture.db.queryForObject("select max(run_id) from ai_agent_run where run_id<?",String.class,runId);
            A1TestFixture.inject(scheduler,"scanAfterRunId",prior==null?"":prior);
            scheduler.tick();
            assertEquals("死租约在途批次必须被隔离为 UNKNOWN","UNKNOWN",statusOf(effectId,1));
            assertEquals("PAUSED",fixture.db.queryForObject("select status from ai_agent_run where run_id=?",String.class,runId));
            long bumped=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
            assertTrue("隔离栅必须提升以阻断旧执行者",bumped>fence0);
            assertEquals("已确认批次 0 不受重启影响","PERSISTED",statusOf(effectId,0));
            try{claim(afterRestart,effectId,0,bumped);fail("confirmed batch replayed after restart");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("BATCH_ALREADY_PERSISTED"));}
            try{afterRestart.receiptBatch(effectId,1,1,receiptOf(c1,true,readbackOf(c1)));fail("stale executor receipt accepted after isolation");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("RESULT_UNKNOWN"));}
            assertEquals("旧执行者的迟到回执不得写入","UNKNOWN",statusOf(effectId,1));
            // --- reconcile 端点：仅服务端核实结果为准 ---
            ObjectNode verdict=afterRestart.reconcile(effectId,1,obj().put("transportVersion",2));
            assertEquals("服务端核实未落盘 → 回到 PLANNED","PLANNED",statusOf(effectId,1));
            assertEquals("EXECUTING",verdict.path("status").asText());
            assertEquals("检查点仍指向批次 0",0,verdict.path("checkpoint").path("batchIndex").asInt());
            assertEquals("实际进度仍为 1/3",1,verdict.path("persistedCount").asInt());
            ObjectNode c1again=claim(afterRestart,effectId,1,fixture.db.queryForObject("select l.fencing_token from ai_agent_lease l join ai_agent_run r on r.run_id=l.run_id where r.run_id=?",Long.class,runId));
            assertFalse("重新领取必须获得新令牌",c1again.path("claimToken").asText().equals(c1.path("claimToken").asText()));
            afterRestart.receiptBatch(effectId,1,1,receiptOf(c1again,true,readbackOf(c1again)));
            assertEquals("PERSISTED",statusOf(effectId,1));
            ObjectNode c2=claim(afterRestart,effectId,2,fixture.db.queryForObject("select l.fencing_token from ai_agent_lease l join ai_agent_run r on r.run_id=l.run_id where r.run_id=?",Long.class,runId));
            ObjectNode done=afterRestart.receiptBatch(effectId,2,1,receiptOf(c2,true,readbackOf(c2)));
            assertEquals("COMPLETED",done.path("status").asText());
            assertEquals("PERSISTED",fixture.db.queryForObject("select status from ai_agent_effect where effect_id=?",String.class,effectId));
            Long version=fixture.db.queryForObject("select version from ai_workbook_working_state where workbook_id=?",Long.class,workbook);
            assertEquals("三个批次各恰好一次 CAS（版本 0,1,2）",Long.valueOf(2L),version);
            JsonNode sheet=parse(String.valueOf(fixture.status(workbook).get("content"))).path("sheets").path("s");
            assertEquals("v1249-1",sheet.path("cellData").path("1249").path("1").path("v").asText());
            assertEquals("检查点序列 = 初始 + 3 批",4,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=?",Integer.class,effectId).intValue());
        }finally{scheduler.destroy();}
    }
    /** D① 跨重启变体：提交已落库但客户端未收到响应，重启后同一回执重发必须幂等且不二次应用。 */
    public void testRestartedInstanceReplaysLostReceiptExactlyOnce() throws Exception {
        ObjectNode created=svc.create(batchedRequest(),1);
        String effectId=created.path("effectId").asText();
        long fence0=fence();
        ObjectNode c0=claim(svc,effectId,0,fence0);
        svc.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0))); // 响应在返回途中“丢失”
        BatchWriteService afterRestart=newService();
        ObjectNode retry=afterRestart.receiptBatch(effectId,0,1,receiptOf(c0,true,readbackOf(c0)));
        assertEquals("PERSISTED",retry.path("batches").get(0).path("status").asText());
        Long version=fixture.db.queryForObject("select version from ai_workbook_working_state where workbook_id=?",Long.class,workbook);
        assertEquals("重启后重发不得二次应用",Long.valueOf(0L),version);
        assertEquals("重启后重发不得写第二个检查点",1,fixture.db.queryForObject("select count(*) from ai_agent_checkpoint where effect_id=? and batch_index=0",Integer.class,effectId).intValue());
    }
    /** 服务端核实为已保存：UNKNOWN 批次在草稿哈希等于 expected_after 时由服务端宣布 PERSISTED（客户端失败声明被推翻）。 */
    public void testServerVerifiedPersistedOverrulesClientFailureDeclaration(){
        ObjectNode created=svc.create(batchedRequest(),1);
        String effectId=created.path("effectId").asText();
        long fence0=fence();
        ObjectNode c0=claim(svc,effectId,0,fence0);
        // 服务端期望的批次 0 结果内容：与 receiptBatch 将保存的完全一致。
        ObjectNode manifest=(ObjectNode)parse(fixture.db.queryForObject("select manifest_json from ai_agent_write_batch where effect_id=? and batch_index=0",String.class,effectId));
        ObjectNode expectedContent=WriteManifest.apply(parse(original),manifest);
        Map<String,Object> seed=new HashMap<>();seed.put("workbookId",workbook);
        seed.put("branchId",fixture.db.queryForObject("select current_branch_id from ai_workbook where workbook_id=?",Long.class,workbook));
        seed.put("ownerUserId",1L);seed.put("baseCommitId",null);seed.put("draftActionsJson","[]");seed.put("executedChangesJson","[]");
        seed.put("content",expectedContent.toString().getBytes(StandardCharsets.UTF_8));
        seed.put("contentHash",com.ruoyi.ai.util.ContentHashUtils.sha256(expectedContent.toString()));
        seed.put("expectedDraftVersion",-1L);seed.put("expectedContentHash",com.ruoyi.ai.util.ContentHashUtils.sha256(original));
        assertEquals("按批次期望内容种入草稿",1,fixture.tx.execute(s->fixture.versions.insertWorkingState(seed)).intValue());
        ObjectNode failed=svc.receiptBatch(effectId,0,1,receiptOf(c0,false,null)); // 客户端单方面声称失败
        assertEquals("UNKNOWN",statusOf(effectId,0));
        assertEquals("PAUSED",failed.path("status").asText());
        ObjectNode verdict=svc.reconcile(effectId,1,obj().put("transportVersion",2));
        assertEquals("服务端核实草稿哈希等于 expected_after → PERSISTED","PERSISTED",statusOf(effectId,0));
        assertEquals("核实后任务回到 EXECUTING","EXECUTING",verdict.path("status").asText());
        assertEquals("检查点推进到批次 0",0,verdict.path("checkpoint").path("batchIndex").asInt());
        ObjectNode c1=claim(svc,effectId,1,fence0); // 租约未死，续跑下一批
        svc.receiptBatch(effectId,1,1,receiptOf(c1,true,readbackOf(c1)));
        assertEquals("PERSISTED",statusOf(effectId,1));
    }
}
