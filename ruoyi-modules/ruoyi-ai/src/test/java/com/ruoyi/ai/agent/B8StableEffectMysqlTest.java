package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated MySQL migration, uniqueness, state transitions and old JSON compatibility. */
public class B8StableEffectMysqlTest extends TestCase {
    private A1TestFixture fixture;private StableEffectLedger effects;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B8a schema required");
        fixture=new A1TestFixture();
        String module=System.getProperty("a1.module");
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_agent_controlled_write_v2.sql")),StandardCharsets.UTF_8);
        fixture.db.execute(sql.replaceAll("(?m)^--[^\\r\\n]*","").trim());
        effects=new StableEffectLedger(fixture.db);
    }
    private ObjectNode manifest(int value){ObjectNode m=obj().put("transportVersion",2).put("overwrite","confirmed");m.set("actions",arr().add(obj().put("type","setCell")));m.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","C1")));m.set("cells",arr().add(obj().put("sheetId","s").put("row",0).put("column",2).put("value",value)));return m;}
    public void testSameLogicalTargetAcrossPlansCannotDispatchTwice(){
        String run=UUID.randomUUID().toString(),first=UUID.randomUUID().toString(),effect=effects.reserve(run,first,manifest(5));
        try{effects.reserve(run,UUID.randomUUID().toString(),manifest(5));fail("same effect dispatched twice");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("EFFECT_ALREADY_REGISTERED"));}
        try{effects.reserve(run,UUID.randomUUID().toString(),manifest(6));fail("same identity accepted changed args");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("EFFECT_PARAMETER_CONFLICT"));}
        assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_effect where run_id=?",Integer.class,run).intValue());
        effects.transition(effect,run,first,"PREPARED","CLAIMED",null,null,null);
        ObjectNode result=obj().put("validation","PASSED").put("persistenceStatus","SAVED");
        effects.transition(effect,run,first,"CLAIMED","PERSISTED","after", "receipt",result);
        new StableEffectLedger(fixture.db).assertStatus(effect,run,first,"PERSISTED");
        assertEquals("after",fixture.db.queryForObject("select after_revision from ai_agent_effect where effect_id=?",String.class,effect));
        try{effects.transition(effect,run,first,"CLAIMED","PERSISTED","other","receipt",result);fail("repeat transition");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("EFFECT_STATE_CONFLICT"));}
    }
    public void testEffectIdentityIgnoresCallAndPlanVersionButBindsValues(){
        ObjectNode a=manifest(5),b=manifest(5);a.put("callId","call-plan-1").put("planVersion",1).put("expectedDraftVersion",0);b.put("callId","call-plan-2").put("planVersion",2).put("expectedDraftVersion",1);
        assertEquals(StableEffectLedger.identity(a),StableEffectLedger.identity(b));
        ObjectNode changed=manifest(6);assertEquals(StableEffectLedger.identity(a).path("semanticKey"),StableEffectLedger.identity(changed).path("semanticKey"));assertFalse(StableEffectLedger.identity(a).path("argsHash").equals(StableEffectLedger.identity(changed).path("argsHash")));
    }
    public void testControlledSaveAndLegacyCallCoexist() throws Exception {
        ObjectNode baseline=(ObjectNode)parse("{\"id\":\"b8-effect\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":4,\"columnCount\":4,\"cellData\":{}}}}");
        long workbook=fixture.seed(baseline.toString());ControlledWriteService controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        for(String field:new String[]{"enabled","b8EffectEnabled"}){Field f=ControlledWriteService.class.getDeclaredField(field);f.setAccessible(true);f.set(controlled,true);}
        ObjectNode ws=fixture.runs.workspace(workbook,1);ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook).put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText()).put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s").put("overwrite","confirmed");req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","C1")));req.set("actions",arr().add((ObjectNode)parse("{\"schemaVersion\":\"2.0\",\"type\":\"setCell\",\"target\":{\"sheetId\":\"s\",\"range\":\"C1\"},\"value\":5}")));
        ObjectNode run=controlled.create(req,1),m=controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","b8-test").put("fencingToken",run.path("fencingToken").asLong()));String effectId=m.path("effectId").asText();assertFalse(effectId.isEmpty());effects.assertStatus(effectId,run.path("runId").asText(),m.path("callId").asText(),"CLAIMED");
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",m.path("callId").asText()).put("effectId",effectId).put("claimToken",m.path("claimToken").asText()).put("argsHash",m.path("argsHash").asText()).put("fencingToken",m.path("fencingToken").asLong()).put("ok",true);ArrayNode readback=arr();for(JsonNode cell:m.path("cells")){ObjectNode value=obj().put("sheetId",cell.path("sheetId").asText()).put("row",cell.path("row").asInt()).put("column",cell.path("column").asInt()).put("formula","");value.set("value",cell.path("value"));readback.add(value);}receipt.set("cells",readback);
        ObjectNode missing=receipt.deepCopy();missing.remove("effectId");try{controlled.receipt(run.path("runId").asText(),1,missing);fail("effect identity omitted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("EFFECT_IDENTITY_CONFLICT"));}assertNull(fixture.status(workbook).get("workingState"));effects.assertStatus(effectId,run.path("runId").asText(),m.path("callId").asText(),"CLAIMED");
        ObjectNode saved=controlled.receipt(run.path("runId").asText(),1,receipt);assertEquals("COMPLETED",saved.path("status").asText());assertEquals(effectId,saved.path("lastControlledResult").path("effectId").asText());effects.assertStatus(effectId,run.path("runId").asText(),m.path("callId").asText(),"PERSISTED");controlled.receipt(run.path("runId").asText(),1,receipt);
        fixture.db.update("update ai_agent_effect set receipt_hash='forged' where effect_id=?",effectId);try{controlled.receipt(run.path("runId").asText(),1,receipt);fail("tampered effect accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("EFFECT_STATE_CONFLICT"));}fixture.db.update("update ai_agent_effect set receipt_hash=? where effect_id=?",WriteManifest.digest(receipt),effectId);
        assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_effect where run_id=?",Integer.class,run.path("runId").asText()).intValue());
        assertEquals(0,((Number)((Map<?,?>)fixture.status(workbook).get("workingState")).get("version")).intValue());
        long oldWorkbook=fixture.seed(baseline.toString());ObjectNode oldWs=fixture.runs.workspace(oldWorkbook,1),oldReq=req.deepCopy();oldReq.put("requestId",UUID.randomUUID().toString()).put("workbookId",oldWorkbook).put("branchId",oldWs.path("branchId").asLong()).put("loadedRevision",oldWs.path("loadedRevision").asText()).put("expectedDraftVersion",oldWs.path("draftVersion").asLong()).put("expectedBranchVersion",oldWs.path("branchVersion").asLong());
        ControlledWriteService old=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());Field enabled=ControlledWriteService.class.getDeclaredField("enabled"),gate=ControlledWriteService.class.getDeclaredField("b8EffectEnabled");enabled.setAccessible(true);gate.setAccessible(true);enabled.set(old,true);
        ObjectNode oldRun=old.create(oldReq,1);gate.set(old,true);ObjectNode oldClaim=old.claim(oldRun.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","old-json").put("fencingToken",oldRun.path("fencingToken").asLong()));assertFalse(oldClaim.has("effectId"));ObjectNode oldReceipt=receipt.deepCopy();oldReceipt.remove("effectId");oldReceipt.put("callId",oldClaim.path("callId").asText()).put("claimToken",oldClaim.path("claimToken").asText()).put("argsHash",oldClaim.path("argsHash").asText()).put("fencingToken",oldClaim.path("fencingToken").asLong());assertEquals("COMPLETED",old.receipt(oldRun.path("runId").asText(),1,oldReceipt).path("status").asText());assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_effect where run_id=?",Integer.class,oldRun.path("runId").asText()).intValue());
    }
}
