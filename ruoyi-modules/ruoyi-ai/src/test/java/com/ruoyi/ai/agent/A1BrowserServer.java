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
    private final String serverToken=System.getProperty("a1.serverToken","missing");
    private A1BrowserServer()throws Exception{
        f=new A1TestFixture();staticRoot=Paths.get(System.getProperty("a1.static")).toAbsolutePath().normalize();
        workbook=f.seed("{\"id\":\"a1-browser\",\"name\":\"A1 isolated\",\"sheetOrder\":[\"orders\",\"arrivals\"],\"sheets\":{\"orders\":{\"id\":\"orders\",\"name\":\"Orders\",\"rowCount\":100,\"columnCount\":26,\"cellData\":{\"0\":{\"0\":{\"v\":\"initial\",\"t\":1}}}},\"arrivals\":{\"id\":\"arrivals\",\"name\":\"Arrivals\",\"rowCount\":100,\"columnCount\":26,\"cellData\":{}}}}");
    }
    private void send(HttpExchange x,Object value)throws Exception {byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json;charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();}
    private void handle(HttpExchange x)throws IOException {
        try {
            f.identity();String uri=x.getRequestURI().getPath();
            if(uri.equals("/test/state")){Map<String,Object> state=new HashMap<>();state.put("serverToken",serverToken);state.put("workbookId",workbook);state.put("runId",runId);state.put("status",f.status(workbook));send(x,state);return;}
            if(uri.equals("/test/run")){seedRun();send(x,obj().put("runId",runId));return;}
            if(uri.equals("/test/unknown")){seedRun();f.tx.execute(tx->{ObjectNode run=new AgentStore(f.db).locked(runId);run.put("status","PAUSED");run.set("call",obj().put("callId","unknown-browser-call").put("status","UNKNOWN"));new AgentStore(f.db).save(run);return null;});send(x,obj().put("runId",runId));return;}
            if(uri.equals("/test/fail")){f.tx.execute(tx->{ObjectNode run=new AgentStore(f.db).locked(runId);run.put("status","FAILED");new AgentStore(f.db).save(run);f.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",workbook,runId);return null;});send(x,obj().put("ok",true));return;}
            if(uri.equals("/test/complete")){completeRun();send(x,obj().put("ok",true));return;}
            if(uri.startsWith("/a1-api/")){String p=uri.substring("/a1-api".length());JsonNode req=("POST".equals(x.getRequestMethod())||"PUT".equals(x.getRequestMethod()))?json.readTree(x.getRequestBody()):obj();
                if(p.equals("/ai/agent/capabilities")){send(x,R.ok(obj().put("enabled",false).put("recoveryAvailable",true).put("snapshotWritesEnabled",false)));return;}
                if(p.equals("/ai/workbook/list")){send(x,R.ok(f.workbooks.selectListByUserId(1L)));return;}
                if(p.equals("/ai/agent/runs/active")){String q=x.getRequestURI().getQuery();long id=Long.parseLong(q.substring(q.indexOf('=')+1));send(x,R.ok(f.runs.active(id,1)));return;}
                if(p.startsWith("/ai/agent/workspace/")){send(x,R.ok(f.runs.workspace(Long.parseLong(p.substring(p.lastIndexOf('/')+1)),1)));return;}
                if(p.startsWith("/ai/agent/runs/")){String rest=p.substring("/ai/agent/runs/".length());String id=rest.split("/")[0];
                    if(rest.endsWith("/events"))send(x,R.ok(f.runs.events(id,1,0)));
                    else if(rest.endsWith("/cancel"))send(x,R.ok(f.runs.cancel(id,1)));
                    else if(rest.endsWith("/resume"))send(x,R.ok(f.runs.resume(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/lease-heartbeat"))send(x,R.ok(f.runs.heartbeat(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/tool-claims"))send(x,R.ok(f.runs.claim(id,1,(ObjectNode)req)));
                    else if(rest.endsWith("/tool-results"))send(x,R.ok(f.runs.receipt(id,1,(ObjectNode)req)));
                    else send(x,R.ok(f.runs.get(id,1)));return;
                }
                if(p.matches("/ai/workbook/[0-9]+.*")){long id=Long.parseLong(p.split("/")[3]);
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
