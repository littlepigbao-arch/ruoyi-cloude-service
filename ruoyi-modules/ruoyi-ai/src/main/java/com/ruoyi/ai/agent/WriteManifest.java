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
    public static final Set<String> VALUE_ACTIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("setCell","setValues","setCellForCell","clearRange")));
    public static final Set<String> READ_DISPLAY_ACTIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("getRangeValue","getRangeFormulas","getMerges","getSelection","getFilter","createChart")));
    public static Set<String> availableActions(boolean formatOpen){return availableActions(formatOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen){return availableActions(formatOpen,filterOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen){return availableActions(formatOpen,filterOpen,formulaOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen){return availableActions(formatOpen,filterOpen,formulaOpen,mergeOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen){return availableActions(formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen){return availableActions(formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen){return availableActions(formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen){return availableActions(formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,copyOpen,false);}
    public static Set<String> availableActions(boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen,boolean deleteOpen){LinkedHashSet<String> actions=new LinkedHashSet<>(VALUE_ACTIONS);if(formatOpen)actions.addAll(Arrays.asList("setStyle","setRowHeight","setColumnWidth"));if(filterOpen)actions.addAll(Arrays.asList("setFilter","clearFilter"));if(formulaOpen)actions.addAll(Arrays.asList("setFormula","sumToCell"));if(mergeOpen)actions.addAll(Arrays.asList("merge","breakApart"));if(structureOpen)actions.addAll(Arrays.asList("insertRows","deleteRows","insertColumns","deleteColumns"));if(sheetOpen)actions.add("createSheet");if(renameOpen)actions.add("renameSheet");if(copyOpen)actions.add("copySheet");if(deleteOpen)actions.add("deleteSheet");return Collections.unmodifiableSet(actions);}
    private WriteManifest() { }
    public static boolean isB5FormatGroup(JsonNode actions){return actions.isArray()&&actions.size()==1&&Arrays.asList("setStyle","setRowHeight","setColumnWidth").contains(actions.get(0).path("type").asText());}
    public static boolean isControlledTransport(JsonNode version){return version.isIntegralNumber()&&java.math.BigInteger.valueOf(2).equals(version.bigIntegerValue());}
    public static boolean sameIntegral(JsonNode value,long expected){return value.isIntegralNumber()&&java.math.BigInteger.valueOf(expected).equals(value.bigIntegerValue());}
    public static void checkTransport(JsonNode request){require(!request.has("transportVersion")||isControlledTransport(request.path("transportVersion")),"AI_CAPABILITY_BLOCKED: 不兼容的 transport");}
    public static void requireTransport(JsonNode request){require(isControlledTransport(request.path("transportVersion")),"AI_CAPABILITY_BLOCKED: 不兼容或缺失的 transport");}
    /** Classify the whole group before execution; never run a supported subset of a mixed group. */
    public static String classify(JsonNode actions){return classify(actions,false);}
    public static String classify(JsonNode actions,boolean formatOpen){return classify(actions,formatOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen){return classify(actions,formatOpen,filterOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen){return classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,copyOpen,false);}
    public static String classify(JsonNode actions,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen,boolean deleteOpen){
        require(actions.isArray()&&actions.size()<=30,"AI_CAPABILITY_BLOCKED: 动作组必须是最多30项的数组");boolean reads=false,writes=false;
        if(isB5FormatGroup(actions)){require(formatOpen,"AI_CAPABILITY_BLOCKED: B5 样式与尺寸写入尚未开放");return "controlled-values";}
        if(FilterManifest.isFilterGroup(actions)){require(filterOpen,"AI_CAPABILITY_BLOCKED: B6 筛选资源写入尚未开放");return "controlled-values";}
        if(FormulaManifest.isFormulaGroup(actions)){require(formulaOpen,"AI_CAPABILITY_BLOCKED: B7 公式写入尚未开放");return "controlled-values";}
        if(MergeManifest.isMergeGroup(actions)){require(mergeOpen,"AI_CAPABILITY_BLOCKED: B8b 合并写入尚未开放");return "controlled-values";}
        if(StructureManifest.isStructureGroup(actions)){String type=actions.get(0).path("type").asText();require("createSheet".equals(type)?sheetOpen:"renameSheet".equals(type)?renameOpen:"copySheet".equals(type)?copyOpen:"deleteSheet".equals(type)?deleteOpen:structureOpen,"AI_CAPABILITY_BLOCKED: B8c 结构写入尚未开放");return "controlled-values";}
        for(JsonNode action:actions){String type=action.path("type").asText();require(action.isObject()&&(VALUE_ACTIONS.contains(type)||READ_DISPLAY_ACTIONS.contains(type)),"AI_CAPABILITY_BLOCKED: 此写动作尚未迁移至受控链路: "+type);checkB4Action(action);writes|=VALUE_ACTIONS.contains(type);reads|=READ_DISPLAY_ACTIONS.contains(type);}
        require(!(reads&&writes),"AI_CAPABILITY_BLOCKED: 读取展示与写入混合动作组暂不执行，请拆分请求");return writes?"controlled-values":"read-display";
    }
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled){return checkChatTransport(actions,clientMeta,enabled,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,mergeOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen){return checkChatTransport(actions,clientMeta,enabled,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,copyOpen,false);}
    public static String checkChatTransport(JsonNode actions,JsonNode clientMeta,boolean enabled,boolean formatOpen,boolean filterOpen,boolean formulaOpen,boolean mergeOpen,boolean structureOpen,boolean sheetOpen,boolean renameOpen,boolean copyOpen,boolean deleteOpen){String mode=classify(actions,formatOpen,filterOpen,formulaOpen,mergeOpen,structureOpen,sheetOpen,renameOpen,copyOpen,deleteOpen);if("controlled-values".equals(mode)){require(enabled,"CONTROLLED_DISABLED: 受控写入入口未开启");require(isControlledTransport(clientMeta.path("controlledWriteTransport")),"CONTROLLED_CLIENT_REQUIRED: 写动作需要受控客户端，不能直接本地执行后保存 Snapshot");}return mode;}
    private static void checkB4Action(JsonNode a){
        if("clearRange".equals(a.path("type").asText()))require(!a.has("clearWhat")||(a.path("clearWhat").isTextual()&&"content".equals(a.path("clearWhat").asText())),"AI_CAPABILITY_BLOCKED: clearRange 仅开放content");
        if("setCellForCell".equals(a.path("type").asText())){JsonNode v=a.path("value");require(v.isTextual()||v.isNumber()||v.isBoolean(),"AI_CAPABILITY_BLOCKED: setCellForCell 仅接受非null普通标量");require(!v.isTextual()||!v.asText().trim().startsWith("="),"AI_CAPABILITY_BLOCKED: 公式写入未开放");}
    }
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
            checkB4Action(raw);String rawType=raw.path("type").asText();boolean clear="clearRange".equals(rawType);
            Set<String> allowed=new HashSet<>(Arrays.asList("schemaVersion","operationId","type","target","sheetId","sheetName","range"));allowed.add(clear?"clearWhat":"setValues".equals(rawType)?"values":"value");raw.fieldNames().forEachRemaining(key->require(allowed.contains(key),"CONTROLLED_UNSUPPORTED: 非值写字段 "+key));
            Action a;try{a=JSON.treeToValue(raw,Action.class);}catch(Exception e){throw new IllegalArgumentException("CONTROLLED_UNSUPPORTED: 动作格式无效",e);}
            require(VALUE_ACTIONS.contains(a.getType()),"CONTROLLED_UNSUPPORTED: 写动作尚未迁移");
            ActionTargetValidator.normalize(a,context);ObjectNode normalized=JSON.valueToTree(a);JsonNode target=normalized.path("target");String sheetId=text(target,"sheetId");int[] r=AgentTools.range(text(target,"range"));JsonNode s=AgentTools.sheet(snapshot,sheetId);
            if(clear){normalized.put("clearWhat","content");for(JsonNode resource:snapshot.path("resources"))if(resource.path("name").asText().toLowerCase(Locale.ROOT).contains("filter"))require(resource.path("data").isTextual()&&Arrays.asList("","{}","null").contains(resource.path("data").asText().trim()),"CONTROLLED_UNSUPPORTED: 筛选状态下清除尚未证明安全");}
            require(r[2]<s.path("rowCount").asInt()&&r[3]<s.path("columnCount").asInt(),"WRITE_SCOPE_INVALID: 不允许自动截断或扩展 Sheet");
            boolean authorized=false;for(JsonNode scope:scopes){int[] permitted=AgentTools.range(text(scope,"range"));if(sheetId.equals(scope.path("sheetId").asText())&&r[0]>=permitted[0]&&r[1]>=permitted[1]&&r[2]<=permitted[2]&&r[3]<=permitted[3])authorized=true;}require(authorized,"WRITE_SCOPE_DENIED: 动作超出预先授权区域");
            for(JsonNode merge:s.path("mergeData"))require(r[2]<merge.path("startRow").asInt()||r[0]>merge.path("endRow").asInt()||r[3]<merge.path("startColumn").asInt()||r[1]>merge.path("endColumn").asInt(),"CONTROLLED_UNSUPPORTED: 不支持合并单元格写入");
            if("setCell".equals(a.getType())||"setCellForCell".equals(a.getType()))require(r[0]==r[2]&&r[1]==r[3]&&raw.has("value"),"WRITE_SHAPE_INVALID: 标量赋值必须是单个单元格");
            else if(!clear){require(raw.path("values").isArray()&&raw.path("values").size()==r[2]-r[0]+1,"WRITE_SHAPE_INVALID: 行数不符");for(JsonNode row:raw.path("values"))require(row.isArray()&&row.size()==r[3]-r[1]+1,"WRITE_SHAPE_INVALID: 列数不符");}
            for(int row=r[0];row<=r[2];row++)for(int col=r[1];col<=r[3];col++){
                require(cells.size()<MAX_WRITE_CELLS,"WRITE_BUDGET_EXCEEDED: B1 每次最多1000单元格");String key=sheetId+":"+row+":"+col;require(covered.add(key),"WRITE_SCOPE_INVALID: 不接受重叠动作");
                JsonNode value=clear?NullNode.instance:"setValues".equals(a.getType())?raw.path("values").get(row-r[0]).get(col-r[1]):raw.path("value");
                require(value.isNull()||value.isBoolean()||value.isNumber()||value.isTextual(),"CONTROLLED_UNSUPPORTED: 仅接受标量值");require(!value.isTextual()||!value.asText().trim().startsWith("="),"CONTROLLED_UNSUPPORTED: 公式写入未开放");
                JsonNode before=AgentTools.cell(s,row,col);require("confirmed".equals(overwrite)||(!before.hasNonNull("v")&&!before.hasNonNull("p")),"WRITE_POLICY_DENIED: 目标已有内容");
                if(clear){require(!before.hasNonNull("custom"),"CONTROLLED_UNSUPPORTED: clearContent会清除custom元数据，本批拒绝");JsonNode rd=s.path("rowData").path(String.valueOf(row)).path("hd"),cd=s.path("columnData").path(String.valueOf(col)).path("hd");require(!(rd.asInt()==1||rd.asBoolean(false)||cd.asInt()==1||cd.asBoolean(false)),"CONTROLLED_UNSUPPORTED: 隐藏行列清除尚未证明安全");}
                ObjectNode cell=obj().put("sheetId",sheetId).put("row",row).put("column",col);cell.set("value",value);cell.set("before",before.deepCopy());cells.add(cell);
                if(clear)cell.put("operation","clear-content");
            }
            actions.add(normalized);
        }
        ObjectNode m=obj().put("transportVersion",2).put("overwrite",overwrite);m.set("actions",actions);m.set("cells",cells);m.set("writeScopes",scopes.deepCopy());return m;
    }
    /** Exact B5 format contract; admission remains behind ControlledWriteService's switch. */
    private static int b5Int(JsonNode value,int min,int max,String field){require(value.isIntegralNumber()&&value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(min))>=0&&value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(max))<=0,"B5_FORMAT_INVALID: "+field+" 超出精确整数范围");return value.intValue();}
    private static boolean b5FalseFlag(JsonNode value){return value.isMissingNode()||(value.isBoolean()&&!value.booleanValue())||(value.isIntegralNumber()&&value.bigIntegerValue().signum()==0);}
    public static ObjectNode previewB5Format(JsonNode snapshot,JsonNode raw,JsonNode scopes){
        checkScopes(scopes,snapshot);require(raw.isObject()&&"2.0".equals(raw.path("schemaVersion").asText()),"B5_FORMAT_INVALID: V2动作必需");
        String type=text(raw,"type");require(Arrays.asList("setStyle","setRowHeight","setColumnWidth").contains(type),"B5_FORMAT_INVALID: 类型未注册");
        boolean style="setStyle".equals(type),row="setRowHeight".equals(type);Set<String> allowed=new HashSet<>(Arrays.asList("schemaVersion","type","target"));
        allowed.add(style?"background":row?"rowPosition":"columnPosition");if(!style)allowed.add(row?"height":"width");
        raw.fieldNames().forEachRemaining(field->require(allowed.contains(field),"B5_FORMAT_INVALID: 未注册属性 "+field));
        JsonNode target=raw.path("target");require(target.isObject()&&target.path("sheetId").isTextual()&&!target.path("sheetId").asText().isEmpty(),"B5_FORMAT_INVALID: Sheet目标缺失");
        target.fieldNames().forEachRemaining(field->require(Arrays.asList("sheetId","range").contains(field)&& (style||!"range".equals(field)),"B5_FORMAT_INVALID: 目标字段无效"));
        String sheetId=text(target,"sheetId");JsonNode sheet=AgentTools.sheet(snapshot,sheetId);int rows=b5Int(sheet.path("rowCount"),1,1000000,"rowCount"),cols=b5Int(sheet.path("columnCount"),1,16384,"columnCount");
        require(rows>0&&cols>0,"B5_FORMAT_INVALID: Sheet尺寸无效");
        for(JsonNode resource:snapshot.path("resources")){String name=resource.path("name").asText().toLowerCase(Locale.ROOT);if(name.matches(".*(protection|permission|authz).*"))require(emptyDefaultPermission(resource),"B5_FORMAT_INVALID: 保护规则未证明安全");if(name.matches(".*(conditional|filter).*"))require(resource.path("data").isTextual()&&Arrays.asList("","{}","null").contains(resource.path("data").asText().trim()),"B5_FORMAT_INVALID: 条件样式或筛选未证明安全");}
        int startRow,endRow,startCol,endCol,index=-1;JsonNode value,before;
        if(style){require(target.path("range").isTextual(),"B5_FORMAT_INVALID: 范围缺失");int[] r=AgentTools.range(target.path("range").asText());require(r[0]==r[2]&&r[1]==r[3]&&r[2]<rows&&r[3]<cols,"B5_FORMAT_INVALID: 仅支持Sheet内单格样式");startRow=endRow=r[0];startCol=endCol=r[1];
            value=raw.path("background");require(value.isTextual()&&value.asText().matches("#[0-9a-fA-F]{6}"),"B5_FORMAT_INVALID: 仅支持六位十六进制background");value=TextNode.valueOf(value.asText().toLowerCase(Locale.ROOT));before=AgentTools.cell(sheet,startRow,startCol).deepCopy();require(!before.has("s")||before.path("s").isObject(),"B5_FORMAT_INVALID: 样式注册表引用尚未支持");require(!before.path("s").has("bg")||before.path("s").path("bg").isObject(),"B5_FORMAT_INVALID: 背景样式结构未支持");
            if(before.path("s").path("bg").isObject())before.path("s").path("bg").fieldNames().forEachRemaining(field->require("rgb".equals(field),"B5_FORMAT_INVALID: 现有背景样式包含 SDK 会丢弃的字段"));
        }else{String position=row?"rowPosition":"columnPosition",size=row?"height":"width";index=b5Int(raw.path(position),1,row?rows:cols,position)-1;int n=b5Int(raw.path(size),row?20:40,row?400:800,size);
            startRow=row?index:0;endRow=row?index:rows-1;startCol=row?0:index;endCol=row?cols-1:index;require((long)(endRow-startRow+1)*(endCol-startCol+1)<=MAX_WRITE_CELLS,"B5_FORMAT_INVALID: 影响范围超过预算");
            before=sheet.path(row?"rowData":"columnData").path(String.valueOf(index)).deepCopy();value=IntNode.valueOf(n);
            require(b5FalseFlag(before.path("hd")),"B5_FORMAT_INVALID: 隐藏行列未证明安全");
            if(row){require(b5FalseFlag(before.path("ah")),"B5_FORMAT_INVALID: 自动行高未证明安全");require(!before.has("ia")||(before.path("ia").isIntegralNumber()&&before.path("ia").bigIntegerValue().signum()==0),"B5_FORMAT_INVALID: 自动适应行高未证明安全");}
            if(row){int baseline=b5Int(sheet.path("defaultRowHeight"),1,400,"defaultRowHeight"),current=before.has("h")?b5Int(before.path("h"),1,400,"currentHeight"):baseline;require(n>baseline&&n>current,"B5_FORMAT_INVALID: 自动行高分支未排除");}
        }
        boolean covered=false;for(JsonNode scope:scopes){int[] r=AgentTools.range(text(scope,"range"));if(sheetId.equals(scope.path("sheetId").asText())&&startRow>=r[0]&&endRow<=r[2]&&startCol>=r[1]&&endCol<=r[3])covered=true;}require(covered,"WRITE_SCOPE_DENIED: 样式或行列影响范围超出授权");
        for(JsonNode merge:sheet.path("mergeData"))require(endRow<merge.path("startRow").asInt()||startRow>merge.path("endRow").asInt()||endCol<merge.path("startColumn").asInt()||startCol>merge.path("endColumn").asInt(),"B5_FORMAT_INVALID: 合并范围未证明安全");
        ObjectNode patch=obj().put("type",type).put("sheetId",sheetId).put("startRow",startRow).put("endRow",endRow).put("startColumn",startCol).put("endColumn",endCol);if(style){patch.put("row",startRow).put("column",startCol);}else patch.put("index",index);patch.set("value",value);patch.set("before",before.isMissingNode()?NullNode.instance:before);
        ObjectNode result=obj().put("transportVersion",2);result.set("action",raw.deepCopy());result.set("actions",arr().add(raw.deepCopy()));result.set("patch",patch);result.set("writeScopes",scopes.deepCopy());return result;
    }
    public static void validateB5FormatReceipt(JsonNode manifest,JsonNode actual){
        JsonNode patch=manifest.path("patch");require(actual.isObject(),"B5_READBACK_INVALID: 回读缺失");boolean style="setStyle".equals(patch.path("type").asText());
        Set<String> fields=new HashSet<>(style?Arrays.asList("sheetId","row","column","value"):Arrays.asList("sheetId","index","value"));actual.fieldNames().forEachRemaining(field->require(fields.contains(field),"B5_READBACK_INVALID: 非授权回读字段"));require(actual.size()==fields.size()&&actual.path("sheetId").equals(patch.path("sheetId"))&&actual.path("value").equals(patch.path("value")),"B5_READBACK_INVALID: 结果与可信补丁不符");
        if(style)require(actual.path("row").equals(patch.path("row"))&&actual.path("column").equals(patch.path("column")),"B5_READBACK_INVALID: 单元格坐标不符");else require(actual.path("index").equals(patch.path("index")),"B5_READBACK_INVALID: 行列索引不符");
    }
    public static ObjectNode applyB5Format(JsonNode trusted,JsonNode manifest){
        JsonNode p=manifest.path("patch");String type=text(p,"type"),id=text(p,"sheetId");require(Arrays.asList("setStyle","setRowHeight","setColumnWidth").contains(type),"B5_FORMAT_INVALID: 补丁类型未注册");
        require(previewB5Format(trusted,manifest.path("action"),manifest.path("writeScopes")).path("patch").equals(p),"B5_FORMAT_INVALID: 补丁与可信动作不一致");
        ObjectNode next=trusted.deepCopy(),sheet=AgentTools.sheet(next,id);boolean style="setStyle".equals(type),row="setRowHeight".equals(type);int r=p.path("row").asInt(-1),c=p.path("column").asInt(-1),index=p.path("index").asInt(-1);
        JsonNode current=style?AgentTools.cell(sheet,r,c):sheet.path(row?"rowData":"columnData").path(String.valueOf(index));require(p.has("before")&&sorted(current.isMissingNode()?NullNode.instance:current).equals(sorted(p.path("before"))),"WRITE_VERSION_CONFLICT: 目标格式已变化");
        if(style){ObjectNode cell=sheet.with("cellData").with(String.valueOf(r)).with(String.valueOf(c));ObjectNode s=cell.path("s").isObject()?(ObjectNode)cell.path("s"):obj();s.with("bg").set("rgb",p.path("value"));cell.set("s",s);}
        else {ObjectNode dimension=sheet.with(row?"rowData":"columnData").with(String.valueOf(index));dimension.set(row?"h":"w",p.path("value"));if(row)dimension.put("ia",0);}return next;
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
            c.remove(Arrays.asList("v","f","p","t","si"));JsonNode v=patch.path("value");if(!v.isNull()){if(v.isBoolean())c.put("v",v.asBoolean()?1:0);else c.set("v",v);c.put("t",v.isNumber()?2:v.isBoolean()?3:1);}
        }return next;
    }
    /** Univer's boolean storage is numeric with t=3; zero with t=2 remains a number. */
    public static JsonNode semanticValue(JsonNode cell){JsonNode v=cell.path("v");if(cell.path("t").isIntegralNumber()&&cell.path("t").asInt()==3&&v.isNumber()&&(v.decimalValue().compareTo(java.math.BigDecimal.ZERO)==0||v.decimalValue().compareTo(java.math.BigDecimal.ONE)==0))return BooleanNode.valueOf(v.asInt()==1);return cell.hasNonNull("v")?v:NullNode.instance;}
}
