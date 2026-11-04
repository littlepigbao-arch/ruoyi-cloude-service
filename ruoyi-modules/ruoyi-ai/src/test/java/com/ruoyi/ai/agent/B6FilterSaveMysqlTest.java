package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated MySQL: trusted B6 resource patch, receipt ledger and draft CAS. */
public class B6FilterSaveMysqlTest extends TestCase {
    private A1TestFixture fixture;
    private ControlledWriteService controlled;
    private long workbook;
    private ObjectNode baseline;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B6 schema required");
        fixture=new A1TestFixture();
        baseline=(ObjectNode)parse("{\"id\":\"b6-filter-save\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":8,\"columnCount\":3,\"cellData\":{\"0\":{\"0\":{\"v\":\"Name\",\"t\":1}},\"1\":{\"0\":{\"v\":\"Alice\",\"t\":1}},\"2\":{\"0\":{\"v\":\"Bob\",\"t\":1}},\"3\":{\"0\":{\"v\":\"Alice\",\"t\":1}}},\"mergeData\":[]}}}");
        workbook=fixture.seed(baseline.toString());
        controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        for(String name:new String[]{"enabled","b6FilterEnabled"}){Field field=ControlledWriteService.class.getDeclaredField(name);field.setAccessible(true);field.set(controlled,true);}
    }
    private ObjectNode action(){return (ObjectNode)parse("{\"schemaVersion\":\"2.0\",\"type\":\"setFilter\",\"target\":{\"sheetId\":\"s\",\"range\":\"A1:A4\"},\"columnIndex\":0,\"filters\":[\"Alice\"]}");}
    private ObjectNode request(){ObjectNode ws=fixture.runs.workspace(workbook,1);ObjectNode req=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook).put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText()).put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s").put("overwrite","confirmed");req.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1:A4")));req.set("actions",arr().add(action()));return req;}
    public void testReceiptSavesOnlyFilterResourceAndDuplicateIsIdempotent(){
        ObjectNode run=controlled.create(request(),1),manifest=controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","b6-isolated").put("fencingToken",run.path("fencingToken").asLong()));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText()).put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText()).put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);receipt.set("filterReadback",manifest.path("filterReadback"));
        ObjectNode saved=controlled.receipt(run.path("runId").asText(),1,receipt);assertEquals("COMPLETED",saved.path("status").asText());assertEquals("SAVED",saved.path("persistenceStatus").asText());
        ObjectNode expected=FilterManifest.apply(baseline,manifest);assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
        JsonNode changes=parse(fixture.db.queryForObject("select executed_changes_json from ai_workbook_working_state where workbook_id=?",String.class,workbook));assertEquals(1,changes.size());assertEquals("controlled-filter",changes.get(0).path("kind").asText());assertEquals(manifest.path("filterPatch"),changes.get(0).path("filterPatch"));
        controlled.receipt(run.path("runId").asText(),1,receipt);Map<?,?> draft=(Map<?,?>)fixture.status(workbook).get("workingState");assertEquals(0,((Number)draft.get("version")).intValue());assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
    }
    public void testForgedReadbackNeverSaves(){
        ObjectNode run=controlled.create(request(),1),manifest=controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2).put("clientId","b6-isolated").put("fencingToken",run.path("fencingToken").asLong()));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText()).put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText()).put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);receipt.set("filterReadback",obj().put("sheetId","other"));
        try{controlled.receipt(run.path("runId").asText(),1,receipt);fail("forged readback accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("B6_READBACK_INVALID"));}
        assertNull(fixture.status(workbook).get("workingState"));assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));
    }
}
