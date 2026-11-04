package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** B8a: one server-owned logical effect per run and target; never dispatch an old effect twice. */
final class StableEffectLedger {
    private final JdbcTemplate db;
    StableEffectLedger(JdbcTemplate db){this.db=db;}
    private static ObjectNode location(JsonNode m){
        ObjectNode target=obj();ArrayNode cells=arr();String kind;
        if(m.has("cells")){
            kind="values";List<String> locations=new ArrayList<>();
            for(JsonNode c:m.path("cells"))locations.add(c.path("sheetId").asText()+":"+c.path("row").asInt()+":"+c.path("column").asInt());
            Collections.sort(locations);for(String s:locations)cells.add(s);
        }else if(m.has("patch")){kind="format";JsonNode p=m.path("patch");target.put("type",p.path("type").asText()).put("sheetId",p.path("sheetId").asText()).put("startRow",p.path("startRow").asInt()).put("endRow",p.path("endRow").asInt()).put("startColumn",p.path("startColumn").asInt()).put("endColumn",p.path("endColumn").asInt());}
        else if(m.has("filterPatch")){kind="filter";JsonNode p=m.path("filterPatch");target.put("sheetId",p.path("sheetId").asText()).put("range",p.path("range").asText());}
        else if(m.has("formulaPatch")){kind="formula";JsonNode p=m.path("formulaPatch");target.put("sheetId",p.path("sheetId").asText()).put("range",p.path("range").asText());}
        else if(m.has("mergePatch")){kind="merge";JsonNode p=m.path("mergePatch");target.put("type",p.path("type").asText()).put("sheetId",p.path("sheetId").asText()).put("range",p.path("range").asText());}
        else if(m.has("structurePatch")){kind="structure";JsonNode p=m.path("structurePatch");target.put("type",p.path("type").asText()).put("sheetId",p.path("sheetId").asText());if(Arrays.asList("createSheet","copySheet").contains(p.path("type").asText()))target.put("index",p.path("index").asInt()).put("newSheetId",p.path("newSheetId").asText()).put("newSheetName",p.path("newSheetName").asText());else if("renameSheet".equals(p.path("type").asText()))target.put("newSheetName",p.path("newSheetName").asText());else if("deleteSheet".equals(p.path("type").asText()))target.put("fallbackSheetId",p.path("fallbackSheetId").asText());else target.put("index",p.path("index").asInt()).put("count",p.path("count").asInt()).put("position",p.path("position").asText());}
        else throw new IllegalArgumentException("EFFECT_TARGET_INVALID: 缺少可信写补丁");
        target.put("kind",kind);if(cells.size()>0)target.set("cells",cells);return target;
    }
    static ObjectNode identity(JsonNode manifest){
        ObjectNode target=location(manifest),parameters=obj();parameters.set("target",target);
        if(manifest.has("cells")){ArrayNode values=arr();List<JsonNode> sorted=new ArrayList<>();for(JsonNode c:manifest.path("cells"))sorted.add(c);sorted.sort(Comparator.comparing(c->c.path("sheetId").asText()+":"+c.path("row").asInt()+":"+c.path("column").asInt()));for(JsonNode c:sorted){ObjectNode v=obj().put("sheetId",c.path("sheetId").asText()).put("row",c.path("row").asInt()).put("column",c.path("column").asInt());v.set("value",c.path("value").deepCopy());if(c.has("operation"))v.set("operation",c.path("operation").deepCopy());values.add(v);}parameters.set("values",values);}
        else if(manifest.has("patch"))parameters.set("patchValue",manifest.path("patch").path("value").deepCopy());
        else if(manifest.has("filterPatch"))parameters.set("filterPatch",manifest.path("filterPatch").deepCopy());
        else if(manifest.has("formulaPatch"))parameters.set("formulaPatch",manifest.path("formulaPatch").deepCopy());
        else if(manifest.has("mergePatch"))parameters.set("mergePatch",manifest.path("mergePatch").deepCopy());
        else parameters.set("structurePatch",manifest.path("structurePatch").deepCopy());
        if(manifest.has("resultSource"))parameters.set("resultSource",manifest.path("resultSource").deepCopy());parameters.set("writeScopes",manifest.path("writeScopes").deepCopy());parameters.set("overwrite",manifest.path("overwrite").deepCopy());
        ObjectNode identity=obj().put("semanticKey",WriteManifest.digest(target)).put("argsHash",WriteManifest.digest(parameters));identity.set("target",target);return identity;
    }
    String reserve(String runId,String callId,JsonNode manifest){
        ObjectNode identity=identity(manifest);String key=identity.path("semanticKey").asText(),args=identity.path("argsHash").asText(),effectId=UUID.randomUUID().toString();
        int inserted=db.update("insert ignore into ai_agent_effect(effect_id,run_id,semantic_key,args_hash,target_json,status,call_id,before_revision,created_at,updated_at) values(?,?,?,?,?,'PREPARED',?,?,now(3),now(3))",effectId,runId,key,args,identity.path("target").toString(),callId,manifest.path("loadedRevision").asText());
        if(inserted==1)return effectId;
        List<Map<String,Object>> rows=db.queryForList("select effect_id,args_hash,status from ai_agent_effect where run_id=? and semantic_key=? for update",runId,key);
        require(rows.size()==1,"EFFECT_IDENTITY_CONFLICT: 效果身份已被占用");
        require(args.equals(String.valueOf(rows.get(0).get("args_hash"))),"EFFECT_PARAMETER_CONFLICT: 同一效果目标的派发参数已变化，须显式修复效果");
        throw new IllegalArgumentException("EFFECT_ALREADY_REGISTERED: 同一任务的效果已有持久事实或在途调用，禁止重复派发");
    }
    void transition(String effectId,String runId,String callId,String from,String to,String after,String receipt,JsonNode result){
        int updated=db.update("update ai_agent_effect set status=?,after_revision=?,receipt_hash=?,result_json=?,updated_at=now(3) where effect_id=? and run_id=? and call_id=? and status=?",to,after,receipt,result==null?null:result.toString(),effectId,runId,callId,from);
        require(updated==1,"EFFECT_STATE_CONFLICT: 效果状态或调用身份不一致");
    }
    void assertStatus(String effectId,String runId,String callId,String status){
        List<String> states=db.queryForList("select status from ai_agent_effect where effect_id=? and run_id=? and call_id=?",String.class,effectId,runId,callId);
        require(states.size()==1&&status.equals(states.get(0)),"EFFECT_STATE_CONFLICT: 效果账本与调用账本不一致");
    }
    void assertPersisted(String runId,JsonNode call){
        List<Map<String,Object>> rows=db.queryForList("select status,after_revision,receipt_hash,result_json from ai_agent_effect where effect_id=? and run_id=? and call_id=?",call.path("effectId").asText(),runId,call.path("callId").asText());
        require(rows.size()==1,"EFFECT_STATE_CONFLICT: 持久效果事实缺失");Map<String,Object> row=rows.get(0);
        require("PERSISTED".equals(row.get("status"))&&Objects.equals(row.get("after_revision"),call.path("result").path("loadedRevision").asText())
            &&Objects.equals(row.get("receipt_hash"),call.path("receiptHash").asText())&&row.get("result_json")!=null
            &&parse(String.valueOf(row.get("result_json"))).equals(call.path("result")),"EFFECT_STATE_CONFLICT: 持久效果事实与保存回执不一致");
    }
}
