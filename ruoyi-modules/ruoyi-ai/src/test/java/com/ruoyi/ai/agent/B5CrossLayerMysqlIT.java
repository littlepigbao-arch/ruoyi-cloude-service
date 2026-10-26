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
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Test-only exchange between the real controlled service and installed front-end Univer. */
public class B5CrossLayerMysqlIT extends TestCase {
    private static final Path ROOT=Paths.get("D:/Code/agentReport/b5-e2e-20261024");
    private A1TestFixture fixture;
    private ControlledWriteService controlled;

    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();
        controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,fixture.workbooks,
            fixture.versions,fixture.tx.getTransactionManager());
        Field enabled=ControlledWriteService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);enabled.set(controlled,true);
    }

    private ObjectNode baseline(String type) throws Exception {
        if(!context().isEmpty()){
            JsonNode candidates=parse(new String(Files.readAllBytes(file("sdk-baselines.json")),StandardCharsets.UTF_8));
            assertTrue("Normalized baselines must be an object",candidates.isObject());
            assertTrue("Missing normalized baseline for "+type,candidates.path(type).isObject());
            return (ObjectNode)candidates.path(type).deepCopy();
        }
        return (ObjectNode)parse("{\"id\":\"b5-cross-"+type+"\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Sheet\",\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\",\"t\":1,\"s\":{\"ff\":\"Arial\",\"bg\":{\"rgb\":\"#111111\"}}}},\"1\":{\"1\":{\"v\":\"outside\",\"t\":1}}},\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}},\"mergeData\":[]}}}");
    }
    private ObjectNode action(String type) {
        ObjectNode action=obj().put("schemaVersion","2.0").put("type",type);
        action.set("target",obj().put("sheetId","s"));
        if("setStyle".equals(type)){
            ((ObjectNode)action.path("target")).put("range","A1");action.put("background","#223344");
        }else if("setRowHeight".equals(type))action.put("rowPosition",1).put("height",50);
        else action.put("columnPosition",1).put("width",120);
        return action;
    }
    private ArrayNode scopes(String type) {
        return arr().add(obj().put("sheetId","s").put("range","setStyle".equals(type)?"A1":
            "setRowHeight".equals(type)?"A1:J1":"A1:A10"));
    }
    private ObjectNode expected(String type,ObjectNode before) {
        ObjectNode out=before.deepCopy(),sheet=(ObjectNode)out.path("sheets").path("s");
        if("setStyle".equals(type)){
            ObjectNode cell=(ObjectNode)sheet.path("cellData").path("0").path("0");
            ObjectNode style=cell.path("s").isObject()?(ObjectNode)cell.path("s"):obj();
            style.with("bg").put("rgb","#223344");cell.set("s",style);
        }
        else if("setRowHeight".equals(type))((ObjectNode)sheet.path("rowData").path("0")).put("h",50).put("ia",0);
        else ((ObjectNode)sheet.path("columnData").path("0")).put("w",120);
        return out;
    }
    private String context(){String value=System.getProperty("b5.crosslayer.context","");assertTrue("Unsupported cross-layer context",value.isEmpty()||value.equals("normalized")||value.equals("settled")||value.equals("settled2")||value.equals("production-gate-20261026")||value.equals("production-no-override-20261026"));return value;}
    private Path file(String name){return (context().isEmpty()?ROOT:ROOT.resolve(context())).resolve(name);}
    public void testExchange() throws Exception {
        String phase=System.getProperty("b5.crosslayer.phase","");
        assertTrue("Explicit export/import/abandon phase required",phase.equals("export")||phase.equals("import")||phase.equals("abandon"));
        if(phase.equals("export"))exportClaims();else if(phase.equals("import"))importReceipts();else abandonClaims();
    }
    private void exportClaims() throws Exception {
        assertFalse("Cross-layer context already exists",Files.exists(file("claimed.json")));
        Files.createDirectories(file(".").normalize());
        ArrayNode records=arr();
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            ObjectNode before=baseline(type);
            long workbook=fixture.seed(before.toString());
            ObjectNode ws=fixture.runs.workspace(workbook,1);
            ObjectNode request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
                .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
                .put("expectedDraftVersion",ws.path("draftVersion").asLong())
                .put("expectedBranchVersion",ws.path("branchVersion").asLong()).put("activeSheetId","s")
                .put("overwrite","confirmed");
            request.set("writeScopes",scopes(type));request.set("actions",arr().add(action(type)));
            ObjectNode run=controlled.create(request,1);
            ObjectNode manifest=controlled.claim(run.path("runId").asText(),1,
                obj().put("transportVersion",2).put("clientId","b5-cross-layer-test")
                    .put("fencingToken",run.path("fencingToken").asLong()));
            ObjectNode record=obj().put("type",type).put("workbookId",workbook).put("runId",run.path("runId").asText());
            record.set("before",before);record.set("expected",expected(type,before));record.set("manifest",manifest);
            records.add(record);
        }
        Files.write(file("claimed.json"),records.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("B5_CROSS_LAYER_CLAIMED="+records.size());
    }
    private void importReceipts() throws Exception {
        JsonNode records=parse(new String(Files.readAllBytes(file("claimed.json")),StandardCharsets.UTF_8));
        JsonNode responses=parse(new String(Files.readAllBytes(file("sdk-receipts.json")),StandardCharsets.UTF_8));
        assertEquals(3,records.size());assertEquals(3,responses.size());
        ArrayNode savedSnapshots=arr();
        for(int i=0;i<records.size();i++){
            JsonNode record=records.get(i),response=responses.get(i),manifest=record.path("manifest");
            assertEquals(record.path("type"),response.path("type"));
            assertEquals(manifest.path("callId"),response.path("receipt").path("callId"));
            ObjectNode saved=controlled.receipt(record.path("runId").asText(),1,(ObjectNode)response.path("receipt"));
            assertEquals("COMPLETED",saved.path("status").asText());
            assertEquals("SAVED",saved.path("persistenceStatus").asText());
            long workbook=record.path("workbookId").asLong();
            JsonNode stored=parse(String.valueOf(fixture.status(workbook).get("content")));
            assertEquals(record.path("expected"),stored);
            assertEquals("outside",stored.path("sheets").path("s").path("cellData").path("1").path("1").path("v").asText());
            savedSnapshots.add(obj().put("type",record.path("type").asText()).set("snapshot",stored.deepCopy()));
            controlled.receipt(record.path("runId").asText(),1,(ObjectNode)response.path("receipt"));
            assertEquals(stored,parse(String.valueOf(fixture.status(workbook).get("content"))));
            System.out.println("B5_CROSS_LAYER_SAVED="+record.path("type").asText());
        }
        Path export=file("server-saved.json");
        String content=savedSnapshots.toString();
        if(Files.exists(export))assertEquals(content,new String(Files.readAllBytes(export),StandardCharsets.UTF_8));
        else Files.write(export,content.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
    private void abandonClaims() throws Exception {
        JsonNode records=parse(new String(Files.readAllBytes(file("claimed.json")),StandardCharsets.UTF_8));
        assertEquals(3,records.size());
        for(JsonNode record:records){
            JsonNode manifest=record.path("manifest");
            ObjectNode identity=obj().put("callId",manifest.path("callId").asText())
                .put("claimToken",manifest.path("claimToken").asText())
                .put("argsHash",manifest.path("argsHash").asText())
                .put("fencingToken",manifest.path("fencingToken").asLong());
            ObjectNode paused=controlled.uncertain(record.path("runId").asText(),1,identity);
            assertEquals("PAUSED",paused.path("status").asText());
            assertEquals("UNKNOWN",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",
                String.class,record.path("runId").asText(),manifest.path("callId").asText()));
            assertNull(fixture.status(record.path("workbookId").asLong()).get("workingState"));
            System.out.println("B5_CROSS_LAYER_UNKNOWN="+record.path("type").asText());
        }
    }
}
