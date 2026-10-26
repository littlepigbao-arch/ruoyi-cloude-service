package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Pure B4/B3 safety contracts; deliberately never constructs a database fixture. */
public class B4OfflineContractTest extends TestCase {
    private ObjectNode snapshot() {
        return (ObjectNode)parse("{\"id\":\"book\",\"resources\":[{\"name\":\"KEEP\",\"data\":\"unchanged\"}],\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Orders\",\"rowCount\":20,\"columnCount\":10,\"rowData\":{\"1\":{\"h\":24}},\"columnData\":{\"1\":{\"w\":80}},\"mergeData\":[],\"cellData\":{\"0\":{\"0\":{\"v\":\"outside\",\"t\":1}},\"1\":{\"1\":{\"v\":\"old\",\"t\":1,\"p\":{\"body\":{\"dataStream\":\"rich\\r\\n\"}},\"s\":{\"bg\":{\"rgb\":\"#abc\"}}}}}}}}");
    }
    private ObjectNode action(String type) { return obj().put("schemaVersion","2.0").put("type",type).set("target",obj().put("sheetId","s").put("range","B2")); }
    private ObjectNode manifest(ObjectNode source,JsonNode actions) { return WriteManifest.create(source,actions,parse("[{\"sheetId\":\"s\",\"range\":\"B2\"}]"),"s","confirmed",true); }
    private void reject(Runnable task) { try { task.run(); fail("Unsafe contract accepted"); } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); } }
    public void testTransportDoesNotNarrowOrCoerceWithoutStartingDatabaseTests() {
        for (JsonNode bad:parse("[4294967298,2.5,2.0,\"2\",null,1,true,{},[]]")) {
            ObjectNode request=obj(); request.set("transportVersion",bad);
            reject(()->WriteManifest.checkTransport(request));
            reject(()->WriteManifest.requireTransport(request));
            ObjectNode meta=obj();meta.set("controlledWriteTransport",bad);
            reject(()->WriteManifest.checkChatTransport(arr().add(action("setCellForCell").put("value",0)),meta,true));
        }
        for (JsonNode valid:new JsonNode[]{IntNode.valueOf(2),LongNode.valueOf(2),new BigIntegerNode(java.math.BigInteger.valueOf(2))}) {
            ObjectNode request=obj();request.set("transportVersion",valid);WriteManifest.checkTransport(request);WriteManifest.requireTransport(request);
        }
        WriteManifest.checkTransport(obj());reject(()->WriteManifest.requireTransport(obj()));
    }
    public void testTrustedScalarPatchPreservesPrimitiveTypesAndAllOtherResources() {
        for (JsonNode value:parse("[\"\",0,false,true,\"001\",42,\"plain\"]")) {
            ObjectNode original=snapshot(),before=original.deepCopy(),a=action("setCellForCell");a.set("value",value);
            ObjectNode result=WriteManifest.apply(original,manifest(original,arr().add(a)));
            JsonNode cell=result.path("sheets").path("s").path("cellData").path("1").path("1");
            assertEquals(value,WriteManifest.semanticValue(cell));assertEquals(value.isNumber()?2:value.isBoolean()?3:1,cell.path("t").intValue());
            assertFalse(cell.hasNonNull("p"));assertEquals(before,original);
            assertEquals(original.path("resources"),result.path("resources"));
            assertEquals(original.path("sheets").path("s").path("rowData"),result.path("sheets").path("s").path("rowData"));
            assertEquals(original.path("sheets").path("s").path("columnData"),result.path("sheets").path("s").path("columnData"));
            assertEquals(original.path("sheets").path("s").path("cellData").path("0"),result.path("sheets").path("s").path("cellData").path("0"));
            assertEquals(original.path("sheets").path("s").path("cellData").path("1").path("1").path("s"),cell.path("s"));
        }
    }
    public void testClearPatchOnlyRemovesContentAndNeverChangesOriginal() {
        ObjectNode original=snapshot(),before=original.deepCopy(),result=WriteManifest.apply(original,manifest(original,arr().add(action("clearRange").put("clearWhat","content"))));
        JsonNode cell=result.path("sheets").path("s").path("cellData").path("1").path("1");
        assertTrue(WriteManifest.semanticValue(cell).isNull());assertFalse(cell.has("t"));assertFalse(cell.has("p"));assertEquals(before,original);
        assertEquals(original.path("resources"),result.path("resources"));
        ObjectNode expected=before.deepCopy();((ObjectNode)expected.path("sheets").path("s").path("cellData").path("1").path("1")).remove(java.util.Arrays.asList("v","t","p","f","si"));
        assertEquals(expected,result);
    }
    public void testEveryUnmigratedOrUnknownMixedWriteIsRejectedWithoutChangingTrustedInput() {
        for(String type:new String[]{"setFormula","merge","breakApart","insertRows","deleteRows","insertColumns","deleteColumns","setRowHeight","setColumnWidth","setStyle","undo","redo","sumToCell","setFilter","clearFilter","unknown"}) {
            ObjectNode original=snapshot(),before=original.deepCopy();ArrayNode actions=arr().add(action("setCellForCell").put("value",0)).add(action(type));
            reject(()->WriteManifest.classify(actions));reject(()->manifest(original,actions));assertEquals(before,original);
        }
    }
}
