package com.ruoyi.ai.agent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;
public class B10MissingFormatImageTest extends TestCase {
 private ObjectNode snapshot(){return (ObjectNode)parse("{\"id\":\"book\",\"sheetOrder\":[\"s\"],\"sheets\":{\"s\":{\"id\":\"s\",\"rowCount\":6,\"columnCount\":6,\"defaultRowHeight\":24,\"cellData\":{},\"rowData\":{},\"columnData\":{}}}}");}
 private ObjectNode action(String type){ObjectNode a=obj().put("schemaVersion","2.0").put("type",type);ObjectNode target=obj().put("sheetId","s");if("setStyle".equals(type)){target.put("range","A1");a.put("background","#223344");}else if("setRowHeight".equals(type))a.put("rowPosition",1).put("height",50);else a.put("columnPosition",1).put("width",120);a.set("target",target);return a;}
 private ObjectNode manifest(ObjectNode s,String type){return WriteManifest.previewB5Format(s,action(type),arr().add(obj().put("sheetId","s").put("range","A1:F6")));}
 public void testAbsentRowMetadataSurvivesPersistedManifestJson(){ObjectNode s=snapshot(),m=(ObjectNode)parse(manifest(s,"setRowHeight").toString());ObjectNode after=WriteManifest.applyB5Format(s,m);assertEquals(50,after.path("sheets").path("s").path("rowData").path("0").path("h").asInt());assertEquals(0,after.path("sheets").path("s").path("rowData").path("0").path("ia").asInt());}
 public void testAbsentColumnMetadataSurvivesPersistedManifestJson(){ObjectNode s=snapshot(),m=(ObjectNode)parse(manifest(s,"setColumnWidth").toString());assertEquals(120,WriteManifest.applyB5Format(s,m).path("sheets").path("s").path("columnData").path("0").path("w").asInt());}
 public void testEmptyTargetCellStyleSurvivesPersistedManifestJson(){ObjectNode s=snapshot(),m=(ObjectNode)parse(manifest(s,"setStyle").toString());assertEquals("#223344",WriteManifest.applyB5Format(s,m).path("sheets").path("s").path("cellData").path("0").path("0").path("s").path("bg").path("rgb").asText());}
 public void testMissingBeforeFieldCannotBeTreatedAsAbsentTarget(){ObjectNode s=snapshot(),m=(ObjectNode)parse(manifest(s,"setRowHeight").toString());((ObjectNode)m.path("patch")).remove("before");try{WriteManifest.applyB5Format(s,m);fail("missing before accepted");}catch(IllegalArgumentException expected){}}
 public void testNewTargetMetadataStillConflictsWithFrozenAbsentImage(){ObjectNode s=snapshot(),m=(ObjectNode)parse(manifest(s,"setRowHeight").toString());((ObjectNode)s.path("sheets").path("s").path("rowData")).set("0",obj().put("h",30));try{WriteManifest.applyB5Format(s,m);fail("new metadata overwritten");}catch(IllegalArgumentException expected){}}
}
