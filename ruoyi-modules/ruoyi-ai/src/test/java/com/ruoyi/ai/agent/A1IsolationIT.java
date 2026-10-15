package com.ruoyi.ai.agent;

import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.common.core.domain.R;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.ai.agent.AgentJson.*;

public class A1IsolationIT extends TestCase {
    private A1TestFixture f;
    protected void setUp() throws Exception { f=new A1TestFixture(); }
    private String hashOf(String content){return com.ruoyi.ai.util.ContentHashUtils.sha256(content);}
    public void testTwoConcurrentPagesOnlyOneSaveSucceeds()throws Exception{
        long id=f.seed("{\"v\":0}");String initial=hashOf("{\"v\":0}");CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Integer> a=pool.submit(()->{start.await();return f.save(id,f.request(id,-1,initial,"{\"v\":1}")).getCode();});
            Future<Integer> b=pool.submit(()->{start.await();return f.save(id,f.request(id,-1,initial,"{\"v\":2}")).getCode();});start.countDown();
            List<Integer> codes=Arrays.asList(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertTrue(codes.contains(200));assertTrue(codes.contains(409));
            assertEquals(0,f.db.queryForObject("select version from ai_workbook_working_state where workbook_id=?",Integer.class,id).intValue());
        }finally{pool.shutdownNow();}
    }
    public void testOldVersionAndForgedLoadedRevisionCannotOverwrite(){
        long id=f.seed("{\"v\":0}");String initial=hashOf("{\"v\":0}");assertEquals(200,f.save(id,f.request(id,-1,initial,"{\"v\":1}")).getCode());
        assertEquals(409,f.save(id,f.request(id,-1,initial,"{\"v\":2}")).getCode());
        assertEquals(409,f.save(id,f.request(id,0,initial,"{\"v\":3}")).getCode());
        assertEquals("{\"v\":1}",f.status(id).get("content"));
        WorkingStateRequest missing=f.request(id,0,null,"{\"v\":4}");missing.setExpectedDraftVersion(null);assertEquals(409,f.save(id,missing).getCode());
        assertEquals("{\"v\":1}",f.status(id).get("content"));
    }
    public void testFirstSaveWithoutLoadedHashRejectedAndVersionedLegacyUpdateCompatible(){
        long id=f.seed("{\"v\":0}");assertEquals(409,f.save(id,f.request(id,-1,null,"{\"v\":1}")).getCode());
        assertNull(f.status(id).get("workingState"));
        assertEquals(200,f.save(id,f.request(id,-1,hashOf("{\"v\":0}"),"{\"v\":1}")).getCode());
        assertEquals(200,f.save(id,f.request(id,0,null,"{\"v\":2}")).getCode());
        assertEquals("{\"v\":2}",f.status(id).get("content"));
    }
    public void testSqlCasAndCurrentReadIgnoreEarlierRepeatableReadView(){
        long id=f.seed("{\"v\":0}");String initial=hashOf("{\"v\":0}");assertEquals(200,f.save(id,f.request(id,-1,initial,"{\"v\":1}")).getCode());
        f.identity();f.tx.execute(s->{
            Map<String,Object> old=f.versions.selectWorkingState(id,f.request(id,0,null,"{}").getBranchId(),1L);assertEquals(0,((Number)old.get("version")).intValue());
            ExecutorService pool=Executors.newSingleThreadExecutor();try{pool.submit(()->f.save(id,f.request(id,0,hashOf("{\"v\":1}"),"{\"v\":2}"))).get(15,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            R<?> stale=f.service.saveWorkingState(id,f.request(id,0,hashOf("{\"v\":1}"),"{\"v\":3}"));assertEquals(409,stale.getCode());return null;
        });assertEquals("{\"v\":2}",f.status(id).get("content"));
        Map<String,Object> state=new HashMap<>();state.put("workbookId",id);state.put("branchId",f.request(id,0,null,"{}").getBranchId());state.put("ownerUserId",1L);state.put("expectedDraftVersion",0L);state.put("expectedContentHash",hashOf("{\"v\":1}"));state.put("content","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));state.put("contentHash",hashOf("{}"));
        assertEquals(0,f.tx.execute(s->f.versions.updateWorkingStateCas(state)).intValue());assertEquals("{\"v\":2}",f.status(id).get("content"));
    }
    public void testDisabledFeatureRetainsLeaseAndOldRunCanReconcileCancel()throws Exception{
        long id=f.seed("{\"v\":0}");long branch=f.request(id,-1,null,"{}").getBranchId();
        ObjectNode run=obj().put("runId",UUID.randomUUID().toString()).put("workbookId",id).put("branchId",branch).put("userId",1).put("version",0).put("eventSeq",0).put("status","PAUSED").put("fencingToken",1).put("revision",hash(parse("{\"v\":0}")));
        run.set("call",obj().put("callId","old-call").put("status","UNKNOWN"));
        f.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'PAUSED',?,0,now(3),now(3))",run.path("runId").asText(),1,id,branch,UUID.randomUUID().toString(),hash(run),run.toString());
        f.db.update("insert into ai_agent_lease values(?,?,1,0)",id,run.path("runId").asText());
        assertFalse(f.guard.enabled());assertTrue(f.guard.recoveryAvailable());
        try{f.save(id,f.request(id,-1,hashOf("{\"v\":0}"),"{}"));fail("Disabled feature bypassed lease");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("活动智能体"));}
        assertEquals("PAUSED",f.runs.get(run.path("runId").asText(),1).path("status").asText());
        assertEquals("CANCELLING",f.runs.cancel(run.path("runId").asText(),1).path("status").asText());
        ObjectNode resolved=f.runs.resume(run.path("runId").asText(),1,obj().put("reloadedServerDraft",true).put("expectedRevision",hash(parse("{\"v\":0}"))));
        assertEquals("CANCELLED",resolved.path("status").asText());assertEquals(0,f.db.queryForObject("select count(*) from ai_agent_lease where workbook_id=? and run_id is not null",Integer.class,id).intValue());
        assertEquals(200,f.save(id,f.request(id,-1,hashOf("{\"v\":0}"),"{\"v\":1}")).getCode());
    }
    public void testSnapshotClaimAndReceiptBlockedBeforePersistence(){
        try{f.runs.claim("any",1,obj());fail("Claim accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("SNAPSHOT_WRITE_BLOCKED"));}
        long id=f.seed("{\"v\":0}");long branch=f.request(id,-1,null,"{}").getBranchId();String rid=UUID.randomUUID().toString();
        ObjectNode run=obj().put("runId",rid).put("workbookId",id).put("branchId",branch).put("userId",1).put("version",0).put("status","EXECUTING");
        f.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))",rid,1,id,branch,UUID.randomUUID().toString(),hash(run),run.toString());
        ObjectNode call=obj().put("callId","old-call").put("status","CLAIMED").put("claimToken","token").put("argsHash","args");
        f.db.update("insert into ai_agent_tool_call values(?,?,'CLAIMED','args',?)",rid,"old-call",call.toString());
        try{f.runs.receipt(rid,1,obj().put("callId","old-call").put("claimToken","token").put("argsHash","args").put("ok",true));fail("Snapshot receipt accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("SNAPSHOT_WRITE_BLOCKED"));}
        assertNull(f.status(id).get("workingState"));assertEquals("{\"v\":0}",f.status(id).get("content"));
    }
    public void testWorkspaceContentAndVersionAreOneTuple(){long id=f.seed("{\"v\":0}");assertEquals(200,f.save(id,f.request(id,-1,hashOf("{\"v\":0}"),"{\"v\":1}")).getCode());ObjectNode state=f.runs.workspace(id,1);assertEquals(0,state.path("draftVersion").asInt());assertEquals(1,state.path("snapshot").path("v").asInt());assertEquals(hashOf("{\"v\":1}"),state.path("loadedRevision").asText());}
    public void testManualCommitAndBranchCheckoutRemainCompatible(){
        long id=f.seed("{\"v\":0}");long main=f.request(id,-1,null,"{}").getBranchId();
        com.ruoyi.ai.domain.dto.CommitRequest commit=new com.ruoyi.ai.domain.dto.CommitRequest();commit.setBranchId(main);commit.setBaseVersion(0L);commit.setOperationId(UUID.randomUUID().toString());commit.setCommitMessage("A1 manual compatibility");commit.setSource("manual");commit.setContent("{\"v\":1}");commit.setExecutedChanges(Collections.singletonList(Collections.singletonMap("kind",(Object)"cell-value")));
        f.identity();R<Map<String,Object>> saved=f.tx.execute(s->f.service.commit(id,commit));assertEquals(200,saved.getCode());String head=String.valueOf(saved.getData().get("commitId"));assertEquals(1,((Number)saved.getData().get("branchVersion")).intValue());assertEquals(1,((Number)saved.getData().get("workbookVersion")).intValue());
        WorkingStateRequest draft=f.request(id,-1,hashOf("{\"v\":1}"),"{\"v\":2}");draft.setBaseVersion(1L);draft.setBaseCommitId(head);assertEquals(200,f.save(id,draft).getCode());
        com.ruoyi.ai.domain.dto.BranchRequest create=new com.ruoyi.ai.domain.dto.BranchRequest();create.setBranchName("draft/a1-"+UUID.randomUUID());create.setFromCommitId(head);create.setOperationId(UUID.randomUUID().toString());
        R<Map<String,Object>> branch=f.tx.execute(s->f.service.createBranch(id,create));assertEquals(200,branch.getCode());long target=((Number)branch.getData().get("branchId")).longValue();
        com.ruoyi.ai.domain.dto.CheckoutRequest checkout=new com.ruoyi.ai.domain.dto.CheckoutRequest();checkout.setCurrentBranchId(main);checkout.setBaseCommitId(head);checkout.setOperationId(UUID.randomUUID().toString());
        R<Map<String,Object>> switched=f.tx.execute(s->f.service.checkout(id,target,checkout));assertEquals(200,switched.getCode());assertEquals("{\"v\":1}",switched.getData().get("content"));
        assertEquals("{\"v\":2}",f.tx.execute(s->f.versions.selectWorkingStateForUpdate(id,main,1L)).get("content"));
    }
}
