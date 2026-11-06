package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.A1TestFixture;
import com.ruoyi.ai.agent.AgentStore;
import com.ruoyi.ai.agent.WriteManifest;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1b dataset service + chunked envelope round-trip against real isolated MySQL.
 * Red first: DatasetService/DatasetReader and ArtifactService.writeArtifactEnvelope do not exist yet.
 * setUp mirrors ArtifactPagingMysqlTest (re-applies ai_agent_dataset_v2.sql tolerating duplicate columns,
 * so the C1b lineage_json ALTER reaches databases already migrated by C1a) and seeds a run row like B9.
 */
public class DatasetServiceTest extends TestCase {
    private A1TestFixture fixture; private AgentStore store; private ObjectNode run, snapshot;
    private DatasetService datasets; private DatasetReader reader;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C1b schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_agent_dataset_v2.sql")),StandardCharsets.UTF_8);
        for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
            String trimmed=statement.trim();if(trimmed.isEmpty())continue;
            try{fixture.db.execute(trimmed);}
            catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate column"))throw alreadyMigrated;}
        }
        snapshot=(ObjectNode)parse("{\"id\":\"p\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Source\",\"rowCount\":6,\"columnCount\":6,\"cellData\":{\"0\":{\"0\":{\"v\":\"Name\"},\"1\":{\"v\":\"Amount\"}},\"1\":{\"0\":{\"v\":\"A\"},\"1\":{\"v\":7}},\"2\":{\"0\":{\"v\":\"B\"},\"1\":{\"v\":9.5}}}}}}");
        long w=fixture.seed(snapshot.toString()),b=fixture.request(w,-1,null,snapshot.toString()).getBranchId();
        run=obj().put("runId",UUID.randomUUID().toString()).put("userId",1L).put("workbookId",w).put("branchId",b).put("revision",hash(snapshot));
        fixture.db.update("insert into ai_agent_run(run_id,user_id,workbook_id,branch_id,request_id,request_hash,status,state_json,row_version,updated_at,created_at) values(?,?,?,?,?,?,'PAUSED',?,0,now(3),now(3))",run.path("runId").asText(),1,w,b,UUID.randomUUID().toString(),hash(run),run.toString());
        store=new AgentStore(fixture.db);datasets=new DatasetService(store);reader=datasets.reader();
    }
    private ObjectNode datasetArgs(){return obj().put("sheetId","s").put("range","A1:B3").put("headerRow",true).put("includeHidden",true).put("includeFiltered",true).put("formulaPolicy","reject");}
    private ArrayNode lineage(){return arr().add(obj().put("sheetId","s").put("range","A1:B3").put("sheetDigest","digest-stable"));}
    private ObjectNode bigDataset(int total){
        ArrayNode rows=arr();
        for(int i=0;i<total;i++){ObjectNode row=obj().put("k",i);if(i==0)row.putNull("v");else if(i==1)row.put("v",7.5);else row.put("v","row-"+i);rows.add(row);}
        ObjectNode data=obj();data.set("columns",arr().add("k").add("v"));data.set("rows",rows);return data;
    }
    private Map<String,Object> artifactRow(String id){return fixture.db.queryForMap("select storage_kind,content_json,lineage_json,workbook_id,branch_id,schema_name from ai_analysis_artifact where artifact_id=?",id);}
    private void assertRealMetadata(Map<String,Object> row){
        assertEquals(run.path("workbookId").asLong(),((Number)row.get("workbook_id")).longValue());
        assertEquals(run.path("branchId").asLong(),((Number)row.get("branch_id")).longValue());
        assertEquals("agent_dataset",row.get("schema_name"));
    }
    public void testDatasetRoundTripThroughServiceStoreAndReader(){
        ObjectNode built=datasets.dataset(snapshot,datasetArgs());
        assertEquals(parse("[\"Name\",\"Amount\"]"),built.path("columns"));
        assertEquals(2,built.path("rows").size());
        String id=store.artifact(run,built,lineage());
        ObjectNode reference=obj().put("resultRef",id).put("sourceRevision",run.path("revision").asText());
        JsonNode readBack=reader.dataset(run,reference);
        assertEquals(built,readBack);
        assertEquals("A",readBack.path("rows").get(0).path("Name").asText());
        assertEquals(9.5,readBack.path("rows").get(1).path("Amount").asDouble());
    }
    public void testCellBudgetStillCapsAtTwoHundredThousand(){
        // MAX_CELLS stays 200000 (red line): build_dataset guards it via the range() area check and
        // effectiveBounds' per-cell count; the area path is the representative rejection here.
        ObjectNode args=datasetArgs();args.put("range","A1:B100001");
        try{datasets.dataset(snapshot,args);fail("Over-budget dataset build accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("20万"));}
    }
    public void testChunkedEnvelopeDualReadKeepsDigestsRowsAndMetadata(){
        ObjectNode data=bigDataset(5001);
        String id=store.artifact(run,data,lineage());
        Map<String,Object> row=artifactRow(id);
        assertEquals("chunked",row.get("storage_kind"));
        assertRealMetadata(row);
        assertNotNull("chunked envelope keeps lineage in lineage_json",row.get("lineage_json"));
        assertEquals(lineage(),parse(String.valueOf(row.get("lineage_json"))));
        String skeleton=String.valueOf(row.get("content_json"));
        assertTrue(skeleton.contains("\"artifactEnvelopeVersion\":2"));
        assertFalse("skeleton must not inline the chunked rows",skeleton.contains("row-4999"));
        assertEquals(2,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_chunk where artifact_id=?",Integer.class,id).intValue());
        ObjectNode record=store.artifactRecord(run,id);
        assertEquals(data,record.path("data"));
        assertEquals(5001,record.path("data").path("rows").size());
        assertEquals(lineage(),record.path("lineage"));
        assertEquals(WriteManifest.digest(data),record.path("dataDigest").asText());
        assertEquals(WriteManifest.digest(lineage()),record.path("lineageDigest").asText());
    }
    public void testTamperedChunkRefusedOnArtifactRecord(){
        String id=store.artifact(run,bigDataset(5001),lineage());
        fixture.db.update("update ai_analysis_artifact_chunk set content_blob='[{\"k\":9999,\"v\":\"forged\"}]' where artifact_id=? and chunk_index=1",id);
        try{store.artifactRecord(run,id);fail("Tampered chunk accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("ARTIFACT_HASH_MISMATCH"));}
    }
    public void testInlineEnvelopeKeptWholeForSmallDatasets(){
        ObjectNode data=bigDataset(2);
        String id=store.artifact(run,data,lineage());
        Map<String,Object> row=artifactRow(id);
        assertEquals("inline",row.get("storage_kind"));
        assertRealMetadata(row);
        assertNull("inline envelopes keep lineage inside content_json",row.get("lineage_json"));
        JsonNode envelope=parse(String.valueOf(row.get("content_json")));
        assertEquals(2,envelope.path("artifactEnvelopeVersion").asInt());
        assertEquals(2,envelope.path("data").path("rows").size());
        assertEquals(lineage(),envelope.path("lineage"));
        ObjectNode record=store.artifactRecord(run,id);
        assertEquals(data,record.path("data"));
        assertEquals(WriteManifest.digest(data),record.path("dataDigest").asText());
    }
}
