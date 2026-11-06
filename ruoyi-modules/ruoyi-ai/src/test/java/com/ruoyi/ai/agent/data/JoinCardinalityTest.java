package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.AgentTools;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1b join cardinality (overall plan §8.1): many-to-one requires the left join key to be unique,
 * so duplicates are rejected before any join runs. Red first: the current join silently fans out
 * duplicate left keys. The assertions go through the AgentTools thin delegate, which after the
 * C1b extraction forwards verbatim to DatasetService.join.
 */
public class JoinCardinalityTest extends TestCase {
    private ObjectNode data(String json) { return (ObjectNode) parse(json); }
    private ObjectNode joinArgs() { return data("{\"leftKey\":\"order\",\"rightKey\":\"order\",\"rightPrefix\":\"arrived_\",\"cardinality\":\"many-to-one\"}"); }
    public void testDuplicateLeftKeyIsRejectedBeforeJoin() {
        JsonNode left=data("{\"columns\":[\"order\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"amount\":1},{\"order\":\"P1\",\"amount\":2},{\"order\":\"P2\",\"amount\":3}]}");
        JsonNode right=data("{\"columns\":[\"order\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"amount\":10},{\"order\":\"P2\",\"amount\":20}]}");
        try { AgentTools.join(left,right,joinArgs()); fail("Duplicate left join key accepted"); }
        catch(IllegalArgumentException expected) { assertTrue("message must carry the JOIN_ violation: "+expected.getMessage(),expected.getMessage().startsWith("JOIN_LEFT_KEY_DUPLICATED")); }
    }
    public void testUniqueLeftKeyStillJoins() {
        JsonNode left=data("{\"columns\":[\"order\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"amount\":1},{\"order\":\"P2\",\"amount\":3}]}");
        JsonNode right=data("{\"columns\":[\"order\",\"amount\"],\"rows\":[{\"order\":\"P1\",\"amount\":10},{\"order\":\"P2\",\"amount\":20}]}");
        JsonNode joined=AgentTools.join(left,right,joinArgs());
        assertEquals(2,joined.path("matched").asInt()); assertEquals(0,joined.path("unmatched").asInt());
        assertEquals(10,joined.path("rows").get(0).path("arrived_amount").asInt());
        assertEquals(20,joined.path("rows").get(1).path("arrived_amount").asInt());
    }
}
