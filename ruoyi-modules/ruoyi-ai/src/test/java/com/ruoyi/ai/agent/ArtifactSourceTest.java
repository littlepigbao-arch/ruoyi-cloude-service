package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Uses real isolated MySQL and authoritative metadata, not an in-memory artifact store. */
public class ArtifactSourceTest extends TestCase {
    private A1TestFixture f; private AgentStore store; private ObjectNode run;
    protected void setUp() throws Exception {
        f=new A1TestFixture();store=new AgentStore(f.db);long w=f.seed("{}");long b=f.request(w,-1,null,"{}").getBranchId();
        run=obj().put("runId",UUID.randomUUID().toString()).put("userId",1).put("workbookId",w).put("branchId",b).put("revision",hash(parse("{}")));
        f.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'PAUSED',?,0,now(3),now(3))",run.path("runId").asText(),1,w,b,UUID.randomUUID().toString(),hash(run),run.toString());
    }
    private String artifact() { return store.artifact(run,parse("{\"columns\":[\"amount\"],\"rows\":[{\"amount\":7}]}")); }
    private void rejected(Runnable r) { try { r.run();fail("Untrusted artifact accepted"); } catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); } }
    public void testForgedRequestRevisionCannotRebindOldArtifact() { String id=artifact();run.put("revision","new");ObjectNode a=obj();a.set("dataset",obj().put("resultRef",id).put("sourceRevision","new"));a.set("groupBy",arr());a.set("sums",arr().add("amount"));rejected(()->new AgentTools(store).execute(run,"aggregate_table",a,obj())); }
    public void testAuthoritativeRevisionReturnedAndValidContentRemainsUsable() { String id=artifact();assertEquals(hash(parse("{}")),store.artifactRecord(run,id).path("sourceRevision").asText());assertEquals(run.path("workbookId"),store.artifactRecord(run,id).path("workbookId"));assertEquals(7,store.artifact(run,id).path("rows").get(0).path("amount").asInt());ObjectNode a=obj();a.set("dataset",obj().put("resultRef",id).put("sourceRevision",hash(parse("{}"))));a.set("groupBy",arr());a.set("sums",arr().add("amount"));assertEquals("PASSED",new AgentTools(store).execute(run,"aggregate_table",a,obj()).path("validation").asText()); }
    public void testOtherUserAndRunCannotAccess() { String id=artifact();ObjectNode other=run.deepCopy();other.put("userId",2);rejected(()->store.artifact(other,id));other.put("userId",1).put("runId",UUID.randomUUID().toString());rejected(()->store.artifact(other,id)); }
    public void testWorkbookOrBranchContextCannotBeForged() { String id=artifact();run.put("workbookId",run.path("workbookId").asLong()+100000);rejected(()->store.artifact(run,id)); }
    public void testBranchContextAndRevokedBranchPermissionRejected() { String id=artifact();ObjectNode other=run.deepCopy();other.put("branchId",run.path("branchId").asLong()+100000);rejected(()->store.artifact(other,id));f.db.update("update ai_workbook_branch set status='archived' where branch_id=?",run.path("branchId").asLong());rejected(()->store.artifact(run,id)); }
    public void testRevokedWorkbookPermissionRejectsArtifact() { String id=artifact();f.db.update("update ai_workbook set user_id=2 where workbook_id=?",run.path("workbookId").asLong());rejected(()->store.artifact(run,id)); }
}
