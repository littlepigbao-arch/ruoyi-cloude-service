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
 * C1-review F2 round 3 registration-identity regressions against real isolated MySQL.
 * The run-side sourceRevision is always AgentJson.hash(parse(authoritative content)) where the
 * authoritative content is working_state.draft_snapshot when a draft row exists and ai_workbook.content
 * otherwise (AgentRunService.workspace/current and ControlledWriteService.current all apply this exact
 * rule); working_state.content_hash is sha256 over the raw saved string and only coincides with the parsed
 * identity when the draft was saved compact. R1 guards the pretty-printed draft (raw hash diverges, parsed
 * identity must still register), R2 guards the no-draft path (committed content advanced under the same
 * version counter must refuse an old snapshot instead of stamping it fresh).
 * setUp mirrors ArtifactDependencyMysqlTest (re-applies ai_agent_dataset_v2.sql tolerating duplicate
 * columns) and seeds a workbook/branch/run like DatasetServiceTest's B9-style setup.
 */
public class ArtifactRegistrationIdentityMysqlTest extends TestCase {
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
    private String envelopeOf(String runId,String sourceRevision){
        ArrayNode rows=arr();rows.add(obj().put("k","a"));rows.add(obj().put("k","b"));
        ObjectNode data=obj();data.set("columns",arr().add("k"));data.set("rows",rows);
        ArrayNode lineage=arr().add(obj().put("sheetId","s").put("range","A1:B4").put("sheetDigest","digest-stable"));
        return artifacts.writeArtifactEnvelope(runId,1,run.path("workbookId").asLong(),run.path("branchId").asLong(),DatasetService.SCHEMA_NAME,sourceRevision,data,lineage,null).path("id").asText();
    }
    private ObjectNode changedSnapshot(){ObjectNode changed=snapshot.deepCopy();((ObjectNode)changed.path("sheets").path("s").path("cellData").path("1").path("1")).put("v",77);return changed;}
    /** 工作区当前快照身份（解析谱系）：与 AgentRunService.workspace 相同——读权威内容（草稿优先，否则已提交内容）、parse、hash。 */
    private String workspaceIdentity(){
        List<Map<String,Object>> draft=fixture.db.queryForList("select cast(draft_snapshot as char character set utf8mb4) content from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=1",run.path("workbookId").asLong(),run.path("branchId").asLong());
        String authoritative=draft.isEmpty()?fixture.db.queryForObject("select content from ai_workbook where workbook_id=?",String.class,run.path("workbookId").asLong()):String.valueOf(draft.get(0).get("content"));
        return hash(parse(authoritative));
    }
    private long draftVersion(){return fixture.db.queryForObject("select version from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=1",Long.class,run.path("workbookId").asLong(),run.path("branchId").asLong());}
    private String draftContentHash(){return fixture.db.queryForObject("select content_hash from ai_workbook_working_state where workbook_id=? and branch_id=? and owner_user_id=1",String.class,run.path("workbookId").asLong(),run.path("branchId").asLong());}
    /** R1：真实 saveWorkingState 链保存格式化（pretty-print）JSON 草稿（保存必须 200），随后以工作区当前快照
     *  身份（解析谱系，run revision 同规则）登记 → 必须成功、制品 ACTIVE、source_draft_version 与该草稿 version 一致。
     *  修复前红：登记将 run 侧解析身份与草稿原始串哈希直接比较，pretty 草稿被误拒 SOURCE_STALE。 */
    public void testPrettyDraftRegistersWithParsedIdentityRevision() {
        String loaded=ContentHashUtils.sha256(snapshot.toString()); // 首存时工作簿内容原始串哈希
        String pretty=snapshot.toPrettyString();
        com.ruoyi.ai.domain.dto.WorkingStateRequest request=fixture.request(run.path("workbookId").asLong(),-1L,loaded,pretty);
        assertEquals("真实链路保存 pretty 草稿必须成功",200,fixture.save(run.path("workbookId").asLong(),request).getCode());
        assertEquals("草稿首存版本为 0",0L,draftVersion());
        String identity=workspaceIdentity();
        assertFalse("前置：pretty 草稿的原始串哈希与解析身份确实分叉（正是误判根因）",draftContentHash().equals(identity));
        String id=envelopeOf(run.path("runId").asText(),identity); // 修复前此处抛 SOURCE_STALE（RED）
        assertEquals("制品 ACTIVE", "ACTIVE",artifacts.readArtifactMetadata(id,1).path("status").asText());
        Map<String,Object> dep=fixture.db.queryForMap("select source_draft_version,source_version from ai_analysis_artifact_dependency where artifact_id=?",id);
        assertEquals("依赖行 source_draft_version 与被核对草稿 version 一致",0L,((Number)dep.get("source_draft_version")).longValue());
    }
    /** R2：无草稿场景——先推进工作簿已提交内容与版本（提交通道），再用旧快照 revision/data 登记 →
     *  必须拒绝 SOURCE_STALE 且零残留（ai_analysis_artifact 与 ai_analysis_artifact_dependency 均无行）。
     *  修复前红：无草稿路径完全跳过来源核对，旧快照登记成功且 ACTIVE（还被盖上推进后的新版本戳）。
     *  正向对照：携带当前已提交内容解析身份的登记必须成功，证明拒绝来自身份核对而非误伤。 */
    public void testNoDraftOldSnapshotRegistrationRefusedWithZeroResidue() {
        assertEquals("前置：无草稿行",0,fixture.db.queryForObject("select count(*) from ai_workbook_working_state where workbook_id=?",Integer.class,run.path("workbookId").asLong()).intValue());
        String oldIdentity=workspaceIdentity(); // 旧快照（当前已提交内容）解析身份
        String changed=changedSnapshot().toString(); // 推进已提交内容与版本（同 advance：content+content_hash 同步、version+1）
        fixture.db.update("update ai_workbook set content=?,content_hash=?,version=version+1,update_time=now(3) where workbook_id=?",changed,ContentHashUtils.sha256(changed),run.path("workbookId").asLong());
        String attemptRunId=UUID.randomUUID().toString();
        try{
            envelopeOf(attemptRunId,oldIdentity);
            fail("无草稿路径旧快照登记被照单全收（旧数据盖上新版本戳）");
        }catch(IllegalArgumentException expected){assertTrue("unexpected message: "+expected.getMessage(),expected.getMessage().startsWith("SOURCE_STALE"));}
        assertEquals("零残留：ai_analysis_artifact 无行",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact where run_id=?",Integer.class,attemptRunId).intValue());
        assertEquals("零残留：ai_analysis_artifact_dependency 无行",0,fixture.db.queryForObject("select count(*) from ai_analysis_artifact_dependency d join ai_analysis_artifact a on a.artifact_id=d.artifact_id where a.run_id=?",Integer.class,attemptRunId).intValue());
        String fresh=envelopeOf(run.path("runId").asText(),workspaceIdentity()); // 正向对照：当前已提交内容解析身份
        assertEquals("正向对照：当前身份登记成功且 ACTIVE","ACTIVE",artifacts.readArtifactMetadata(fresh,1).path("status").asText());
    }
}
