package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Negative cases run against the protected A1 sources before implementation. */
public class FormulaCoverageTest extends TestCase {
    private ObjectNode snapshot() { return (ObjectNode)parse("{\"sheets\":{\"s\":{\"cellData\":{\"0\":{\"0\":{\"v\":\"qty\"},\"1\":{\"v\":\"price\"},\"2\":{\"v\":\"amount\"}},\"1\":{\"0\":{\"v\":3},\"1\":{\"v\":0.1},\"2\":{\"f\":\"=A2*B2\",\"v\":0.3}},\"3\":{\"0\":{\"v\":2},\"1\":{\"v\":4},\"2\":{\"v\":8}}}}}}"); }
    private ObjectNode args(String range) { return obj().put("sheetId","s").put("range",range).put("kind","product").put("leftColumn","A").put("rightColumn","B").put("scale",2); }
    private void rejected(Runnable r) { try { r.run(); fail("Unsafe result was accepted"); } catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); } }
    public void testWrongFormulaCacheCannotPass() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).put("v",999);rejected(()->AgentTools.verify(s,args("C2:C4"))); }
    public void testMissingFormulaCacheCannotPass() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).remove("v");rejected(()->AgentTools.verify(s,args("C2:C4"))); }
    public void testUnknownOrRecursiveFormulaCannotPass() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).put("f","=SUM(A2:B2)");rejected(()->AgentTools.verify(s,args("C2:C4"))); }
    public void testRecursiveErrorAndDivisionByZeroFormulaRejected() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,0)).put("f","=B2*C2");final ObjectNode recursive=s;rejected(()->AgentTools.verify(recursive,args("C2:C4")));s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).put("v","#VALUE!");final ObjectNode error=s;rejected(()->AgentTools.verify(error,args("C2:C4")));final ObjectNode zero=snapshot();((ObjectNode)AgentTools.cell(zero.path("sheets").path("s"),1,1)).put("v",0);((ObjectNode)AgentTools.cell(zero.path("sheets").path("s"),1,2)).put("f","=A2/B2");rejected(()->AgentTools.verify(zero,args("C2:C4"))); }
    public void testProductCannotSkipFirstOrLastEffectiveRow() { rejected(()->AgentTools.verify(snapshot(),args("C4")));rejected(()->AgentTools.verify(snapshot(),args("C2"))); }
    public void testBlankInteriorRowsAreNotBusinessRows() { assertEquals(2,AgentTools.verify(snapshot(),args("C2:C4")).path("checkedCells").asInt()); }
    public void testDatasetCannotUseInteriorRowAsHeader() { ObjectNode a=obj().put("sheetId","s").put("range","A4:C4").put("headerRow",true).put("includeHidden",true).put("includeFiltered",true).put("formulaPolicy","reject");rejected(()->AgentTools.dataset(snapshot(),a)); }
    public void testDatasetRejectsFormulaHeaderAndMissingLeadingColumn() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).remove("f");ObjectNode a=obj().put("sheetId","s").put("range","B1:C4").put("headerRow",true).put("includeHidden",true).put("includeFiltered",true).put("formulaPolicy","reject");rejected(()->AgentTools.dataset(s,a));a.put("range","A1:C4");((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),0,0)).put("f","=B1+C1");rejected(()->AgentTools.dataset(s,a)); }
    public void testNoOpQualityRuleCannotCertifyBusinessResult() { ObjectNode d=(ObjectNode)parse("{\"columns\":[\"a\"],\"rows\":[{\"a\":1}]}");rejected(()->AgentTools.quality(d,obj())); }
    public void testValidOffsetTableAndBlankRowsRemainSupported() { ObjectNode s=(ObjectNode)parse("{\"sheets\":{\"s\":{\"cellData\":{\"2\":{\"1\":{\"v\":\"amount\"}},\"3\":{\"1\":{\"v\":10}},\"5\":{\"1\":{\"v\":20}}}}}}");ObjectNode a=obj().put("sheetId","s").put("range","B3:B6").put("headerRow",true).put("includeHidden",true).put("includeFiltered",true).put("formulaPolicy","reject");assertEquals(2,AgentTools.dataset(s,a).path("rows").size()); }
    public void testNumericStringsCannotHideFirstBusinessRowAsHeader() { ObjectNode s=snapshot();((ObjectNode)s.path("sheets").path("s").path("cellData")).remove("0");for(int c=0;c<3;c++){ObjectNode x=(ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,c);x.remove("f");x.put("v",c==0?"3":c==1?"0.1":"0.3");}rejected(()->AgentTools.verify(s,args("C4"))); }
    public void testEmptyDatasetCannotCertifyBusinessResult() { ObjectNode d=(ObjectNode)parse("{\"columns\":[\"a\"],\"rows\":[]}");ObjectNode a=obj();a.set("required",arr().add("a"));rejected(()->AgentTools.quality(d,a)); }
}
