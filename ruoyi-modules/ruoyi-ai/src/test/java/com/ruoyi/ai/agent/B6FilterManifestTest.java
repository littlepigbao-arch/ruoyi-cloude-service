package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

public class B6FilterManifestTest extends TestCase {
    private ObjectNode snapshot(){return (ObjectNode)parse("{\"sheets\":{\"s\":{\"name\":\"Sheet\",\"rowCount\":8,\"columnCount\":3,\"cellData\":{\"0\":{\"0\":{\"v\":\"Name\",\"t\":1}},\"1\":{\"0\":{\"v\":\"Alice\",\"t\":1}},\"2\":{\"0\":{\"v\":\"Bob\",\"t\":1}},\"3\":{\"0\":{\"v\":\"Alice\",\"t\":1}}},\"mergeData\":[]}}}");}
    private ArrayNode scopes(){return (ArrayNode)parse("[{\"sheetId\":\"s\",\"range\":\"A1:A4\"}]");}
    private ObjectNode set(){return (ObjectNode)parse("{\"schemaVersion\":\"2.0\",\"type\":\"setFilter\",\"target\":{\"sheetId\":\"s\",\"range\":\"A1:A4\"},\"columnIndex\":0,\"filters\":[\"Alice\"]}");}
    private ObjectNode clear(boolean all){ObjectNode a=(ObjectNode)parse("{\"schemaVersion\":\"2.0\",\"type\":\"clearFilter\",\"target\":{\"sheetId\":\"s\",\"range\":\"A1:A4\"}}");if(all)a.put("removeFilter",true);else a.put("columnIndex",0);return a;}
    private void reject(Runnable action){try{action.run();fail("Unsafe filter action accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    public void testGateAndWholeGroup(){assertFalse(WriteManifest.availableActions(true).contains("setFilter"));assertTrue(WriteManifest.availableActions(true,true).contains("setFilter"));reject(()->WriteManifest.classify(arr().add(set()),true));assertEquals("controlled-values",WriteManifest.classify(arr().add(set()),true,true));reject(()->WriteManifest.classify(arr().add(set()).add(set()),true,true));}
    public void testSetAndClearUseTrustedResourceOnly(){ObjectNode initial=snapshot(),before=initial.deepCopy(),manifest=FilterManifest.preview(initial,set(),scopes());assertTrue(manifest.path("filterPatch").path("beforeData").isNull());assertEquals(2,manifest.path("filterPatch").path("filter").path("cachedFilteredOut").get(0).asInt());FilterManifest.validateReceipt(manifest,manifest.path("filterReadback"));ObjectNode setState=FilterManifest.apply(initial,manifest);assertEquals(before,initial);assertEquals(1,setState.path("resources").size());
        ObjectNode column=FilterManifest.preview(setState,clear(false),scopes()),cleared=FilterManifest.apply(setState,column);assertEquals(0,parse(cleared.path("resources").get(0).path("data").asText()).path("s").path("filterColumns").size());
        ObjectNode remove=FilterManifest.preview(setState,clear(true),scopes()),removed=FilterManifest.apply(setState,remove);assertEquals("{}",removed.path("resources").get(0).path("data").asText());reject(()->FilterManifest.validateReceipt(manifest,obj().put("sheetId","s")));
        ObjectNode forged=manifest.deepCopy();((ObjectNode)forged.path("filterPatch")).put("resourceData","{}");reject(()->FilterManifest.apply(initial,forged));
    }
    public void testScopeAndMalformedInputsReject(){ObjectNode a=set(),scopeAction=a;reject(()->FilterManifest.preview(snapshot(),scopeAction,(ArrayNode)parse("[{\"sheetId\":\"s\",\"range\":\"A1\"}]")));
        a=set();a.put("columnIndex",2);ObjectNode badColumn=a;reject(()->FilterManifest.preview(snapshot(),badColumn,scopes()));
        a=set();((ObjectNode)a.path("target")).remove("range");ObjectNode missingRange=a;reject(()->FilterManifest.preview(snapshot(),missingRange,scopes()));
        a=set();a.put("customFormula","=A1");ObjectNode formula=a;reject(()->FilterManifest.preview(snapshot(),formula,scopes()));
        ObjectNode changed=snapshot();changed.set("resources",parse("[{\"name\":\"SHEET_FILTER_PLUGIN\",\"data\":\"{bad}\"}]"));reject(()->FilterManifest.preview(changed,set(),scopes()));
    }
    public void testNumericExactMatchAndExistingResourceScope(){
        ObjectNode source=snapshot();ObjectNode data=(ObjectNode)source.path("sheets").path("s").path("cellData");
        ((ObjectNode)data.path("1").path("0")).put("v",10).put("t",2);
        ((ObjectNode)data.path("2").path("0")).put("v",20).put("t",2);
        ((ObjectNode)data.path("3").path("0")).put("v",10).put("t",2);
        ObjectNode a=set();a.set("filters",arr().add("10"));
        ObjectNode manifest=FilterManifest.preview(source,a,scopes());assertEquals(2,manifest.path("filterPatch").path("filter").path("cachedFilteredOut").get(0).asInt());
        ObjectNode prior=FilterManifest.apply(source,manifest),other=prior.deepCopy();
        ((ObjectNode)other.path("resources").get(0)).put("data","{\"other\":{\"unexpected\":true}}");
        reject(()->FilterManifest.apply(other,manifest));
        ObjectNode wrongRange=set();((ObjectNode)wrongRange.path("target")).put("range","A1:A3");reject(()->FilterManifest.preview(prior,wrongRange,scopes()));
    }
    public void testListOfValuesAndReadOnlyFilterCapability(){
        ObjectNode a=set();a.set("filters",arr().add("Alice").add("Bob"));
        ObjectNode manifest=FilterManifest.preview(snapshot(),a,scopes());
        assertEquals(0,manifest.path("filterPatch").path("filter").path("cachedFilteredOut").size());
        assertTrue(WriteManifest.READ_DISPLAY_ACTIONS.contains("getFilter"));
        assertFalse(WriteManifest.availableActions(false,true).contains("getFilter"));
    }
}
