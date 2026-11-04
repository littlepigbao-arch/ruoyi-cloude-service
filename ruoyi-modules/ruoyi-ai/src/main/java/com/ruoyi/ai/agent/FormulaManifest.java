package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** B7 bounded formula patch. Integer sources and results make the browser cache independently comparable. */
public final class FormulaManifest {
    private static final Pattern REF=Pattern.compile("(\\$?)([A-Z]{1,3})(\\$?)([1-9][0-9]*)");
    private static final Pattern BINARY=Pattern.compile("^=(\\$?[A-Z]{1,3}\\$?[1-9][0-9]*)([+*/-])(\\$?[A-Z]{1,3}\\$?[1-9][0-9]*)$");
    private static final Pattern SUM=Pattern.compile("^=SUM\\((\\$?[A-Z]{1,3}\\$?[1-9][0-9]*):(\\$?[A-Z]{1,3}\\$?[1-9][0-9]*)\\)$");
    private static final long LIMIT=1_000_000_000L;
    private FormulaManifest() { }
    public static boolean isFormulaGroup(JsonNode actions){return actions.isArray()&&actions.size()==1&&Arrays.asList("setFormula","sumToCell").contains(actions.get(0).path("type").asText());}
    /** Derive the executable formula without changing the original request/audit identity. */
    public static ObjectNode normalizedAction(JsonNode raw){
        if("setFormula".equals(raw.path("type").asText()))return (ObjectNode)raw.deepCopy();
        fields(raw,"schemaVersion","type","target","sourceRange","targetRange");require("sumToCell".equals(raw.path("type").asText())&&"2.0".equals(raw.path("schemaVersion").asText()),"B10_SUM_INVALID: 必须为V2 sumToCell");
        JsonNode target=raw.path("target");fields(target,"sheetId","range");String id=text(target,"sheetId"),range=target.has("range")?text(target,"range"):text(raw,"targetRange"),source=text(raw,"sourceRange");
        if(raw.has("targetRange"))require(range.equals(text(raw,"targetRange")),"B10_SUM_INVALID: 目标范围冲突");
        int[] destination=AgentTools.range(range);AgentTools.range(source);require(destination[0]==destination[2]&&destination[1]==destination[3],"B10_SUM_INVALID: 仅接受单格目标");
        if(!source.contains(":"))source=source+":"+source;
        ObjectNode out=obj().put("schemaVersion","2.0").put("type","setFormula").put("formula","=SUM("+source+")");out.set("target",obj().put("sheetId",id).put("range",range));return out;
    }
    private static void fields(JsonNode node,String... allowed){require(node.isObject(),"B7_FORMULA_INVALID: 必须为对象");Set<String> keys=new HashSet<>(Arrays.asList(allowed));node.fieldNames().forEachRemaining(k->require(keys.contains(k),"B7_FORMULA_INVALID: 未注册字段 "+k));}
    private static int[] ref(String token,int dr,int dc){Matcher m=REF.matcher(token);require(m.matches(),"B7_FORMULA_INVALID: 引用格式无效");int c=AgentTools.col(m.group(2))+(m.group(1).isEmpty()?dc:0);long row=Long.parseLong(m.group(4))-1+(m.group(3).isEmpty()?dr:0);require(c>=0&&c<16384&&row>=0&&row<1_000_000,"B7_FORMULA_INVALID: 平移引用越界");return new int[]{(int)row,c};}
    private static String a1(int row,int column){StringBuilder out=new StringBuilder();for(int n=column+1;n>0;n=(n-1)/26)out.insert(0,(char)('A'+(n-1)%26));return out.append(row+1).toString();}
    private static String shifted(String token,int dr,int dc){Matcher m=REF.matcher(token);require(m.matches(),"B7_FORMULA_INVALID: 引用格式无效");int[] position=ref(token,dr,dc);String plain=a1(position[0],position[1]);Matcher converted=REF.matcher(plain);require(converted.matches(),"B7_FORMULA_INVALID: 平移失败");return m.group(1)+converted.group(2)+m.group(3)+converted.group(4);}
    private static long numeric(JsonNode sheet,int row,int col){JsonNode cell=AgentTools.cell(sheet,row,col),value=cell.path("v");require(cell.path("f").asText("").isEmpty()&&!cell.hasNonNull("p")&&!cell.hasNonNull("si")&&WriteManifest.sameIntegral(cell.path("t"),2)&&value.isIntegralNumber(),"FORMULA_UNVERIFIED: 仅接受无公式的整数依赖");long v=value.asLong();require(value.bigIntegerValue().equals(java.math.BigInteger.valueOf(v))&&v>=-LIMIT&&v<=LIMIT,"FORMULA_UNVERIFIED: 依赖值超出精确范围");return v;}
    private static long bounded(long value){require(value>=-LIMIT&&value<=LIMIT,"FORMULA_UNVERIFIED: 公式结果超出精确范围");return value;}
    private static ObjectNode calculated(JsonNode sheet,String formula,int dr,int dc,int[] target){
        Matcher binary=BINARY.matcher(formula),sum=SUM.matcher(formula);long value;String rendered;
        if(binary.matches()){
            int[] left=ref(binary.group(1),dr,dc),right=ref(binary.group(3),dr,dc);require(left[0]<sheet.path("rowCount").asInt()&&left[1]<sheet.path("columnCount").asInt()&&right[0]<sheet.path("rowCount").asInt()&&right[1]<sheet.path("columnCount").asInt(),"B7_FORMULA_INVALID: 依赖超出Sheet");
            require(!inside(left,target)&&!inside(right,target),"FORMULA_UNVERIFIED: 公式依赖目标范围");long l=numeric(sheet,left[0],left[1]),r=numeric(sheet,right[0],right[1]);
            try{switch(binary.group(2)){case "+":value=Math.addExact(l,r);break;case "-":value=Math.subtractExact(l,r);break;case "*":value=Math.multiplyExact(l,r);break;default:require(r!=0,"FORMULA_UNVERIFIED: 除数为0");require(l%r==0,"FORMULA_UNVERIFIED: 首批仅接受整数商");value=l/r;}}
            catch(ArithmeticException ex){throw new IllegalArgumentException("FORMULA_UNVERIFIED: 算术溢出",ex);}
            rendered="="+shifted(binary.group(1),dr,dc)+binary.group(2)+shifted(binary.group(3),dr,dc);
        }else if(sum.matches()){
            int[] first=ref(sum.group(1),dr,dc),last=ref(sum.group(2),dr,dc);require(first[0]<=last[0]&&first[1]<=last[1]&&last[0]<sheet.path("rowCount").asInt()&&last[1]<sheet.path("columnCount").asInt(),"B7_FORMULA_INVALID: SUM 范围无效");require((long)(last[0]-first[0]+1)*(last[1]-first[1]+1)<=WriteManifest.MAX_WRITE_CELLS,"B7_FORMULA_INVALID: SUM 依赖超预算");value=0;
            for(int row=first[0];row<=last[0];row++)for(int col=first[1];col<=last[1];col++){require(!inside(new int[]{row,col},target),"FORMULA_UNVERIFIED: SUM 依赖目标范围");try{value=Math.addExact(value,numeric(sheet,row,col));}catch(ArithmeticException ex){throw new IllegalArgumentException("FORMULA_UNVERIFIED: SUM 溢出",ex);}}
            rendered="=SUM("+shifted(sum.group(1),dr,dc)+":"+shifted(sum.group(2),dr,dc)+")";
        }else throw new IllegalArgumentException("FORMULA_UNVERIFIED: 公式语法未注册");
        return obj().put("formula",rendered).put("value",(int)bounded(value));
    }
    private static boolean inside(int[] position,int[] range){return position[0]>=range[0]&&position[0]<=range[2]&&position[1]>=range[1]&&position[1]<=range[3];}
    public static ObjectNode preview(JsonNode snapshot,JsonNode raw,JsonNode scopes){
        JsonNode action=normalizedAction(raw);
        WriteManifest.checkScopes(scopes,snapshot);fields(action,"schemaVersion","type","target","formula");require("2.0".equals(action.path("schemaVersion").asText())&&"setFormula".equals(action.path("type").asText()),"B7_FORMULA_INVALID: V2 setFormula 必需");
        JsonNode target=action.path("target");fields(target,"sheetId","range");String sheetId=text(target,"sheetId"),range=text(target,"range"),formula=text(action,"formula");require(formula.length()<=100,"B7_FORMULA_INVALID: 公式过长");int[] r=AgentTools.range(range);JsonNode sheet=AgentTools.sheet(snapshot,sheetId);require(r[2]<sheet.path("rowCount").asInt()&&r[3]<sheet.path("columnCount").asInt()&&(long)(r[2]-r[0]+1)*(r[3]-r[1]+1)<=WriteManifest.MAX_WRITE_CELLS,"B7_FORMULA_INVALID: 目标超范围");
        boolean covered=false;for(JsonNode scope:scopes){int[] s=AgentTools.range(text(scope,"range"));if(sheetId.equals(scope.path("sheetId").asText())&&r[0]>=s[0]&&r[1]>=s[1]&&r[2]<=s[2]&&r[3]<=s[3])covered=true;}require(covered,"WRITE_SCOPE_DENIED: 公式目标超出授权");
        for(JsonNode s:snapshot.path("sheets"))for(JsonNode row:s.path("cellData"))for(JsonNode cell:row)require(cell.path("f").asText("").isEmpty()&&!cell.hasNonNull("si"),"FORMULA_UNVERIFIED: 已有公式依赖尚未验证");
        for(JsonNode resource:snapshot.path("resources")){String name=resource.path("name").asText().toLowerCase(Locale.ROOT);if(name.matches(".*(protection|permission|authz|conditional|filter).*"))require(resource.path("data").isTextual()&&Arrays.asList("","{}","null").contains(resource.path("data").asText().trim()),"FORMULA_UNVERIFIED: 资源依赖未验证");}
        for(JsonNode merge:sheet.path("mergeData"))require(r[2]<merge.path("startRow").asInt()||r[0]>merge.path("endRow").asInt()||r[3]<merge.path("startColumn").asInt()||r[1]>merge.path("endColumn").asInt(),"FORMULA_UNVERIFIED: 合并目标未验证");
        ArrayNode cells=arr();for(int row=r[0];row<=r[2];row++)for(int col=r[1];col<=r[3];col++){
            JsonNode prior=AgentTools.cell(sheet,row,col);require(prior.isMissingNode()||prior.isObject()&&prior.size()==0,"WRITE_POLICY_DENIED: 首批公式目标必须为空");ObjectNode value=calculated(sheet,formula,row-r[0],col-r[1],r).put("row",row).put("column",col);cells.add(value);
        }
        ObjectNode patch=obj().put("sheetId",sheetId).put("range",range);patch.set("cells",cells);ObjectNode result=obj().put("transportVersion",2);result.set("action",raw.deepCopy());result.set("actions",arr().add(raw.deepCopy()));result.set("writeScopes",scopes.deepCopy());result.set("formulaPatch",patch);result.set("formulaReadback",cells.deepCopy());return result;
    }
    public static void validateReceipt(JsonNode manifest,JsonNode readback){require(readback.isArray()&&readback.equals(manifest.path("formulaReadback")),"B7_READBACK_INVALID: 公式或计算缓存回读不一致");}
    public static ObjectNode apply(JsonNode trusted,JsonNode manifest){ObjectNode expected=preview(trusted,manifest.path("action"),manifest.path("writeScopes"));require(expected.path("formulaPatch").equals(manifest.path("formulaPatch")),"B7_FORMULA_INVALID: 补丁与可信动作不一致");ObjectNode next=trusted.deepCopy(),sheet=AgentTools.sheet(next,text(manifest.path("formulaPatch"),"sheetId"));for(JsonNode cell:manifest.path("formulaPatch").path("cells")){ObjectNode target=sheet.with("cellData").with(cell.path("row").asText()).with(cell.path("column").asText());target.put("f",text(cell,"formula")).put("v",cell.path("value").asInt()).put("t",2);}return next;}
    public static ObjectNode verifySaved(JsonNode snapshot,JsonNode args,int[] range){
        JsonNode write=args.path("write"),change=write.path("executedChange"),patch=change.path("formulaPatch");String savedHash=hash(snapshot),callId=text(write,"callId");
        require(WriteManifest.isControlledTransport(write.path("transportVersion"))&&"SAVED".equals(write.path("persistenceStatus").asText())&&"PASSED".equals(write.path("validation").asText())&&"controlled-formula".equals(change.path("kind").asText())&&callId.equals(change.path("operationId").asText())&&savedHash.equals(write.path("loadedRevision").asText())&&savedHash.equals(write.path("sourceRevision").asText())&&savedHash.equals(change.path("afterHash").asText()),"RESULT_UNKNOWN: 公式缺少可信保存结果");
        require(text(patch,"sheetId").equals(text(args,"sheetId"))&&text(patch,"range").equals(text(args,"range")),"RESULT_UNKNOWN: 公式验证范围不一致");
        JsonNode sheet=AgentTools.sheet(snapshot,text(patch,"sheetId")),action=normalizedAction(write.path("actions").path(0));require("setFormula".equals(action.path("type").asText())&&patch.path("cells").isArray()&&patch.path("cells").size()==(range[2]-range[0]+1)*(range[3]-range[1]+1),"RESULT_UNKNOWN: 公式动作或影响区缺失");
        int index=0;for(int row=range[0];row<=range[2];row++)for(int col=range[1];col<=range[3];col++){
            JsonNode cell=patch.path("cells").get(index++),actual=AgentTools.cell(sheet,row,col);
            ObjectNode independent=calculated(sheet,text(action,"formula"),row-range[0],col-range[1],range);
            require(WriteManifest.sameIntegral(cell.path("row"),row)&&WriteManifest.sameIntegral(cell.path("column"),col)&&independent.path("formula").equals(cell.path("formula"))&&independent.path("value").equals(cell.path("value"))&&actual.path("f").equals(cell.path("formula"))&&actual.path("v").equals(cell.path("value"))&&WriteManifest.sameIntegral(actual.path("t"),2),"RESULT_UNKNOWN: 已保存公式或缓存与独立计算不符");
        }
        return obj().put("passed",true).put("checkedCells",patch.path("cells").size()).put("method","saved-controlled-formula-comparison");
    }
}
