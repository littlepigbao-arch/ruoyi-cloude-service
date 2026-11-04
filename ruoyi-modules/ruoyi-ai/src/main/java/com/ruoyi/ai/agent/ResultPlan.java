package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Pure B9 admission adapter. The returned placeholder is validated only, never dispatched. */
final class ResultPlan {
    private ResultPlan(){}
    static JsonNode admissionCopy(JsonNode plan,boolean enabled){
        return admissionCopy(plan,enabled,false,obj());
    }
    static JsonNode admissionCopy(JsonNode plan,boolean enabled,boolean sheetEnabled,JsonNode run){
        boolean present=false,newSheet=false;for(JsonNode step:plan.path("steps"))if("write_result".equals(step.path("tool").asText())){present=true;newSheet|=step.path("inputBindings").has("newSheet");}
        require(!run.has("resultSheetAuthorization")||present&&newSheet,"RESULT_SHEET_AUTH_REQUIRED: 结构结果授权只能用于新 Sheet 结果任务");if(!present)return plan;
        require(enabled,"AI_CAPABILITY_BLOCKED: B9 resultRef 写回未开放");for(JsonNode step:plan.path("steps"))rejectLiteralSources(step.path("inputBindings"));ObjectNode copy=plan.deepCopy();Map<String,Set<String>> sources=new HashMap<>();String writeId=null;JsonNode writeArgs=null;
        for(JsonNode step:copy.path("steps")){
            String id=text(step,"stepId"),tool=text(step,"tool");JsonNode a=step.path("inputBindings");require(!"apply_actions".equals(tool),"RESULT_WRITE_POLICY: 首组不能混排其他写入");
            if(writeId!=null)require("verify_result".equals(tool),"RESULT_WRITE_POLICY: 写回后只允许结果验证");
            if("build_dataset".equals(tool))sources.put(id,new HashSet<>(Arrays.asList(text(a,"sheetId"))));
            if(Arrays.asList("aggregate_table","derive_columns","join_tables").contains(tool)){Set<String> set=new HashSet<>();for(String key:"join_tables".equals(tool)?new String[]{"left","right"}:new String[]{"dataset"})set.addAll(source(a.path(key),sources,step.path("dependsOn")));sources.put(id,set);}
            if("write_result".equals(tool)){
                if(a.has("newSheet")){
                    require(writeId==null&&sheetEnabled,"AI_CAPABILITY_BLOCKED: 新 Sheet 结果写回未开放或重复写入");
                    require(a.isObject()&&a.size()==3&&a.has("dataset")&&"confirmed".equals(a.path("overwrite").asText())&&run.path("resultSheetAuthorization").isObject()&&WriteManifest.digest(a.path("newSheet")).equals(WriteManifest.digest(run.path("resultSheetAuthorization"))),"RESULT_SHEET_AUTH_REQUIRED: 新 Sheet 计划必须精确匹配授权");
                    source(a.path("dataset"),sources,step.path("dependsOn"));writeId=id;writeArgs=a.deepCopy();ObjectNode replacement=obj().put("overwrite","confirmed");replacement.set("actions",arr().add(ResultSheetManifest.action(a.path("newSheet"))));((ObjectNode)step).put("tool","apply_actions").set("inputBindings",replacement);continue;
                }
                require(writeId==null,"RESULT_WRITE_POLICY: 首组只接受一次结果写回");require(a.isObject()&&a.size()==4&&a.has("dataset")&&a.has("sheetId")&&a.has("range")&&a.has("overwrite"),"RESULT_WRITE_INVALID: 仅接受 dataset/sheetId/range/overwrite");String target=text(a,"sheetId");require(!source(a.path("dataset"),sources,step.path("dependsOn")).contains(target),"SOURCE_OVERLAP: 结果目标不得使用来源 Sheet");writeId=id;writeArgs=a.deepCopy();
                int[] r=AgentTools.range(text(a,"range"));require((long)(r[2]-r[0]+1)*(r[3]-r[1]+1)<=WriteManifest.MAX_WRITE_CELLS,"RESULT_WRITE_BUDGET: 结果范围超预算");ArrayNode values=arr();for(int row=r[0];row<=r[2];row++){ArrayNode line=arr();for(int col=r[1];col<=r[3];col++)line.addNull();values.add(line);}ObjectNode action=obj().put("schemaVersion","2.0").put("type","setValues");action.set("target",obj().put("sheetId",target).put("range",text(a,"range")));action.set("values",values);ObjectNode replacement=obj().put("overwrite",text(a,"overwrite"));replacement.set("actions",arr().add(action));((ObjectNode)step).put("tool","apply_actions").set("inputBindings",replacement);
            }else if("verify_result".equals(tool)&&writeId!=null&&writeId.equals(a.path("write").path("$step").asText())){
                if(writeArgs.has("newSheet"))require("matches_structure".equals(a.path("kind").asText())&&text(a,"sheetId").equals(text(writeArgs.path("newSheet"),"anchorSheetId")),"RESULT_WRITE_VERIFY: 新 Sheet 必须验证完整结构保存");
                else require("matches_write".equals(a.path("kind").asText())&&text(a,"sheetId").equals(text(writeArgs,"sheetId"))&&text(a,"range").equals(text(writeArgs,"range")),"RESULT_WRITE_VERIFY: 结果验证必须精确匹配写回目标");
            }
        }
        return copy;
    }
    private static Set<String> source(JsonNode reference,Map<String,Set<String>> known,JsonNode dependencies){require(reference.isObject()&&reference.size()==1&&reference.has("$step")&&known.containsKey(reference.path("$step").asText()),"SOURCE_FORBIDDEN: 结果必须引用上游数据集步骤");String id=reference.path("$step").asText();boolean declared=false;for(JsonNode dep:dependencies)if(id.equals(dep.asText()))declared=true;require(declared,"SOURCE_FORBIDDEN: 来源步骤缺少显式依赖");return known.get(id);}
    private static void rejectLiteralSources(JsonNode n){if(n.isObject())require(!n.has("resultRef")&&!n.has("sourceRevision"),"SOURCE_FORBIDDEN: 计划不能编造来源引用或版本");if(n.isContainerNode())for(JsonNode child:n)rejectLiteralSources(child);}
}
