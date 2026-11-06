package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.A1TestFixture;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Date;

/**
 * C1-review round-2 F5 red tests: status materialization (sweepInvalidated / markInvalidated) must run
 * in its own REQUIRES_NEW transaction so an outer caller's rollback can never resurrect ACTIVE status.
 * Every artifact here is committed by its own write transaction BEFORE the outer fixture.tx opens, per
 * the review constraint. All tests use the plain new A1TestFixture() wiring - a REQUIRES_NEW inner
 * transaction needs a second physical connection, which the SingleConnectionDataSource reuse mode
 * cannot provide (it would self-deadlock on the one shared connection).
 */
public class ArtifactMaterializationMysqlTest extends TestCase {
    private A1TestFixture fixture; private ArtifactService artifacts;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C1-review schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_agent_dataset_v2.sql")),StandardCharsets.UTF_8);
        for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
            String trimmed=statement.trim();if(trimmed.isEmpty())continue;
            try{fixture.db.execute(trimmed);}
            catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate column"))throw alreadyMigrated;}
        }
        artifacts=new ArtifactService(fixture.db,fixture.tx.getTransactionManager());
    }
    @Override protected void tearDown(){fixture.close();}
    private String write(String runId,int total,Date expiresAt){
        com.fasterxml.jackson.databind.node.ArrayNode rows=com.ruoyi.ai.agent.AgentJson.arr();
        for(int i=0;i<total;i++)rows.add(com.ruoyi.ai.agent.AgentJson.obj().put("idx",i));
        return artifacts.writeArtifact(runId,1,101,202,"orders","rev-"+runId,rows,expiresAt).path("id").asText();
    }
    private String statusOf(String id){return fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,id);}
    /** 反例6 red: sweepInvalidated inside an outer transaction that rolls back - the materialized EXPIRED
     *  status must survive (old REQUIRED propagation joined the outer transaction and resurrected ACTIVE). */
    public void testSweepMaterializationSurvivesOuterRollback(){
        Date past=new Date(System.currentTimeMillis()-3600000L);
        String expired=write("run-c1r2-f5-sweep",10,past); // committed in its own transaction beforehand
        fixture.tx.execute(s->{artifacts.sweepInvalidated(101);s.setRollbackOnly();return null;}); // outer transaction rolls back
        assertEquals("物化必须独立于外层事务回滚","EXPIRED",statusOf(expired));
    }
    /** 反例7 red: markInvalidated inside an outer transaction that rolls back - INVALIDATED must survive
     *  (old code had no transaction boundary and joined the caller's transaction). */
    public void testMarkInvalidationSurvivesOuterRollback(){
        String id=write("run-c1r2-f5-mark",10,null); // committed in its own transaction beforehand
        fixture.tx.execute(s->{artifacts.markInvalidated(id,1);s.setRollbackOnly();return null;}); // outer transaction rolls back
        assertEquals("失效物化必须独立于外层事务回滚","INVALIDATED",statusOf(id));
    }
    /** 追加 red: outer transaction holds the artifact row lock - sweep's independent transaction must fail
     *  within a bounded lock wait (session innodb_lock_wait_timeout=5), never hang; after the outer rollback
     *  nothing was materialized and the read path still refuses by comparison (zero-mutation gate backstop).
     *  Red semantics: old REQUIRED propagation reused the lock-holding transaction, so sweep succeeded with
     *  no conflict at all. */
    public void testSweepUnderOuterRowLockFailsBoundedNotHang(){
        Date past=new Date(System.currentTimeMillis()-3600000L);
        String expired=write("run-c1r2-f5-lock",10,past); // committed in its own transaction beforehand
        long start=System.currentTimeMillis();
        try{
            fixture.tx.execute(s->{
                fixture.db.queryForList("select artifact_id from ai_analysis_artifact where artifact_id=? for update",expired); // outer tx owns the row lock
                artifacts.sweepInvalidated(101); // REQUIRES_NEW must block on the locked row and fail bounded
                return null;});
            fail("Sweep 未与持锁外层事务冲突（未走独立事务），或有界等待后仍然成功");
        }catch(org.springframework.dao.DataAccessException bounded){
            long elapsed=System.currentTimeMillis()-start;
            assertTrue("锁等待必须有界(<=10s)，实际 "+elapsed+"ms",elapsed<=10000L);
        }
        assertEquals("锁冲突时物化不得落地","ACTIVE",statusOf(expired));
        try{artifacts.readArtifactMetadata(expired,1);fail("Expired artifact read accepted");}
        catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_EXPIRED"));}
    }
}
