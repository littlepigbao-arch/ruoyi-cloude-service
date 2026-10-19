package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import java.lang.reflect.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Pure contract tests: no HTTP, database, model or fake persistence. */
public class B3PlanAdmissionTest extends TestCase {
    private ObjectNode snapshot(){return (ObjectNode)parse("{\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"Orders\",\"rowCount\":20,\"columnCount\":10,\"cellData\":{}}}}");}
    private ObjectNode plan(){return (ObjectNode)parse("{\"steps\":[{\"stepId\":\"R\",\"tool\":\"read_range\",\"dependsOn\":[],\"inputBindings\":{\"sheetId\":\"s\",\"range\":\"A1:B2\"},\"verificationRules\":[{\"type\":\"tool_success\"}]},{\"stepId\":\"W\",\"tool\":\"apply_actions\",\"dependsOn\":[],\"inputBindings\":{\"overwrite\":\"empty-only\",\"actions\":[{\"type\":\"setCell\",\"sheetId\":\"s\",\"range\":\"B2\",\"value\":11}]},\"verificationRules\":[{\"type\":\"tool_success\"}]},{\"stepId\":\"V\",\"tool\":\"verify_result\",\"dependsOn\":[\"W\"],\"inputBindings\":{\"write\":{\"$step\":\"W\"},\"sheetId\":\"s\",\"range\":\"B2\",\"kind\":\"non_empty\"},\"verificationRules\":[{\"type\":\"tool_success\"}]}],\"acceptanceCriteria\":[{\"type\":\"step_succeeded\",\"stepId\":\"R\"},{\"type\":\"step_succeeded\",\"stepId\":\"W\"},{\"type\":\"step_succeeded\",\"stepId\":\"V\"}]}");}
    private ObjectNode input(ObjectNode p,int i){return (ObjectNode)p.path("steps").get(i).path("inputBindings");}
    private void admit(ObjectNode p,boolean enabled){try{AgentPlan.class.getMethod("validateForRun",com.fasterxml.jackson.databind.JsonNode.class,com.fasterxml.jackson.databind.JsonNode.class,com.fasterxml.jackson.databind.JsonNode.class,String.class,boolean.class,boolean.class).invoke(null,p,snapshot(),parse("[{\"sheetId\":\"s\",\"range\":\"B2:C3\"}]"),"s",false,enabled);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException)throw(RuntimeException)e.getCause();throw new AssertionError(e.getCause());}catch(Exception e){throw new AssertionError("Missing B3 admission contract",e);}}
    private void reject(ObjectNode p){try{admit(p,true);fail("Unsupported plan admitted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    public void testExistingV1ScalarPlanPasses(){admit(plan(),true);}
    public void testExistingV2MatrixPlanPasses(){ObjectNode p=plan();input(p,1).set("actions",parse("[{\"type\":\"setValues\",\"schemaVersion\":\"2.0\",\"target\":{\"sheetId\":\"s\",\"range\":\"B2\"},\"values\":[[11]]}]"));admit(p,true);}
    public void testLegacyResultWriteRejectedBeforePriorRead(){ObjectNode p=plan();((ObjectNode)p.path("steps").get(1)).put("tool","write_result");input(p,1).put("sheetId","s").put("range","B2");reject(p);}
    public void testUnmigratedWritesUnknownAndUnsafeB4SubsetsAreRejected(){for(String type:new String[]{"setFormula","merge","breakApart","insertRows","deleteRows","insertColumns","deleteColumns","setRowHeight","setColumnWidth","setStyle","undo","redo","sumToCell","setFilter","clearFilter","unknown"}){ObjectNode p=plan();((ObjectNode)input(p,1).path("actions").get(0)).put("type",type);reject(p);}ObjectNode alias=plan();((ObjectNode)input(alias,1).path("actions").get(0)).put("type","setCellForCell").set("value",obj().put("v",11));reject(alias);for(String mode:new String[]{"format","all"}){ObjectNode clear=plan();ObjectNode a=(ObjectNode)input(clear,1).path("actions").get(0);a.put("type","clearRange").put("clearWhat",mode);a.remove("value");reject(clear);}}
    public void testMalformedMatrixRejectedBeforePriorRead(){ObjectNode p=plan();input(p,1).set("actions",parse("[{\"type\":\"setValues\",\"sheetId\":\"s\",\"range\":\"B2\",\"values\":[[1,2]]}]"));reject(p);}
    public void testInvalidReadArgumentsRejected(){ObjectNode p=plan();input(p,0).put("range","A:A");reject(p);}
    public void testUnknownReadFieldsRejected(){ObjectNode p=plan();input(p,0).put("arbitrarySql","delete");reject(p);}
    public void testUnknownToolRejected(){ObjectNode p=plan();((ObjectNode)p.path("steps").get(0)).put("tool","eval");reject(p);}
    public void testInvalidTransportRejected(){ObjectNode p=plan();p.put("transportVersion",1);reject(p);}
    public void testInvalidWriteTransportRejected(){ObjectNode p=plan();input(p,1).put("transportVersion",1);reject(p);}
    public void testMissingV2SheetRejected(){ObjectNode p=plan();((ObjectNode)input(p,1).path("actions").get(0)).put("schemaVersion","2.0");reject(p);}
    public void testOutOfScopeRejected(){ObjectNode p=plan();((ObjectNode)input(p,1).path("actions").get(0)).put("range","B9");input(p,2).put("range","B9");reject(p);}
    public void testFormulaDisguisedAsValueRejected(){ObjectNode p=plan();((ObjectNode)input(p,1).path("actions").get(0)).put("value","=B1+C1");reject(p);}
    public void testMixedReadWriteGroupRejected(){ObjectNode p=plan();((ArrayNode)input(p,1).path("actions")).add(obj().put("type","getSelection"));reject(p);}
    public void testDisabledWritingRejectsOtherwiseValidPlan(){try{admit(plan(),false);fail("Disabled write admitted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
}
