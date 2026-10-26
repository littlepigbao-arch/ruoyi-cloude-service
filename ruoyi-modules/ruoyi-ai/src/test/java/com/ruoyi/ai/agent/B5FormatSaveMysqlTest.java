package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated MySQL transactions through the production B5 format gate. */
public class B5FormatSaveMysqlTest extends TestCase {
    private A1TestFixture fixture;
    private ControlledWriteService controlled;
    private long workbook;
    private ObjectNode baseline;

    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();
        baseline=(ObjectNode)parse("{\"id\":\"b5-format-save\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\",\"t\":1,\"s\":{\"ff\":\"Arial\",\"bg\":{\"rgb\":\"#111111\"}}}},\"1\":{\"1\":{\"v\":\"outside\",\"t\":1}}},\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}},\"mergeData\":[]}}}");
        workbook=fixture.seed(baseline.toString());
        controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,
            fixture.versions,fixture.tx.getTransactionManager());
        Field enabled=ControlledWriteService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);enabled.set(controlled,true);
    }

    private void savedFormat(String type) {
        ObjectNode ws=fixture.runs.workspace(workbook,1);
        ObjectNode request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong())
            .put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s")
            .put("overwrite","confirmed");
        ObjectNode action=obj().put("schemaVersion","2.0").put("type",type);
        action.set("target",obj().put("sheetId","s"));
        ArrayNode scopes=arr();ObjectNode expected=baseline.deepCopy();
        ObjectNode readback=obj().put("sheetId","s");
        if("setStyle".equals(type)){
            ((ObjectNode)action.path("target")).put("range","A1");action.put("background","#223344");
            scopes.add(obj().put("sheetId","s").put("range","A1"));
            ((ObjectNode)expected.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg")).put("rgb","#223344");
            readback.put("row",0).put("column",0).put("value","#223344");
        }else if("setRowHeight".equals(type)){
            action.put("rowPosition",1).put("height",50);
            scopes.add(obj().put("sheetId","s").put("range","A1:J1"));
            ((ObjectNode)expected.path("sheets").path("s").path("rowData").path("0")).put("h",50).put("ia",0);
            readback.put("index",0).put("value",50);
        }else{
            action.put("columnPosition",1).put("width",120);
            scopes.add(obj().put("sheetId","s").put("range","A1:A10"));
            ((ObjectNode)expected.path("sheets").path("s").path("columnData").path("0")).put("w",120);
            readback.put("index",0).put("value",120);
        }
        request.set("writeScopes",scopes);request.set("actions",arr().add(action));
        ObjectNode run=controlled.create(request,1);
        ObjectNode manifest=controlled.claim(run.path("runId").asText(),1,
            obj().put("transportVersion",2).put("clientId","b5-isolated-test")
                .put("fencingToken",run.path("fencingToken").asLong()));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText())
            .put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText())
            .put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);
        receipt.set("formatReadback",readback);
        ObjectNode saved=controlled.receipt(run.path("runId").asText(),1,receipt);
        assertEquals("COMPLETED",saved.path("status").asText());
        assertEquals("SAVED",saved.path("persistenceStatus").asText());
        assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
        Map<?,?> draft=(Map<?,?>)fixture.status(workbook).get("workingState");
        assertEquals(0,((Number)draft.get("version")).intValue());
        String changes=fixture.db.queryForObject("select executed_changes_json from ai_workbook_working_state where workbook_id=?",String.class,workbook);
        JsonNode executed=parse(changes);assertEquals(1,executed.size());
        assertEquals("controlled-format",executed.get(0).path("kind").asText());
        assertEquals(type,executed.get(0).path("formatPatch").path("type").asText());
        assertEquals(manifest.path("callId"),executed.get(0).path("operationId"));
        controlled.receipt(run.path("runId").asText(),1,receipt);
        assertEquals(0,((Number)((Map<?,?>)fixture.status(workbook).get("workingState")).get("version")).intValue());
        assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
        String export=System.getProperty("b5.snapshot.output","");
        if(!export.isEmpty())try{
            Path location=Paths.get(export).toAbsolutePath().normalize();
            assertEquals(Paths.get("D:/Code/agentReport/b5-db-20261024/sdk-import").toAbsolutePath().normalize(),location);
            Files.createDirectories(location);
            Path file=location.resolve(type+".json");
            String savedContent=String.valueOf(fixture.status(workbook).get("content"));
            if(Files.exists(file))assertEquals(savedContent,new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            else Files.write(file,savedContent.getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE_NEW);
        }catch(java.io.IOException io){throw new AssertionError("Cannot export isolated B5 saved snapshot",io);}
    }

    private ObjectNode[] claimedStyle() {
        ObjectNode ws=fixture.runs.workspace(workbook,1);
        ObjectNode request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong())
            .put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s")
            .put("overwrite","confirmed");
        ObjectNode action=obj().put("schemaVersion","2.0").put("type","setStyle").put("background","#223344");
        action.set("target",obj().put("sheetId","s").put("range","A1"));
        request.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1")));
        request.set("actions",arr().add(action));
        ObjectNode run=controlled.create(request,1);
        ObjectNode manifest=controlled.claim(run.path("runId").asText(),1,
            obj().put("transportVersion",2).put("clientId","b5-isolated-test")
                .put("fencingToken",run.path("fencingToken").asLong()));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText())
            .put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText())
            .put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);
        receipt.set("formatReadback",obj().put("sheetId","s").put("row",0).put("column",0).put("value","#223344"));
        return new ObjectNode[]{run,manifest,receipt};
    }

    private void rejectReceipt(ObjectNode run,ObjectNode receipt,String reason) {
        try{controlled.receipt(run.path("runId").asText(),1,receipt);fail("Unsafe B5 receipt saved");}
        catch(IllegalArgumentException blocked){assertTrue(blocked.getMessage(),blocked.getMessage().contains(reason));}
    }

    private void isolateUnknown(ObjectNode run,ObjectNode manifest) {
        ObjectNode identity=obj().put("callId",manifest.path("callId").asText())
            .put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText())
            .put("fencingToken",manifest.path("fencingToken").asLong());
        assertEquals("PAUSED",controlled.uncertain(run.path("runId").asText(),1,identity).path("status").asText());
        assertEquals("UNKNOWN",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",
            String.class,run.path("runId").asText(),manifest.path("callId").asText()));
        try{controlled.claim(run.path("runId").asText(),1,obj().put("transportVersion",2)
            .put("fencingToken",manifest.path("fencingToken").asLong()));fail("Uncertain B5 call dispatched again");}
        catch(IllegalArgumentException blocked){assertTrue(blocked.getMessage(),blocked.getMessage().contains("RESULT_UNKNOWN"));}
    }

    public void testStyleTrustedPatchCasAndDuplicateReceipt(){savedFormat("setStyle");}
    public void testRowHeightTrustedPatchCasAndDuplicateReceipt(){savedFormat("setRowHeight");}
    public void testColumnWidthTrustedPatchCasAndDuplicateReceipt(){savedFormat("setColumnWidth");}
    public void testWrongFormatReadbackCannotSaveAndUnknownCannotReplay(){
        ObjectNode[] parts=claimedStyle();ObjectNode run=parts[0],manifest=parts[1],receipt=parts[2];
        ((ObjectNode)receipt.path("formatReadback")).put("value","#ffffff");
        rejectReceipt(run,receipt,"B5_READBACK_INVALID");
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));
        isolateUnknown(run,manifest);
    }
    public void testContradictorySuccessErrorCannotSave(){
        ObjectNode[] parts=claimedStyle();ObjectNode run=parts[0],manifest=parts[1],receipt=parts[2];
        receipt.put("error","setter failed after partial execution");
        rejectReceipt(run,receipt,"WRITE_READBACK_INVALID");
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));
        isolateUnknown(run,manifest);
    }
    public void testNonBooleanSuccessFlagCannotSave(){
        ObjectNode[] parts=claimedStyle();ObjectNode run=parts[0],manifest=parts[1],receipt=parts[2];
        receipt.put("ok","true");
        rejectReceipt(run,receipt,"WRITE_READBACK_INVALID");
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));
        isolateUnknown(run,manifest);
    }
    public void testFormatCasConflictKeepsExternalDraftAndCannotReplay(){
        ObjectNode[] parts=claimedStyle();ObjectNode run=parts[0],manifest=parts[1],receipt=parts[2];
        String external="{\"external\":true}";
        fixture.db.update("insert into ai_workbook_working_state(workbook_id,branch_id,owner_user_id,base_commit_id,draft_actions_json,executed_changes_json,draft_snapshot,content_hash,version,update_time) values(?,?,1,null,'[]','[]',?,?,0,now(3))",
            workbook,run.path("branchId").asLong(),external.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            com.ruoyi.ai.util.ContentHashUtils.sha256(external));
        rejectReceipt(run,receipt,"WRITE_VERSION_CONFLICT");
        assertEquals(external,fixture.status(workbook).get("content"));
        assertEquals(0,((Number)((Map<?,?>)fixture.status(workbook).get("workingState")).get("version")).intValue());
        isolateUnknown(run,manifest);
    }
    public void testFormatLeaseFenceChangeCannotSaveAndCannotReplay(){
        ObjectNode[] parts=claimedStyle();ObjectNode run=parts[0],manifest=parts[1],receipt=parts[2];
        fixture.db.update("update ai_agent_lease set fencing_token=fencing_token+1 where workbook_id=?",workbook);
        rejectReceipt(run,receipt,"RESULT_UNKNOWN");
        assertNull(fixture.status(workbook).get("workingState"));
        assertEquals(baseline,parse(String.valueOf(fixture.status(workbook).get("content"))));
        isolateUnknown(run,manifest);
    }
}
