package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.agent.AgentStore;
import com.ruoyi.ai.agent.AgentTools;
import java.math.*;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1b dataset plane: the dataset/aggregate/join/derive operators moved verbatim from AgentTools
 * (move + delegate, no semantic change to operators, error messages or budget numbers), plus the
 * per-tool execution entry points AgentTools.execute now delegates to. Many-to-one joins verify
 * the left key's uniqueness first (JOIN_LEFT_KEY_DUPLICATED) so duplicate grain cannot fan out
 * silently. The 200000-cell budget stays enforced through AgentTools.MAX_CELLS.
 */
public class DatasetService {
    /** Logical schema name recorded on dataset artifacts (ai_analysis_artifact.schema_name); agent datasets share one {columns,rows} logical shape. */
    public static final String SCHEMA_NAME = "agent_dataset";
    /** C1c dependency-registration rule version stamped on every ai_analysis_artifact_dependency row (dependency_kind='dataset_source'). */
    public static final String RULE_VERSION = "1";
    private final DatasetReader reader;
    public DatasetService(AgentStore store) { this.reader = new DatasetReader(store); }
    public DatasetReader reader() { return reader; }
    /** Execution entry points delegated from AgentTools.execute; behavior identical to the former inline branches. */
    public ObjectNode buildDataset(JsonNode snapshot, JsonNode args) { return dataset(snapshot, args); }
    public ObjectNode aggregateTable(ObjectNode run, JsonNode args) { return aggregate(reader.dataset(run, args.get("dataset")), args); }
    public ObjectNode joinTables(ObjectNode run, JsonNode args) { return join(reader.dataset(run, args.get("left")), reader.dataset(run, args.get("right")), args); }
    public ObjectNode deriveColumns(ObjectNode run, JsonNode args) { return derive(reader.dataset(run, args.get("dataset")), args); }
    public static ObjectNode dataset(JsonNode snapshot, JsonNode args) {
        require(args.path("headerRow").asBoolean() && args.has("includeHidden") && args.has("includeFiltered"), "必须声明表头、隐藏行和筛选行口径");
        require(args.path("includeHidden").asBoolean() && args.path("includeFiltered").asBoolean(), "首期全量工具仅支持包含隐藏及筛选行，其他口径需先明确提取规则");
        require("reject".equals(args.path("formulaPolicy").asText()), "必须显式拒绝未验证公式值");
        JsonNode s=AgentTools.sheet(snapshot,text(args,"sheetId"));int[] r=AgentTools.range(text(args,"range"));
        int[] bounds=AgentTools.effectiveBounds(s);
        require(r[0]==bounds[0]&&r[1]==bounds[1]&&r[2]>=bounds[2]&&r[3]==bounds[3], "RESULT_UNKNOWN: 数据范围必须从实际表头开始并覆盖全部有效行列");
        ArrayNode columns=arr(),rows=arr();Set<String> names=new HashSet<>();
        for(int c=r[1];c<=r[3];c++){JsonNode header=AgentTools.cell(s,r[0],c);require(header.path("f").asText("").isEmpty()&&header.path("v").isTextual(),"RESULT_UNKNOWN: 表头必须是无公式的字段名");String name=header.path("v").asText("").trim();require(!name.isEmpty()&&names.add(name),"表头为空或重复");columns.add(name);}
        for(int i=r[0]+1;i<=r[2];i++){ObjectNode row=obj();boolean populated=false;
            for(int c=r[1];c<=r[3];c++){JsonNode cell=AgentTools.cell(s,i,c);require(cell.path("f").asText("").isEmpty(),"数据集含未验证公式，请先使用确定性计算列");JsonNode v=cell.has("v")?cell.get("v"):NullNode.instance;row.set(columns.get(c-r[1]).asText(),v);if(!v.isNull()&&!v.asText().isEmpty())populated=true;}
            if(populated)rows.add(row);
        }
        ObjectNode result=obj();result.set("columns",columns);result.set("rows",rows);return result;
    }
    public static void columns(JsonNode d, JsonNode required) {require(d.path("rows").isArray(),"输入不是数据集");for(JsonNode n:required){boolean found=false;for(JsonNode c:d.path("columns"))if(c.equals(n))found=true;require(found,"字段不存在: "+n.asText());}}
    public static ObjectNode aggregate(JsonNode d, JsonNode a) {
        require(a.path("groupBy").isArray()&&a.path("sums").isArray(),"汇总字段无效");columns(d,a.path("groupBy"));columns(d,a.path("sums"));
        Map<String,ObjectNode> groups=new LinkedHashMap<>();
        for(JsonNode row:d.path("rows")){ArrayNode key=arr();for(JsonNode g:a.path("groupBy"))key.add(row.path(g.asText()));ObjectNode out=groups.get(key.toString());
            if(out==null){out=obj();for(JsonNode g:a.path("groupBy"))out.set(g.asText(),row.path(g.asText()));for(JsonNode sum:a.path("sums"))out.put(sum.asText(),BigDecimal.ZERO);groups.put(key.toString(),out);}
            for(JsonNode sum:a.path("sums"))out.put(sum.asText(),AgentTools.decimal(out.path(sum.asText())).add(AgentTools.decimal(row.path(sum.asText()))));
        }
        ArrayNode cols=arr().addAll((ArrayNode)a.path("groupBy")).addAll((ArrayNode)a.path("sums"));ArrayNode rows=arr();groups.values().forEach(rows::add);ObjectNode result=obj();result.set("columns",cols);result.set("rows",rows);return result;
    }
    public static ObjectNode join(JsonNode l, JsonNode r, JsonNode a) {
        require("many-to-one".equals(a.path("cardinality").asText()),"必须声明many-to-one，先聚合分批记录");String lk=text(a,"leftKey"),rk=text(a,"rightKey"),prefix=text(a,"rightPrefix");
        columns(l,arr().add(lk));columns(r,arr().add(rk));
        Set<String> leftKeys=new HashSet<>();
        for(JsonNode row:l.path("rows"))require(leftKeys.add(row.path(lk).toString()),"JOIN_LEFT_KEY_DUPLICATED: 左表关联键 "+lk+" 重复，many-to-one 语义要求左键唯一，请先核实粒度或汇总");
        Map<String,JsonNode> index=new HashMap<>();
        for(JsonNode row:r.path("rows")){JsonNode key=row.path(rk);require(!key.isNull()&&!key.asText().isEmpty(),"关联键为空");require(index.put(key.toString(),row)==null,"右侧关联键重复，必须先汇总");}
        ArrayNode cols=l.path("columns").deepCopy();Set<String> names=new HashSet<>();for(JsonNode c:cols)names.add(c.asText());for(JsonNode c:r.path("columns")){require(names.add(prefix+c.asText()),"关联后字段重名");cols.add(prefix+c.asText());}
        require((long)cols.size()*l.path("rows").size()<=AgentTools.MAX_CELLS,"关联结果超过数据预算");ArrayNode rows=arr();int matched=0;
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
                else if("if_gt".equals(op))v=AgentTools.decimal(left).compareTo(AgentTools.decimal(right))>0?c.path("thenValue"):c.path("elseValue");
                else v=DecimalNode.valueOf(calculate(op,AgentTools.decimal(left),AgentTools.decimal(right),scale).setScale(scale,RoundingMode.HALF_UP));
                require(!v.isMissingNode(),"计算结果缺失");((ObjectNode)row).set(name,v);
            }
        }
        require((long)cols.size()*out.path("rows").size()<=AgentTools.MAX_CELLS,"结果超过预算");return out;
    }
    public static BigDecimal calculate(String op, BigDecimal l, BigDecimal r, int scale) {
        switch(op) {
            case "add": case "+": return l.add(r);
            case "subtract": case "-": return l.subtract(r);
            case "multiply": case "*": return l.multiply(r);
            case "divide": case "/": require(r.signum()!=0,"除数为0"); return l.divide(r,scale,RoundingMode.HALF_UP);
            default: throw new IllegalArgumentException("不支持的计算运算");
        }
    }
}
