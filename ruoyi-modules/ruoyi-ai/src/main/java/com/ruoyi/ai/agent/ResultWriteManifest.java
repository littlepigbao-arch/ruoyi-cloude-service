package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** B9: server-owned Artifact expansion into the existing bounded V2 value manifest. */
public final class ResultWriteManifest {
    private ResultWriteManifest(){}
    static void validateSource(JsonNode run,JsonNode record,JsonNode snapshot,JsonNode reference,String targetSheet){
        require(record.isObject()&&reference.isObject()&&text(record,"resultRef").equals(text(reference,"resultRef")),"SOURCE_FORBIDDEN: 来源身份不符");
        require(text(record,"runId").equals(text(run,"runId")),"SOURCE_FORBIDDEN: 制品 Run 不符");for(String field:Arrays.asList("userId","workbookId","branchId"))require(record.path(field).isIntegralNumber()&&run.path(field).isIntegralNumber()&&record.path(field).bigIntegerValue().equals(run.path(field).bigIntegerValue()),"SOURCE_FORBIDDEN: 制品归属不符");
        String revision=text(record,"sourceRevision");require(revision.equals(text(reference,"sourceRevision"))&&revision.equals(text(run,"revision"))&&revision.equals(hash(snapshot)),"SOURCE_STALE: 来源或当前版本已变化");
        require(WriteManifest.digest(record.path("data")).equals(text(record,"dataDigest"))&&WriteManifest.digest(record.path("lineage")).equals(text(record,"lineageDigest")),"SOURCE_INVALID: 来源摘要不符");
        JsonNode lineage=record.path("lineage");require(lineage.isArray()&&lineage.size()>0&&lineage.size()<=30,"SOURCE_UNTRACKED: 缺少完整来源区域沿袭");Set<String> seen=new HashSet<>();
        for(JsonNode source:lineage){require(source.isObject()&&source.size()==3,"SOURCE_INVALID: 来源区域字段无效");String id=text(source,"sheetId"),range=text(source,"range");JsonNode sheet=AgentTools.sheet(snapshot,id);int[] r=AgentTools.range(range);require(r[2]<sheet.path("rowCount").asInt()&&r[3]<sheet.path("columnCount").asInt()&&seen.add(id+":"+range),"SOURCE_INVALID: 来源区域无效或重复");require(!id.equals(targetSheet),"SOURCE_OVERLAP: 首组目标不得使用来源 Sheet");require(WriteManifest.digest(sheet).equals(text(source,"sheetDigest")),"SOURCE_STALE: 来源 Sheet 已变化");}
    }
    static ObjectNode create(ObjectNode run,JsonNode record,JsonNode snapshot,JsonNode args){
        if(args.has("newSheet"))return ResultSheetManifest.create(run,record,snapshot,args);
        require(args.isObject(),"RESULT_WRITE_INVALID: 参数必须为对象");Set<String> allowed=new HashSet<>(Arrays.asList("dataset","sheetId","range","overwrite"));args.fieldNames().forEachRemaining(k->require(allowed.contains(k),"RESULT_WRITE_INVALID: 未注册参数 "+k));String target=text(args,"sheetId"),range=text(args,"range");validateSource(run,record,snapshot,args.path("dataset"),target);
        ArrayNode values=values(record.path("data"));
        int[] r=AgentTools.range(range);require(r[2]-r[0]+1==values.size()&&r[3]-r[1]+1==values.get(0).size(),"RESULT_WRITE_SHAPE: 目标必须精确匹配结果矩阵");ObjectNode action=obj().put("schemaVersion","2.0").put("type","setValues");action.set("target",obj().put("sheetId",target).put("range",range));action.set("values",values);
        ObjectNode manifest=WriteManifest.create(snapshot,arr().add(action),run.path("writeScopes"),run.path("activeSheetId").asText(),text(args,"overwrite"),run.path("allowOverwrite").asBoolean());
        ObjectNode frozen=obj();for(String field:Arrays.asList("resultRef","sourceRevision","runId","userId","workbookId","branchId","dataDigest","lineageDigest","lineage"))frozen.set(field,record.path(field).deepCopy());manifest.set("resultSource",frozen);return manifest;
    }
    static ArrayNode values(JsonNode data){
        JsonNode columns=data.path("columns"),rows=data.path("rows");require(data.isObject()&&columns.isArray()&&columns.size()>0&&rows.isArray()&&rows.size()>0&&(long)(rows.size()+1)*columns.size()<=WriteManifest.MAX_WRITE_CELLS&&data.toString().length()<=64000,"RESULT_WRITE_BUDGET: 数据集为空、不完整或超过预算");
        Set<String> names=new LinkedHashSet<>();ArrayNode values=arr(),header=arr();for(JsonNode column:columns){require(column.isTextual(),"RESULT_WRITE_INVALID: 列名必须为文本");String name=column.asText();require(!name.trim().isEmpty()&&name.equals(name.trim())&&!name.startsWith("=")&&names.add(name),"RESULT_WRITE_INVALID: 列名为空、重复或危险");header.add(name);}values.add(header);
        for(JsonNode row:rows){require(row.isObject()&&row.size()==names.size(),"RESULT_WRITE_SHAPE: 数据行字段不完整");ArrayNode out=arr();for(String name:names){JsonNode value=row.get(name);require(value!=null&&(value.isNull()||value.isBoolean()||value.isNumber()||value.isTextual()),"RESULT_WRITE_INVALID: 结果必须为完整标量");require(!value.isTextual()||!value.asText().trim().startsWith("="),"RESULT_WRITE_INVALID: 不允许公式字符串");out.add(value.deepCopy());}values.add(out);}
        return values;
    }
}
