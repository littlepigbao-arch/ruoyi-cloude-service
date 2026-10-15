package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import java.lang.reflect.Method;
import java.util.UUID;
import static com.ruoyi.ai.agent.AgentJson.*;

public class ReplanGuardTest extends TestCase {
    private A1TestFixture f; private ObjectNode run;
    protected void setUp() throws Exception {
        f=new A1TestFixture();long w=f.seed("{}");long b=f.request(w,-1,null,"{}").getBranchId();String id=UUID.randomUUID().toString();
        run=obj().put("runId",id).put("userId",1).put("workbookId",w).put("branchId",b).put("revision",hash(obj())).put("status","WAITING_USER").put("version",0).put("eventSeq",0).put("fencingToken",1).put("deadline",System.currentTimeMillis()+600000).put("planVersion",1);
        run.set("constraints",arr());run.set("completedSteps",arr());run.set("outputs",obj().put("keep","evidence"));run.set("plan",obj().set("steps",arr()));
        f.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'WAITING_USER',?,0,now(3),now(3))",id,1,w,b,UUID.randomUUID().toString(),hash(run),run.toString());
        f.db.update("insert into ai_agent_lease values(?,?,1,?)",w,id,System.currentTimeMillis()+600000);
        A1TestFixture.inject(f.runs,"replanning",true);A1TestFixture.inject(f.runs,"tokenLimit",60000);
    }
    private void call(String tool,String status) { ObjectNode c=obj().put("tool",tool).put("status",status).put("callId","history");f.db.update("insert into ai_agent_tool_call values(?,?,?,?,?)",run.path("runId").asText(),"history",status,hash(c),c.toString()); }
    private ObjectNode answer() { return f.runs.answer(run.path("runId").asText(),1,obj().put("answer","continue remaining work")); }
    public void testCompletedWriteInLedgerPausesAndKeepsEvidence() { call("apply_actions","SUCCEEDED");ObjectNode out=answer();assertEquals("PAUSED",out.path("status").asText());assertEquals("evidence",out.path("outputs").path("keep").asText());assertEquals(1,out.path("planVersion").asInt());assertEquals(1,f.db.queryForObject("select count(*) from ai_agent_tool_call where run_id=?",Integer.class,run.path("runId").asText()).intValue()); }
    public void testUnknownCallWithoutStatePointerCannotReplay() { call("apply_actions","UNKNOWN");assertEquals("PAUSED",answer().path("status").asText()); }
    public void testCompletedStepWithoutCallLedgerAlsoProtected() { ObjectNode step=obj().put("stepId","done").put("tool","write_result").put("status","SUCCEEDED");f.db.update("insert into ai_agent_step values(?,1,'done','SUCCEEDED',?)",run.path("runId").asText(),step.toString());assertEquals("PAUSED",answer().path("status").asText()); }
    public void testSchedulerBlocksReplanningBeforeModelCall() throws Exception { call("apply_actions","SUCCEEDED");run.put("status","REPLANNING");Method advance=AgentRunService.class.getDeclaredMethod("advance",ObjectNode.class);advance.setAccessible(true);assertNull(f.tx.execute(s->{try{return advance.invoke(f.runs,run);}catch(Exception e){throw new RuntimeException(e);}}));assertEquals("PAUSED",run.path("status").asText());assertEquals(0,run.path("modelCalls").asInt());assertEquals("evidence",run.path("outputs").path("keep").asText()); }
    public void testReadOnlyCompletedCallCanReplan() { call("inspect_sheet","SUCCEEDED");assertEquals("REPLANNING",answer().path("status").asText()); }
    public void testLateModelResponseCannotClearNewWriteEvidence() throws Exception {
        run.put("status","PLANNING").put("planningToken","inflight");run.set("expectedSnapshot",obj());
        f.db.update("update ai_agent_run set status='PLANNING',state_json=? where run_id=?",run.toString(),run.path("runId").asText());
        A1TestFixture.inject(f.runs,"contextLimit",24000);
        A1TestFixture.inject(f.runs,"llm",new com.ruoyi.ai.service.LlmClient(){
            public com.ruoyi.ai.service.LlmResponse chat(java.util.List<java.util.Map<String,String>> messages){
                call("write_result","SUCCEEDED");
                com.ruoyi.ai.service.LlmResponse response=new com.ruoyi.ai.service.LlmResponse();
                response.setContent("{\"steps\":[{\"stepId\":\"again\",\"tool\":\"get_workbook_catalog\",\"dependsOn\":[],\"inputBindings\":{},\"verificationRules\":[{\"type\":\"tool_success\"}]}],\"acceptanceCriteria\":[{\"type\":\"step_succeeded\",\"stepId\":\"again\"}]}");return response;
            }
        });
        Method plan=AgentRunService.class.getDeclaredMethod("plan",ObjectNode.class,long.class);plan.setAccessible(true);plan.invoke(f.runs,run,1L);
        ObjectNode actual=f.runs.get(run.path("runId").asText(),1);assertEquals("PAUSED",actual.path("status").asText());assertEquals("evidence",actual.path("outputs").path("keep").asText());assertEquals(1,actual.path("planVersion").asInt());
        assertEquals(0,f.db.queryForObject("select count(*) from ai_agent_plan where run_id=?",Integer.class,run.path("runId").asText()).intValue());
    }
}
