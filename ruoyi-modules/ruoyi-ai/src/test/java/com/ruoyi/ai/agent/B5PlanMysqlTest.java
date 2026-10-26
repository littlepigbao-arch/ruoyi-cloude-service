package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.lang.reflect.Method;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated MySQL scheduler/receipt/CAS/verification test; the readback is a test fixture, not Univer. */
public class B5PlanMysqlTest extends TestCase {
    private A1TestFixture fixture;
    private AgentStore store;
    private Method advance;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();store=new AgentStore(fixture.db);
        A1TestFixture.inject(fixture.guard,"enabled",true);
        ControlledWriteService controlled=new ControlledWriteService(store,fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        java.lang.reflect.Field enabled=ControlledWriteService.class.getDeclaredField("enabled");enabled.setAccessible(true);enabled.set(controlled,true);
        A1TestFixture.inject(fixture.runs,"controlled",controlled);A1TestFixture.inject(fixture.runs,"timeoutMs",900000L);
        advance=AgentRunService.class.getDeclaredMethod("advance",ObjectNode.class);advance.setAccessible(true);
    }
    @Override protected void tearDown() throws Exception {fixture.runs.destroy();}
    private ObjectNode baseline(){return (ObjectNode)parse("{\"id\":\"b5-plan\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\",\"t\":1}}},\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}},\"mergeData\":[]}}}");}
    private String range(String type){return "setStyle".equals(type)?"A1":"setRowHeight".equals(type)?"A1:J1":"A1:A10";}
    private ObjectNode action(String type){ObjectNode a=obj().put("schemaVersion","2.0").put("type",type),target=obj().put("sheetId","s");if("setStyle".equals(type)){target.put("range","A1");a.put("background","#223344");}else if("setRowHeight".equals(type))a.put("rowPosition",1).put("height",50);else a.put("columnPosition",1).put("width",120);a.set("target",target);return a;}
    private ObjectNode plan(String type){ObjectNode w=obj().put("stepId","W").put("tool","apply_actions").put("objective","format").put("executionMode","fixed");w.set("dependsOn",arr());w.set("verificationRules",arr().add(obj().put("type","tool_success")));w.set("inputBindings",obj().put("overwrite","confirmed").set("actions",arr().add(action(type))));ObjectNode v=obj().put("stepId","V").put("tool","verify_result").put("objective","verify").put("executionMode","fixed");v.set("dependsOn",arr().add("W"));v.set("verificationRules",arr().add(obj().put("type","tool_success")));v.set("inputBindings",obj().put("sheetId","s").put("range",range(type)).put("kind","matches_format").set("write",obj().put("$step","W")));ObjectNode p=obj();p.set("steps",arr().add(w).add(v));p.set("acceptanceCriteria",arr().add(obj().put("type","step_succeeded").put("stepId","W")).add(obj().put("type","step_succeeded").put("stepId","V")));return p;}
    private void advance(String id){fixture.tx.execute(tx->{ObjectNode prior=store.get(id);fixture.guard.lock(prior.path("workbookId").asLong(),1L);ObjectNode r=store.locked(id);try{advance.invoke(fixture.runs,r);}catch(Exception error){throw new RuntimeException(error);}store.save(r);return null;});}
    public void testAllFormatsCompleteOnlyAfterActualSavedDraftVerification(){for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
        ObjectNode original=baseline();long workbook=fixture.seed(original.toString());ObjectNode ws=fixture.runs.workspace(workbook,1);ArrayNode scopes=arr().add(obj().put("sheetId","s").put("range",range(type)));
        ObjectNode request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook).put("branchId",ws.path("branchId").asLong()).put("goal","format").put("expectedRevision",ws.path("revision").asText()).put("loadedRevision",ws.path("loadedRevision").asText()).put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s").put("allowOverwrite",true);request.set("writeScopes",scopes);
        ObjectNode run=fixture.runs.create(request,1);String id=run.path("runId").asText();ObjectNode p=plan(type);AgentPlan.validateForRun(p,original,scopes,"s",true,true,true);
        fixture.tx.execute(tx->{fixture.guard.lock(workbook,1L);ObjectNode locked=store.locked(id);locked.set("plan",p);locked.put("planVersion",1).put("stepIndex",0).put("status","EXECUTING");store.save(locked);return null;});
        advance(id);ObjectNode pending=fixture.runs.get(id,1);assertEquals("PENDING",pending.path("call").path("status").asText());
        ObjectNode manifest=fixture.runs.claimControlled(id,1,obj().put("transportVersion",2).put("clientId","b5-plan-test").put("fencingToken",pending.path("fencingToken").asLong()));ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText()).put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText()).put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);ObjectNode readback=obj().put("sheetId","s");if("setStyle".equals(type))readback.put("row",0).put("column",0).put("value","#223344");else readback.put("index",0).put("value","setRowHeight".equals(type)?50:120);receipt.set("formatReadback",readback);
        ObjectNode saved=fixture.runs.receiptControlled(id,1,receipt);assertEquals("SAVED",saved.path("persistenceStatus").asText());assertEquals(1,saved.path("stepIndex").asInt());assertEquals("EXECUTING",saved.path("status").asText());JsonNode draft=parse(String.valueOf(fixture.status(workbook).get("content")));assertEquals(WriteManifest.applyB5Format(original,WriteManifest.previewB5Format(original,action(type),scopes)),draft);
        advance(id);ObjectNode verified=fixture.runs.get(id,1);assertEquals(verified.toString(),2,verified.path("stepIndex").asInt());assertTrue(verified.path("outputs").path("V").path("dataSummary").path("preview").asText().contains("saved-controlled-format-comparison"));advance(id);assertEquals("COMPLETED",fixture.runs.get(id,1).path("status").asText());
        assertNull(fixture.db.queryForObject("select run_id from ai_agent_lease where workbook_id=?",String.class,workbook));
        assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_tool_call where run_id=? and call_id=?",Integer.class,id,manifest.path("callId").asText()).intValue());
        fixture.runs.receiptControlled(id,1,receipt);assertEquals(0,((Number)((java.util.Map<?,?>)fixture.status(workbook).get("workingState")).get("version")).intValue());
    }}
}
