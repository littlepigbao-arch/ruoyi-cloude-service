package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.*;
import junit.framework.TestCase;
import static com.ruoyi.ai.agent.AgentJson.*;

public class PlanValidationTest extends TestCase {
    private ObjectNode plan() { return (ObjectNode)parse("{\"steps\":[{\"stepId\":\"W\",\"tool\":\"apply_actions\",\"dependsOn\":[],\"inputBindings\":{\"actions\":[{\"type\":\"setValues\",\"sheetId\":\"s\",\"range\":\"C2:C4\",\"values\":[[1],[2],[3]]}]},\"verificationRules\":[{\"type\":\"tool_success\"}]},{\"stepId\":\"V\",\"tool\":\"verify_result\",\"dependsOn\":[\"W\"],\"inputBindings\":{\"write\":{\"$step\":\"W\"},\"sheetId\":\"s\",\"range\":\"C2:C4\",\"kind\":\"non_empty\"},\"verificationRules\":[{\"type\":\"tool_success\"}]}],\"acceptanceCriteria\":[{\"type\":\"step_succeeded\",\"stepId\":\"W\"},{\"type\":\"step_succeeded\",\"stepId\":\"V\"}]}"); }
    private ObjectNode verifier(ObjectNode p) { return (ObjectNode)p.path("steps").get(1); }
    private void rejected(ObjectNode p) { try { AgentPlan.validate(p);fail("Unsafe plan accepted"); } catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); } }
    public void testLiteralResultReferenceRejectedRecursively() { ObjectNode p=plan();((ObjectNode)verifier(p).path("inputBindings")).set("dataset",obj().put("resultRef","forged").put("sourceRevision","forged"));rejected(p); }
    public void testUnrelatedVerifierDoesNotValidateWrite() { ObjectNode p=plan();verifier(p).put("tool","check_data_quality");verifier(p).set("inputBindings",obj());rejected(p); }
    public void testVerifierRequiresExplicitWriteDependency() { ObjectNode p=plan();((ObjectNode)verifier(p).path("inputBindings")).remove("write");rejected(p); }
    public void testVerifierMustCoverWrittenSheetAndRange() { ObjectNode p=plan();((ObjectNode)verifier(p).path("inputBindings")).put("sheetId","other");rejected(p);p=plan();((ObjectNode)verifier(p).path("inputBindings")).put("range","C4");rejected(p); }
    public void testNonEmptyDoesNotProveFormulaBusinessResult() { ObjectNode p=plan();ObjectNode action=(ObjectNode)p.path("steps").get(0).path("inputBindings").path("actions").get(0);action.put("type","setFormula").put("formula","=A2*B2");rejected(p); }
    public void testEveryWriteNeedsItsOwnBoundVerifier() { ObjectNode p=plan();ObjectNode second=((ObjectNode)p.path("steps").get(0)).deepCopy();second.put("stepId","W2");((ArrayNode)p.path("steps")).insert(1,second);((ArrayNode)p.path("acceptanceCriteria")).add(obj().put("type","step_succeeded").put("stepId","W2"));rejected(p); }
    public void testProductRuleAcceptsExplicitFormulaOutputBinding() { ObjectNode p=plan();ObjectNode action=(ObjectNode)p.path("steps").get(0).path("inputBindings").path("actions").get(0);action.put("type","setFormula").put("formula","=A2*B2");((ObjectNode)verifier(p).path("inputBindings")).put("kind","product").put("leftColumn","A").put("rightColumn","B");AgentPlan.validate(p); }
    public void testValidExplicitWriteVerificationAndReadOnlyPlans() { AgentPlan.validate(plan());ObjectNode p=plan();((ArrayNode)p.path("steps")).remove(0);((ArrayNode)p.path("acceptanceCriteria")).remove(0);ObjectNode v=verifierRead(p);v.put("tool","get_workbook_catalog");v.set("dependsOn",arr());v.set("inputBindings",obj());AgentPlan.validate(p); }
    private ObjectNode verifierRead(ObjectNode p) { return (ObjectNode)p.path("steps").get(0); }
}
