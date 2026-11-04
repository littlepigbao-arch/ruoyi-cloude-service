package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;
import java.util.regex.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Deterministic tools operate on full, persisted snapshots. Never evaluate arbitrary expressions. */
@Component
public class AgentTools {
    public static final int MAX_CELLS = 200000;
    private static final Pattern A1 = Pattern.compile("^([A-Z]{1,3})([1-9][0-9]*)(?::([A-Z]{1,3})([1-9][0-9]*))?$");
    private static final Pattern FORMULA = Pattern.compile("^=([A-Z]{1,3}[1-9][0-9]*)([+*/-])([A-Z]{1,3}[1-9][0-9]*)$");
    private final AgentStore store;
    public AgentTools(AgentStore store) { this.store = store; }
    public static int col(String s) { int c = 0; for (char ch : s.toCharArray()) c = c * 26 + ch - 'A' + 1; return c - 1; }
    public static int[] range(String a1) {
        Matcher m = A1.matcher(a1); require(m.matches(), "区域必须是有限A1范围");
        int r = Integer.parseInt(m.group(2)) - 1, c = col(m.group(1));
        int er = m.group(4) == null ? r : Integer.parseInt(m.group(4)) - 1, ec = m.group(3) == null ? c : col(m.group(3));
        require(er >= r && ec >= c && er < 1000000 && ec < 16384 && (long)(er-r+1)*(ec-c+1) <= MAX_CELLS, "范围无效或超过20万单元格预算");
        return new int[]{r,c,er,ec};
    }
    public static ObjectNode sheet(JsonNode snapshot, String id) {
        JsonNode s = snapshot.path("sheets").path(id); require(s.isObject(), "Sheet不存在: " + id); return (ObjectNode)s;
    }
    public static JsonNode cell(JsonNode sheet, int r, int c) { return sheet.path("cellData").path(""+r).path(""+c); }
    private static ObjectNode writableCell(ObjectNode s, int r, int c) {
        ObjectNode data = s.with("cellData"), row = data.with(""+r);
        if (!row.path(""+c).isObject()) row.set(""+c, obj());
        return (ObjectNode)row.get(""+c);
    }
    public static BigDecimal decimal(JsonNode n) {
        require(n != null && !n.isMissingNode() && !n.isNull() && !n.asText().trim().isEmpty(), "空值不能隐式当作0");
        try { return new BigDecimal(n.asText()); } catch (NumberFormatException e) { throw new IllegalArgumentException("非数值: " + n.asText()); }
    }
    public static JsonNode value(JsonNode s, int r, int c) {
        JsonNode x = cell(s,r,c);
        if (!x.path("f").asText("").isEmpty()) {
            BigDecimal expected=formulaExpected(s,r,c);
            require(x.hasNonNull("v"),"FORMULA_UNVERIFIED: 公式缓存缺失");
            BigDecimal cached;
            try { cached=decimal(x.path("v")); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("FORMULA_UNVERIFIED: 公式缓存无效",e); }
            require(cached.compareTo(expected)==0,"FORMULA_UNVERIFIED: 公式缓存与独立计算不一致");
            return x.get("v");
        }
        return x.has("v") ? x.get("v") : NullNode.instance;
    }
    private static BigDecimal formulaExpected(JsonNode s,int r,int c) {
            JsonNode x=cell(s,r,c);
            Matcher m = FORMULA.matcher(x.path("f").asText());
            require(m.matches(), "FORMULA_UNVERIFIED: 无法独立验证此公式");
            int[] a = range(m.group(1)), b = range(m.group(3));
            require(cell(s,a[0],a[1]).path("f").asText("").isEmpty() && cell(s,b[0],b[1]).path("f").asText("").isEmpty(), "FORMULA_UNVERIFIED: 不接受公式递归依赖");
            BigDecimal l = decimal(cell(s,a[0],a[1]).path("v")), rr = decimal(cell(s,b[0],b[1]).path("v"));
            return calculate(m.group(2), l, rr, 10);
    }
    private static BigDecimal calculate(String op, BigDecimal l, BigDecimal r, int scale) {
        switch(op) {
            case "add": case "+": return l.add(r);
            case "subtract": case "-": return l.subtract(r);
            case "multiply": case "*": return l.multiply(r);
            case "divide": case "/": require(r.signum()!=0,"除数为0"); return l.divide(r,scale,RoundingMode.HALF_UP);
            default: throw new IllegalArgumentException("不支持的计算运算");
        }
    }
    public ObjectNode execute(ObjectNode run, String tool, JsonNode args, JsonNode snapshot) {
        JsonNode result;
        switch(tool) {
            case "get_workbook_catalog": result = catalog(snapshot); break;
            case "inspect_sheet": result = inspect(sheet(snapshot,text(args,"sheetId"))); break;
            case "read_range": result = read(sheet(snapshot,text(args,"sheetId")),range(text(args,"range"))); break;
            case "read_display": result = ReadDisplayTools.execute(snapshot,run,args); break;
            case "build_dataset": result = dataset(snapshot,args); break;
            case "aggregate_table": result = aggregate(data(run,args.get("dataset")),args); break;
            case "join_tables": result = join(data(run,args.get("left")),data(run,args.get("right")),args); break;
            case "derive_columns": result = derive(data(run,args.get("dataset")),args); break;
            case "check_data_quality": result = quality(data(run,args.get("dataset")),args); break;
            case "verify_result": result = verify(snapshot,args); break;
            default: throw new IllegalArgumentException("未知服务端工具");
        }
        require(!"check_data_quality".equals(tool)||result.path("errors").asInt()==0,"RESULT_UNKNOWN: 数据质量检查存在违规，不能标记通过");
        boolean datasetTool=Arrays.asList("build_dataset","aggregate_table","join_tables","derive_columns").contains(tool);
        String id = datasetTool?store.artifact(run,result,resultLineage(run,tool,args,snapshot)):store.artifact(run,result);
        ObjectNode out = obj().put("resultRef",id).put("sourceRevision",run.path("revision").asText()).put("truncated",false).put("validation","PASSED");
        out.set("dataSummary",summary(result)); return out;
    }
    private JsonNode data(ObjectNode run, JsonNode reference) {
        require(reference != null && reference.has("resultRef"),"缺少真实上游结果引用");
        ObjectNode record=store.artifactRecord(run,text(reference,"resultRef"));
        require(record.path("sourceRevision").asText().equals(reference.path("sourceRevision").asText()),"SOURCE_STALE: 引用声明与制品权威来源不一致");
        return record.path("data");
    }
    private ArrayNode resultLineage(ObjectNode run,String tool,JsonNode args,JsonNode snapshot){
        if("build_dataset".equals(tool)){String sheetId=text(args,"sheetId");return arr().add(obj().put("sheetId",sheetId).put("range",text(args,"range")).put("sheetDigest",WriteManifest.digest(sheet(snapshot,sheetId))));}
        ArrayNode merged=arr();Set<String> seen=new HashSet<>();String[] refs="join_tables".equals(tool)?new String[]{"left","right"}:new String[]{"dataset"};
        for(String key:refs){ObjectNode record=store.artifactRecord(run,text(args.path(key),"resultRef"));JsonNode lineage=record.path("lineage");if(!lineage.isArray()||lineage.size()==0)return arr();for(JsonNode source:lineage){String identity=source.path("sheetId").asText()+":"+source.path("range").asText();if(seen.add(identity))merged.add(source.deepCopy());else for(JsonNode prior:merged)if(identity.equals(prior.path("sheetId").asText()+":"+prior.path("range").asText()))require(prior.equals(source),"SOURCE_STALE: 来源区域事实冲突");}}
        require(merged.size()<=30,"SOURCE_BUDGET_EXCEEDED: 来源区域过多");return merged;
    }
    /** B9 server-owned expansion; invoked only by the gated controlled scheduler. */
    public ObjectNode prepareResultWrite(ObjectNode run,JsonNode args,JsonNode snapshot){
        ObjectNode record=store.artifactRecord(run,text(args.path("dataset"),"resultRef"));return ResultWriteManifest.create(run,record,snapshot,args);
    }
    public static ObjectNode summary(JsonNode data) {
        ObjectNode s = obj();
        if (data.has("rows")) { s.put("rowCount",data.path("rows").size()); s.set("columns",data.path("columns")); }
        else { String txt = data.toString(); s.put("preview",txt.substring(0,Math.min(txt.length(),4000))); s.put("truncated",txt.length()>4000); }
        return s;
    }
    public static ArrayNode catalog(JsonNode snapshot) {
        ArrayNode out = arr(); Iterator<Map.Entry<String,JsonNode>> it = snapshot.path("sheets").fields();
        while(it.hasNext()) { Map.Entry<String,JsonNode> e=it.next(); ObjectNode s = inspect(e.getValue()); s.put("sheetId",e.getKey()); out.add(s); }
        require(out.size() <= 100,"工作表数量超过100张预算"); return out;
    }
    public static ObjectNode inspect(JsonNode s) {
        int lastRow=0,lastCol=0,count=0;
        Iterator<Map.Entry<String,JsonNode>> rows=s.path("cellData").fields();
        while(rows.hasNext()) { Map.Entry<String,JsonNode> row=rows.next(); Iterator<Map.Entry<String,JsonNode>> cells=row.getValue().fields();
            while(cells.hasNext()) { Map.Entry<String,JsonNode> c=cells.next(); require(++count<=MAX_CELLS,"数据超过20万单元格预算");
                if(c.getValue().hasNonNull("v") || c.getValue().hasNonNull("f")) {lastRow=Math.max(lastRow,Integer.parseInt(row.getKey()));lastCol=Math.max(lastCol,Integer.parseInt(c.getKey()));}
            }
        }
        ObjectNode out=obj().put("sheetId",s.path("id").asText()).put("name",s.path("name").asText()).put("lastRow",lastRow+1).put("lastColumn",lastCol+1);
        out.set("sample",read(s,new int[]{0,0,Math.min(lastRow,4),Math.min(lastCol,25)})); out.put("sampleTruncated",lastRow>4||lastCol>25); return out;
    }
    private static ArrayNode read(JsonNode s,int[] r) {
        ArrayNode out=arr(); for(int i=r[0];i<=r[2];i++){ArrayNode row=arr();for(int c=r[1];c<=r[3];c++)row.add(cell(s,i,c).deepCopy());out.add(row);}return out;
    }
    public static ObjectNode dataset(JsonNode snapshot, JsonNode args) {
        require(args.path("headerRow").asBoolean() && args.has("includeHidden") && args.has("includeFiltered"),"必须声明表头、隐藏行和筛选行口径");
        require(args.path("includeHidden").asBoolean() && args.path("includeFiltered").asBoolean(),"首期全量工具仅支持包含隐藏及筛选行，其他口径需先明确提取规则");
        require("reject".equals(args.path("formulaPolicy").asText()),"必须显式拒绝未验证公式值");
        JsonNode s=sheet(snapshot,text(args,"sheetId"));int[] r=range(text(args,"range"));
        int[] bounds=effectiveBounds(s);
        require(r[0]==bounds[0]&&r[1]==bounds[1]&&r[2]>=bounds[2]&&r[3]==bounds[3],"RESULT_UNKNOWN: 数据范围必须从实际表头开始并覆盖全部有效行列");
        ArrayNode columns=arr(),rows=arr();Set<String> names=new HashSet<>();
        for(int c=r[1];c<=r[3];c++){JsonNode header=cell(s,r[0],c);require(header.path("f").asText("").isEmpty()&&header.path("v").isTextual(),"RESULT_UNKNOWN: 表头必须是无公式的字段名");String name=header.path("v").asText("").trim();require(!name.isEmpty()&&names.add(name),"表头为空或重复");columns.add(name);}
        for(int i=r[0]+1;i<=r[2];i++){ObjectNode row=obj();boolean populated=false;
            for(int c=r[1];c<=r[3];c++){JsonNode cell=cell(s,i,c);require(cell.path("f").asText("").isEmpty(),"数据集含未验证公式，请先使用确定性计算列");JsonNode v=cell.has("v")?cell.get("v"):NullNode.instance;row.set(columns.get(c-r[1]).asText(),v);if(!v.isNull()&&!v.asText().isEmpty())populated=true;}
            if(populated)rows.add(row);
        }
        ObjectNode result=obj();result.set("columns",columns);result.set("rows",rows);return result;
    }
    private static boolean populated(JsonNode c) { return !c.path("f").asText("").isEmpty() || (c.hasNonNull("v")&&!c.path("v").asText().trim().isEmpty()); }
    private static boolean headerLabel(JsonNode c) {
        JsonNode v=c.path("v");if(!v.isTextual()||v.asText().trim().isEmpty()||!c.path("f").asText("").isEmpty())return false;
        try { new BigDecimal(v.asText().trim());return false; } catch(NumberFormatException expected) { return true; }
    }
    private static int[] effectiveBounds(JsonNode s) {
        int firstRow=Integer.MAX_VALUE,firstCol=Integer.MAX_VALUE,lastRow=-1,lastCol=-1,count=0;
        Iterator<Map.Entry<String,JsonNode>> rows=s.path("cellData").fields();
        while(rows.hasNext()){Map.Entry<String,JsonNode> row=rows.next();Iterator<Map.Entry<String,JsonNode>> cells=row.getValue().fields();while(cells.hasNext()){Map.Entry<String,JsonNode> c=cells.next();require(++count<=MAX_CELLS,"数据超过20万单元格预算");if(populated(c.getValue())){int ri=Integer.parseInt(row.getKey()),ci=Integer.parseInt(c.getKey());firstRow=Math.min(firstRow,ri);firstCol=Math.min(firstCol,ci);lastRow=Math.max(lastRow,ri);lastCol=Math.max(lastCol,ci);}}}
        require(lastRow>=0,"RESULT_UNKNOWN: 没有可验证的有效数据");return new int[]{firstRow,firstCol,lastRow,lastCol};
    }
    private static void columns(JsonNode d, JsonNode required) {require(d.path("rows").isArray(),"输入不是数据集");for(JsonNode n:required){boolean found=false;for(JsonNode c:d.path("columns"))if(c.equals(n))found=true;require(found,"字段不存在: "+n.asText());}}
    public static ObjectNode aggregate(JsonNode d, JsonNode a) {
        require(a.path("groupBy").isArray()&&a.path("sums").isArray(),"汇总字段无效");columns(d,a.path("groupBy"));columns(d,a.path("sums"));
        Map<String,ObjectNode> groups=new LinkedHashMap<>();
        for(JsonNode row:d.path("rows")){ArrayNode key=arr();for(JsonNode g:a.path("groupBy"))key.add(row.path(g.asText()));ObjectNode out=groups.get(key.toString());
            if(out==null){out=obj();for(JsonNode g:a.path("groupBy"))out.set(g.asText(),row.path(g.asText()));for(JsonNode sum:a.path("sums"))out.put(sum.asText(),BigDecimal.ZERO);groups.put(key.toString(),out);}
            for(JsonNode sum:a.path("sums"))out.put(sum.asText(),decimal(out.path(sum.asText())).add(decimal(row.path(sum.asText()))));
        }
        ArrayNode cols=arr().addAll((ArrayNode)a.path("groupBy")).addAll((ArrayNode)a.path("sums"));ArrayNode rows=arr();groups.values().forEach(rows::add);ObjectNode result=obj();result.set("columns",cols);result.set("rows",rows);return result;
    }
    public static ObjectNode join(JsonNode l, JsonNode r, JsonNode a) {
        require("many-to-one".equals(a.path("cardinality").asText()),"必须声明many-to-one，先聚合分批记录");String lk=text(a,"leftKey"),rk=text(a,"rightKey"),prefix=text(a,"rightPrefix");
        columns(l,arr().add(lk));columns(r,arr().add(rk));Map<String,JsonNode> index=new HashMap<>();
        for(JsonNode row:r.path("rows")){JsonNode key=row.path(rk);require(!key.isNull()&&!key.asText().isEmpty(),"关联键为空");require(index.put(key.toString(),row)==null,"右侧关联键重复，必须先汇总");}
        ArrayNode cols=l.path("columns").deepCopy();Set<String> names=new HashSet<>();for(JsonNode c:cols)names.add(c.asText());for(JsonNode c:r.path("columns")){require(names.add(prefix+c.asText()),"关联后字段重名");cols.add(prefix+c.asText());}
        require((long)cols.size()*l.path("rows").size()<=MAX_CELLS,"关联结果超过数据预算");ArrayNode rows=arr();int matched=0;
        for(JsonNode row:l.path("rows")){require(!row.path(lk).isNull()&&!row.path(lk).asText().isEmpty(),"左关联键为空");ObjectNode out=row.deepCopy();JsonNode right=index.get(row.path(lk).toString());if(right!=null)matched++;for(JsonNode c:r.path("columns"))out.set(prefix+c.asText(),right==null?NullNode.instance:right.path(c.asText()));rows.add(out);}
        ObjectNode out=obj().put("matched",matched).put("unmatched",rows.size()-matched);out.set("columns",cols);out.set("rows",rows);return out;
    }
    private static JsonNode operand(JsonNode row,JsonNode arg){require(arg!=null&&arg.isObject(),"计算参数必须指定field或value");return arg.has("field")?row.path(arg.path("field").asText()):arg.path("value");}
    public static ObjectNode derive(JsonNode d, JsonNode a) {
        require("reject".equals(a.path("nullPolicy").asText()),"默认拒绝空值，使用coalesce显式指定缺失值口径");ObjectNode out=d.deepCopy();ArrayNode cols=(ArrayNode)out.path("columns");
        require(a.path("columns").isArray()&&a.path("columns").size()>0,"缺少计算列");
        for(JsonNode c:a.path("columns")){String name=text(c,"name"),op=text(c,"op");boolean exists=false;for(JsonNode n:cols)if(n.asText().equals(name))exists=true;require(!exists,"计算列已存在，拒绝隐式覆盖");cols.add(name);
            int scale=c.path("scale").asInt(2);require(scale>=0&&scale<=10,"小数精度必须0至10");
            for(JsonNode row:out.path("rows")){JsonNode left=operand(row,c.get("left")),right=operand(row,c.get("right"));JsonNode v;
                if("coalesce".equals(op))v=left.isMissingNode()||left.isNull()?right:left;
                else if("if_gt".equals(op))v=decimal(left).compareTo(decimal(right))>0?c.path("thenValue"):c.path("elseValue");
                else v=DecimalNode.valueOf(calculate(op,decimal(left),decimal(right),scale).setScale(scale,RoundingMode.HALF_UP));
                require(!v.isMissingNode(),"计算结果缺失");((ObjectNode)row).set(name,v);
            }
        }
        require((long)cols.size()*out.path("rows").size()<=MAX_CELLS,"结果超过预算");return out;
    }
    public static ObjectNode quality(JsonNode d,JsonNode a){int errors=0,rules=0;ArrayNode examples=arr();
        for(String kind:Arrays.asList("required","unique","nonNegative")){require(!a.has(kind)||a.path(kind).isArray(),"RESULT_UNKNOWN: 质量规则必须是字段数组");rules+=a.path(kind).size();}
        require(rules>0,"RESULT_UNKNOWN: 质量验证没有有效业务规则");
        require(d.path("rows").isArray()&&d.path("rows").size()>0,"RESULT_UNKNOWN: 空数据集不能证明业务结果");
        for(String kind:Arrays.asList("required","unique","nonNegative")){columns(d,a.path(kind));for(JsonNode c:a.path(kind)){Set<String> seen=new HashSet<>();int i=0;for(JsonNode row:d.path("rows")){JsonNode v=row.path(c.asText());boolean bad=false;
            if("required".equals(kind))bad=v.isNull()||v.isMissingNode()||v.asText().isEmpty();
            if("unique".equals(kind))bad=!seen.add(v.toString());
            if("nonNegative".equals(kind))bad=decimal(v).signum()<0;
            if(bad){errors++;if(examples.size()<20)examples.add(obj().put("row",i).put("column",c.asText()).put("rule",kind));}i++;
        }}}
        require(!a.path("failOnViolation").asBoolean(true)||errors==0,"数据质量验证失败，共"+errors+"项");ObjectNode result=obj().put("errors",errors).put("checkedRows",d.path("rows").size());result.set("examples",examples);return result;
    }
    public static ObjectNode verify(JsonNode snapshot,JsonNode a){if("matches_structure".equals(a.path("kind").asText()))return StructureManifest.verifySaved(snapshot,a);JsonNode s=sheet(snapshot,text(a,"sheetId"));int[] r=range(text(a,"range"));String kind=text(a,"kind");require(Arrays.asList("non_empty","product","matches_write","matches_format","matches_filter","matches_formula","matches_merge","matches_structure").contains(kind),"未注册验证规则");if("matches_format".equals(kind))return verifyFormat(snapshot,a,s,r);if("matches_filter".equals(kind))return FilterManifest.verifySaved(snapshot,a,r);if("matches_formula".equals(kind))return FormulaManifest.verifySaved(snapshot,a,r);if("matches_merge".equals(kind))return MergeManifest.verifySaved(snapshot,a);if("matches_structure".equals(kind))return StructureManifest.verifySaved(snapshot,a);int checked=0;
        if("matches_write".equals(kind)){
            JsonNode write=a.path("write"),patches=write.path("executedChange").path("cells");
            require(WriteManifest.isControlledTransport(write.path("transportVersion"))&&"SAVED".equals(write.path("persistenceStatus").asText())&&"PASSED".equals(write.path("validation").asText())&&"controlled-values".equals(write.path("executedChange").path("kind").asText()),"RESULT_UNKNOWN: 缺少可信已保存受控变更");
            require(patches.isArray()&&patches.size()>0&&patches.size()<=WriteManifest.MAX_WRITE_CELLS,"RESULT_UNKNOWN: 已保存变更范围无效");Set<String> seen=new HashSet<>();
            for(JsonNode patch:patches){int row=patch.path("row").asInt(-1),col=patch.path("column").asInt(-1);require(patch.path("row").isIntegralNumber()&&patch.path("column").isIntegralNumber()&&patch.path("sheetId").asText().equals(a.path("sheetId").asText())&&row>=r[0]&&row<=r[2]&&col>=r[1]&&col<=r[3]&&seen.add(row+":"+col),"RESULT_UNKNOWN: 验证范围与保存事实不一致");JsonNode actual=cell(s,row,col),v=WriteManifest.semanticValue(actual);require(patch.has("value")&&WriteManifest.digest(v).equals(WriteManifest.digest(patch.path("value")))&&!actual.hasNonNull("p")&&actual.path("f").asText("").isEmpty()&&actual.path("si").asText("").isEmpty(),"RESULT_UNKNOWN: 内容与实际保存变更不符");checked++;}
            return obj().put("passed",true).put("checkedCells",checked).put("method","saved-controlled-content-comparison");
        }
        Set<Integer> effectiveRows=new HashSet<>();
        if("product".equals(kind)){
            String lc=text(a,"leftColumn"),rc=text(a,"rightColumn");require(lc.matches("[A-Z]{1,3}")&&rc.matches("[A-Z]{1,3}"),"乘法字段必须为列字母");require(r[1]==r[3],"RESULT_UNKNOWN: 乘法验证必须指定单个结果列");
            int[] bounds=effectiveBounds(s);int first=bounds[0];
            JsonNode lh=cell(s,first,col(lc)),rh=cell(s,first,col(rc)),oh=cell(s,first,r[1]);
            if(headerLabel(lh)&&headerLabel(rh)&&headerLabel(oh))first++;
            require((long)(bounds[2]-first+1)*3<=MAX_CELLS,"验证超过单元格预算");
            for(int row=first;row<=bounds[2];row++)if(populated(cell(s,row,col(lc)))||populated(cell(s,row,col(rc)))||populated(cell(s,row,r[1]))){require(row>=r[0]&&row<=r[2],"RESULT_UNKNOWN: 验证范围遗漏有效业务行");effectiveRows.add(row);}
            require(!effectiveRows.isEmpty(),"RESULT_UNKNOWN: 没有有效业务行");
        }
        for(int row=r[0];row<=r[2];row++){if("product".equals(kind)&&!effectiveRows.contains(row))continue;for(int c=r[1];c<=r[3];c++){JsonNode v=value(s,row,c);require(!v.isNull()&&!v.asText().isEmpty(),"结果缺失于行"+(row+1));
            if("product".equals(kind)){String lc=text(a,"leftColumn"),rc=text(a,"rightColumn");int scale=a.path("scale").asInt(2);require(scale>=0&&scale<=10,"精度无效");BigDecimal expected=decimal(value(s,row,col(lc))).multiply(decimal(value(s,row,col(rc)))).setScale(scale,RoundingMode.HALF_UP);require(decimal(v).setScale(scale,RoundingMode.HALF_UP).compareTo(expected)==0,"金额验证失败于行"+(row+1));}checked++;}}
        return obj().put("passed",true).put("checkedCells",checked).put("method","independent-decimal-evaluation");
    }
    private static ObjectNode verifyFormat(JsonNode snapshot,JsonNode a,JsonNode sheet,int[] range){JsonNode write=a.path("write"),change=write.path("executedChange"),patch=change.path("formatPatch");String savedHash=hash(snapshot),callId=text(write,"callId");
        require(WriteManifest.isControlledTransport(write.path("transportVersion"))&&"SAVED".equals(write.path("persistenceStatus").asText())&&"PASSED".equals(write.path("validation").asText())&&"controlled-format".equals(change.path("kind").asText())&&callId.equals(change.path("operationId").asText())&&savedHash.equals(write.path("loadedRevision").asText())&&savedHash.equals(write.path("sourceRevision").asText())&&savedHash.equals(change.path("afterHash").asText()),"RESULT_UNKNOWN: 缺少可信已保存格式变更");
        require(patch.isObject()&&text(patch,"sheetId").equals(text(a,"sheetId"))&&WriteManifest.sameIntegral(patch.path("startRow"),range[0])&&WriteManifest.sameIntegral(patch.path("startColumn"),range[1])&&WriteManifest.sameIntegral(patch.path("endRow"),range[2])&&WriteManifest.sameIntegral(patch.path("endColumn"),range[3]),"RESULT_UNKNOWN: B5 保存补丁与完整验证范围不一致");
        String type=text(patch,"type");JsonNode expected=patch.path("value"),actual;
        if("setStyle".equals(type)){require(range[0]==range[2]&&range[1]==range[3]&&WriteManifest.sameIntegral(patch.path("row"),range[0])&&WriteManifest.sameIntegral(patch.path("column"),range[1])&&expected.isTextual()&&expected.asText().matches("#[0-9a-f]{6}"),"RESULT_UNKNOWN: B5 样式补丁无效");actual=cell(sheet,range[0],range[1]).path("s").path("bg").path("rgb");}
        else if("setRowHeight".equals(type)){require(range[0]==range[2]&&WriteManifest.sameIntegral(patch.path("index"),range[0])&&expected.isIntegralNumber(),"RESULT_UNKNOWN: B5 行高补丁无效");JsonNode row=sheet.path("rowData").path(String.valueOf(range[0]));require(WriteManifest.sameIntegral(row.path("ia"),0),"RESULT_UNKNOWN: B5 行高自动适应状态不符");actual=row.path("h");}
        else if("setColumnWidth".equals(type)){require(range[1]==range[3]&&WriteManifest.sameIntegral(patch.path("index"),range[1])&&expected.isIntegralNumber(),"RESULT_UNKNOWN: B5 列宽补丁无效");actual=sheet.path("columnData").path(String.valueOf(range[1])).path("w");}
        else throw new IllegalArgumentException("RESULT_UNKNOWN: B5 格式类型未注册");
        require(actual.equals(expected),"RESULT_UNKNOWN: B5 已保存格式与可信补丁不符");return obj().put("passed",true).put("checkedCells",(range[2]-range[0]+1)*(range[3]-range[1]+1)).put("method","saved-controlled-format-comparison");
    }
    /** Produce an exact, bounded write proposal; verification compares the persisted snapshot to it. */
    public ObjectNode prepareWrite(ObjectNode run,String tool,JsonNode args,JsonNode snapshot) {
        ObjectNode next=snapshot.deepCopy();ArrayNode actions=arr();
        if("write_result".equals(tool)){
            JsonNode d=data(run,args.get("dataset"));String id=text(args,"sheetId");
            if(!next.path("sheets").has(id)){String name=text(args,"newSheetName");for(JsonNode s:next.path("sheets"))require(!name.equals(s.path("name").asText()),"结果Sheet名称已存在");
                ObjectNode s=obj().put("id",id).put("name",name).put("rowCount",Math.max(1000,d.path("rows").size()+1)).put("columnCount",Math.max(26,d.path("columns").size()));s.set("cellData",obj());next.with("sheets").set(id,s);((ArrayNode)next.path("sheetOrder")).add(id);
            }
            ArrayNode values=arr();values.add(d.path("columns"));for(JsonNode row:d.path("rows")){ArrayNode v=arr();for(JsonNode c:d.path("columns"))v.add(row.path(c.asText()));values.add(v);}
            ObjectNode act=obj().put("type","setValues").put("sheetId",id).put("range",text(args,"range"));act.set("values",values);actions.add(act);
        }else{require(args.path("actions").isArray()&&args.path("actions").size()>0&&args.path("actions").size()<=30,"动作数应为1至30");actions.addAll((ArrayNode)args.path("actions"));}
        String overwrite=text(args,"overwrite");require(Arrays.asList("empty-only","confirmed").contains(overwrite),"覆盖策略无效");
        require(!"confirmed".equals(overwrite)||run.path("allowOverwrite").asBoolean(),"覆盖已有数据需用户明确勾选授权");
        for(JsonNode a:actions){String type=text(a,"type");require(Arrays.asList("setValues","setFormula","setStyle","clearRange").contains(type),"智能体暂不支持此写动作: "+type);ObjectNode s=sheet(next,text(a,"sheetId"));int[] r=range(text(a,"range"));
            require(r[2]<s.path("rowCount").asInt()&&r[3]<s.path("columnCount").asInt(),"目标超出Sheet尺寸");
            if("setValues".equals(type)){require(a.path("values").isArray()&&a.path("values").size()==r[2]-r[0]+1,"写入行数不匹配");for(JsonNode row:a.path("values"))require(row.isArray()&&row.size()==r[3]-r[1]+1,"写入列数不匹配");}
            for(int row=r[0];row<=r[2];row++)for(int c=r[1];c<=r[3];c++){ObjectNode cell=writableCell(s,row,c);
                require("confirmed".equals(overwrite)||(!cell.hasNonNull("v")&&!cell.hasNonNull("f")),"目标区域已有内容，请确认覆盖");
                if("setStyle".equals(type)){require(a.path("style").isObject(),"样式无效");cell.set("s",a.path("style"));continue;}
                if("clearRange".equals(type)){cell.remove(Arrays.asList("v","f","p","t"));continue;}
                JsonNode v="setFormula".equals(type)?a.path("formula"):a.path("values").get(row-r[0]).get(c-r[1]);require(v!=null&&!v.isMissingNode()&&!v.isContainerNode(),"写入值必须是标量");cell.remove(Arrays.asList("v","f","p","t"));
                if(v.isTextual()&&v.asText().startsWith("=")){require(FORMULA.matcher(v.asText()).matches(),"当前版本只接受可独立验证的单层算术公式");cell.put("f",v.asText());}
                else {cell.set("v",v);cell.put("t",v.isNumber()?2:1);}
            }
        }
        if("write_result".equals(tool)&&args.has("highlight")){JsonNode h=args.path("highlight"),d=data(run,args.get("dataset"));String col=text(h,"column");int ci=-1;for(int i=0;i<d.path("columns").size();i++)if(col.equals(d.path("columns").get(i).asText()))ci=i;require(ci>=0,"标色列不存在");String color=text(h,"color");require(color.matches("#[0-9a-fA-F]{6}"),"颜色无效");int[] rr=range(text(args,"range"));ObjectNode s=sheet(next,text(args,"sheetId"));for(int i=0;i<d.path("rows").size();i++)if(decimal(d.path("rows").get(i).path(col)).compareTo(decimal(h.path("gt")))>0){ObjectNode st=obj();st.set("bg",obj().put("rgb",color));writableCell(s,rr[0]+i+1,rr[1]+ci).set("s",st);}}
        // Resolve only formulas that this engine can independently validate. No stale cached values.
        for(JsonNode a:actions){ObjectNode s=sheet(next,text(a,"sheetId"));int[] rr=range(text(a,"range"));for(int r=rr[0];r<=rr[2];r++)for(int c=rr[1];c<=rr[3];c++)if(cell(s,r,c).hasNonNull("f"))writableCell(s,r,c).set("v",DecimalNode.valueOf(formulaExpected(s,r,c)));}
        return next;
    }
}
