package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Atomic B9 create-and-fill: the source is checked against the unchanged pre-write revision. */
final class ResultSheetManifest {
    private ResultSheetManifest(){}
    static ObjectNode action(JsonNode authorization){
        require(authorization.isObject()&&authorization.size()==4,"RESULT_SHEET_AUTH_REQUIRED: 需要明确锚点、名称和有限尺寸");
        ObjectNode action=obj().put("schemaVersion","2.0").put("type","createSheet").put("newSheetName",text(authorization,"newSheetName"));
        action.set("target",obj().put("sheetId",text(authorization,"anchorSheetId")));for(String k:Arrays.asList("rowCount","columnCount"))action.set(k,authorization.path(k));return action;
    }
    static ObjectNode preview(JsonNode snapshot,JsonNode authorization,JsonNode scopes){return StructureManifest.preview(snapshot,action(authorization),scopes);}
    static ObjectNode create(ObjectNode run,JsonNode record,JsonNode snapshot,JsonNode args){
        require(args.isObject()&&args.size()==3&&args.has("dataset")&&args.has("newSheet")&&"confirmed".equals(args.path("overwrite").asText()),"RESULT_SHEET_INVALID: 仅接受 dataset/newSheet/overwrite:confirmed");
        require(run.path("resultSheetAuthorization").isObject()&&WriteManifest.digest(run.path("resultSheetAuthorization")).equals(WriteManifest.digest(args.path("newSheet"))),"RESULT_SHEET_AUTH_REQUIRED: 新 Sheet 与明确授权不符");
        ResultWriteManifest.validateSource(run,record,snapshot,args.path("dataset"),"");
        ObjectNode manifest=filled(snapshot,action(args.path("newSheet")),run.path("writeScopes"),record.path("data"),text(run,"loadedRevision"));
        ObjectNode source=obj();for(String key:Arrays.asList("resultRef","sourceRevision","runId","userId","workbookId","branchId","dataDigest","lineageDigest","lineage"))source.set(key,record.path(key));
        manifest.set("resultSource",source);manifest.put("resultSheet",true).put("overwrite","confirmed");manifest.set("resultWriteArgs",args.deepCopy());return manifest;
    }
    private static ObjectNode filled(JsonNode snapshot,JsonNode action,JsonNode scopes,JsonNode data,String beforeContentHash){
        ObjectNode m=StructureManifest.preview(snapshot,action,scopes),patch=(ObjectNode)m.path("structurePatch");ArrayNode matrix=ResultWriteManifest.values(data);
        require(matrix.size()==patch.path("rowCount").asInt()&&matrix.get(0).size()==patch.path("columnCount").asInt(),"RESULT_WRITE_SHAPE: 结果必须精确匹配新 Sheet 授权尺寸");
        String id=text(patch,"newSheetId"),range="A1:"+column(matrix.get(0).size())+matrix.size();
        ObjectNode valueAction=obj().put("schemaVersion","2.0").put("type","setValues");valueAction.set("target",obj().put("sheetId",id).put("range",range));valueAction.set("values",matrix);
        ObjectNode valueManifest=WriteManifest.create(patch.path("afterWorkbook"),arr().add(valueAction),arr().add(obj().put("sheetId",id).put("range",range)),id,"empty-only",false);
        ObjectNode after=WriteManifest.apply(patch.path("afterWorkbook"),valueManifest);patch.set("afterWorkbook",after);patch.put("afterDigest",WriteManifest.digest(after));patch.set("resultData",data.deepCopy());
        if(beforeContentHash!=null)patch.put("beforeContentHash",beforeContentHash);
        ((ObjectNode)m.path("structureReadback")).put("workbookDigest",text(patch,"afterDigest"));return m;
    }
    private static String column(int count){StringBuilder s=new StringBuilder();for(int n=count;n>0;n=(n-1)/26)s.insert(0,(char)('A'+(n-1)%26));return s.toString();}
    static ObjectNode apply(JsonNode snapshot,JsonNode manifest){JsonNode patch=manifest.path("structurePatch");ObjectNode expected=filled(snapshot,manifest.path("action"),manifest.path("writeScopes"),patch.path("resultData"),patch.has("beforeContentHash")?text(manifest,"loadedRevision"):null);require(WriteManifest.digest(expected.path("structurePatch")).equals(WriteManifest.digest(patch)),"RESULT_SHEET_INVALID: 完整新 Sheet 补丁与可信展开不符");return expected.path("structurePatch").path("afterWorkbook").deepCopy();}
    static ObjectNode verifySaved(JsonNode snapshot,JsonNode args){
        JsonNode write=args.path("write"),change=write.path("executedChange"),patch=change.path("structurePatch"),source=change.path("resultSource");String saved=hash(snapshot);
        // loadedRevision hashes the original UTF-8 content; sourceRevision hashes the parsed snapshot.
        // Preserve the historical canonical-content rule for saved records without the new binding.
        String beforeContentHash=patch.has("beforeContentHash")?text(patch,"beforeContentHash"):hash(patch.path("beforeWorkbook"));
        require(WriteManifest.isControlledTransport(write.path("transportVersion"))&&"SAVED".equals(write.path("persistenceStatus").asText())&&"PASSED".equals(write.path("validation").asText())&&"controlled-structure".equals(change.path("kind").asText())&&text(write,"callId").equals(text(change,"operationId"))&&saved.equals(text(write,"loadedRevision"))&&saved.equals(text(write,"sourceRevision"))&&saved.equals(text(change,"afterHash"))&&beforeContentHash.equals(text(change,"beforeHash")),"RESULT_UNKNOWN: 新 Sheet 结果缺少可信保存身份");
        require(text(patch,"sheetId").equals(text(args,"sheetId"))&&text(patch,"scope").equals(text(args,"range")),"RESULT_UNKNOWN: 新 Sheet 结果验证锚点或范围不符");
        ObjectNode record=source.deepCopy();record.set("data",patch.path("resultData"));ObjectNode binding=source.deepCopy();binding.put("revision",text(source,"sourceRevision"));ResultWriteManifest.validateSource(binding,record,patch.path("beforeWorkbook"),source,"");
        ObjectNode manifest=obj().put("loadedRevision",text(change,"beforeHash"));manifest.set("action",patch.path("action"));manifest.set("writeScopes",patch.path("writeScopes"));manifest.set("structurePatch",patch);
        require(WriteManifest.digest(snapshot).equals(WriteManifest.digest(apply(patch.path("beforeWorkbook"),manifest))),"RESULT_UNKNOWN: 新 Sheet 保存内容与来源展开不符");return obj().put("passed",true).put("method","saved-controlled-result-sheet-comparison");
    }
}
