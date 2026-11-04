package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** B6: one bounded filter resource effect, derived only from a trusted workbook. */
public final class FilterManifest {
    private static final String RESOURCE="SHEET_FILTER_PLUGIN";
    private FilterManifest() { }

    public static boolean isFilterGroup(JsonNode actions) {
        return actions.isArray()&&actions.size()==1&&Arrays.asList("setFilter","clearFilter").contains(actions.get(0).path("type").asText());
    }
    private static void fields(JsonNode value,String... allowed) {
        require(value.isObject(),"B6_FILTER_INVALID: 必须是对象");
        Set<String> keys=new HashSet<>(Arrays.asList(allowed));
        value.fieldNames().forEachRemaining(key->require(keys.contains(key),"B6_FILTER_INVALID: 未注册字段 "+key));
    }
    private static int exactInt(JsonNode value,int min,int max,String field) {
        require(value.isIntegralNumber()&&value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(min))>=0&&value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(max))<=0,"B6_FILTER_INVALID: "+field+" 非法");
        return value.intValue();
    }
    private static JsonNode filterResource(JsonNode snapshot) {
        JsonNode found=MissingNode.getInstance();
        for(JsonNode resource:snapshot.path("resources")) {
            require(resource.isObject()&&resource.path("name").isTextual(),"B6_FILTER_INVALID: 资源结构无效");
            String name=resource.path("name").asText();
            if(name.toLowerCase(Locale.ROOT).matches(".*(protection|permission|authz|conditional).*"))
                require(resource.path("data").isTextual()&&Arrays.asList("","{}").contains(resource.path("data").asText().trim()),"B6_FILTER_INVALID: 保护或条件资源未证明安全");
            if(RESOURCE.equals(name)) { require(found.isMissingNode()&&resource.path("data").isTextual(),"B6_FILTER_INVALID: 重复或无效筛选资源");found=resource.path("data"); }
        }
        return found;
    }
    private static ObjectNode filterMap(JsonNode data) {
        if(data.isMissingNode())return obj();
        try {JsonNode parsed=JSON.readTree(data.asText());require(parsed.isObject(),"B6_FILTER_INVALID: 筛选资源必须为对象");return ((ObjectNode)parsed).deepCopy();}
        catch(Exception e){throw new IllegalArgumentException("B6_FILTER_INVALID: 筛选资源 JSON 无效",e);}
    }
    private static ArrayNode existingColumns(JsonNode filter,int[] range) {
        require(filter.path("ref").isObject()&&filter.path("filterColumns").isArray(),"B6_FILTER_INVALID: 既有筛选结构无效");
        JsonNode ref=filter.path("ref");
        require(WriteManifest.sameIntegral(ref.path("startRow"),range[0])&&WriteManifest.sameIntegral(ref.path("startColumn"),range[1])&&WriteManifest.sameIntegral(ref.path("endRow"),range[2])&&WriteManifest.sameIntegral(ref.path("endColumn"),range[3]),"B6_FILTER_INVALID: 既有筛选范围不同");
        ArrayNode cols=(ArrayNode)filter.path("filterColumns");require(cols.size()<=1,"B6_FILTER_INVALID: 多列筛选尚未验证");
        for(JsonNode c:cols){fields(c,"colId","filters");require(c.path("filters").isObject()&&c.path("filters").path("filters").isArray(),"B6_FILTER_INVALID: 既有条件未注册");fields(c.path("filters"),"filters");}
        return cols;
    }
    private static ArrayNode hidden(JsonNode sheet,int[] range,int column,JsonNode filters) {
        Set<String> accepted=new HashSet<>();for(JsonNode value:filters)accepted.add(value.asText());
        ArrayNode rows=arr();for(int row=range[0]+1;row<=range[2];row++) {
            JsonNode cell=AgentTools.cell(sheet,row,column);require(!cell.hasNonNull("f")&&!cell.hasNonNull("p")&&!cell.hasNonNull("si"),"B6_FILTER_INVALID: 公式或富文本筛选未验证");
            JsonNode value=cell.path("v");require(value.isMissingNode()||value.isNull()||value.isTextual()||value.isNumber(),"B6_FILTER_INVALID: 筛选值类型未注册");
            String scalar=value.isNumber()?value.decimalValue().stripTrailingZeros().toPlainString():value.asText();
            if(value.isMissingNode()||value.isNull()||!accepted.contains(scalar))rows.add(row);
        }
        return rows;
    }
    public static ObjectNode preview(JsonNode snapshot,JsonNode raw,JsonNode scopes) {
        WriteManifest.checkScopes(scopes,snapshot);require(raw.isObject()&&"2.0".equals(raw.path("schemaVersion").asText()),"B6_FILTER_INVALID: V2 动作必需");
        String type=text(raw,"type"),sheetId;boolean set="setFilter".equals(type);
        require(set||"clearFilter".equals(type),"B6_FILTER_INVALID: 动作未注册");
        fields(raw,set?new String[]{"schemaVersion","type","target","columnIndex","filters"}:new String[]{"schemaVersion","type","target","columnIndex","removeFilter"});
        JsonNode target=raw.path("target");fields(target,"sheetId","range");sheetId=text(target,"sheetId");int[] r=AgentTools.range(text(target,"range"));JsonNode sheet=AgentTools.sheet(snapshot,sheetId);
        require(r[2]<sheet.path("rowCount").asInt()&&r[3]<sheet.path("columnCount").asInt()&&r[2]>r[0]&&r[3]>=r[1]&&
                (long)(r[2]-r[0]+1)*(r[3]-r[1]+1)<=WriteManifest.MAX_WRITE_CELLS,"B6_FILTER_INVALID: 必须是有界、含表头的显式范围");
        boolean authorized=false;for(JsonNode scope:scopes){int[] s=AgentTools.range(text(scope,"range"));if(sheetId.equals(scope.path("sheetId").asText())&&r[0]>=s[0]&&r[1]>=s[1]&&r[2]<=s[2]&&r[3]<=s[3])authorized=true;}
        require(authorized,"WRITE_SCOPE_DENIED: 筛选影响范围超出授权");
        for(JsonNode merge:sheet.path("mergeData"))require(r[2]<merge.path("startRow").asInt()||r[0]>merge.path("endRow").asInt()||r[3]<merge.path("startColumn").asInt()||r[1]>merge.path("endColumn").asInt(),"B6_FILTER_INVALID: 合并范围未验证");
        for(JsonNode row:sheet.path("cellData"))for(JsonNode cell:row)require(!cell.hasNonNull("f")&&!cell.hasNonNull("si"),"B6_FILTER_INVALID: 含公式工作簿未验证");
        boolean removeAll=!set&&raw.path("removeFilter").asBoolean(false);
        int column=removeAll?-1:exactInt(raw.path("columnIndex"),r[1],r[3],"columnIndex");
        JsonNode before=filterResource(snapshot);ObjectNode map=filterMap(before),prior=map.deepCopy();JsonNode old=map.path(sheetId);ArrayNode existing=old.isMissingNode()?arr():existingColumns(old,r);
        ArrayNode columns=arr(),filtered=arr();
        if(set){JsonNode values=raw.path("filters");require(values.isArray()&&values.size()>0&&values.size()<=20,"B6_FILTER_INVALID: 按值筛选清单必须为1至20项");Set<String> distinct=new HashSet<>();
            ArrayNode normalized=arr();for(JsonNode value:values){require(value.isTextual()&&value.asText().length()<=100&&distinct.add(value.asText()),"B6_FILTER_INVALID: 筛选值必须是唯一短文本");normalized.add(value.asText());}
            require(existing.size()==0||WriteManifest.sameIntegral(existing.get(0).path("colId"),column-r[1]),"B6_FILTER_INVALID: 多列筛选尚未验证");
            ObjectNode criterion=obj().put("colId",column-r[1]);criterion.set("filters",obj().set("filters",normalized));columns.add(criterion);filtered=hidden(sheet,r,column,normalized);
        } else {
            require(!old.isMissingNode(),"B6_FILTER_INVALID: 目标无 Filter");
            if(removeAll){require(raw.path("removeFilter").isBoolean()&&!raw.has("columnIndex"),"B6_FILTER_INVALID: 整体移除参数冲突");map.remove(sheetId);}
            else {require(raw.has("columnIndex")&&existing.size()==1&&WriteManifest.sameIntegral(existing.get(0).path("colId"),column-r[1]),"B6_FILTER_INVALID: 清除列条件与既有条件不符");}
        }
        if(set||!removeAll){
            ObjectNode filter=obj(),ref=obj().put("startRow",r[0]).put("startColumn",r[1]).put("endRow",r[2]).put("endColumn",r[3]);
            filter.set("ref",ref);filter.set("filterColumns",columns);filter.set("cachedFilteredOut",filtered);map.set(sheetId,filter);
        }
        ObjectNode patch=obj().put("type",type).put("sheetId",sheetId).put("range",text(target,"range")).put("resourceData",map.toString());
        if(before.isMissingNode())patch.putNull("beforeData");else patch.set("beforeData",before);
        patch.set("filter",map.path(sheetId).isMissingNode()?NullNode.instance:map.path(sheetId).deepCopy());
        ObjectNode readback=obj().put("sheetId",sheetId).put("range",text(target,"range")).put("hasFilter",!map.path(sheetId).isMissingNode());
        readback.set("filter",patch.path("filter").deepCopy());
        ObjectNode result=obj().put("transportVersion",2);result.set("action",raw.deepCopy());result.set("actions",arr().add(raw.deepCopy()));result.set("filterPatch",patch);result.set("filterReadback",readback);result.set("writeScopes",scopes.deepCopy());return result;
    }
    public static void validateReceipt(JsonNode manifest,JsonNode readback) {
        require(readback.isObject()&&readback.equals(manifest.path("filterReadback")),"B6_READBACK_INVALID: 实际 Filter 与可信资源不符");
    }
    public static ObjectNode apply(JsonNode trusted,JsonNode manifest) {
        ObjectNode expected=preview(trusted,manifest.path("action"),manifest.path("writeScopes"));
        require(expected.path("filterPatch").equals(manifest.path("filterPatch")),"B6_FILTER_INVALID: 补丁与可信动作不一致");
        ObjectNode next=trusted.deepCopy();ArrayNode resources=next.path("resources").isArray()?(ArrayNode)next.path("resources"):next.putArray("resources");
        boolean found=false;for(JsonNode resource:resources)if(RESOURCE.equals(resource.path("name").asText())){((ObjectNode)resource).put("data",text(manifest.path("filterPatch"),"resourceData"));found=true;}
        if(!found)resources.add(obj().put("name",RESOURCE).put("data",text(manifest.path("filterPatch"),"resourceData")));
        return next;
    }
    public static ObjectNode verifySaved(JsonNode snapshot,JsonNode args,int[] range) {
        JsonNode write=args.path("write"),change=write.path("executedChange"),patch=change.path("filterPatch");String savedHash=hash(snapshot),callId=text(write,"callId");
        require(WriteManifest.isControlledTransport(write.path("transportVersion"))&&"SAVED".equals(write.path("persistenceStatus").asText())&&"PASSED".equals(write.path("validation").asText())&&"controlled-filter".equals(change.path("kind").asText())&&callId.equals(change.path("operationId").asText())&&savedHash.equals(write.path("loadedRevision").asText())&&savedHash.equals(write.path("sourceRevision").asText())&&savedHash.equals(change.path("afterHash").asText()),"RESULT_UNKNOWN: 缺少可信已保存筛选变更");
        require(patch.isObject()&&text(patch,"sheetId").equals(text(args,"sheetId"))&&text(patch,"range").equals(text(args,"range")),"RESULT_UNKNOWN: B6 验证目标与保存范围不一致");
        JsonNode data=filterResource(snapshot);require(data.isTextual()&&data.asText().equals(text(patch,"resourceData")),"RESULT_UNKNOWN: B6 已保存筛选资源与可信补丁不符");
        JsonNode filter=filterMap(data).path(text(patch,"sheetId"));require((filter.isMissingNode()?NullNode.instance:filter).equals(patch.path("filter")),"RESULT_UNKNOWN: B6 已保存 Filter 与可信补丁不符");
        if(filter.isObject()){
            ArrayNode cols=existingColumns(filter,range);if(cols.size()==1){int col=range[1]+exactInt(cols.get(0).path("colId"),0,range[3]-range[1],"colId");ArrayNode calculated=hidden(AgentTools.sheet(snapshot,text(patch,"sheetId")),range,col,cols.get(0).path("filters").path("filters"));require(calculated.equals(filter.path("cachedFilteredOut")),"RESULT_UNKNOWN: B6 可见行缓存与数据不符");}
            else require(filter.path("cachedFilteredOut").isArray()&&filter.path("cachedFilteredOut").size()==0,"RESULT_UNKNOWN: B6 已清条件仍有隐藏行");
        }
        return obj().put("passed",true).put("checkedCells",(range[2]-range[0]+1)*(range[3]-range[1]+1)).put("method","saved-controlled-filter-resource-comparison");
    }
}
