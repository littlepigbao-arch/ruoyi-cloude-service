package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated MySQL: formula cache proof, receipt ledger and draft CAS. */
public class B7FormulaSaveMysqlTest extends TestCase {
    private A1TestFixture fixture;private ControlledWriteService controlled;private long workbook;private ObjectNode baseline;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B7 schema required");
        fixture=new A1TestFixture();baseline=(ObjectNode)parse("{\"id\":\"b7-formula-save\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":8,\"columnCount\":5,\"cellData\":{\"0\":{\"0\":{\"v\":2,\"t\":2},\"1\":{\"v\":3,\"t\":2}},\"1\":{\"0\":{\"v\":4,\"t\":2},\"1\":{\"v\":5,\"t\":2}}},\"mergeData\":[]}}}");workbook=fixture.seed(baseline.toString());controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());for(String name:new String[]{"enabled","b7FormulaEnabled"}){Field f=ControlledWriteService.class.getDeclaredField(name);f.setAccessible(true);f.set(controlled,true);}
    }
    private ObjectNode action(){return (ObjectNode)parse("{\"schemaVersion\":\"2.0\",\"type\":\"setFormula\",\"target\":{\"sheetId\":\"s\",\"range\":\"C1:C2\"},\"formula\":\"=A1+B1\"}");}
    private ObjectNode request(){ObjectNode ws=fixture.runs.workspace(workbook,1);ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook).put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText()).put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s").put("overwrite","confirmed");req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","C1:C2")));req.set("actions",arr().add(action()));return req;}
    private ObjectNode receipt(JsonNode manifest){ObjectNode r=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText()).put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText()).put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);r.set("formulaReadback",manifest.path("formulaReadback").deepCopy());return r;}
    public void testSavedFormulaAndDuplicateReceipt(){ObjectNode run=controlled.create(request(),1),m=controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","b7-isolated").put("fencingToken",run.path("fencingToken").asLong())),r=receipt(m),saved=controlled.receipt(run.path("runId").asText(),1,r);assertEquals("COMPLETED",saved.path("status").asText());ObjectNode expected=FormulaManifest.apply(baseline,m);assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));JsonNode changes=parse(fixture.db.queryForObject("select executed_changes_json from ai_workbook_working_state where workbook_id=?",String.class,workbook));assertEquals("controlled-formula",changes.get(0).path("kind").asText());ObjectNode verify=obj().put("sheetId","s").put("range","C1:C2").put("kind","matches_formula");verify.set("write",saved.path("lastControlledResult"));assertTrue(AgentTools.verify(expected,verify).path("passed").asBoolean());controlled.receipt(run.path("runId").asText(),1,r);Map<?,?> draft=(Map<?,?>)fixture.status(workbook).get("workingState");assertEquals(0,((Number)draft.get("version")).intValue());}
    public void testForgedCacheNeverSaves(){ObjectNode run=controlled.create(request(),1),m=controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","b7-isolated").put("fencingToken",run.path("fencingToken").asLong())),r=receipt(m);((ObjectNode)r.path("formulaReadback").get(0)).put("value",99);try{controlled.receipt(run.path("runId").asText(),1,r);fail("forged cache accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("B7_READBACK_INVALID"));}assertNull(fixture.status(workbook).get("workingState"));assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));}
}
