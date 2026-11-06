package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.A1TestFixture;
import com.ruoyi.ai.util.ContentHashUtils;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1c dependency registration + lazy upstream invalidation against real isolated MySQL.
 * Invalidations compare the ai_workbook.version counter stamped at registration (source_version), never
 * hash lineages: dependency.source_revision is a run-side tree hash while ai_workbook.content_hash is a
 * raw-string hash, and the two legitimately diverge on non-canonical content (B9AcceptanceMatrixMysqlTest's
 * pretty-print scenario asserts exactly that divergence) - test g is the regression guard for it.
 * setUp mirrors ArtifactPagingMysqlTest (re-applies ai_agent_dataset_v2.sql tolerating duplicate columns)
 * and seeds a workbook/branch/run like DatasetServiceTest's B9-style setup.
 */
public class ArtifactDependencyMysqlTest extends TestCase {
    private A1TestFixture fixture; private ArtifactService artifacts; private ObjectNode run, snapshot;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C1c schema required");
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
        artifacts=new ArtifactService(fixture.db,fixture.tx.getTransactionManager());
    }
    @Override protected void tearDown(){fixture.close();}
    private String envelope(String sourceRevision){return envelopeOf(run.path("runId").asText(),sourceRevision);}
    private String envelopeOf(String runId,String sourceRevision){
        ArrayNode rows=arr();rows.add(obj().put("k","a"));rows.add(obj().put("k","b"));
        ObjectNode data=obj();data.set("columns",arr().add("k"));data.set("rows",rows);
        ArrayNode lineage=arr().add(obj().put("sheetId","s").put("range","A1:B4").put("sheetDigest","digest-stable"));
        return artifacts.writeArtifactEnvelope(runId,1,run.path("workbookId").asLong(),run.path("branchId").asLong(),DatasetService.SCHEMA_NAME,sourceRevision,data,lineage,null).path("id").asText();
    }
    /** 版本推进模拟真实提交指针更新（AiWorkbookMapper.updateVersionPointers：content+content_hash 同步、version+1， 与 B9AcceptanceMatrixMysqlTest 的手工推进一致）；无现成 helper，故直接 SQL 推进。 */
    private void advance(ObjectNode changed){
        String content=changed.toString();
        fixture.db.update("update ai_workbook set content=?,content_hash=?,version=version+1,update_time=now(3) where workbook_id=?",content,ContentHashUtils.sha256(content),run.path("workbookId").asLong());
    }
    private ObjectNode changedSnapshot(){ObjectNode changed=snapshot.deepCopy();((ObjectNode)changed.path("sheets").path("s").path("cellData").path("1").path("1")).put("v",77);return changed;}
    private long workbookVersion(){return fixture.db.queryForObject("select version from ai_workbook where workbook_id=?",Long.class,run.path("workbookId").asLong());}
    private String status(String id){return fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,id);}
    private void rejected(Runnable action){try{action.run();fail("Unsafe artifact access accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("ARTIFACT_"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}}
    private void staleRejected(Runnable action){try{action.run();fail("Stale dependent artifact read accepted");}catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_SOURCE_STALE"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}}
    /** a) dataset envelope 写入即登记依赖（含登记时 workbook 版本计数器）；版本未推进时读正常；行数组路径不登记。 */
    public void testEnvelopeRegistersDependencyAndFreshReadWorks(){
        String id=envelope(run.path("revision").asText());
        Map<String,Object> dep=fixture.db.queryForMap("select source_revision,source_version,dependency_kind,rule_version,created_at from ai_analysis_artifact_dependency where artifact_id=?",id);
        assertEquals(run.path("revision").asText(),dep.get("source_revision"));
        assertEquals(workbookVersion(),((Number)dep.get("source_version")).longValue()); // 登记时的 ai_workbook.version
        assertEquals("dataset_source",dep.get("dependency_kind"));
        assertEquals("1",dep.get("rule_version")); // DatasetService.RULE_VERSION 的落库值
        assertNotNull(dep.get("created_at"));
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        assertEquals(2,artifacts.readArtifactForLegacy(id,1).path("data").path("rows").size());
        ArrayNode rows=arr();for(int i=0;i<3;i++)rows.add(obj().put("k",i));
        String arrayId=artifacts.writeArtifact(run.path("runId").asText(),1,run.path("workbookId").asLong(),run.path("branchId").asLong(),"orders",run.path("revision").asText(),rows,null).path("id").asText();
        assertEquals("行数组路径不登记依赖",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_dependency where artifact_id=?",Integer.class,arrayId).intValue());
    }
    /** b) workbook 版本（计数器，即提交推进通道）推进后失效：读拒绝（ARTIFACT_SOURCE_STALE，C1r-F5 零突变）；
     *  INVALIDATED 物化只经 sweep 独立事务（原断言"读拒绝且状态翻转 INVALIDATED"按 F5 契约更新）。 */
    public void testWorkbookAdvanceLazilyInvalidatesDependentArtifact(){
        String id=envelope(run.path("revision").asText());
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        advance(changedSnapshot());
        staleRejected(()->artifacts.readArtifactMetadata(id,1));
        assertEquals("读拒绝零突变：读后 status 仍 ACTIVE","ACTIVE",status(id));
        assertEquals("sweep 物化 INVALIDATED",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
        rejected(()->artifacts.readArtifactForLegacy(id,1)); // 物化后持续拒绝
        rejected(()->artifacts.readChunkPage(id,1,null));
    }
    /** c) 无依赖登记的制品（直接 inline insert 模拟 B 阶段旧制品，含 workbook_id）在版本推进后读不受影响。 */
    public void testUnregisteredArtifactImmuneToWorkbookAdvance(){
        String id=UUID.randomUUID().toString();
        fixture.db.update("insert into ai_analysis_artifact(artifact_id,run_id,user_id,source_revision,content_json,workbook_id,branch_id,schema_name,storage_kind,status,created_at) values(?,?,?,?,?,?,?,?, 'inline','ACTIVE',now(3))",
            id,run.path("runId").asText(),1,run.path("revision").asText(),"{\"columns\":[\"k\"],\"rows\":[{\"k\":1}]}",run.path("workbookId").asLong(),run.path("branchId").asLong(),DatasetService.SCHEMA_NAME);
        advance(changedSnapshot());
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        assertEquals(1,artifacts.readArtifactForLegacy(id,1).path("rows").size());
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_dependency where artifact_id=?",Integer.class,id).intValue());
    }
    /** d) pin 后版本推进仍失效：pin 只豁免 TTL，不豁免上游失效（C1r-F5：拒绝零突变，物化经 sweep）。 */
    public void testPinDoesNotExemptUpstreamInvalidation(){
        String id=envelope(run.path("revision").asText());
        assertEquals("PINNED",artifacts.pinArtifact(id,1).path("status").asText());
        advance(changedSnapshot());
        staleRejected(()->artifacts.readArtifactMetadata(id,1));
        assertEquals("读拒绝零突变","PINNED",status(id));
        artifacts.sweepInvalidated(run.path("workbookId").asLong());
        assertEquals("INVALIDATED",status(id));
    }
    /** e) 他人读仍报 ARTIFACT_FORBIDDEN：传播在权限校验之后，不泄露失效状态、不触发翻转。 */
    public void testForeignReaderSeesForbiddenNotStaleState(){
        String id=envelope(run.path("revision").asText());
        advance(changedSnapshot());
        try{artifacts.readArtifactMetadata(id,2);fail("foreign read accepted");}
        catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("ARTIFACT_FORBIDDEN"));}
        catch(org.springframework.dao.DataAccessException db){fail("Rejected by database instead of artifact guard: "+db.getMessage());}
        assertEquals("他人读不得触发失效翻转","ACTIVE",status(id));
        staleRejected(()->artifacts.readArtifactMetadata(id,1));
    }
    /** f) sweepInvalidated 与读路径同一双通道判定（C1r-F5：读拒绝不再物化，由 sweep 计数并物化）：
     *  不误伤当前版本制品，已物化的不再重复计，重复 sweep 幂等。 */
    public void testSweepMatchesLazyPropagation(){
        String first=envelope(run.path("revision").asText()),second=envelope(run.path("revision").asText());
        advance(changedSnapshot());
        assertEquals(2,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(first));assertEquals("INVALIDATED",status(second));
        assertEquals("重复 sweep 幂等",0,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        String refused=envelope(hash(changedSnapshot())); // 登记时取当前版本
        advance(changedSnapshot()); // 再推进一次：失效
        staleRejected(()->artifacts.readArtifactMetadata(refused,1)); // 读拒绝（零突变，不物化）
        assertEquals("读拒绝零突变","ACTIVE",status(refused));
        assertEquals("读路径已拒绝的制品由 sweep 计数并物化",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(refused));
        assertEquals("已物化的制品 sweep 不再重复计",0,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        String fresh=envelope(hash(changedSnapshot())); // 登记于最终版本：不失效
        assertEquals("sweep 不得误伤当前版本制品","ACTIVE",artifacts.readArtifactMetadata(fresh,1).path("status").asText());
    }
    /** g) 哈希谱系分叉不误判（生产误判场景回归保护）：登记后仅改 content/content_hash 为非规范形（pretty 串）而 version 不动，
     *  读该制品必须仍正常——source_revision（树哈希）与 content_hash（原始串哈希）本就不可比，失效只比版本计数器。 */
    public void testHashLineageDivergenceDoesNotFalselyInvalidate(){
        String id=envelope(run.path("revision").asText());
        String pretty=snapshot.toPrettyString();
        fixture.db.update("update ai_workbook set content=?,content_hash=? where workbook_id=?",pretty,ContentHashUtils.sha256(pretty),run.path("workbookId").asLong());
        assertFalse("前置：两条哈希谱系确实分叉（run 树哈希 vs pretty 原始串哈希）",
            run.path("revision").asText().equals(fixture.db.queryForObject("select content_hash from ai_workbook where workbook_id=?",String.class,run.path("workbookId").asLong())));
        assertEquals(workbookVersion(),0L); // 版本计数器未推进
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        assertEquals(2,artifacts.readArtifactForLegacy(id,1).path("data").path("rows").size());
        assertEquals("ACTIVE",status(id));
    }
    /** 真实草稿保存链路（B10 saveWorkingState）：首存 v0 必须 200，loadedRevision 为工作簿内容哈希。 */
    private void draftSave(long expectedDraftVersion,String loadedRevision,String content){
        com.ruoyi.ai.domain.dto.WorkingStateRequest request=fixture.request(run.path("workbookId").asLong(),expectedDraftVersion,loadedRevision,content);
        assertEquals("真实草稿保存链路必须成功",200,fixture.save(run.path("workbookId").asLong(),request).getCode());
    }
    private Long draftStamp(String id){
        List<Map<String,Object>> rows=fixture.db.queryForList("select source_draft_version from ai_analysis_artifact_dependency where artifact_id=?",id);
        return rows.isEmpty()?null:((Number)rows.get(0).get("source_draft_version")).longValue();
    }
    /** h) F2 红测：真实草稿保存推进 working_state.version 而 ai_workbook.version 不动——源数据已变，读依赖制品必须拒绝。
     *  现行判定只比 version 通道（放行），双通道实现后按草稿通道拒绝；登记于新草稿版本的制品仍可读。 */
    public void testDraftSaveAdvanceInvalidatesDependentArtifact(){
        String loaded=ContentHashUtils.sha256(snapshot.toString());
        draftSave(-1L,loaded,snapshot.toString()); // 真实链路建立草稿 v0
        String id=envelope(run.path("revision").asText()); // 登记时戳记草稿 v0
        draftSave(0L,loaded,changedSnapshot().toString()); // 真实链路推进草稿 v0→v1（version 计数器不动）
        assertEquals("前置：提交通道未推进",0L,workbookVersion());
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行单通道放行
        rejected(()->artifacts.readArtifactForLegacy(id,1)); // 持续拒绝（物化后消息为 INVALIDATED）
        assertEquals("登记时戳记草稿版本通道",0L,draftStamp(id).longValue());
        // C1r2-F2b 断言更新：草稿已推进后，run 的旧 revision（hash(snapshot)）不再对应当前草稿，
        // 旧 revision 登记会被登记时绑定拒绝；新鲜登记必须携带当前草稿 revision（hash(changedSnapshot)）。
        String fresh=envelope(hash(changedSnapshot())); // 登记于草稿 v1（当前草稿 revision）：不失效
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(fresh,1).path("status").asText());
        assertEquals("新鲜登记戳记当前草稿版本",1L,draftStamp(fresh).longValue());
    }
    /** i) 回归：他人 working_state 行（同工作簿同分支、owner_user_id=2、版本悬殊）不影响本人制品——
     *  传播 join 按 owner 限定；他人行无真实服务路径可达（工作簿为本人所有），此处直插行为 join 回归的最小模拟。 */
    public void testForeignWorkingStateAdvanceDoesNotAffectMyArtifacts(){
        String loaded=ContentHashUtils.sha256(snapshot.toString());
        draftSave(-1L,loaded,snapshot.toString());
        String id=envelope(run.path("revision").asText());
        fixture.db.update("insert into ai_workbook_working_state(workbook_id,branch_id,owner_user_id,base_commit_id,draft_actions_json,executed_changes_json,content_hash,version,update_time) values(?,?,2,null,'[]','[]','foreign',99,now(3))",
            run.path("workbookId").asLong(),run.path("branchId").asLong());
        assertEquals("他人草稿推进不影响本人制品","ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        assertEquals(2,artifacts.readArtifactForLegacy(id,1).path("data").path("rows").size());
        assertEquals("ACTIVE",status(id));
    }
    /** j) C1r-F5 红测：失效拒绝零突变——已提交外层事务内被拒绝的读不得物化 INVALIDATED（旧 update-then-require 会随提交落库）；
     *  拒绝本身由实时比对判定、与事务边界无关；物化仅经 sweep 独立事务。 */
    public void testRefusedReadZeroMutationAndSweepMaterializesInvalidation(){
        String id=envelope(run.path("revision").asText());
        advance(changedSnapshot());
        fixture.tx.execute(s->{try{artifacts.readArtifactMetadata(id,1);}catch(IllegalArgumentException refused){}return null;}); // 外层事务提交
        assertEquals("已提交外层事务中被拒绝的读不得物化 INVALIDATED","ACTIVE",status(id));
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 拒绝由实时比对判定
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        artifacts.sweepInvalidated(run.path("workbookId").asLong());
        assertEquals("物化仅经 sweep 独立事务","INVALIDATED",status(id));
        rejected(()->artifacts.readArtifactMetadata(id,1));
    }
    /** k) C1r2-F2a 红测（审核原始反例）：登记时无 working_state 行（NULL 草稿通道）→ 首次真实 saveWorkingState
     *  创建草稿行（v0）→ 源数据已变，读必须拒绝（旧语义 NULL 永久豁免放行）；sweep 同谓词物化。 */
    public void testFirstDraftSaveAfterNullChannelRegistrationInvalidates(){
        String id=envelope(run.path("revision").asText()); // 登记时尚无草稿行
        assertNull("登记时草稿通道为 NULL",fixture.db.queryForObject("select source_draft_version from ai_analysis_artifact_dependency where artifact_id=?",Object.class,id));
        assertEquals("无草稿时读正常","ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        String loaded=ContentHashUtils.sha256(snapshot.toString());
        draftSave(-1L,loaded,snapshot.toString()); // 真实链路首次创建草稿 v0
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行 NULL 豁免放行
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        assertEquals("sweep 同三态谓词物化",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
    }
    /** l) C1r2-F2b 红测：草稿推进后用旧快照 revision 构造 envelope 登记 → 写路径必须拒绝（SOURCE_STALE 前缀）
     *  且事务回滚零残留（artifact/chunk/dependency 三表按本次 runId 计数全 0）；携带当前草稿 revision 的登记仍成功。 */
    public void testRegisteringOldSnapshotAfterDraftAdvanceRefusedWithZeroResidue(){
        String loaded=ContentHashUtils.sha256(snapshot.toString());
        draftSave(-1L,loaded,snapshot.toString()); // 真实链路建立草稿 v0
        draftSave(0L,loaded,changedSnapshot().toString()); // 真实链路推进草稿 v0→v1（当前草稿为变更内容）
        String attemptRunId=UUID.randomUUID().toString();
        ArrayNode big=arr();for(int i=0;i<5200;i++)big.add(obj().put("k",i)); // 超过单块，登记拒绝必须连块一起回滚
        ObjectNode data=obj();data.set("columns",arr().add("k"));data.set("rows",big);
        ArrayNode lineage=arr().add(obj().put("sheetId","s").put("range","A1:B4").put("sheetDigest","digest-stable"));
        try{
            artifacts.writeArtifactEnvelope(attemptRunId,1,run.path("workbookId").asLong(),run.path("branchId").asLong(),DatasetService.SCHEMA_NAME,run.path("revision").asText(),data,lineage,null);
            fail("旧快照 revision 的依赖制品登记被照单全收");
        }catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("SOURCE_STALE"));}
        assertEquals("零残留：artifact 行未落库",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact where run_id=?",Integer.class,attemptRunId).intValue());
        assertEquals("零残留：chunk 行已回滚",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_chunk c join ai_analysis_artifact a on a.artifact_id=c.artifact_id where a.run_id=?",Integer.class,attemptRunId).intValue());
        assertEquals("零残留：dependency 行已回滚",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_dependency d join ai_analysis_artifact a on a.artifact_id=d.artifact_id where a.run_id=?",Integer.class,attemptRunId).intValue());
        String fresh=envelope(hash(changedSnapshot())); // 正向对照：当前草稿 revision 登记，不得被绑定误伤
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(fresh,1).path("status").asText());
    }
    /** m) C1r2-F2c 红测：登记时有草稿 → working_state 行此后被删除 → 读必须拒绝（fail-closed：来源不复存在，防伪造新鲜）。 */
    public void testDeletingWorkingStateRowInvalidates(){
        String loaded=ContentHashUtils.sha256(snapshot.toString());
        draftSave(-1L,loaded,snapshot.toString()); // 草稿 v0
        String id=envelope(run.path("revision").asText()); // 登记时戳记草稿 v0
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        fixture.db.update("delete from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=1",run.path("workbookId").asLong(),run.path("branchId").asLong());
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行左连 NULL 比较不成立 → 放行
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        assertEquals("sweep 同谓词物化",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
    }
    /** n) C1r2-F2c 红测（追加）：登记无草稿（NULL 通道）→ 删除 ai_workbook 行 → 读必须拒绝（工作簿缺行 fail-closed）。 */
    public void testDeletedSourceWorkbookInvalidatesNullDraftArtifact(){
        String id=envelope(run.path("revision").asText()); // 无草稿登记
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        fixture.db.update("delete from ai_workbook_working_state where workbook_id=?",run.path("workbookId").asLong()); // 本例无草稿行，容忍零行删除
        fixture.db.update("delete from ai_workbook where workbook_id=?",run.path("workbookId").asLong());
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行内连 ai_workbook 缺行即漏检
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        assertEquals("sweep 同谓词物化（artifact.workbook_id 仍可定位）",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
    }
    /** o) C1r2-F2c 红测（追加）：登记无草稿 → 删除 ai_workbook_branch 行 → 读必须拒绝（分支缺行 fail-closed）。 */
    public void testDeletedBranchInvalidatesNullDraftArtifact(){
        String id=envelope(run.path("revision").asText()); // 无草稿登记
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        fixture.db.update("delete from ai_workbook_working_state where workbook_id=?",run.path("workbookId").asLong());
        fixture.db.update("delete from ai_workbook_branch where branch_id=?",run.path("branchId").asLong());
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行谓词不校验分支行存在性
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        assertEquals("sweep 同谓词物化",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
    }
    /** p) C1r2-F2c 红测（追加）：登记无草稿 → 分支 owner 失权（owner_user_id 改为他人）→ 读必须拒绝。 */
    public void testBranchOwnerReassignmentInvalidatesNullDraftArtifact(){
        String id=envelope(run.path("revision").asText()); // 无草稿登记
        assertEquals("ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        fixture.db.update("delete from ai_workbook_working_state where workbook_id=?",run.path("workbookId").asLong());
        fixture.db.update("update ai_workbook_branch set owner_user_id=2 where branch_id=?",run.path("branchId").asLong());
        staleRejected(()->artifacts.readArtifactMetadata(id,1)); // 红：现行谓词不校验分支属主
        assertEquals("读拒绝零突变","ACTIVE",status(id));
        assertEquals("sweep 同谓词物化",1,artifacts.sweepInvalidated(run.path("workbookId").asLong()));
        assertEquals("INVALIDATED",status(id));
    }
}
