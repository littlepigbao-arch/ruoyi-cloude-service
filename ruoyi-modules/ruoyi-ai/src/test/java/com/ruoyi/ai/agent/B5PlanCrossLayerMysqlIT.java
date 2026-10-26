package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Dedicated, explicit export/import exchange with installed Univer; never part of routine module tests. */
public class B5PlanCrossLayerMysqlIT extends TestCase {
    private static final Path ROOT=Paths.get("D:/Code/agentReport/b5-plan-sdk-20261026");
    private static final Path BASELINES=Paths.get("D:/Code/agentReport/b5-e2e-20261024/settled2/sdk-baselines.json");
    private A1TestFixture fixture;
    private AgentStore store;
    private Method advance;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();store=new AgentStore(fixture.db);A1TestFixture.inject(fixture.guard,"enabled",true);
        ControlledWriteService controlled=new ControlledWriteService(store,fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        Field flag=ControlledWriteService.class.getDeclaredField("enabled");flag.setAccessible(true);flag.set(controlled,true);
        A1TestFixture.inject(fixture.runs,"controlled",controlled);A1TestFixture.inject(fixture.runs,"timeoutMs",900000L);
        advance=AgentRunService.class.getDeclaredMethod("advance",ObjectNode.class);advance.setAccessible(true);
    }
    @Override protected void tearDown() throws Exception {fixture.runs.destroy();}
    private Path file(String name){String selected=System.getProperty("b5.plan.crosslayer.root","");
        Path root=selected.isEmpty()?ROOT:Paths.get(selected).toAbsolutePath().normalize();
        if(!selected.isEmpty())assertEquals(Paths.get("D:/Code/agentReport/b5-plan-production-20261026").toAbsolutePath().normalize(),root);
        return root.resolve(name);}
    private String range(String type){return "setStyle".equals(type)?"A1":"setRowHeight".equals(type)?"A1:J1":"A1:A10";}
    private ObjectNode action(String type){ObjectNode a=obj().put("schemaVersion","2.0").put("type",type),target=obj().put("sheetId","s");if("setStyle".equals(type)){target.put("range","A1");a.put("background","#223344");}else if("setRowHeight".equals(type))a.put("rowPosition",1).put("height",50);else a.put("columnPosition",1).put("width",120);a.set("target",target);return a;}
    private ObjectNode plan(String type){ObjectNode w=obj().put("stepId","W").put("tool","apply_actions").put("objective","format").put("executionMode","fixed");w.set("dependsOn",arr());w.set("verificationRules",arr().add(obj().put("type","tool_success")));w.set("inputBindings",obj().put("overwrite","confirmed").set("actions",arr().add(action(type))));ObjectNode v=obj().put("stepId","V").put("tool","verify_result").put("objective","verify").put("executionMode","fixed");v.set("dependsOn",arr().add("W"));v.set("verificationRules",arr().add(obj().put("type","tool_success")));v.set("inputBindings",obj().put("sheetId","s").put("range",range(type)).put("kind","matches_format").set("write",obj().put("$step","W")));ObjectNode p=obj();p.set("steps",arr().add(w).add(v));p.set("acceptanceCriteria",arr().add(obj().put("type","step_succeeded").put("stepId","W")).add(obj().put("type","step_succeeded").put("stepId","V")));return p;}
    private void advance(String id){fixture.tx.execute(tx->{ObjectNode prior=store.get(id);fixture.guard.lock(prior.path("workbookId").asLong(),1L);ObjectNode r=store.locked(id);try{advance.invoke(fixture.runs,r);}catch(Exception e){throw new RuntimeException(e);}store.save(r);return null;});}
    public void testExchange() throws Exception {String phase=System.getProperty("b5.plan.crosslayer.phase","");assertTrue("Explicit export/import phase required",phase.equals("export")||phase.equals("import"));if(phase.equals("export"))exportClaims();else importReceipts();}
    private void exportClaims() throws Exception {
        assertTrue("New output folder required",Files.isDirectory(file(".").normalize()));assertFalse("Claim file already exists",Files.exists(file("claimed.json")));
        JsonNode baselines=parse(new String(Files.readAllBytes(BASELINES),StandardCharsets.UTF_8));ArrayNode records=arr();
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            ObjectNode before=(ObjectNode)baselines.path(type).deepCopy();assertTrue("Missing baseline "+type,before.isObject());long book=fixture.seed(before.toString());ObjectNode ws=fixture.runs.workspace(book,1);ArrayNode scopes=arr().add(obj().put("sheetId","s").put("range",range(type)));
            ObjectNode request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",book).put("branchId",ws.path("branchId").asLong()).put("goal","format").put("expectedRevision",ws.path("revision").asText()).put("loadedRevision",ws.path("loadedRevision").asText()).put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s").put("allowOverwrite",true);request.set("writeScopes",scopes);
            ObjectNode run=fixture.runs.create(request,1);String id=run.path("runId").asText();ObjectNode p=plan(type);AgentPlan.validateForRun(p,before,scopes,"s",true,true,true);
            fixture.tx.execute(tx->{fixture.guard.lock(book,1L);ObjectNode r=store.locked(id);r.set("plan",p);r.put("planVersion",1).put("stepIndex",0).put("status","EXECUTING");store.save(r);return null;});
            advance(id);ObjectNode pending=fixture.runs.get(id,1);assertEquals("PENDING",pending.path("call").path("status").asText());
            ObjectNode manifest=fixture.runs.claimControlled(id,1,obj().put("transportVersion",2).put("clientId","b5-plan-sdk").put("fencingToken",pending.path("fencingToken").asLong()));
            ObjectNode expected=WriteManifest.applyB5Format(before,WriteManifest.previewB5Format(before,action(type),scopes));
            ObjectNode record=obj().put("type",type).put("workbookId",book).put("runId",id);record.set("before",before);record.set("expected",expected);record.set("manifest",manifest);records.add(record);
        }
        Files.write(file("claimed.json"),records.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("B5_PLAN_REAL_SDK_CLAIMED="+records.size());
    }
    private void importReceipts() throws Exception {
        JsonNode records=parse(new String(Files.readAllBytes(file("claimed.json")),StandardCharsets.UTF_8));JsonNode responses=parse(new String(Files.readAllBytes(file("sdk-receipts.json")),StandardCharsets.UTF_8));assertEquals(3,records.size());assertEquals(3,responses.size());ArrayNode results=arr();
        for(int i=0;i<records.size();i++){
            JsonNode record=records.get(i),response=responses.get(i),manifest=record.path("manifest");assertEquals(record.path("type"),response.path("type"));assertEquals(manifest.path("callId"),response.path("receipt").path("callId"));
            String id=record.path("runId").asText();long book=record.path("workbookId").asLong();ObjectNode receipt=(ObjectNode)response.path("receipt");ObjectNode saved=fixture.runs.receiptControlled(id,1,receipt);assertEquals("SAVED",saved.path("persistenceStatus").asText());assertEquals(1,saved.path("stepIndex").asInt());assertEquals("EXECUTING",saved.path("status").asText());
            JsonNode actual=parse(String.valueOf(fixture.status(book).get("content")));assertEquals(record.path("expected"),actual);advance(id);ObjectNode verified=fixture.runs.get(id,1);assertEquals(2,verified.path("stepIndex").asInt());assertTrue(verified.path("outputs").path("V").path("dataSummary").path("preview").asText().contains("saved-controlled-format-comparison"));advance(id);assertEquals("COMPLETED",fixture.runs.get(id,1).path("status").asText());assertNull(fixture.db.queryForObject("select run_id from ai_agent_lease where workbook_id=?",String.class,book));
            fixture.runs.receiptControlled(id,1,receipt);Map<?,?> draft=(Map<?,?>)fixture.status(book).get("workingState");assertEquals(0,((Number)draft.get("version")).intValue());assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_tool_call where run_id=? and call_id=?",Integer.class,id,manifest.path("callId").asText()).intValue());
            results.add(obj().put("type",record.path("type").asText()).put("runId",id).put("status","COMPLETED").put("draftVersion",0).put("writeCalls",1));System.out.println("B5_PLAN_REAL_SDK_SAVED="+record.path("type").asText());
        }
        Files.write(file("server-results.json"),results.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
