package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.agent.WriteManifest;
import junit.framework.TestCase;
import java.util.*;

public class ActionTargetCompatibilityTest extends TestCase {
    private WorkbookContext context() {
        WorkbookContext c=new WorkbookContext();c.setActiveSheetId("b");c.setActiveSheetName("Arrivals");
        SheetContext a=new SheetContext();a.setSheetId("a");a.setSheetName("Orders");
        SheetContext b=new SheetContext();b.setSheetId("b");b.setSheetName("Arrivals");c.setSheets(Arrays.asList(a,b));return c;
    }
    private Action action(String version,String id) {
        Action a=new Action();a.setType("setCell");a.setSchemaVersion(version);a.setRange("A1");
        if(id!=null){Action.Target t=new Action.Target();t.setSheetId(id);t.setRange("A1");a.setTarget(t);}return a;
    }
    private void reject(Action action) {try{ActionTargetValidator.normalize(action,context());fail("Unsafe target accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    public void testV2NeverDefaultsToActiveSheet(){reject(action("2.0",null));reject(action("2.0","missing"));Action a=action("2.0",null);a.setSheetId("a");reject(a);}
    public void testV2ConflictingIdAndNameRejected(){Action a=action("2.0","a");a.setSheetId("b");reject(a);a=action("2.0","a");a.getTarget().setSheetName("Arrivals");reject(a);}
    public void testV2PrefixConflictRejected(){Action a=action("2.0","a");a.setRange(null);a.getTarget().setRange("Arrivals!A1");reject(a);}
    public void testValidV2RetainsExplicitTarget(){Action a=action("2.0","a");ActionTargetValidator.normalize(a,context());assertEquals("a",a.getTarget().getSheetId());assertEquals("Orders",a.getTarget().getSheetName());}
    public void testV1ActiveAndNamedTargetsCompatible(){Action a=action("1.0",null);ActionTargetValidator.normalize(a,context());assertEquals("b",a.getTarget().getSheetId());a=action(null,null);a.setRange("'Orders'!A1");ActionTargetValidator.normalize(a,context());assertEquals("a",a.getTarget().getSheetId());assertEquals("A1",a.getTarget().getRange());}
    public void testUnknownProtocolRejected(){reject(action("9.0","a"));}
    public void testB5StyleNormalizesToOnlyTrustedV2TargetFields(){
        Action a=action("2.0","a");a.setType("setStyle");a.setBackground("#112233");
        ActionTargetValidator.normalize(a,context());
        assertEquals("2.0",a.getSchemaVersion());assertEquals("a",a.getTarget().getSheetId());
        assertEquals("A1",a.getTarget().getRange());assertNull(a.getTarget().getSheetName());assertNull(a.getRange());
        assertEquals("#112233",a.getBackground());
        JsonNode wire=new ObjectMapper().valueToTree(a);assertFalse(wire.has("range"));assertFalse(wire.path("target").has("sheetName"));
    }
    public void testB5DimensionsDoNotInventOrSilentlyDropTargets(){
        for(String type:new String[]{"setRowHeight","setColumnWidth"}){
            Action a=action("2.0","a");a.setType(type);a.setRange(null);a.getTarget().setRange(null);
            ActionTargetValidator.normalize(a,context());
            assertEquals("a",a.getTarget().getSheetId());assertNull(a.getTarget().getSheetName());
            assertNull(a.getTarget().getRange());assertNull(a.getRange());
            JsonNode wire=new ObjectMapper().valueToTree(a);assertFalse(wire.has("range"));assertFalse(wire.path("target").has("sheetName"));assertFalse(wire.path("target").has("range"));
            Action withRange=action("2.0","a");withRange.setType(type);reject(withRange);
            Action blankRange=action("2.0","a");blankRange.setType(type);blankRange.setRange("");blankRange.getTarget().setRange("");
            ActionTargetValidator.normalize(blankRange,context());assertFalse(new ObjectMapper().valueToTree(blankRange).path("target").has("range"));
        }
    }
    public void testB5NeverInfersSheetFromLegacyOrActiveContext(){
        Action legacy=action("1.0",null);legacy.setType("setStyle");reject(legacy);
        Action missing=action("2.0",null);missing.setType("setStyle");reject(missing);
        Action unnamed=action(null,"a");unnamed.setType("setStyle");reject(unnamed);
    }
    public void testB5NormalizedWireCanEnterTrustedPreviewWithoutOpeningGate()throws Exception{
        ObjectMapper mapper=new ObjectMapper();JsonNode snapshot=mapper.readTree("{\"sheets\":{\"a\":{\"rowCount\":10,\"columnCount\":10,\"defaultRowHeight\":24,\"rowData\":{\"0\":{\"h\":24}},\"columnData\":{\"0\":{\"w\":80}}}}}");
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            Action a=action("2.0","a");a.setType(type);
            if("setStyle".equals(type))a.setBackground("#112233");
            else {a.setRange(null);a.getTarget().setRange(null);if("setRowHeight".equals(type)){a.setRowPosition(1);a.setHeight(50);}else{a.setColumnPosition(1);a.setWidth(120);}}
            ActionTargetValidator.normalize(a,context());
            String scope="setStyle".equals(type)?"A1":"setRowHeight".equals(type)?"A1:J1":"A1:A10";
            JsonNode scopes=mapper.readTree("[{\"sheetId\":\"a\",\"range\":\""+scope+"\"}]");
            assertEquals(type,WriteManifest.previewB5Format(snapshot,mapper.valueToTree(a),scopes).path("patch").path("type").asText());
        }
    }
}
