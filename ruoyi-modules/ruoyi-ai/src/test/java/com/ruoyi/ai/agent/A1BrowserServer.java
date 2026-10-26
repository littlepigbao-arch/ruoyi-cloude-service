package com.ruoyi.ai.agent;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.common.core.domain.R;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Loopback-only browser fixture using production services/mappers and real MySQL.
 * No Nacos, Redis, gateway, JWT, or real-model claim is made by this fixture.
 */
public class A1BrowserServer {
    private final A1TestFixture f;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final Path staticRoot;
    private final long workbook;
    private String runId;
    private ControlledWriteService controlled;
    private final boolean b2=Boolean.getBoolean("b2.controlled");
    private final boolean b4=Boolean.getBoolean("b4.controlled");
    private final boolean b5=Boolean.getBoolean("b5.controlled");
    private JsonNode b5Baselines;
    private JsonNode fixtureActions=arr();
    private String seedSnapshot;
    private final String serverToken=System.getProperty("a1.serverToken","missing");
    private A1BrowserServer()throws Exception{
        f=new A1TestFixture();staticRoot=Paths.get(System.getProperty("a1.static")).toAbsolutePath().normalize();
        if(Boolean.getBoolean("b1.controlled")||b2||b5){
            controlled=b5?new ControlledWriteService(new AgentStore(f.db),f.guard,f.workbooks,f.versions,f.tx.getTransactionManager()){
                @Override boolean b5FormatOpen(){return true;}
            }:new ControlledWriteService(new AgentStore(f.db),f.guard,f.workbooks,f.versions,f.tx.getTransactionManager());
            if(b5){java.lang.reflect.Field flag=ControlledWriteService.class.getDeclaredField("enabled");flag.setAccessible(true);flag.set(controlled,true);}
            else A1TestFixture.inject(controlled,"enabled",true);
            A1TestFixture.inject(f.runs,"controlled",controlled);
        }
        if(b2){A1TestFixture.inject(f.guard,"enabled",true);A1TestFixture.inject(f.runs,"timeoutMs",900000L);}
        seedSnapshot="{\"id\":\"a1-browser\",\"name\":\"A1 isolated\",\"sheetOrder\":[\"orders\",\"arrivals\"],\"sheets\":{\"orders\":{\"id\":\"orders\",\"name\":\"Orders\",\"rowCount\":100,\"columnCount\":26,\"cellData\":{\"0\":{\"0\":{\"v\":\"initial\",\"t\":1}}}},\"arrivals\":{\"id\":\"arrivals\",\"name\":\"Arrivals\",\"rowCount\":100,\"columnCount\":26,\"cellData\":{}}}}";
        if(b5){b5Baselines=parse(new String(Files.readAllBytes(Paths.get(System.getProperty("b5.baselines"))),java.nio.charset.StandardCharsets.UTF_8));
            require(b5Baselines.isObject()&&b5Baselines.path("setStyle").isObject()&&b5Baselines.path("setRowHeight").isObject()&&b5Baselines.path("setColumnWidth").isObject(),"B5 browser baselines missing");
            seedSnapshot=b5Baselines.path("setStyle").toString();}
        workbook=f.seed(seedSnapshot);
    }
    private void send(HttpExchange x,Object value)throws Exception {byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json;charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();}
    private void handle(HttpExchange x)throws IOException {
        try {
            f.identity();String uri=x.getRequestURI().getPath();
            if(uri.equals("/test/state")){long requested=workbook;String q=x.getRequestURI().getQuery();if(b2&&q!=null&&q.startsWith("workbookId="))requested=Long.parseLong(q.substring(11));Map<String,Object> state=new HashMap<>();state.put("serverToken",serverToken);state.put("workbookId",requested);state.put("runId",runId);state.put("status",f.status(requested));send(x,state);return;}
            if(b2&&uri.equals("/test/new-workbook")){String selected=seedSnapshot;
                if(b5){String q=x.getRequestURI().getQuery(),type=q!=null&&q.startsWith("type=")?q.substring(5):"";
                    require(Arrays.asList("setStyle","setRowHeight","setColumnWidth").contains(type),"B5 browser type invalid");selected=b5Baselines.path(type).toString();}
                send(x,obj().put("workbookId",f.seed(selected)));return;}
            if(b2&&uri.equals("/test/chat-actions")){fixtureActions=json.readTree(x.getRequestBody());send(x,obj().put("ok",true));return;}
            if(b2&&uri.equals("/test/external-draft")){JsonNode req=json.readTree(x.getRequestBody());long id=req.path("workbookId").asLong();ObjectNode snapshot=(ObjectNode)parse(String.valueOf(f.status(id).get("content")));((ObjectNode)snapshot.path("sheets").path("orders").path("cellData").path("0").path("0")).put("v","external-conflict");String content=snapshot.toString();f.db.update("update ai_workbook_working_state set draft_snapshot=?,content_hash=?,version=version+1 where workbook_id=? and owner_user_id=1",content.getBytes(java.nio.charset.StandardCharsets.UTF_8),com.ruoyi.ai.util.ContentHashUtils.sha256(content),id);send(x,obj().put("ok",true));return;}
            if(uri.equals("/test/run")){seedRun();send(x,obj().put("runId",runId));return;}
            if(uri.equals("/test/unknown")){seedRun();f.tx.execute(tx->{ObjectNode run=new AgentStore(f.db).locked(runId);run.put("status","PAUSED");run.set("call",obj().put("callId","unknown-browser-call").put("status","UNKNOWN"));new AgentStore(f.db).save(run);return null;});send(x,obj().put("runId",runId));return;}
            if(uri.equals("/test/fail")){f.tx.execute(tx->{ObjectNode run=new AgentStore(f.db).locked(runId);run.put("status","FAILED");new AgentStore(f.db).save(run);f.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",workbook,runId);return null;});send(x,obj().put("ok",true));return;}
            if(uri.equals("/test/complete")){completeRun();send(x,obj().put("ok",true));return;}
            if(uri.startsWith("/a1-api/")){String p=uri.substring("/a1-api".length());JsonNode req=("POST".equals(x.getRequestMethod())||"PUT".equals(x.getRequestMethod()))?json.readTree(x.getRequestBody()):obj();
                if(p.equals("/ai/agent/capabilities")){send(x,R.ok(obj().put("enabled",b2).put("recoveryAvailable",true).put("controlledWritesEnabled",controlled!=null).put("ordinaryAiControlledEnabled",b2).put("snapshotWritesEnabled",false)));return;}
                if(b2&&p.equals("/ai/chat")){JsonNode actions=fixtureActions.deepCopy();WriteManifest.checkChatTransport(actions,req.path("clientMeta"),controlled.enabled());com.ruoyi.ai.domain.dto.WorkbookContext context=json.treeToValue(req.path("context"),com.ruoyi.ai.domain.dto.WorkbookContext.class);for(JsonNode a:actions){com.ruoyi.ai.domain.dto.Action action=json.treeToValue(a,com.ruoyi.ai.domain.dto.Action.class);com.ruoyi.ai.service.ActionTargetValidator.normalize(action,context);((ObjectNode)a).setAll((ObjectNode)json.valueToTree(action));}send(x,R.ok(obj().put("reply","Fixture-generated actions; not a real model").set("actions",actions)));return;}
                if(b2&&p.equals("/ai/agent/runs/resolve-create")){send(x,R.ok(f.runs.resolveCreation((ObjectNode)req,1)));return;}
                if(b2&&p.equals("/ai/agent/runs")){ObjectNode run=f.runs.create((ObjectNode)req,1);runId=run.path("runId").asText();if(!run.has("plan"))installPlan(runId);send(x,R.ok(f.runs.get(runId,1)));return;}
                if(p.equals("/ai/agent/controlled-writes")&&controlled!=null){ObjectNode run=controlled.create((ObjectNode)req,1);runId=run.path("runId").asText();send(x,R.ok(run));return;}
                if(p.equals("/ai/workbook/list")){send(x,R.ok(f.workbooks.selectListByUserId(1L)));return;}
                if(p.equals("/ai/agent/runs/active")){String q=x.getRequestURI().getQuery();long id=Long.parseLong(q.substring(q.indexOf('=')+1));send(x,R.ok(f.runs.active(id,1)));return;}
                if(p.startsWith("/ai/agent/workspace/")){send(x,R.ok(f.runs.workspace(Long.parseLong(p.substring(p.lastIndexOf('/')+1)),1)));return;}
                if(p.startsWith("/ai/agent/runs/")){String rest=p.substring("/ai/agent/runs/".length());String id=rest.split("/")[0];
                    if(rest.endsWith("/controlled-claims")&&controlled!=null)send(x,R.ok(controlled.claim(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/controlled-results")&&controlled!=null)send(x,R.ok(f.runs.receiptControlled(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/controlled-uncertain")&&controlled!=null)send(x,R.ok(controlled.uncertain(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/events"))send(x,R.ok(f.runs.events(id,1,0)));
                    else if(rest.endsWith("/cancel"))send(x,R.ok(f.runs.cancel(id,1)));
                    else if(rest.endsWith("/resume"))send(x,R.ok(f.runs.resume(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/lease-heartbeat"))send(x,R.ok(f.runs.heartbeat(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/tool-claims"))send(x,R.ok(f.runs.claim(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/tool-results"))send(x,R.ok(f.runs.receipt(id,1,(ObjectNode)req)));
                    else {if(b2)advancePlan(id);send(x,R.ok(f.runs.get(id,1)));}return;
                }
                if(p.matches("/ai/workbook/[0-9]+.*")){long id=Long.parseLong(p.split("/")[3]);
                    if(b4&&p.endsWith("/commits")){send(x,"POST".equals(x.getRequestMethod())?f.tx.execute(tx->f.service.commit(id,json.convertValue(req,com.ruoyi.ai.domain.dto.CommitRequest.class))):f.service.commits(id));return;}
                    if(b4&&p.endsWith("/snapshot")){send(x,f.service.snapshot(id,p.split("/")[5]));return;}
                    if(b4&&p.endsWith("/branches")){send(x,"POST".equals(x.getRequestMethod())?f.tx.execute(tx->f.service.createBranch(id,json.convertValue(req,com.ruoyi.ai.domain.dto.BranchRequest.class))):f.service.branches(id));return;}
                    if(b4&&p.endsWith("/checkout")){send(x,f.tx.execute(tx->f.service.checkout(id,Long.parseLong(p.split("/")[5]),json.convertValue(req,com.ruoyi.ai.domain.dto.CheckoutRequest.class))));return;}
                    if(p.endsWith("/working-state"))send(x,f.save(id,json.treeToValue(req,WorkingStateRequest.class)));
                    else if(p.endsWith("/status"))send(x,R.ok(f.status(id)));
                    else send(x,R.ok(f.workbooks.selectById(id,1L)));return;
                }
                send(x,R.ok(Collections.emptyList()));return;
            }
            Path file=staticRoot.resolve(uri.equals("/")?"index.html":uri.substring(1)).normalize();
            if(!file.startsWith(staticRoot)||!Files.isRegularFile(file)){x.sendResponseHeaders(404,-1);x.close();return;}
            String type=uri.endsWith(".js")?"application/javascript":uri.endsWith(".css")?"text/css":"text/html";
            x.getResponseHeaders().set("Content-Type",type+";charset=utf-8");byte[] bytes=Files.readAllBytes(file);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
        }catch(Exception e){try{send(x,R.fail(409,e.getMessage()));}catch(Exception ignored){x.close();}}
    }
    private void installPlan(String id){f.tx.execute(tx->{AgentStore store=new AgentStore(f.db);ObjectNode r=store.locked(id);ObjectNode plan=(ObjectNode)parse("{\"steps\":[{\"stepId\":\"W\",\"objective\":\"fixture write\",\"tool\":\"apply_actions\",\"dependsOn\":[],\"inputBindings\":{\"overwrite\":\"empty-only\"},\"verificationRules\":[{\"type\":\"tool_success\"}]},{\"stepId\":\"V\",\"objective\":\"fixture verify\",\"tool\":\"verify_result\",\"dependsOn\":[\"W\"],\"inputBindings\":{\"write\":{\"$step\":\"W\"},\"sheetId\":\"orders\",\"range\":\"B2:C2\",\"kind\":\"non_empty\"},\"verificationRules\":[{\"type\":\"tool_success\"}]}],\"acceptanceCriteria\":[{\"type\":\"step_succeeded\",\"stepId\":\"W\"},{\"type\":\"step_succeeded\",\"stepId\":\"V\"}]}");((ObjectNode)plan.path("steps").get(0).path("inputBindings")).set("actions",fixtureActions.deepCopy());if(b4){((ObjectNode)plan.path("steps").get(0).path("inputBindings")).put("overwrite",r.path("allowOverwrite").asBoolean()?"confirmed":"empty-only");ObjectNode v=(ObjectNode)plan.path("steps").get(1).path("inputBindings");v.put("kind","matches_write").put("sheetId",r.path("writeScopes").get(0).path("sheetId").asText()).put("range",r.path("writeScopes").get(0).path("range").asText());try{AgentPlan.validateForRun(plan,parse(String.valueOf(f.status(r.path("workbookId").asLong()).get("content"))),r.path("writeScopes"),r.path("activeSheetId").asText(),r.path("allowOverwrite").asBoolean(),true);}catch(IllegalArgumentException denied){r.put("status","PAUSED").put("message",denied.getMessage());store.save(r);return null;}}r.set("plan",plan);r.put("planVersion",1).put("stepIndex",0).put("status","EXECUTING");store.save(r);return null;});}
    private void advancePlan(String id)throws Exception{java.lang.reflect.Method advance=AgentRunService.class.getDeclaredMethod("advance",ObjectNode.class);advance.setAccessible(true);f.tx.execute(tx->{AgentStore store=new AgentStore(f.db);ObjectNode prior=store.get(id);if("CONTROLLED_WRITE".equals(prior.path("kind").asText())||!"EXECUTING".equals(prior.path("status").asText()))return null;f.guard.lock(prior.path("workbookId").asLong(),1L);ObjectNode r=store.locked(id);try{advance.invoke(f.runs,r);}catch(Exception e){throw new RuntimeException(e);}store.save(r);return null;});}
    private void seedRun(){
        if(runId!=null&&!Arrays.asList("COMPLETED","CANCELLED","FAILED").contains(f.runs.get(runId,1).path("status").asText()))throw new IllegalStateException("Fixture run already active");runId=UUID.randomUUID().toString();ObjectNode ws=f.runs.workspace(workbook,1);
        ObjectNode run=obj().put("runId",runId).put("workbookId",workbook).put("branchId",ws.path("branchId").asLong()).put("userId",1).put("version",0).put("eventSeq",0).put("status","EXECUTING").put("fencingToken",1).put("revision",ws.path("revision").asText());
        f.db.update("insert ignore into ai_agent_lease(workbook_id) values(?)",workbook);
        if(f.db.update("update ai_agent_lease set run_id=?,fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id is null",runId,System.currentTimeMillis()+120000,workbook)!=1)throw new IllegalStateException("Fixture lease still active");
        run.put("fencingToken",f.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook));
        f.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))",runId,1,workbook,ws.path("branchId").asLong(),UUID.randomUUID().toString(),hash(run),run.toString());
    }
    private void completeRun(){
        f.identity();f.tx.execute(tx->{
            f.guard.lock(workbook,1L);ObjectNode run=new AgentStore(f.db).locked(runId);
            // Simulate a previously authorized legacy task's persisted completion.
            // This test seam never accepts a Snapshot from the browser.
            f.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",workbook,runId);
            Map<String,Object> state=f.status(workbook);ObjectNode snapshot=(ObjectNode)parse(String.valueOf(state.get("content")));
            ((ObjectNode)snapshot.path("sheets").path("orders").path("cellData").path("0").path("0")).put("v","server-completed");
            long version=state.get("workingState")==null?-1:((Number)((Map<?,?>)state.get("workingState")).get("version")).longValue();
            R<?> saved=f.service.saveWorkingState(workbook,f.request(workbook,version,String.valueOf(state.get("loadedRevision")),snapshot.toString()));
            if(saved.getCode()!=200)throw new IllegalStateException(saved.getMsg());run.put("status","COMPLETED");new AgentStore(f.db).save(run);return null;
        });
    }
    public static void main(String[]args)throws Exception{
        A1BrowserServer app=new A1BrowserServer();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",33358),0);server.setExecutor(Executors.newFixedThreadPool(4));server.createContext("/",app::handle);server.start();System.out.println("A1_BROWSER_SERVER_READY workbookId="+app.workbook+" port=33358");
    }
}
