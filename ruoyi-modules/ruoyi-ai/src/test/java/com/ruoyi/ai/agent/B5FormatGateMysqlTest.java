package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Real isolated MySQL, mapper, locks and transaction for both B5 gate states. */
public class B5FormatGateMysqlTest extends TestCase {
    private A1TestFixture fixture;
    private ControlledWriteService controlled;
    private long workbook;
    private ObjectNode request;
    private String original;

    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();
        original="{\"id\":\"b5-gate\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\",\"t\":1}}},\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}},\"mergeData\":[]}}}";
        workbook=fixture.seed(original);
        ObjectNode workspace=fixture.runs.workspace(workbook,1);
        request=obj().put("workbookId",workbook).put("branchId",workspace.path("branchId").asLong())
            .put("loadedRevision",workspace.path("loadedRevision").asText())
            .put("expectedDraftVersion",workspace.path("draftVersion").asLong())
            .put("expectedBranchVersion",workspace.path("branchVersion").asLong())
            .put("activeSheetId","s").put("overwrite","confirmed");
        controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager()){
            @Override boolean b5FormatOpen(){return false;}
        };
        setControlledEnabled(controlled);
    }

    private static void setControlledEnabled(ControlledWriteService service) throws Exception {
        java.lang.reflect.Field field=ControlledWriteService.class.getDeclaredField("enabled");
        field.setAccessible(true);field.setBoolean(service,true);
    }

    public void testProductionGateRequiresControlledWriteSwitchAndAdmitsB5Subset() throws Exception {
        ControlledWriteService production=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        assertFalse("The master controlled-write switch must still default off",production.formatActionsEnabled());
        setControlledEnabled(production);
        assertTrue("The production B5 gate must expose the verified subset",production.formatActionsEnabled());
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"})
            assertTrue(WriteManifest.availableActions(production.formatActionsEnabled()).contains(type));
        for(String type:new String[]{"setFormula","setFilter","clearFilter","undo","redo"})
            assertFalse(WriteManifest.availableActions(production.formatActionsEnabled()).contains(type));
    }

    public void testFormatActionsFromEveryEntryRemainClosedBeforeLedgerAndLease() {
        for(String entry:new String[]{"explicit","ordinary-ai","plan-agent"})for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            ObjectNode next=request.deepCopy().put("requestId",UUID.randomUUID().toString()).put("entryPoint",entry);
            ObjectNode action=obj().put("schemaVersion","2.0").put("type",type);
            action.set("target",obj().put("sheetId","s"));
            ArrayNode scopes=arr();
            if("setStyle".equals(type)){
                ((ObjectNode)action.path("target")).put("range","A1");action.put("background","#112233");
                scopes.add(obj().put("sheetId","s").put("range","A1"));
            }else if("setRowHeight".equals(type)){
                action.put("rowPosition",1).put("height",50);
                scopes.add(obj().put("sheetId","s").put("range","A1:J1"));
            }else{
                action.put("columnPosition",1).put("width",120);
                scopes.add(obj().put("sheetId","s").put("range","A1:A10"));
            }
            next.set("writeScopes",scopes);next.set("actions",arr().add(action));
            try{controlled.create(next,1);fail("B5 format gate opened via "+entry+" / "+type);}
            catch(IllegalArgumentException blocked){assertTrue(blocked.getMessage(),blocked.getMessage().contains("AI_CAPABILITY_BLOCKED"));}
        }
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_run where workbook_id=?",Integer.class,workbook).intValue());
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_tool_call c join ai_agent_run r on r.run_id=c.run_id where r.workbook_id=?",Integer.class,workbook).intValue());
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_lease where workbook_id=?",Integer.class,workbook).intValue());
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(original,fixture.status(workbook).get("content"));
    }

    public void testPreexistingClaimedFormatCallCannotBypassReceiptSaveGate() {
        ObjectNode action=obj().put("schemaVersion","2.0").put("type","setStyle")
            .put("background","#112233").set("target",obj().put("sheetId","s").put("range","A1"));
        ArrayNode scopes=arr().add(obj().put("sheetId","s").put("range","A1"));
        ObjectNode workspace=fixture.runs.workspace(workbook,1);
        String runId=UUID.randomUUID().toString(),callId=UUID.randomUUID().toString(),claimToken=UUID.randomUUID().toString();
        long fence=7;
        ObjectNode manifest=WriteManifest.previewB5Format(parse(original),action,scopes);
        manifest.put("callId",callId).put("workbookId",workbook).put("branchId",workspace.path("branchId").asLong())
            .put("fencingToken",fence).put("loadedRevision",workspace.path("loadedRevision").asText())
            .put("expectedDraftVersion",workspace.path("draftVersion").asLong())
            .put("expectedBranchVersion",workspace.path("branchVersion").asLong());
        String argsHash=WriteManifest.digest(manifest);
        ObjectNode call=obj().put("callId",callId).put("transportVersion",2).put("tool","apply_actions")
            .put("status","CLAIMED").put("claimToken",claimToken).put("argsHash",argsHash);
        call.set("manifest",manifest);
        ObjectNode run=obj().put("runId",runId).put("userId",1).put("workbookId",workbook)
            .put("branchId",workspace.path("branchId").asLong()).put("requestId",UUID.randomUUID().toString())
            .put("requestHash","isolated-test").put("version",0).put("eventSeq",0).put("kind","CONTROLLED_WRITE")
            .put("transportVersion",2).put("status","EXECUTING").put("persistenceStatus","NOT_SAVED")
            .put("fencingToken",fence).put("deadline",System.currentTimeMillis()+120000);
        run.set("call",call);
        fixture.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))",
            runId,1,workbook,workspace.path("branchId").asLong(),run.path("requestId").asText(),run.path("requestHash").asText(),run.toString());
        fixture.db.update("insert into ai_agent_lease(workbook_id,run_id,fencing_token,expires_at) values(?,?,?,?)",
            workbook,runId,fence,System.currentTimeMillis()+120000);
        new AgentStore(fixture.db).call(run,call);
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",callId).put("claimToken",claimToken)
            .put("argsHash",argsHash).put("fencingToken",fence).put("ok",true);
        receipt.set("formatReadback",obj().put("sheetId","s").put("row",0).put("column",0).put("value","#112233"));
        try{controlled.receipt(runId,1,receipt);fail("Existing claimed B5 call bypassed the save gate");}
        catch(IllegalArgumentException blocked){assertTrue(blocked.getMessage(),blocked.getMessage().contains("AI_CAPABILITY_BLOCKED"));}
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(original,fixture.status(workbook).get("content"));
        assertEquals("CLAIMED",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",String.class,runId,callId));
        assertEquals("EXECUTING",fixture.db.queryForObject("select status from ai_agent_run where run_id=?",String.class,runId));
        ObjectNode identity=obj().put("callId",callId).put("claimToken",claimToken)
            .put("argsHash",argsHash).put("fencingToken",fence);
        ObjectNode uncertain=controlled.uncertain(runId,1,identity);
        assertEquals("PAUSED",uncertain.path("status").asText());
        assertEquals("UNKNOWN",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",String.class,runId,callId));
        try{controlled.claim(runId,1,obj().put("transportVersion",2).put("fencingToken",fence));fail("UNKNOWN call was dispatched again");}
        catch(IllegalArgumentException blocked){assertTrue(blocked.getMessage(),blocked.getMessage().contains("RESULT_UNKNOWN"));}
        assertNull(fixture.status(workbook).get("workingState"));
    }
}
