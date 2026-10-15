package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Single effective row isolates cache errors from coverage/blank-row failures. */
public class FormulaCacheIntegrityTest extends TestCase {
    private ObjectNode snapshot() { return (ObjectNode)parse("{\"sheets\":{\"s\":{\"cellData\":{\"0\":{\"0\":{\"v\":\"qty\"},\"1\":{\"v\":\"price\"},\"2\":{\"v\":\"amount\"}},\"1\":{\"0\":{\"v\":3},\"1\":{\"v\":0.1},\"2\":{\"f\":\"=A2*B2\",\"v\":0.3}}}}}}"); }
    private ObjectNode args() { return obj().put("sheetId","s").put("range","C2").put("kind","product").put("leftColumn","A").put("rightColumn","B").put("scale",2); }
    private void rejected(ObjectNode s) { try { AgentTools.verify(s,args());fail("Formula cache was ignored"); } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("FORMULA_UNVERIFIED")); } }
    public void testWrongCacheRejectedWithoutOtherRowErrors() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).put("v",999);rejected(s); }
    public void testMissingCacheRejectedWithoutOtherRowErrors() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).remove("v");rejected(s); }
    public void testWrongCacheCannotHideBehindBusinessRounding() { ObjectNode s=snapshot();((ObjectNode)AgentTools.cell(s.path("sheets").path("s"),1,2)).put("v",0.300001);rejected(s); }
    public void testCorrectCachePassesWithActualValueUnchanged() { ObjectNode s=snapshot();assertTrue(AgentTools.verify(s,args()).path("passed").asBoolean());assertEquals("0.3",AgentTools.cell(s.path("sheets").path("s"),1,2).path("v").asText()); }
}
