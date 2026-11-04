package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Explicit ordinary-AI selection. Never changes the same-run Artifact lookup contract. */
final class SelectedResultSource {
    private SelectedResultSource(){}
    static ObjectNode record(AgentStore store,ObjectNode target,JsonNode selection,JsonNode snapshot){
        require(selection.isObject()&&selection.size()==5,"SOURCE_SELECTION_INVALID: 请选择服务端结果");
        for(String key:Arrays.asList("sourceRunId","resultRef","sourceRevision","dataDigest","lineageDigest"))text(selection,key);
        ObjectNode source=store.get(text(selection,"sourceRunId"));
        require("COMPLETED".equals(source.path("status").asText())&&!"CONTROLLED_WRITE".equals(source.path("kind").asText())&&source.path("plan").path("steps").isArray()&&source.path("plan").path("steps").size()>0&&!source.has("lastControlledResult"),"SOURCE_FORBIDDEN: 仅支持已完成只读任务的结果");
        for(JsonNode step:source.path("plan").path("steps"))require(Arrays.asList("get_workbook_catalog","inspect_sheet","read_range","build_dataset","aggregate_table","derive_columns","join_tables","check_data_quality","verify_result").contains(step.path("tool").asText()),"SOURCE_FORBIDDEN: 来源任务包含写入或未知工具");
        for(JsonNode step:source.path("completedSteps"))require(!AgentPlan.WRITES.contains(step.path("tool").asText())&&!step.path("tool").asText().isEmpty(),"SOURCE_FORBIDDEN: 来源任务已有写入历史");
        for(String field:Arrays.asList("userId","workbookId","branchId"))require(source.path(field).isIntegralNumber()&&target.path(field).isIntegralNumber()&&source.path(field).bigIntegerValue().equals(target.path(field).bigIntegerValue()),"SOURCE_FORBIDDEN: 来源任务归属不符");
        require(text(source,"revision").equals(text(target,"revision")),"SOURCE_STALE: 来源任务与当前版本不同");
        ObjectNode record=store.artifactRecord(source,text(selection,"resultRef"));
        for(String field:Arrays.asList("sourceRevision","dataDigest","lineageDigest"))require(text(record,field).equals(text(selection,field)),"SOURCE_STALE: 所选结果已变化，请刷新");
        ResultWriteManifest.validateSource(source,record,snapshot,selection,"");ResultWriteManifest.values(record.path("data"));return record;
    }
    static ObjectNode prepare(AgentStore store,ObjectNode target,JsonNode selection,JsonNode args,JsonNode snapshot){
        ObjectNode record=record(store,target,selection,snapshot);
        // Validate the source as its owning run, while keeping destination scopes and policy from the new run.
        ObjectNode binding=target.deepCopy().put("runId",text(record,"runId"));
        ObjectNode manifest=ResultWriteManifest.create(binding,record,snapshot,args);
        manifest.set("selectedResult",selection.deepCopy());manifest.set("resultWriteArgs",args.deepCopy());return manifest;
    }
}
