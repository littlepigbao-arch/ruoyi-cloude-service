package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.A1TestFixture;
import com.ruoyi.ai.agent.AgentStore;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1-review F1 red tests: inline artifacts must not bypass the artifact state gate.
 * AgentStore.artifactRecord (and therefore DatasetReader / AgentTools dataset tools) used to parse
 * inline content_json directly, so an INVALIDATED or EXPIRED inline artifact was still served there
 * even though ArtifactService metadata reads refused it. Red first on the current code.
 */
public class ArtifactGateMysqlTest extends TestCase {
    private A1TestFixture fixture; private ArtifactService artifacts; private AgentStore store; private DatasetReader reader; private ObjectNode run;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C1-review schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_agent_dataset_v2.sql")),StandardCharsets.UTF_8);
        for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
            String trimmed=statement.trim();if(trimmed.isEmpty())continue;
            try{fixture.db.execute(trimmed);}
            catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate column"))throw alreadyMigrated;}
        }
        ObjectNode snapshot=(ObjectNode)parse("{\"id\":\"p\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Source\",\"rowCount\":3,\"columnCount\":2,\"cellData\":{\"0\":{\"0\":{\"v\":\"k\"}}}}}}");
        long w=fixture.seed(snapshot.toString()),b=fixture.request(w,-1,null,snapshot.toString()).getBranchId();
        run=obj().put("runId",UUID.randomUUID().toString()).put("userId",1L).put("workbookId",w).put("branchId",b).put("revision",hash(snapshot));
        fixture.db.update("insert into ai_agent_run(run_id,user_id,workbook_id,branch_id,request_id,request_hash,status,state_json,row_version,updated_at,created_at) values(?,?,?,?,?,?,'PAUSED',?,0,now(3),now(3))",run.path("runId").asText(),1,w,b,UUID.randomUUID().toString(),hash(run),run.toString());
        artifacts=new ArtifactService(fixture.db,fixture.tx.getTransactionManager());
        store=new AgentStore(fixture.db);reader=new DatasetReader(store);
    }
    @Override protected void tearDown(){fixture.close();}
    /** Legacy-shape inline artifact with full workbook/branch/run lineage so artifactRecord's join passes. */
    private String inlineArtifact(String contentJson){
        String id=UUID.randomUUID().toString();
        fixture.db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,workbook_id,branch_id,schema_name,storage_kind,status,created_at) values(?,?,?,?,?,?,?,?, 'inline','ACTIVE',now(3))",
            id,run.path("runId").asText(),1,run.path("revision").asText(),contentJson,run.path("workbookId").asLong(),run.path("branchId").asLong(),DatasetService.SCHEMA_NAME);
        return id;
    }
    private ObjectNode reference(String id){return obj().put("resultRef",id).put("sourceRevision",run.path("revision").asText());}
    private void rejectedByGate(Runnable action,String expectedPrefix){
        try{action.run();fail("Inline artifact bypassed the artifact state gate");}
        catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith(expectedPrefix));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}
    }
    /** a) INVALIDATED inline artifact must be refused by artifactRecord and DatasetReader, not just metadata reads. */
    public void testInvalidatedInlineArtifactRefusedOnRecordAndDatasetRead(){
        String id=inlineArtifact("{\"columns\":[\"k\"],\"rows\":[{\"k\":1}]}");
        assertEquals(1,store.artifactRecord(run,id).path("data").path("rows").size()); // sanity: gate open while ACTIVE
        artifacts.markInvalidated(id,1);
        rejectedByGate(()->store.artifactRecord(run,id),"ARTIFACT_INVALIDATED");
        rejectedByGate(()->reader.dataset(run,reference(id)),"ARTIFACT_INVALIDATED");
        rejectedByGate(()->artifacts.readArtifactMetadata(id,1),"ARTIFACT_INVALIDATED");
    }
    /** b) EXPIRED inline artifact (ACTIVE row past expires_at) must be refused by artifactRecord and DatasetReader. */
    public void testExpiredInlineArtifactRefusedOnRecordAndDatasetRead(){
        String id=inlineArtifact("{\"columns\":[\"k\"],\"rows\":[{\"k\":1}]}");
        fixture.db.update("update ai_analysis_artifact set expires_at=now(3)-interval 1 hour where artifact_id=?",id);
        rejectedByGate(()->store.artifactRecord(run,id),"ARTIFACT_EXPIRED");
        rejectedByGate(()->reader.dataset(run,reference(id)),"ARTIFACT_EXPIRED");
        rejectedByGate(()->artifacts.readArtifactMetadata(id,1),"ARTIFACT_EXPIRED");
    }
    /** c) ACTIVE inline artifact stays readable through both paths: the gate must not over-block the legacy lane. */
    public void testActiveInlineArtifactStillReadableThroughBothPaths(){
        String id=inlineArtifact("{\"columns\":[\"k\"],\"rows\":[{\"k\":7}]}");
        assertEquals(7,store.artifactRecord(run,id).path("data").path("rows").get(0).path("k").asInt());
        assertEquals(7,reader.dataset(run,reference(id)).path("rows").get(0).path("k").asInt());
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
    }
}
