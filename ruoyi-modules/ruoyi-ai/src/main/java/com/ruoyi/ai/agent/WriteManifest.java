package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.domain.dto.*;
import com.ruoyi.ai.service.ActionTargetValidator;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** B1: immutable, bounded value patches. This is not another cell execution engine. */
public final class WriteManifest {
    public static final int MAX_WRITE_CELLS=1000;
    private WriteManifest() { }
    public static String digest(JsonNode value) { return hash(sorted(value)); }
    private static JsonNode sorted(JsonNode value) {
        if(value.isObject()){ObjectNode out=obj();TreeSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)out.set(key,sorted(value.get(key)));return out;}
        if(value.isArray()){ArrayNode out=arr();for(JsonNode child:value)out.add(sorted(child));return out;}
        if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());return value;
    }
    public static void checkScopes(JsonNode scopes,JsonNode snapshot) {
        require(scopes.isArray()&&scopes.size()>0&&scopes.size()<=30,"WRITE_SCOPE_REQUIRED: 必须预先授权有限选区");
        for(JsonNode scope:scopes){JsonNode s=AgentTools.sheet(snapshot,text(scope,"sheetId"));int[] r=AgentTools.range(text(scope,"range"));require(r[2]<s.path("rowCount").asInt()&&r[3]<s.path("columnCount").asInt(),"WRITE_SCOPE_INVALID: 授权区域超出 Sheet");}
    }
    public static ObjectNode create(JsonNode snapshot,JsonNode rawActions,JsonNode scopes,String activeSheetId,String overwrite,boolean allowOverwrite) {
        checkScopes(scopes,snapshot);
        require(rawActions.isArray()&&rawActions.size()>0&&rawActions.size()<=30,"CONTROLLED_UNSUPPORTED: 动作数必须1至30");
        require(Arrays.asList("empty-only","confirmed").contains(overwrite),"WRITE_POLICY_INVALID");
        require(!"confirmed".equals(overwrite)||allowOverwrite,"WRITE_POLICY_DENIED: 覆盖必须由用户明确授权");
        // B1 deliberately excludes formula dependencies and protection semantics that cannot yet be verified.
        for(JsonNode s:snapshot.path("sheets"))for(JsonNode row:s.path("cellData"))for(JsonNode c:row)require(c.path("f").asText("").isEmpty()&&c.path("si").asText("").isEmpty(),"CONTROLLED_UNSUPPORTED: B1 暂不处理含公式的工作簿");
        for(JsonNode resource:snapshot.path("resources"))if(resource.path("name").asText().toLowerCase(Locale.ROOT).matches(".*(protection|permission|authz).*"))require(emptyDefaultPermission(resource),"CONTROLLED_UNSUPPORTED: 保护规则尚未支持");
        WorkbookContext context=new WorkbookContext();context.setActiveSheetId(activeSheetId);List<SheetContext> sheets=new ArrayList<>();
        Iterator<Map.Entry<String,JsonNode>> entries=snapshot.path("sheets").fields();while(entries.hasNext()){Map.Entry<String,JsonNode> entry=entries.next();SheetContext s=new SheetContext();s.setSheetId(entry.getKey());s.setSheetName(entry.getValue().path("name").asText());sheets.add(s);}context.setSheets(sheets);
        ArrayNode actions=arr(),cells=arr();Set<String> covered=new HashSet<>();
        for(JsonNode raw:rawActions){
            require(raw.isObject(),"CONTROLLED_UNSUPPORTED: 动作必须是对象");
            Set<String> allowed=new HashSet<>(Arrays.asList("schemaVersion","operationId","type","target","sheetId","sheetName","range","value","values"));raw.fieldNames().forEachRemaining(key->require(allowed.contains(key),"CONTROLLED_UNSUPPORTED: 非值写字段 "+key));
            Action a;try{a=JSON.treeToValue(raw,Action.class);}catch(Exception e){throw new IllegalArgumentException("CONTROLLED_UNSUPPORTED: 动作格式无效",e);}
            require(Arrays.asList("setCell","setValues").contains(a.getType()),"CONTROLLED_UNSUPPORTED: B1 仅支持 setCell/setValues");
            ActionTargetValidator.normalize(a,context);ObjectNode normalized=JSON.valueToTree(a);JsonNode target=normalized.path("target");String sheetId=text(target,"sheetId");int[] r=AgentTools.range(text(target,"range"));JsonNode s=AgentTools.sheet(snapshot,sheetId);
            require(r[2]<s.path("rowCount").asInt()&&r[3]<s.path("columnCount").asInt(),"WRITE_SCOPE_INVALID: 不允许自动截断或扩展 Sheet");
            boolean authorized=false;for(JsonNode scope:scopes){int[] permitted=AgentTools.range(text(scope,"range"));if(sheetId.equals(scope.path("sheetId").asText())&&r[0]>=permitted[0]&&r[1]>=permitted[1]&&r[2]<=permitted[2]&&r[3]<=permitted[3])authorized=true;}require(authorized,"WRITE_SCOPE_DENIED: 动作超出预先授权区域");
            for(JsonNode merge:s.path("mergeData"))require(r[2]<merge.path("startRow").asInt()||r[0]>merge.path("endRow").asInt()||r[3]<merge.path("startColumn").asInt()||r[1]>merge.path("endColumn").asInt(),"CONTROLLED_UNSUPPORTED: 不支持合并单元格写入");
            if("setCell".equals(a.getType()))require(r[0]==r[2]&&r[1]==r[3]&&raw.has("value"),"WRITE_SHAPE_INVALID: setCell 必须是单个单元格");
            else {require(raw.path("values").isArray()&&raw.path("values").size()==r[2]-r[0]+1,"WRITE_SHAPE_INVALID: 行数不符");for(JsonNode row:raw.path("values"))require(row.isArray()&&row.size()==r[3]-r[1]+1,"WRITE_SHAPE_INVALID: 列数不符");}
            for(int row=r[0];row<=r[2];row++)for(int col=r[1];col<=r[3];col++){
                require(cells.size()<MAX_WRITE_CELLS,"WRITE_BUDGET_EXCEEDED: B1 每次最多1000单元格");String key=sheetId+":"+row+":"+col;require(covered.add(key),"WRITE_SCOPE_INVALID: 不接受重叠动作");
                JsonNode value="setCell".equals(a.getType())?raw.path("value"):raw.path("values").get(row-r[0]).get(col-r[1]);
                require(value.isNull()||value.isBoolean()||value.isNumber()||value.isTextual(),"CONTROLLED_UNSUPPORTED: 仅接受标量值");require(!value.isTextual()||!value.asText().trim().startsWith("="),"CONTROLLED_UNSUPPORTED: 公式写入未开放");
                JsonNode before=AgentTools.cell(s,row,col);require("confirmed".equals(overwrite)||(!before.hasNonNull("v")&&!before.hasNonNull("p")),"WRITE_POLICY_DENIED: 目标已有内容");
                ObjectNode cell=obj().put("sheetId",sheetId).put("row",row).put("column",col);cell.set("value",value);cell.set("before",before.deepCopy());cells.add(cell);
            }
            actions.add(normalized);
        }
        ObjectNode m=obj().put("transportVersion",2).put("overwrite",overwrite);m.set("actions",actions);m.set("cells",cells);m.set("writeScopes",scopes.deepCopy());return m;
    }
    public static void validateReceipt(JsonNode manifest,JsonNode actual) {
        require(actual.isArray()&&actual.size()==manifest.path("cells").size(),"WRITE_READBACK_INVALID: 回读单元格数量不符");Map<String,JsonNode> expected=new HashMap<>();
        for(JsonNode cell:manifest.path("cells"))expected.put(key(cell),cell);Set<String> seen=new HashSet<>();
        for(JsonNode cell:actual){require(cell.isObject(),"WRITE_READBACK_INVALID");cell.fieldNames().forEachRemaining(k->require(Arrays.asList("sheetId","row","column","value","formula").contains(k),"WRITE_READBACK_INVALID: 非授权字段"));
            require(cell.path("row").isIntegralNumber()&&cell.path("column").isIntegralNumber(),"WRITE_READBACK_INVALID: 坐标无效");String key=key(cell);require(expected.containsKey(key)&&seen.add(key),"WRITE_SCOPE_DENIED: 回读越界或重复");
            require(cell.has("value")&&cell.path("formula").isTextual()&&cell.path("formula").asText().isEmpty(),"WRITE_READBACK_INVALID: 缺值或存在未授权公式");
            require(sorted(cell.path("value")).equals(sorted(expected.get(key).path("value"))),"WRITE_READBACK_INVALID: 实际值与可信变更不一致");
        }
    }
    private static String key(JsonNode c){return c.path("sheetId").asText()+":"+c.path("row").asText()+":"+c.path("column").asText();}
    private static boolean emptyDefaultPermission(JsonNode resource){
        if(!Arrays.asList("SHEET_RANGE_PROTECTION_PLUGIN","SHEET_WORKSHEET_PROTECTION_PLUGIN","SHEET_WORKSHEET_PROTECTION_POINT_PLUGIN","SHEET_AuthzIoMockService_PLUGIN").contains(resource.path("name").asText())||!resource.path("data").isTextual())return false;
        String data=resource.path("data").asText().trim();if(data.isEmpty())return true;
        try{JsonNode rules=JSON.readTree(data);return rules.isObject()&&rules.size()==0;}catch(Exception malformed){return false;}
    }
    public static ObjectNode apply(JsonNode trusted,JsonNode manifest) {
        ObjectNode next=trusted.deepCopy();for(JsonNode patch:manifest.path("cells")){
            ObjectNode s=AgentTools.sheet(next,text(patch,"sheetId"));ObjectNode c=s.with("cellData").with(patch.path("row").asText()).with(patch.path("column").asText());
            c.remove(Arrays.asList("v","f","p","t","si"));JsonNode v=patch.path("value");if(!v.isNull()){c.set("v",v);c.put("t",v.isNumber()?2:v.isBoolean()?3:1);}
        }return next;
    }
}
