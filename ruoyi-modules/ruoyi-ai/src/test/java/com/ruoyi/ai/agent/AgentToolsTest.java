package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.math.BigDecimal;
import static com.ruoyi.ai.agent.AgentJson.*;

public class AgentToolsTest extends TestCase {
    private void rejected(Runnable action){try{action.run();fail("Expected rejection");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    private ObjectNode data(String json){return (ObjectNode)parse(json);}
    public void testPartialDeliveriesAreAggregatedBeforeJoin(){
        JsonNode purchases=data("{\"columns\":[\"order\",\"dept\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"dept\":\"A\",\"amount\":200000},{\"order\":\"P2\",\"dept\":\"A\",\"amount\":50000},{\"order\":\"P3\",\"dept\":\"B\",\"amount\":100}]}");
        JsonNode arrivals=data("{\"columns\":[\"order\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"amount\":20000},{\"order\":\"P1\",\"amount\":30000},{\"order\":\"P2\",\"amount\":50000},{\"order\":\"P3\",\"amount\":200}]}");
        ObjectNode join=data("{\"leftKey\":\"order\",\"rightKey\":\"order\",\"rightPrefix\":\"arrived_\",\"cardinality\":\"many-to-one\"}");
        rejected(()->AgentTools.join(purchases,arrivals,join));
        JsonNode grouped=AgentTools.aggregate(arrivals,data("{\"groupBy\":[\"order\"],\"sums\":[\"amount\"]}"));
        JsonNode matched=AgentTools.join(purchases,grouped,join);
        assertEquals(3,matched.path("matched").asInt());assertEquals(0,matched.path("unmatched").asInt());
        JsonNode derived=AgentTools.derive(matched,data("{\"nullPolicy\":\"reject\",\"columns\":[{\"name\":\"pending\",\"op\":\"subtract\",\"left\":{\"field\":\"amount\"},\"right\":{\"field\":\"arrived_amount\"},\"scale\":2}]}"));
        JsonNode summary=AgentTools.aggregate(derived,data("{\"groupBy\":[\"dept\"],\"sums\":[\"pending\"]}"));
        assertEquals(0,new BigDecimal("150000").compareTo(summary.path("rows").get(0).path("pending").decimalValue()));
        assertEquals(0,new BigDecimal("-100").compareTo(summary.path("rows").get(1).path("pending").decimalValue()));
        rejected(()->AgentTools.quality(derived,data("{\"nonNegative\":[\"pending\"],\"failOnViolation\":true}")));
    }
    public void testNullIsNotZeroAndDivisionByZeroRejected(){
        rejected(()->AgentTools.decimal(NullNode.instance));
        JsonNode d=data("{\"columns\":[\"a\",\"b\"],\"rows\":[{\"a\":10,\"b\":0}]}");
        rejected(()->AgentTools.derive(d,data("{\"nullPolicy\":\"reject\",\"columns\":[{\"name\":\"c\",\"op\":\"divide\",\"left\":{\"field\":\"a\"},\"right\":{\"field\":\"b\"}}]}")));
    }
    public void testMoneyUsesDecimalHalfUp(){
        JsonNode d=data("{\"columns\":[\"price\"],\"rows\":[{\"price\":0.105}]}");
        JsonNode out=AgentTools.derive(d,data("{\"nullPolicy\":\"reject\",\"columns\":[{\"name\":\"amount\",\"op\":\"multiply\",\"left\":{\"field\":\"price\"},\"right\":{\"value\":3},\"scale\":2}]}"));
        assertEquals("0.32",out.path("rows").get(0).path("amount").asText());
    }
    public void testRangeBudgetAndInvalidCoordinates(){
        rejected(()->AgentTools.range("A0"));rejected(()->AgentTools.range("B3:A1"));rejected(()->AgentTools.range("A1:ZZ999999"));
        assertEquals(26,AgentTools.col("AA"));
    }
    public void testFormulaVerifiedAgainstAllRows(){
        ObjectNode s=data("{\"sheets\":{\"s1\":{\"cellData\":{\"1\":{\"0\":{\"v\":3},\"1\":{\"v\":0.1},\"2\":{\"f\":\"=A2*B2\",\"v\":0.3}}}}}}");
        JsonNode result=AgentTools.verify(s,data("{\"sheetId\":\"s1\",\"range\":\"C2\",\"kind\":\"product\",\"leftColumn\":\"A\",\"rightColumn\":\"B\",\"scale\":2}"));
        assertTrue(result.path("passed").asBoolean());
        rejected(()->AgentTools.verify(s,data("{\"sheetId\":\"s1\",\"range\":\"C2:C3\",\"kind\":\"non_empty\"}")));
    }
    public void testDatasetRejectsTruncationAndFormulaCache(){
        ObjectNode s=data("{\"sheets\":{\"s1\":{\"cellData\":{\"0\":{\"0\":{\"v\":\"amount\"}},\"1\":{\"0\":{\"v\":10}},\"2\":{\"0\":{\"v\":20}}}}}}");
        ObjectNode args=data("{\"sheetId\":\"s1\",\"range\":\"A1:A2\",\"headerRow\":true,\"includeHidden\":true,\"includeFiltered\":true,\"formulaPolicy\":\"reject\"}");
        rejected(()->AgentTools.dataset(s,args));args.put("range","A1:A3");assertEquals(2,AgentTools.dataset(s,args).path("rows").size());
        ((ObjectNode)s.path("sheets").path("s1").path("cellData").path("1").path("0")).put("f","=B2*C2");rejected(()->AgentTools.dataset(s,args));
    }
    public void testWriteScopeAndOverwrite(){
        AgentTools tools=new AgentTools(null);ObjectNode run=obj().put("allowOverwrite",false);
        ObjectNode snapshot=data("{\"sheetOrder\":[\"s1\"],\"sheets\":{\"s1\":{\"name\":\"Orders\",\"rowCount\":10,\"columnCount\":10,\"cellData\":{\"0\":{\"0\":{\"v\":\"keep\"}}}}}}");
        ObjectNode args=data("{\"overwrite\":\"empty-only\",\"actions\":[{\"type\":\"setValues\",\"sheetId\":\"s1\",\"range\":\"B1\",\"values\":[[100]]}]}");
        JsonNode next=tools.prepareWrite(run,"apply_actions",args,snapshot);assertEquals("keep",next.path("sheets").path("s1").path("cellData").path("0").path("0").path("v").asText());
        assertFalse(AgentRunService.sameContent(snapshot,next));assertTrue(AgentRunService.sameContent(next,next.deepCopy()));
        ((ObjectNode)args.path("actions").get(0)).put("range","A1");rejected(()->tools.prepareWrite(run,"apply_actions",args,snapshot));args.put("overwrite","confirmed");rejected(()->tools.prepareWrite(run,"apply_actions",args,snapshot));
    }
    public void testPlanRejectsCyclesUnknownToolsAndUnverifiedWrite(){
        ObjectNode plan=data("{\"steps\":[{\"stepId\":\"S1\",\"tool\":\"get_workbook_catalog\",\"dependsOn\":[],\"inputBindings\":{},\"verificationRules\":[{\"type\":\"tool_success\"}]}],\"acceptanceCriteria\":[{\"type\":\"step_succeeded\",\"stepId\":\"S1\"}]}");
        AgentPlan.validate(plan);ObjectNode step=(ObjectNode)plan.path("steps").get(0);step.put("tool","eval");rejected(()->AgentPlan.validate(plan));step.put("tool","apply_actions");rejected(()->AgentPlan.validate(plan));step.put("tool","get_workbook_catalog");step.set("dependsOn",arr().add("S1"));rejected(()->AgentPlan.validate(plan));
    }
    public void testReferencesCannotFabricateResults(){
        rejected(()->AgentPlan.resolve(data("{\"$step\":\"missing\"}"),obj()));ObjectNode outputs=obj();outputs.set("S1",obj().put("resultRef","real"));assertEquals("real",AgentPlan.resolve(data("{\"$step\":\"S1\"}"),outputs).path("resultRef").asText());
    }
}
