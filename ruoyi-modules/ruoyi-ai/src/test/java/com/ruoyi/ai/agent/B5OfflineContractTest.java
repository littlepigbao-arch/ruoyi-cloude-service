package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Pure contract tests: no database, server or real Univer session. */
public class B5OfflineContractTest extends TestCase {
    private ObjectNode snapshot(){return (ObjectNode)parse("{\"sheets\":{\"s\":{\"name\":\"Orders\",\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\",\"s\":{\"bg\":{\"rgb\":\"#ffffff\"}}}}},\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}},\"mergeData\":[]}}}");}
    private ArrayNode scopes(){return (ArrayNode)parse("[{\"sheetId\":\"s\",\"range\":\"A1:B2\"}]");}
    private ArrayNode dimensionScopes(String type){return (ArrayNode)parse("setRowHeight".equals(type)?"[{\"sheetId\":\"s\",\"range\":\"A1:J1\"}]":"[{\"sheetId\":\"s\",\"range\":\"A1:A10\"}]");}
    private ObjectNode action(String type){return obj().put("schemaVersion","2.0").put("type",type).set("target",obj().put("sheetId","s").put("range","A1"));}
    private void reject(Runnable body){try{body.run();fail("Unsafe B5 contract accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    public void testEntryWhitelistStaysClosed(){for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"})reject(()->WriteManifest.classify(arr().add(action(type))));}
    public void testCandidateEntryClassifiesOnlyOneExplicitFormatBehindGate(){
        JsonNode meta=parse("{\"controlledWriteTransport\":2}"),old=parse("{}");
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            ObjectNode a=action(type);if("setStyle".equals(type))a.put("background","#112233");
            else {((ObjectNode)a.path("target")).remove("range");a.put("setRowHeight".equals(type)?"rowPosition":"columnPosition",1).put("setRowHeight".equals(type)?"height":"width",50);}
            ArrayNode group=arr().add(a);
            assertEquals("controlled-values",WriteManifest.classify(group,true));
            assertEquals("controlled-values",WriteManifest.checkChatTransport(group,meta,true,true));
            reject(()->WriteManifest.checkChatTransport(group,meta,true,false));
            reject(()->WriteManifest.checkChatTransport(group,old,true,true));
            reject(()->WriteManifest.classify(arr().add(a).add(a),true));
            reject(()->WriteManifest.classify(arr().add(a).add(obj().put("type","setCell")),true));
        }
    }
    public void testPublishedActionsRemainClosedUntilFormatGateOpens(){
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            assertFalse(WriteManifest.availableActions(false).contains(type));
            assertTrue(WriteManifest.availableActions(true).contains(type));
            assertFalse(WriteManifest.VALUE_ACTIONS.contains(type));
        }
    }
    public void testSingleBackgroundPatchAndReadbackAreExact(){ObjectNode source=snapshot(),before=source.deepCopy(),a=action("setStyle").put("background","#A1b2C3");ObjectNode m=WriteManifest.previewB5Format(source,a,scopes());
        assertEquals("#a1b2c3",m.path("patch").path("value").asText());WriteManifest.validateB5FormatReceipt(m,obj().put("sheetId","s").put("row",0).put("column",0).put("value","#a1b2c3"));
        ObjectNode next=WriteManifest.applyB5Format(source,m);assertEquals("#a1b2c3",next.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg").path("rgb").asText());assertEquals(before,source);
        ObjectNode expected=before.deepCopy();((ObjectNode)expected.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg")).put("rgb","#a1b2c3");assertEquals(expected,next);
        assertEquals(before.path("sheets").path("s").path("rowData"),next.path("sheets").path("s").path("rowData"));
        for(JsonNode bad:parse("[{\"sheetId\":\"s\",\"row\":0,\"column\":0,\"value\":\"#ffffff\"},{\"sheetId\":\"s\",\"row\":0,\"column\":0,\"value\":\"#a1b2c3\",\"extra\":true} ]"))reject(()->WriteManifest.validateB5FormatReceipt(m,bad));
    }
    public void testUnsupportedStyleAndUnclearTargetsRejectBeforePatch(){for(String field:new String[]{"fontColor","fontSize","fontWeight","wrap","textRotation","fontFamily","hAlign","vAlign"}){ObjectNode a=action("setStyle").put("background","#ff0000");a.put(field,"x");reject(()->WriteManifest.previewB5Format(snapshot(),a,scopes()));}
        for(String color:new String[]{"red","#fff","#12345678","#gggggg",""})reject(()->WriteManifest.previewB5Format(snapshot(),action("setStyle").put("background",color),scopes()));
        ObjectNode outside=action("setStyle").put("background","#ff0000");((ObjectNode)outside.path("target")).put("range","C3");reject(()->WriteManifest.previewB5Format(snapshot(),outside,scopes()));
        ObjectNode wrong=action("setStyle").put("background","#ff0000");((ObjectNode)wrong.path("target")).put("sheetId","missing");reject(()->WriteManifest.previewB5Format(snapshot(),wrong,scopes()));
        ObjectNode registry=snapshot();((ObjectNode)registry.path("sheets").path("s").path("cellData").path("0").path("0")).put("s","style-id");reject(()->WriteManifest.previewB5Format(registry,action("setStyle").put("background","#ff0000"),scopes()));
    }
    public void testDimensionsPatchOnlyTheirOwnMetadata(){for(String type:new String[]{"setRowHeight","setColumnWidth"}){ObjectNode source=snapshot(),before=source.deepCopy(),a=action(type);((ObjectNode)a.path("target")).remove("range");a.put("setRowHeight".equals(type)?"rowPosition":"columnPosition",1).put("setRowHeight".equals(type)?"height":"width","setRowHeight".equals(type)?50:120);
            reject(()->WriteManifest.previewB5Format(source,a,scopes()));ObjectNode m=WriteManifest.previewB5Format(source,a,dimensionScopes(type));ObjectNode actual=obj().put("sheetId","s").put("index",0).put("value","setRowHeight".equals(type)?50:120);WriteManifest.validateB5FormatReceipt(m,actual);ObjectNode next=WriteManifest.applyB5Format(source,m);assertEquals(before,source);
            assertEquals(actual.path("value"),next.path("sheets").path("s").path("setRowHeight".equals(type)?"rowData":"columnData").path("0").path("setRowHeight".equals(type)?"h":"w"));
            ObjectNode expected=before.deepCopy();ObjectNode dimension=(ObjectNode)expected.path("sheets").path("s").path("setRowHeight".equals(type)?"rowData":"columnData").path("0");dimension.put("setRowHeight".equals(type)?"h":"w","setRowHeight".equals(type)?50:120);if("setRowHeight".equals(type))dimension.put("ia",0);assertEquals(expected,next);
            reject(()->WriteManifest.validateB5FormatReceipt(m,obj().put("sheetId","s").put("index",0).put("value",999)));
        }
        for(int bad:new int[]{0,20,401}){ObjectNode a=action("setRowHeight");((ObjectNode)a.path("target")).remove("range");a.put("rowPosition",1).put("height",bad);reject(()->WriteManifest.previewB5Format(snapshot(),a,dimensionScopes("setRowHeight")));}
        for(int bad:new int[]{0,39,801}){ObjectNode a=action("setColumnWidth");((ObjectNode)a.path("target")).remove("range");a.put("columnPosition",1).put("width",bad);reject(()->WriteManifest.previewB5Format(snapshot(),a,dimensionScopes("setColumnWidth")));}
    }
    public void testRowHeightTrustedPatchPersistsSdkManualHeightFlag(){ObjectNode source=snapshot(),a=action("setRowHeight");
        ((ObjectNode)a.path("target")).remove("range");a.put("rowPosition",1).put("height",50);
        ObjectNode next=WriteManifest.applyB5Format(source,WriteManifest.previewB5Format(source,a,dimensionScopes("setRowHeight")));
        assertEquals(0,next.path("sheets").path("s").path("rowData").path("0").path("ia").asInt(-1));
        assertFalse(source.path("sheets").path("s").path("rowData").path("0").has("ia"));
    }
    public void testOversizedAndMalformedDimensionFlagsReject(){for(String field:new String[]{"hd","ah","ia"}){
        ObjectNode source=snapshot(),a=action("setRowHeight");((ObjectNode)a.path("target")).remove("range");a.put("rowPosition",1).put("height",50);
        ((ObjectNode)source.path("sheets").path("s").path("rowData").path("0")).set(field,parse("4294967296"));
        reject(()->WriteManifest.previewB5Format(source,a,dimensionScopes("setRowHeight")));
    }}
    public void testAutomaticOrHiddenDimensionsAndStalePatchReject(){ObjectNode row=action("setRowHeight");((ObjectNode)row.path("target")).remove("range");row.put("rowPosition",1).put("height",50);
        ObjectNode auto=snapshot();((ObjectNode)auto.path("sheets").path("s").path("rowData").path("0")).put("ah",1);reject(()->WriteManifest.previewB5Format(auto,row,dimensionScopes("setRowHeight")));
        ObjectNode autoFit=snapshot();((ObjectNode)autoFit.path("sheets").path("s").path("rowData").path("0")).put("ia",1);reject(()->WriteManifest.previewB5Format(autoFit,row,dimensionScopes("setRowHeight")));
        ObjectNode hidden=snapshot();((ObjectNode)hidden.path("sheets").path("s").path("rowData").path("0")).put("hd",1);reject(()->WriteManifest.previewB5Format(hidden,row,dimensionScopes("setRowHeight")));
        ObjectNode col=action("setColumnWidth");((ObjectNode)col.path("target")).remove("range");col.put("columnPosition",1).put("width",120);
        ObjectNode hiddenCol=snapshot();((ObjectNode)hiddenCol.path("sheets").path("s").path("columnData").path("0")).put("hd",1);reject(()->WriteManifest.previewB5Format(hiddenCol,col,dimensionScopes("setColumnWidth")));
        ObjectNode source=snapshot(),manifest=WriteManifest.previewB5Format(source,row,dimensionScopes("setRowHeight"));((ObjectNode)source.path("sheets").path("s").path("rowData").path("0")).put("h",30);reject(()->WriteManifest.applyB5Format(source,manifest));
        ObjectNode forged=WriteManifest.previewB5Format(snapshot(),row,dimensionScopes("setRowHeight"));((ObjectNode)forged.path("patch")).put("value",100);reject(()->WriteManifest.applyB5Format(snapshot(),forged));
    }
    public void testRepeatClaimAndRecoveryGateKeepOneCallIdentity(){ObjectNode claimed=obj().put("callId","same").put("status","CLAIMED").put("claimToken","secret");
        ObjectNode duplicate=ControlledWriteService.repeatClaimReply(claimed);assertEquals("same",duplicate.path("callId").asText());assertFalse(duplicate.path("replayAllowed").asBoolean());assertFalse(duplicate.has("actions"));assertFalse(duplicate.has("claimToken"));
        for(String state:new String[]{"UNKNOWN","SUCCEEDED","CANCELLED","PENDING"})reject(()->ControlledWriteService.repeatClaimReply(obj().put("callId","same").put("status",state)));
        ObjectNode run=obj().put("kind","PLAN_AGENT").set("call",obj().put("transportVersion",2).put("status","UNKNOWN"));assertTrue(ControlledWriteService.ownsLifecycle(run));
        ((ObjectNode)run.path("call")).put("status","CLAIMED");assertTrue(ControlledWriteService.ownsLifecycle(run));
        ((ObjectNode)run.path("call")).put("status","PENDING");assertTrue(ControlledWriteService.ownsLifecycle(run));
        assertTrue(ControlledWriteService.ownsLifecycle(obj().put("kind","CONTROLLED_WRITE")));
        assertFalse(ControlledWriteService.ownsLifecycle(obj().put("kind","PLAN_AGENT")));
    }
    public void testOversizedIntegralParametersNeverWrapIntoValidCoordinatesOrSizes(){
        String hugePosition="4294967297",hugeRowHeight="4294967346",hugeColumnWidth="4294967416";
        for(String type:new String[]{"setRowHeight","setColumnWidth"}){
            ObjectNode a=action(type);((ObjectNode)a.path("target")).remove("range");String position="setRowHeight".equals(type)?"rowPosition":"columnPosition",size="setRowHeight".equals(type)?"height":"width";
            a.put(position,1).put(size,"setRowHeight".equals(type)?50:120);
            a.set(position,parse(hugePosition));reject(()->WriteManifest.previewB5Format(snapshot(),a,dimensionScopes(type)));
            a.put(position,1);a.set(size,parse("setRowHeight".equals(type)?hugeRowHeight:hugeColumnWidth));reject(()->WriteManifest.previewB5Format(snapshot(),a,dimensionScopes(type)));
            a.set(position,parse("18446744073709551617"));reject(()->WriteManifest.previewB5Format(snapshot(),a,dimensionScopes(type)));
        }
    }
    public void testInlineStylePreservesUnrelatedFieldsAndRejectsMalformedBackground(){
        ObjectNode source=snapshot();ObjectNode style=(ObjectNode)source.path("sheets").path("s").path("cellData").path("0").path("0").path("s");style.put("ff","Arial");
        ObjectNode next=WriteManifest.applyB5Format(source,WriteManifest.previewB5Format(source,action("setStyle").put("background","#112233"),scopes()));
        assertEquals("Arial",next.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("ff").asText());
        assertEquals("#112233",next.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg").path("rgb").asText());
        ObjectNode malformed=snapshot();((ObjectNode)malformed.path("sheets").path("s").path("cellData").path("0").path("0").path("s")).put("bg","legacy");
        reject(()->WriteManifest.previewB5Format(malformed,action("setStyle").put("background","#112233"),scopes()));
    }
    public void testSdkDiscardedBackgroundMetadataRejectsBeforeTrustedPatch(){
        ObjectNode source=snapshot();
        ((ObjectNode)source.path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg")).put("theme","kept");
        ObjectNode before=source.deepCopy();
        reject(()->WriteManifest.previewB5Format(source,action("setStyle").put("background","#112233"),scopes()));
        assertEquals(before,source);
    }
    public void testFenceAndVersionIdentityNeverAcceptWrappedIntegers(){
        assertTrue(WriteManifest.sameIntegral(parse("7"),7));
        assertFalse(WriteManifest.sameIntegral(parse("18446744073709551623"),7));
        assertFalse(WriteManifest.sameIntegral(parse("7.0"),7));
        assertFalse(WriteManifest.sameIntegral(parse("\"7\""),7));
    }
    public void testRecoveredSucceededLedgerRequiresCompleteSavedReceiptEvidence(){
        ObjectNode manifest=obj().put("callId","call-1").put("transportVersion",2).put("expectedDraftVersion",3)
            .put("fencingToken",7).put("loadedRevision","before");
        manifest.set("actions",arr().add(action("setCell")));
        manifest.set("cells",arr().add(obj().put("sheetId","s").put("row",0).put("column",0).put("value",1)));
        ObjectNode result=obj().put("callId","call-1").put("transportVersion",2).put("draftVersion",4)
            .put("loadedRevision","after").put("sourceRevision","source").put("validation","PASSED").put("persistenceStatus","SAVED");
        result.set("actions",manifest.path("actions").deepCopy());
        result.set("executedChange",obj().put("schemaVersion","1.0").put("source","ai")
            .put("operationId","call-1").put("kind","controlled-values")
            .put("beforeHash","before").put("afterHash","after").set("cells",manifest.path("cells").deepCopy()));
        ObjectNode call=obj().put("callId","call-1").put("transportVersion",2).put("status","SUCCEEDED")
            .put("persistenceStatus","SAVED").put("claimToken","claimed-once").put("receiptHash","receipt").put("argsHash",WriteManifest.digest(manifest));
        call.set("manifest",manifest);call.set("result",result);
        assertTrue(ControlledWriteService.hasSavedReceiptEvidence(call));
        for(String field:new String[]{"persistenceStatus","claimToken","receiptHash","argsHash","result","manifest"}){
            ObjectNode broken=call.deepCopy();broken.remove(field);
            assertFalse(field,ControlledWriteService.hasSavedReceiptEvidence(broken));
        }
        ObjectNode wrong=call.deepCopy();((ObjectNode)wrong.path("result")).put("validation","FAILED");
        assertFalse(ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("result")).put("callId","another-call");
        assertFalse(ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("result")).put("draftVersion",5);
        assertFalse(ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("manifest")).put("fencingToken",8);
        assertFalse(ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("result")).remove("executedChange");
        assertFalse("saved change missing",ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("result").path("executedChange")).put("afterHash","different");
        assertFalse("saved hash mismatch",ControlledWriteService.hasSavedReceiptEvidence(wrong));
        wrong=call.deepCopy();((ObjectNode)wrong.path("result")).set("actions",arr());
        assertFalse("saved actions mismatch",ControlledWriteService.hasSavedReceiptEvidence(wrong));
    }
    public void testPostCasReadbackMustMatchExactAuthoritativeDraft(){
        Map<String,Object> expected=new HashMap<>(),saved=new HashMap<>();
        expected.put("workbookId",11L);expected.put("branchId",12L);expected.put("ownerUserId",13L);
        expected.put("baseCommitId","head-1");expected.put("draftActionsJson","[{\"type\":\"setStyle\"}]");
        expected.put("executedChangesJson","[{\"kind\":\"controlled-format\"}]");
        expected.put("content","{\"sheets\":{}}".getBytes(StandardCharsets.UTF_8));
        expected.put("contentHash",com.ruoyi.ai.util.ContentHashUtils.sha256("{\"sheets\":{}}"));
        saved.putAll(expected);saved.put("content","{\"sheets\":{}}");saved.put("version",4L);
        assertTrue(ControlledWriteService.savedDraftMatches(saved,expected,4));
        for(String key:new String[]{"content","contentHash","draftActionsJson","executedChangesJson","baseCommitId","workbookId","branchId","ownerUserId","version"}){
            Map<String,Object> changed=new HashMap<>(saved);changed.put(key,"version".equals(key)?5L:"altered");
            assertFalse(key,ControlledWriteService.savedDraftMatches(changed,expected,4));
        }
        assertFalse(ControlledWriteService.savedDraftMatches(null,expected,4));
    }
}
