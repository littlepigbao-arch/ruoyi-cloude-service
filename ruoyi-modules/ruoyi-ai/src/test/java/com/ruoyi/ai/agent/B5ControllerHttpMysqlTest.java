package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.controller.AgentRunController;
import com.ruoyi.common.core.context.SecurityContextHolder;
import junit.framework.TestCase;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Actual Spring MVC mapping and JSON over loopback, backed only by the allowlisted B5 MySQL fixture.
 * Gateway authentication and a mounted Vue page are outside this test's scope.
 */
public class B5ControllerHttpMysqlTest extends TestCase {
    private static AgentRunController controller;
    @Configuration @EnableWebMvc public static class WebConfig {
        @Bean public AgentRunController agentRunController(){return controller;}
    }
    private A1TestFixture fixture;
    private ControlledWriteService controlled;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext spring;
    private Path base;
    private long workbook;
    private ObjectNode request;
    private String original;

    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated B5 schema required");
        fixture=new A1TestFixture();
        ObjectNode snapshot=obj().put("id","b5-http");snapshot.set("sheetOrder",arr().add("s"));
        ObjectNode sheet=obj().put("id","s").put("name","Sheet").put("rowCount",10).put("columnCount",10);
        sheet.with("cellData").with("0").set("0",obj().put("v","keep").put("t",1));
        snapshot.with("sheets").set("s",sheet);original=snapshot.toString();
        workbook=fixture.seed(original);
        ObjectNode ws=fixture.runs.workspace(workbook,1);
        request=obj().put("requestId",UUID.randomUUID().toString()).put("workbookId",workbook)
            .put("branchId",ws.path("branchId").asLong()).put("loadedRevision",ws.path("loadedRevision").asText())
            .put("expectedDraftVersion",ws.path("draftVersion").asLong()).put("expectedBranchVersion",ws.path("branchVersion").asLong())
            .put("activeSheetId","s").put("overwrite","confirmed");
        controlled=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,
            fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager());
        A1TestFixture.inject(controlled,"enabled",true);
        A1TestFixture.inject(fixture.runs,"controlled",controlled);
        controller=new AgentRunController(fixture.runs);

        Path testRoot=Paths.get(System.getProperty("a1.module",System.getProperty("basedir",".")))
            .toAbsolutePath().normalize().resolve("target/b5-controller-http");
        Files.createDirectories(testRoot);
        base=Files.createTempDirectory(testRoot,"tomcat-");
        spring=new AnnotationConfigWebApplicationContext();spring.register(WebConfig.class);
        tomcat=new Tomcat();tomcat.setBaseDir(base.toString());tomcat.setHostname("127.0.0.1");tomcat.setPort(0);
        Context context=tomcat.addContext("",base.toString());
        Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(spring));context.addServletMappingDecoded("/","dispatcher");
        FilterDef identity=new FilterDef();identity.setFilterName("isolatedIdentity");identity.setFilter(new Filter(){
            @Override public void doFilter(ServletRequest in,ServletResponse out,FilterChain chain)throws IOException,ServletException{
                String user=((HttpServletRequest)in).getHeader("X-Isolated-User");
                SecurityContextHolder.setUserId("2".equals(user)?"2":"1");
                try{chain.doFilter(in,out);}finally{SecurityContextHolder.remove();}
            }
        });context.addFilterDef(identity);
        FilterMap map=new FilterMap();map.setFilterName("isolatedIdentity");map.addURLPattern("/*");context.addFilterMap(map);
        tomcat.getConnector();tomcat.start();
    }
    @Override protected void tearDown() throws Exception {
        try{if(tomcat!=null){tomcat.stop();tomcat.destroy();}}finally{
            if(spring!=null)spring.close();controller=null;
            if(base!=null){
                Path target=base.toRealPath(),root=Paths.get(System.getProperty("a1.module",System.getProperty("basedir",".")))
                    .toAbsolutePath().normalize().resolve("target/b5-controller-http").toRealPath();
                if(!target.startsWith(root)||!target.getFileName().toString().startsWith("tomcat-"))
                    throw new IllegalStateException("Refusing to remove an unexpected Tomcat test directory");
                try(Stream<Path> paths=Files.walk(target)){
                    for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
                }
            }
        }
    }
    private JsonNode http(String method,String path,JsonNode body,int user)throws Exception{
        URL url=new URL("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+path);
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();conn.setRequestMethod(method);conn.setConnectTimeout(5000);conn.setReadTimeout(10000);
        conn.setRequestProperty("X-Isolated-User",String.valueOf(user));
        if(body!=null){conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}}
        int status=conn.getResponseCode();assertEquals("HTTP transport failed",200,status);
        try(InputStream in=conn.getInputStream()){return parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        finally{conn.disconnect();}
    }
    private ObjectNode format(String type){
        ObjectNode next=request.deepCopy(),action=obj().put("schemaVersion","2.0").put("type",type);
        action.set("target",obj().put("sheetId","s"));
        String range;
        if("setStyle".equals(type)){((ObjectNode)action.path("target")).put("range","A1");action.put("background","#223344");range="A1";}
        else if("setRowHeight".equals(type)){action.put("rowPosition",1).put("height",50);range="A1:J1";}
        else{action.put("columnPosition",1).put("width",120);range="A1:A10";}
        next.set("actions",arr().add(action));next.set("writeScopes",arr().add(obj().put("sheetId","s").put("range",range)));
        return next;
    }
    public void testFormatGateAcrossHttpCreatesNoLedgerOrDraft()throws Exception{
        ControlledWriteService closed=new ControlledWriteService(new AgentStore(fixture.db),fixture.guard,
            fixture.workbooks,fixture.versions,fixture.tx.getTransactionManager()){
            @Override boolean b5FormatOpen(){return false;}
        };
        java.lang.reflect.Field enabled=ControlledWriteService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);enabled.setBoolean(closed,true);
        A1TestFixture.inject(fixture.runs,"controlled",closed);
        JsonNode capabilities=http("GET","/ai/agent/capabilities",null,1);
        assertEquals(200,capabilities.path("code").asInt());
        assertFalse(capabilities.path("data").path("snapshotWritesEnabled").asBoolean());
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"}){
            assertFalse(capabilities.path("data").path("controlledActions").toString().contains(type));
            for(String entry:new String[]{"explicit","ordinary-ai","plan-agent"}){
                ObjectNode next=format(type).put("requestId",UUID.randomUUID().toString()).put("entryPoint",entry);
                JsonNode denied=http("POST","/ai/agent/controlled-writes",next,1);
                assertEquals(type+" / "+entry,409,denied.path("code").asInt());
                assertTrue(denied.path("msg").asText(),denied.path("msg").asText().contains("AI_CAPABILITY_BLOCKED"));
            }
        }
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_run where workbook_id=?",Integer.class,workbook).intValue());
        assertEquals(0,fixture.db.queryForObject("select count(*) from ai_agent_lease where workbook_id=?",Integer.class,workbook).intValue());
        assertNull(fixture.status(workbook).get("workingState"));assertEquals(original,fixture.status(workbook).get("content"));
    }
    public void testProductionGateSavesOnlyServerValidatedFormatOverHttp()throws Exception{
        JsonNode capabilities=http("GET","/ai/agent/capabilities",null,1);
        assertEquals(200,capabilities.path("code").asInt());
        assertFalse(capabilities.path("data").path("snapshotWritesEnabled").asBoolean());
        assertEquals("BLOCKED_NOT_FIXED",capabilities.path("data").path("snapshotRiskStatus").asText());
        for(String type:new String[]{"setStyle","setRowHeight","setColumnWidth"})
            assertTrue(capabilities.path("data").path("controlledActions").toString().contains(type));
        for(String type:new String[]{"setFormula","setFilter","clearFilter","undo","redo"})
            assertFalse(capabilities.path("data").path("controlledActions").toString().contains(type));
        JsonNode created=http("POST","/ai/agent/controlled-writes",format("setStyle"),1);
        assertEquals(200,created.path("code").asInt());JsonNode run=created.path("data");
        String id=run.path("runId").asText();
        ObjectNode claimRequest=obj().put("transportVersion",2).put("clientId","b5-production-http")
            .put("fencingToken",run.path("fencingToken").asLong());
        JsonNode claimed=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimRequest,1);
        assertEquals(200,claimed.path("code").asInt());JsonNode manifest=claimed.path("data");
        assertEquals("setStyle",manifest.path("patch").path("type").asText());
        assertFalse(http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimRequest,1)
            .path("data").has("claimToken"));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText())
            .put("claimToken",manifest.path("claimToken").asText())
            .put("argsHash",manifest.path("argsHash").asText())
            .put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);
        receipt.set("formatReadback",obj().put("sheetId","s").put("row",0).put("column",0).put("value","#223344"));
        JsonNode saved=http("POST","/ai/agent/runs/"+id+"/controlled-results",receipt,1);
        assertEquals(200,saved.path("code").asInt());
        assertEquals("COMPLETED",saved.path("data").path("status").asText());
        assertEquals("SAVED",saved.path("data").path("persistenceStatus").asText());
        ObjectNode expected=(ObjectNode)parse(original);
        ((ObjectNode)expected.path("sheets").path("s").path("cellData").path("0").path("0"))
            .set("s",obj().set("bg",obj().put("rgb","#223344")));
        assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
        assertEquals(0,((Number)((java.util.Map<?,?>)fixture.status(workbook).get("workingState")).get("version")).intValue());
        assertEquals(200,http("POST","/ai/agent/runs/"+id+"/controlled-results",receipt,1).path("code").asInt());
        assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
        ObjectNode forged=receipt.deepCopy();((ObjectNode)forged.path("formatReadback")).put("value","#ffffff");
        assertEquals(409,http("POST","/ai/agent/runs/"+id+"/controlled-results",forged,1).path("code").asInt());
        assertEquals(expected,parse(String.valueOf(fixture.status(workbook).get("content"))));
    }
    public void testScalarHttpClaimReceiptAndDuplicateProtection()throws Exception{
        ObjectNode next=request.deepCopy();next.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1")));
        next.set("actions",arr().add(obj().put("schemaVersion","1.0").put("type","setCellForCell")
            .put("sheetId","s").put("range","A1").put("value","updated")));
        assertEquals(409,http("POST","/ai/agent/controlled-writes",next,2).path("code").asInt());
        JsonNode created=http("POST","/ai/agent/controlled-writes",next,1);assertEquals(200,created.path("code").asInt());
        JsonNode run=created.path("data");String id=run.path("runId").asText();
        ObjectNode claimReq=obj().put("transportVersion",2).put("clientId","b5-http-test")
            .put("fencingToken",run.path("fencingToken").asLong());
        JsonNode first=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1);
        assertEquals(200,first.path("code").asInt());JsonNode manifest=first.path("data");
        assertTrue(manifest.path("claimToken").isTextual());
        JsonNode repeated=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1);
        assertEquals(200,repeated.path("code").asInt());assertFalse(repeated.path("data").path("replayAllowed").asBoolean(true));
        assertFalse(repeated.path("data").has("claimToken"));
        ObjectNode receipt=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText())
            .put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText())
            .put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);
        receipt.set("cells",arr().add(obj().put("sheetId","s").put("row",0).put("column",0)
            .put("formula","").put("value","updated")));
        JsonNode saved=http("POST","/ai/agent/runs/"+id+"/controlled-results",receipt,1);
        assertEquals(200,saved.path("code").asInt());assertEquals("COMPLETED",saved.path("data").path("status").asText());
        assertEquals("SAVED",saved.path("data").path("persistenceStatus").asText());
        String content=String.valueOf(fixture.status(workbook).get("content"));
        JsonNode duplicate=http("POST","/ai/agent/runs/"+id+"/controlled-results",receipt,1);
        assertEquals(200,duplicate.path("code").asInt());assertEquals(content,fixture.status(workbook).get("content"));
        ObjectNode forged=receipt.deepCopy();((ObjectNode)forged.path("cells").get(0)).put("value","forged");
        assertEquals(409,http("POST","/ai/agent/runs/"+id+"/controlled-results",forged,1).path("code").asInt());
        assertEquals(content,fixture.status(workbook).get("content"));
    }
    public void testUnknownHttpRecoveryKeepsOriginalCallAndDoesNotReplay()throws Exception{
        ObjectNode next=request.deepCopy();next.set("writeScopes",arr().add(obj().put("sheetId","s").put("range","A1")));
        next.set("actions",arr().add(obj().put("schemaVersion","1.0").put("type","setCellForCell")
            .put("sheetId","s").put("range","A1").put("value","unverified")));
        JsonNode created=http("POST","/ai/agent/controlled-writes",next,1);
        assertEquals(200,created.path("code").asInt());
        JsonNode run=created.path("data");String id=run.path("runId").asText();
        ObjectNode claimReq=obj().put("transportVersion",2).put("clientId","b5-http-unknown")
            .put("fencingToken",run.path("fencingToken").asLong());
        JsonNode claim=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1).path("data");
        ObjectNode failed=obj().put("transportVersion",2).put("callId",claim.path("callId").asText())
            .put("claimToken",claim.path("claimToken").asText()).put("argsHash",claim.path("argsHash").asText())
            .put("fencingToken",claim.path("fencingToken").asLong()).put("ok",false).put("error","setter returned false");
        JsonNode paused=http("POST","/ai/agent/runs/"+id+"/controlled-results",failed,1);
        assertEquals(200,paused.path("code").asInt());assertEquals("PAUSED",paused.path("data").path("status").asText());
        assertEquals("UNKNOWN",paused.path("data").path("call").path("status").asText());
        JsonNode recovered=http("GET","/ai/agent/runs/active?workbookId="+workbook,null,1);
        assertEquals(200,recovered.path("code").asInt());assertEquals(id,recovered.path("data").path("runId").asText());
        assertEquals("UNKNOWN",recovered.path("data").path("call").path("status").asText());
        assertFalse(recovered.path("data").path("call").has("claimToken"));
        JsonNode reClaim=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1);
        assertEquals(409,reClaim.path("code").asInt());
        ObjectNode resume=obj().put("reloadedServerDraft",true).put("expectedRevision",run.path("revision").asText());
        JsonNode reResume=http("POST","/ai/agent/runs/"+id+"/resume",resume,1);
        assertEquals(409,reResume.path("code").asInt());
        assertTrue(reResume.path("msg").asText(),reResume.path("msg").asText().contains("SNAPSHOT_WRITE_BLOCKED"));
        assertEquals("UNKNOWN",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",
            String.class,id,claim.path("callId").asText()));
        assertNull(fixture.status(workbook).get("workingState"));assertEquals(original,fixture.status(workbook).get("content"));
    }
    public void testRestartedSchedulerIsolatesExpiredClaimWithoutReplay()throws Exception{
        AgentStore store=new AgentStore(fixture.db);
        ControlledWriteService recovery=new ControlledWriteService(store,fixture.guard,fixture.workbooks,
            fixture.versions,fixture.tx.getTransactionManager()){
            @Override boolean b5FormatOpen(){return true;}
        };
        java.lang.reflect.Field enabled=ControlledWriteService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);enabled.set(recovery,true);
        A1TestFixture.inject(fixture.runs,"controlled",recovery);
        ObjectNode next=format("setStyle");
        JsonNode created=http("POST","/ai/agent/controlled-writes",next,1);
        assertEquals(200,created.path("code").asInt());JsonNode run=created.path("data");String id=run.path("runId").asText();
        ObjectNode claimReq=obj().put("transportVersion",2).put("clientId","b5-restart-test")
            .put("fencingToken",run.path("fencingToken").asLong());
        JsonNode claimed=http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1);
        assertEquals(200,claimed.path("code").asInt());JsonNode manifest=claimed.path("data");
        fixture.db.update("update ai_agent_lease set expires_at=0 where workbook_id=? and run_id=? and fencing_token=?",
            workbook,id,run.path("fencingToken").asLong());
        AgentRunService restarted=new AgentRunService(store,fixture.guard,new AgentTools(store),
            fixture.workbooks,fixture.versions,null,fixture.tx.getTransactionManager());
        A1TestFixture.inject(restarted,"controlled",recovery);
        String prior=fixture.db.queryForObject("select max(run_id) from ai_agent_run where run_id<?",String.class,id);
        A1TestFixture.inject(restarted,"scanAfterRunId",prior==null?"":prior);
        try{
            restarted.tick();
            JsonNode paused=http("GET","/ai/agent/runs/"+id,null,1).path("data");
            assertEquals("PAUSED",paused.path("status").asText());
            assertEquals("UNKNOWN",paused.path("call").path("status").asText());
            assertEquals(manifest.path("callId"),paused.path("call").path("callId"));
            assertEquals("UNKNOWN",fixture.db.queryForObject("select status from ai_agent_tool_call where run_id=? and call_id=?",
                String.class,id,manifest.path("callId").asText()));
            assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_tool_call where run_id=?",Integer.class,id).intValue());
            long newFence=fixture.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
            assertTrue(newFence>manifest.path("fencingToken").asLong());
            assertEquals(409,http("POST","/ai/agent/runs/"+id+"/controlled-claims",claimReq,1).path("code").asInt());
            ObjectNode late=obj().put("transportVersion",2).put("callId",manifest.path("callId").asText())
                .put("claimToken",manifest.path("claimToken").asText()).put("argsHash",manifest.path("argsHash").asText())
                .put("fencingToken",manifest.path("fencingToken").asLong()).put("ok",true);
            late.set("formatReadback",obj().put("sheetId","s").put("row",0).put("column",0).put("value","#223344"));
            assertEquals(409,http("POST","/ai/agent/runs/"+id+"/controlled-results",late,1).path("code").asInt());
            assertEquals(original,fixture.status(workbook).get("content"));
            assertNull(fixture.status(workbook).get("workingState"));
            A1TestFixture.inject(restarted,"scanAfterRunId",prior==null?"":prior);
            restarted.tick();
            JsonNode repeated=http("GET","/ai/agent/runs/"+id,null,1).path("data");
            assertEquals("PAUSED",repeated.path("status").asText());
            assertEquals("UNKNOWN",repeated.path("call").path("status").asText());
            assertEquals(1,fixture.db.queryForObject("select count(*) from ai_agent_tool_call where run_id=?",Integer.class,id).intValue());
        }finally{restarted.destroy();}
    }
}
