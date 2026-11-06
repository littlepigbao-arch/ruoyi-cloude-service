package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.A1TestFixture;
import com.ruoyi.ai.util.ContentHashUtils;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** C1a chunked artifact paging against real isolated MySQL. Red first: ArtifactService does not exist yet. */
public class ArtifactPagingMysqlTest extends TestCase {
    private A1TestFixture fixture; private ArtifactService artifacts;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C1a schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        // C1a migration re-applies per test; only the duplicate-column outcome of an already migrated schema is tolerated
        // (MySQL 1060 may surface as either DataIntegrityViolationException or BadSqlGrammarException depending on translation).
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_agent_dataset_v2.sql")),StandardCharsets.UTF_8);
        for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
            String trimmed=statement.trim();if(trimmed.isEmpty())continue;
            try{fixture.db.execute(trimmed);}
            catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate column"))throw alreadyMigrated;}
        }
        artifacts=new ArtifactService(fixture.db,fixture.tx.getTransactionManager());
    }
    private void rejected(Runnable action){try{action.run();fail("Unsafe artifact access accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("ARTIFACT_"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}}
    private ArrayNode rows(int total){ArrayNode rows=arr();for(int i=0;i<total;i++)rows.add(obj().put("idx",i).put("value","row-"+i));return rows;}
    private String write(String runId,int total,Date expiresAt){return artifacts.writeArtifact(runId,1,101,202,"orders","rev-"+runId,rows(total),expiresAt).path("id").asText();}
    public void testChunkedPagingWalksEveryRowOnceInStableOrder(){
        String id=write("run-c1a-paging",12000,null);Long cursor=null;int seen=0,expected=0,pages=0;
        List<Integer> rowStarts=new ArrayList<>(),rowCounts=new ArrayList<>();
        while(true){
            ObjectNode page=artifacts.readChunkPage(id,1,cursor);pages++;assertEquals(pages-1,page.path("chunkIndex").asInt());
            rowStarts.add(page.path("rowStart").asInt());rowCounts.add(page.path("rowCount").asInt());
            for(JsonNode row:page.path("rows")){assertEquals("row order must be stable across pages",expected,row.path("idx").asInt());expected++;}
            seen+=page.path("rows").size();
            if(page.path("nextCursor").isNull())break;
            cursor=page.path("nextCursor").asLong();
        }
        assertEquals(12000,seen);assertEquals(3,pages);
        assertEquals(Arrays.asList(0,5000,10000),rowStarts);assertEquals(Arrays.asList(5000,5000,2000),rowCounts);
    }
    public void testLegacyInlineArtifactDualRead(){
        String id=UUID.randomUUID().toString();
        fixture.db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,created_at) values(?,?,?,?,?,now(3))",id,"run-c1a-legacy",1,"rev-old","{\"columns\":[\"amount\"],\"rows\":[{\"amount\":7}]}");
        ObjectNode meta=artifacts.readArtifactMetadata(id,1);
        assertEquals("inline",meta.path("storageKind").asText());assertEquals("ACTIVE",meta.path("status").asText());assertEquals(0,meta.path("chunkCount").asInt());assertEquals("rev-old",meta.path("sourceRevision").asText());
        assertEquals(7,artifacts.readArtifactForLegacy(id,1).path("rows").get(0).path("amount").asInt());
        rejected(()->artifacts.readChunkPage(id,1,null));
    }
    public void testExpiredArtifactRejectedAndPinnedSurvives(){
        Date past=new Date(System.currentTimeMillis()-3600000L);
        String expired=write("run-c1a-ttl",10,past);
        rejected(()->artifacts.readArtifactMetadata(expired,1));
        // C1r-F5 契约更新（原断言"读拒绝且 status 变 EXPIRED"）：读路径零突变，读拒绝后 status 保持 ACTIVE；
        // EXPIRED 物化只发生在 sweepInvalidated 的独立事务中。
        assertEquals("读拒绝零突变：读后 status 仍 ACTIVE","ACTIVE",fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,expired));
        artifacts.sweepInvalidated(101);
        assertEquals("sweep 后物化 EXPIRED","EXPIRED",fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,expired));
        rejected(()->artifacts.readChunkPage(expired,1,null));rejected(()->artifacts.readArtifactForLegacy(expired,1));
        String pinned=write("run-c1a-ttl-pin",10,past);
        assertEquals("PINNED",artifacts.pinArtifact(pinned,1).path("status").asText());
        assertEquals("PINNED",artifacts.readArtifactMetadata(pinned,1).path("status").asText());
        assertEquals(10,artifacts.readChunkPage(pinned,1,null).path("rows").size());
        assertEquals(10,artifacts.readArtifactForLegacy(pinned,1).size());
    }
    public void testOtherUserIsRejected(){
        String id=write("run-c1a-owner",10,null);
        rejected(()->artifacts.readArtifactMetadata(id,2));rejected(()->artifacts.readChunkPage(id,2,null));
        rejected(()->artifacts.readArtifactForLegacy(id,2));rejected(()->artifacts.pinArtifact(id,2));rejected(()->artifacts.markInvalidated(id,2));
        assertEquals(10,artifacts.readChunkPage(id,1,null).path("rows").size());
    }
    public void testTamperedChunkRejectedOnRead(){
        String id=write("run-c1a-tamper",10000,null);
        assertEquals(5000,artifacts.readChunkPage(id,1,1L).path("rows").size());
        fixture.db.update("update ai_analysis_artifact_chunk set content_blob='[{\"idx\":999999}]' where artifact_id=? and chunk_index=1",id);
        rejected(()->artifacts.readChunkPage(id,1,1L));
        assertEquals(5000,artifacts.readChunkPage(id,1,null).path("rows").size());
        rejected(()->artifacts.readArtifactForLegacy(id,1));
    }
    public void testInvalidatedArtifactIsRejected(){
        String id=write("run-c1a-invalidate",10,null);
        assertEquals("INVALIDATED",artifacts.markInvalidated(id,1).path("status").asText());
        rejected(()->artifacts.readArtifactMetadata(id,1));rejected(()->artifacts.readChunkPage(id,1,null));rejected(()->artifacts.readArtifactForLegacy(id,1));
    }
    public void testMetadataMatchesStoredChunks(){
        String id=artifacts.writeArtifact("run-c1a-meta",7,3001,4002,"orders","rev-meta",rows(12000),null).path("id").asText();
        ObjectNode meta=artifacts.readArtifactMetadata(id,7);
        List<String> blobs=fixture.db.queryForList("select content_blob from ai_analysis_artifact_chunk where artifact_id=? order by chunk_index",String.class,id);
        String joined=String.join("",blobs);
        assertEquals("chunked",meta.path("storageKind").asText());assertEquals("ACTIVE",meta.path("status").asText());
        assertEquals("run-c1a-meta",meta.path("runId").asText());assertEquals("rev-meta",meta.path("sourceRevision").asText());
        assertEquals(12000,meta.path("rowCount").asInt());
        assertEquals(joined.getBytes(StandardCharsets.UTF_8).length,meta.path("byteSize").asLong());
        assertEquals(ContentHashUtils.sha256(joined),meta.path("contentHash").asText());
        assertEquals(3,meta.path("chunkCount").asInt());assertTrue("permanent artifact has no expiry",meta.path("expiresAt").isNull());
        assertFalse("metadata must never carry full content",meta.has("content")||meta.has("rows")||meta.has("contentJson"));
    }
    private void sequenceRejected(String id,Long cursor){
        try{artifacts.readChunkPage(id,1,cursor);fail("Broken chunk sequence silently served");}
        catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_CHUNK_SEQUENCE_INVALID"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of sequence guard: "+db.getMessage());}
    }
    /** C1r-F3 red: deleting the middle chunk of a 12000-row/3-chunk artifact must fail paging explicitly, not silently skip 5000 rows. */
    public void testMissingMiddleChunkFailsPagingExplicitly(){
        String id=write("run-c1r-f3-mid",12000,null);
        fixture.db.update("delete from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=1",id);
        sequenceRejected(id,null); // the walk must fail on its first page, not serve 0/2 and end 5000 rows short
        try{artifacts.readChunkPage(id,1,1L);fail("Dangling nextCursor of a deleted chunk accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("ARTIFACT_"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}
    }
    /** C1r-F3 red: missing first chunk must fail the first page (min-index walk must not silently start at 1). */
    public void testMissingFirstChunkFailsPagingExplicitly(){
        String id=write("run-c1r-f3-first",12000,null);
        fixture.db.update("delete from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=0",id);
        sequenceRejected(id,null);
    }
    /** C1r-F3 red: missing last chunk must fail the first page (chunk count vs row_count), not end the walk 2000 rows short. */
    public void testMissingLastChunkFailsPagingExplicitly(){
        String id=write("run-c1r-f3-last",12000,null);
        fixture.db.update("delete from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=2",id);
        sequenceRejected(id,null);
    }
    /** C1r-F3 red: a later page whose row_start/row_count disagree with its index must fail, not serve shifted rows. */
    public void testTamperedChunkBoundaryOnLaterPageFails(){
        String id=write("run-c1r-f3-shift",12000,null);
        fixture.db.update("update ai_analysis_artifact_chunk set row_start=9999 where artifact_id=? and chunk_index=1",id);
        sequenceRejected(id,1L);
    }
    /** C1r-F4 red: cursors outside the lossless int range must be rejected - 2^32 used to intValue()-narrow to 0 and serve the first chunk again. */
    public void testOverflowingAndNegativeCursorRejected(){
        String id=write("run-c1r-f4-cursor",12000,null);
        for(long bad:new long[]{4294967296L,-1L,(long)Integer.MAX_VALUE+1L}){
            try{artifacts.readChunkPage(id,1,bad);fail("Lossy cursor accepted: "+bad);}
            catch(IllegalArgumentException expected){assertTrue("unexpected message for "+bad+": "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_CURSOR_INVALID"));}
            catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of cursor guard: "+db.getMessage());}
        }
        assertEquals(5000,artifacts.readChunkPage(id,1,0L).path("rows").size()); // 0 stays a valid first-chunk page
        assertEquals(2000,artifacts.readChunkPage(id,1,2L).path("rows").size()); // int-range boundary value stays valid
    }
    private String statusOf(String id){return fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,id);}
    /** C1r-F5 red: a refused read must leave no durable mutation behind - neither inside a committed outer
     *  transaction (old code materialized EXPIRED as a read side effect) nor after a rolled-back one (old
     *  code's flip was undone, proving the refusal was never transaction-independent). Materialization is
     *  sweep-only; the refusal itself survives every transaction boundary because it is a fresh comparison. */
    public void testRefusedReadNeverMutatesStatusAndSweepMaterializesExpiry(){
        Date past=new Date(System.currentTimeMillis()-3600000L);
        String expired=write("run-c1r-f5-ttl",10,past);
        fixture.tx.execute(s->{try{artifacts.readArtifactMetadata(expired,1);}catch(IllegalArgumentException refused){}return null;}); // outer tx commits
        assertEquals("已提交外层事务中被拒绝的读不得物化 EXPIRED","ACTIVE",statusOf(expired));
        try{fixture.tx.execute(s->artifacts.readChunkPage(expired,1,null));fail("Expired artifact read accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("ARTIFACT_EXPIRED"));} // outer tx rolls back
        assertEquals("回滚外层事务后 status 未被翻转也未被复原（从未突变）","ACTIVE",statusOf(expired));
        rejected(()->artifacts.readArtifactMetadata(expired,1)); // refusal persists, decided by comparison
        artifacts.sweepInvalidated(101);
        assertEquals("物化仅经 sweep 独立事务","EXPIRED",statusOf(expired));
        rejected(()->artifacts.readArtifactMetadata(expired,1));
    }
    /** C1r2-F3 red (review counterexample): the full-sequence check ran on the first page only - after
     *  page 1 is served, deleting the tail chunk let page 2 serve 5000 rows and end with nextCursor=null
     *  as if the artifact ended at 10000 rows. Page 2 must fail explicitly instead. */
    public void testDeletedTailChunkAfterFirstPageFailsSecondPage(){
        String id=write("run-c1r2-f3-page2",12000,null);
        assertEquals(5000,artifacts.readChunkPage(id,1,null).path("rows").size()); // first page served fine
        fixture.db.update("delete from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=2",id);
        sequenceRejected(id,1L); // second page must fail, not serve 5000 and end 2000 rows short
        sequenceRejected(id,null); // re-walking from the first page must fail too
    }
    /** C1r2-F3 red (review counterexample): chunk content replaced with [] and the chunk content_hash
     *  re-synced while the declared row_count stays - the page claimed 1 row but served 0. The parsed
     *  rows size must equal the chunk's declared row_count. */
    public void testEmptyArrayBlobWithSyncedChunkHashFailsPage(){
        String id=write("run-c1r2-f3-emptyblob",1,null);
        assertEquals(1,artifacts.readChunkPage(id,1,null).path("rows").size()); // sanity before tamper
        fixture.db.update("update ai_analysis_artifact_chunk set content_blob='[]',content_hash=? where artifact_id=? and chunk_index=0",ContentHashUtils.sha256("[]"),id);
        try{artifacts.readChunkPage(id,1,null);fail("Empty-array blob with synced hash and kept row_count accepted");}
        catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_CHUNK_SEQUENCE_INVALID")&&expected.getMessage().contains("声明"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of content guard: "+db.getMessage());}
    }
}
