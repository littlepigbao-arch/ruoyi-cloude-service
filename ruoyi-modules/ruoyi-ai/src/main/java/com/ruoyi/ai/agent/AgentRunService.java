package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.mapper.AiWorkbookMapper;
import com.ruoyi.ai.mapper.WorkbookVersionMapper;
import com.ruoyi.ai.service.LlmClient;
import com.ruoyi.ai.service.LlmResponse;
import com.ruoyi.ai.util.ContentHashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Durable serial scheduler. Browser calls are never replayed after their outcome becomes unknown. */
@Service
@EnableScheduling
public class AgentRunService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunService.class);
    private final AgentStore store;
    private final AgentWriteGuard guard;
    private final AgentTools tools;
    private final AiWorkbookMapper workbooks;
    private final WorkbookVersionMapper versions;
    private final LlmClient llm;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ControlledWriteService controlled;
    public boolean controlledWritesEnabled(){return controlled!=null&&controlled.enabled();}
    public ObjectNode createControlled(ObjectNode request,long user){require(controlled!=null,"CONTROLLED_DISABLED");return controlled.create(request,user);}
    public ObjectNode claimControlled(String id,long user,ObjectNode request){require(controlled!=null,"CONTROLLED_DISABLED");return controlled.claim(id,user,request);}
    public ObjectNode receiptControlled(String id,long user,ObjectNode request){require(controlled!=null,"CONTROLLED_DISABLED");return controlled.receipt(id,user,request,this::completeStep);}
    public ObjectNode uncertainControlled(String id,long user,ObjectNode request){require(controlled!=null,"CONTROLLED_DISABLED");return controlled.uncertain(id,user,request);}
    @Value("${ai.agent.replanning-enabled:true}") private boolean replanning;
    @Value("${ai.agent.business-tools-enabled:true}") private boolean businessTools;
    @Value("${ai.agent.max-context-chars:24000}") private int contextLimit;
    @Value("${ai.agent.max-tokens:60000}") private int tokenLimit;
    @Value("${ai.agent.timeout-ms:900000}") private long timeoutMs;
    public AgentRunService(AgentStore store, AgentWriteGuard guard, AgentTools tools, AiWorkbookMapper workbooks,
                           WorkbookVersionMapper versions, LlmClient llm, PlatformTransactionManager manager) {
        this.store=store;this.guard=guard;this.tools=tools;this.workbooks=workbooks;this.versions=versions;this.llm=llm;this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public boolean enabled(){return guard.enabled();}
    public boolean recoveryAvailable(){return guard.recoveryAvailable();}
    public ObjectNode workspace(long workbook,long user){return tx.execute(status->{
        guard.lock(workbook,user);AiWorkbook w=workbooks.selectByIdForUpdate(workbook,user);
        Map<String,Object> branch=versions.selectBranchForUpdate(workbook,w.getCurrentBranchId(),user);
        require(branch!=null,"分支不存在或无权访问");
        Map<String,Object> draft=versions.selectWorkingStateForUpdate(workbook,w.getCurrentBranchId(),user);
        String content=draft==null?w.getContent():String.valueOf(draft.get("content"));
        require(draft==null||ContentHashUtils.sha256(content).equals(draft.get("contentHash")),"工作草稿完整性校验失败");
        JsonNode snapshot=parse(content);
        ObjectNode out=obj().put("workbookId",workbook).put("branchId",w.getCurrentBranchId()).put("revision",hash(snapshot))
            .put("loadedRevision",ContentHashUtils.sha256(content)).put("draftVersion",draft==null?-1:((Number)draft.get("version")).longValue())
            .put("branchVersion",((Number)branch.get("version")).longValue()).put("workbookVersion",w.getVersion());
        out.set("headCommitId",JSON.valueToTree(branch.get("headCommitId")));out.put("branchName",String.valueOf(branch.get("branchName")));
        out.set("snapshot",snapshot);
        if(draft==null)out.putNull("workingState");
        else out.set("workingState",obj().put("version",((Number)draft.get("version")).longValue())
            .put("contentHash",String.valueOf(draft.get("contentHash")))
            .put("draftActionsJson",String.valueOf(draft.get("draftActionsJson")))
            .put("executedChangesJson",String.valueOf(draft.get("executedChangesJson"))));return out;
    });}
    private void enabledCheck(){require(enabled(),"Plan-and-Execute尚未启用，请先执行数据库迁移并配置ai.agent.enabled");}
    public ObjectNode get(String id,long user){ObjectNode r=store.get(id);require(r.path("userId").asLong()==user,"任务不存在或无权访问");return view(r);}
    public List<JsonNode> events(String id,long user,long after){get(id,user);List<JsonNode> out=new ArrayList<>();store.db.queryForList("select event_json from ai_agent_event where run_id=? and sequence_no>? order by sequence_no limit 200",String.class,id,Math.max(0,after)).forEach(s->out.add(parse(s)));return out;}
    public ObjectNode active(long workbook,long user){guardOwnership(workbook,user);if(!recoveryAvailable())return obj();List<String> ids=store.db.queryForList("select run_id from ai_agent_run where workbook_id=? and user_id=? order by created_at desc limit 1",String.class,workbook,user);return ids.isEmpty()?obj():get(ids.get(0),user);}
    private void guardOwnership(long workbook,long user){require(workbooks.selectById(workbook,user)!=null,"工作簿不存在或无权访问");}
    private <T> T mutate(String id,long user,Function<ObjectNode,T> fn){
        ObjectNode preview=store.get(id);require(preview.path("userId").asLong()==user,"任务不存在或无权访问");
        return tx.execute(status->{guard.lock(preview.path("workbookId").asLong(),user);ObjectNode r=store.locked(id);T out=fn.apply(r);store.save(r);return out;});
    }
    public ObjectNode create(ObjectNode req,long user){enabledCheck();
        String goal=text(req,"goal"),requestId=text(req,"requestId");require(goal.length()<=8000&&requestId.length()<=80,"请求过长");long workbook=req.path("workbookId").asLong(),branch=req.path("branchId").asLong();
        return tx.execute(status->{guard.lock(workbook,user);
            List<String> previous=store.db.queryForList("select run_id from ai_agent_run where user_id=? and request_id=?",String.class,user,requestId);
            if(!previous.isEmpty()){ObjectNode old=store.get(previous.get(0));require(old.path("requestHash").asText().equals(hash(req)),"相同requestId参数不一致");return view(old);}
            AiWorkbook w=workbooks.selectById(workbook,user);require(Objects.equals(w.getCurrentBranchId(),branch),"工作簿分支已变化");
            Map<String,Object> b=versions.selectBranch(workbook,branch,user);require(b!=null,"分支不存在");
            JsonNode snapshot=current(workbook,branch,user);String revision=hash(snapshot);require(revision.equals(text(req,"expectedRevision")),"工作草稿已变化，请重新打开后再运行");
            if(req.hasNonNull("conversationId"))require(!store.db.queryForList("select conversation_id from ai_conversation where conversation_id=? and user_id=? and workbook_id=?",req.path("conversationId").asText(),user,workbook).isEmpty(),"会话与工作簿不匹配");
            store.db.update("insert ignore into ai_agent_lease(workbook_id) values (?)",workbook);
            String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
            require(store.db.update("update ai_agent_lease set run_id=?,fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id is null",id,now+120000,workbook)==1,"已有活动任务，不能重复创建");
            long fence=store.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook);
            ObjectNode r=obj().put("runId",id).put("userId",user).put("workbookId",workbook).put("branchId",branch).put("requestHash",hash(req))
                .put("goal",goal).put("entryPoint","plan-agent").put("status","QUEUED").put("version",0).put("eventSeq",0).put("revision",revision).put("startRevision",revision)
                .put("fencingToken",fence).put("startedAt",now).put("deadline",now+timeoutMs).put("modelCalls",0).put("tokens",0).put("replans",0).put("planVersion",0)
                .put("allowOverwrite",req.path("allowOverwrite").asBoolean()).put("persistenceStatus","SAVED");
            r.set("outputs",obj());r.set("completedSteps",arr());r.set("constraints",arr());r.set("initialSnapshot",snapshot);r.set("expectedSnapshot",snapshot);
            if(controlledWritesEnabled()){
                WriteManifest.checkScopes(req.path("writeScopes"),snapshot);require(req.path("expectedDraftVersion").isIntegralNumber()&&req.path("expectedBranchVersion").isIntegralNumber()&&req.path("loadedRevision").isTextual(),"WRITE_VERSION_REQUIRED");
                Map<String,Object> draft=versions.selectWorkingStateForUpdate(workbook,branch,user);String content=draft==null?w.getContent():String.valueOf(draft.get("content"));
                require(req.path("expectedDraftVersion").asLong()==(draft==null?-1:((Number)draft.get("version")).longValue())&&req.path("expectedBranchVersion").asLong()==((Number)b.get("version")).longValue()&&req.path("loadedRevision").asText().equals(ContentHashUtils.sha256(content)),"WRITE_VERSION_CONFLICT");
                r.set("writeScopes",req.path("writeScopes"));r.set("expectedDraftVersion",req.path("expectedDraftVersion"));r.set("expectedBranchVersion",req.path("expectedBranchVersion"));r.set("loadedRevision",req.path("loadedRevision"));r.put("activeSheetId",text(req,"activeSheetId"));
            }
            store.db.update("insert into ai_agent_run values (?,?,?,?,?,?,?, ?,0,now(3),now(3))",id,user,workbook,branch,requestId,hash(req),"QUEUED",r.toString());
            store.event(r,"CREATED","已取得工作区写租约，开始读取结构与制定计划");store.save(r);return view(r);
        });
    }
    private JsonNode current(long workbook,long branch,long user){Map<String,Object> draft=versions.selectWorkingState(workbook,branch,user);return parse(draft==null?workbooks.selectById(workbook,user).getContent():String.valueOf(draft.get("content")));}
    private void lease(ObjectNode r){long now=System.currentTimeMillis();require(!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong(),now).isEmpty(),"租约已过期，请核实后恢复");}
    private void sameRevision(ObjectNode r){require(hash(current(r.path("workbookId").asLong(),r.path("branchId").asLong(),r.path("userId").asLong())).equals(r.path("revision").asText()),"工作草稿版本冲突");}
    public ObjectNode heartbeat(String id,long user,ObjectNode req){return mutate(id,user,r->{require(req.path("fencingToken").asLong()==r.path("fencingToken").asLong(),"陈旧租约");lease(r);store.db.update("update ai_agent_lease set expires_at=? where workbook_id=? and run_id=?",System.currentTimeMillis()+120000,r.path("workbookId").asLong(),id);return view(r);});}
    public ObjectNode claim(String id,long user,ObjectNode req){
        require(false,"SNAPSHOT_WRITE_BLOCKED: A1 已阻断完整 Snapshot 智能体写入，请核实或取消旧任务");
        return mutate(id,user,r->{lease(r);sameRevision(r);if(!"EXECUTING".equals(r.path("status").asText())||!r.has("call"))return obj();ObjectNode c=(ObjectNode)r.get("call");
        if(!"PENDING".equals(c.path("status").asText()))return obj();require(req.path("fencingToken").asLong()==r.path("fencingToken").asLong(),"陈旧租约");
        c.put("status","CLAIMED").put("claimToken",UUID.randomUUID().toString()).put("claimedAt",System.currentTimeMillis()).put("clientId",text(req,"clientId"));store.call(r,c);store.event(r,"CALL_CLAIMED","浏览器已领取写入，等待回读和保存证据");
        ObjectNode result=c.deepCopy();result.set("snapshot",r.path("proposal"));result.put("revisionBefore",r.path("revision").asText());result.put("fencingToken",r.path("fencingToken").asLong());return result;
    });}
    public ObjectNode receipt(String id,long user,ObjectNode req){return mutate(id,user,r->{
        String callId=text(req,"callId");List<String> prior=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,id,callId);require(!prior.isEmpty(),"调用不存在");JsonNode recorded=parse(prior.get(0));
        require(recorded.path("claimToken").asText().equals(text(req,"claimToken"))&&recorded.path("argsHash").asText().equals(text(req,"argsHash")),"领取令牌或参数摘要不一致");
        if("SUCCEEDED".equals(recorded.path("status").asText())){require(recorded.path("receiptHash").asText().equals(hash(req)),"重复回执内容不一致");return view(r);}
        require(!req.path("ok").asBoolean(),"SNAPSHOT_WRITE_BLOCKED: 未持久化的完整 Snapshot 回执已拒绝");
        lease(r);sameRevision(r);require(req.path("fencingToken").asLong()==r.path("fencingToken").asLong(),"陈旧写入已拒绝");
        ObjectNode c=(ObjectNode)r.path("call");require(c.path("callId").asText().equals(callId)&&"CLAIMED".equals(c.path("status").asText()),"调用状态不允许回执");
        if(!req.path("ok").asBoolean()){c.put("status","UNKNOWN");store.call(r,c);pause(r,"浏览器执行失败或回滚尚未核实，请重新加载服务器草稿后恢复");return view(r);}
        JsonNode actual=req.path("snapshot"),expected=r.path("proposal");require(actual.isObject()&&actual.toString().length()<=20000000,"浏览器快照无效或过大");
        require(sameContent(actual,expected),"回读结果与受控写入计划不符，未保存");
        require(req.path("calculationVerified").asBoolean(),"公式计算尚未完成，未保存");
        persist(r,actual,c);c.put("status","SUCCEEDED").put("receiptHash",hash(req)).put("persistenceStatus","SAVED").put("revisionAfter",r.path("revision").asText());store.call(r,c);
        ObjectNode output=obj().put("callId",callId).put("validation","PASSED").put("persistenceStatus","SAVED").put("sourceRevision",r.path("revision").asText());completeStep(r,output);r.remove(Arrays.asList("call","proposal"));
        if(r.path("cancelRequested").asBoolean())finish(r,"CANCELLED","在途步骤已核实保存，任务已停止");return view(r);
    });}
    private static JsonNode canonical(JsonNode snapshot){ObjectNode out=obj();Iterator<Map.Entry<String,JsonNode>> sheets=snapshot.path("sheets").fields();while(sheets.hasNext()){Map.Entry<String,JsonNode> entry=sheets.next();JsonNode s=entry.getValue();ObjectNode ss=obj().put("name",s.path("name").asText());ObjectNode cells=obj();Iterator<Map.Entry<String,JsonNode>> rows=s.path("cellData").fields();while(rows.hasNext()){Map.Entry<String,JsonNode> row=rows.next();Iterator<Map.Entry<String,JsonNode>> cs=row.getValue().fields();while(cs.hasNext()){Map.Entry<String,JsonNode> ce=cs.next();JsonNode v=ce.getValue();ObjectNode cv=obj();for(String f:Arrays.asList("v","f","s","p"))if(v.hasNonNull(f)){JsonNode val=v.get(f);if("s".equals(f)&&val.isTextual())val=snapshot.path("styles").path(val.asText());if("v".equals(f)&&v.hasNonNull("f"))continue;if(val.isNumber())val=DecimalNode.valueOf(val.decimalValue().stripTrailingZeros());cv.set(f,val);}if(cv.size()>0)cells.set(row.getKey()+":"+ce.getKey(),cv);}}
        ss.set("cells",cells);ss.set("merges",s.path("mergeData").isMissingNode()?arr():s.path("mergeData"));
        for(String key:Arrays.asList("rowCount","columnCount","rowData","columnData"))ss.set(key,s.path(key).isMissingNode()?obj():s.path(key));out.set(entry.getKey(),ss);}return out;}
    public static boolean sameContent(JsonNode a,JsonNode b){return canonical(a).equals(canonical(b));}
    private void persist(ObjectNode r,JsonNode snapshot,ObjectNode c){long w=r.path("workbookId").asLong(),b=r.path("branchId").asLong(),u=r.path("userId").asLong();Map<String,Object> branch=versions.selectBranch(w,b,u);Map<String,Object> prior=versions.selectWorkingState(w,b,u);
        ArrayNode changes=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("executedChangesJson")));ArrayNode actions=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("draftActionsJson")));
        ObjectNode change=obj().put("schemaVersion","1.0").put("source","ai").put("kind","agent-step").put("operationId",c.path("callId").asText()).put("runId",r.path("runId").asText()).put("beforeHash",r.path("revision").asText()).put("afterHash",hash(snapshot)).put("needsSnapshotDiff",true);change.set("inputBindings",c.path("args"));changes.add(change);actions.add(c.path("args"));
        Map<String,Object> state=new HashMap<>();state.put("workbookId",w);state.put("branchId",b);state.put("ownerUserId",u);state.put("baseCommitId",branch.get("headCommitId"));state.put("draftActionsJson",actions.toString());state.put("executedChangesJson",changes.toString());state.put("content",snapshot.toString().getBytes(StandardCharsets.UTF_8));state.put("contentHash",ContentHashUtils.sha256(snapshot.toString()));versions.upsertWorkingState(state);
        r.put("revision",hash(snapshot));r.set("expectedSnapshot",snapshot);r.put("persistenceStatus","SAVED");store.event(r,"SAVED","步骤已回读核对并保存工作草稿，未创建正式Commit");
    }
    public ObjectNode cancel(String id,long user){return mutate(id,user,r->{if(terminal(r))return view(r);r.put("status","CANCELLING").put("cancelRequested",true);JsonNode c=r.path("call");if(Arrays.asList("CLAIMED","UNKNOWN").contains(c.path("status").asText())){store.event(r,"CANCELLING","等待在途写入核实；请重新加载服务器草稿后确认停止");}else{if(c.path("transportVersion").asInt()==2&&"PENDING".equals(c.path("status").asText())){((ObjectNode)c).put("status","CANCELLED");store.call(r,(ObjectNode)c);}finish(r,"CANCELLED","任务已停止，已保存的步骤仍然保留");}return view(r);});}
    public ObjectNode resume(String id,long user,ObjectNode req){return mutate(id,user,r->{require(Arrays.asList("PAUSED","CANCELLING","WAITING_USER").contains(r.path("status").asText()),"当前状态不能恢复");
        JsonNode trusted=current(r.path("workbookId").asLong(),r.path("branchId").asLong(),user);
        require(req.path("reloadedServerDraft").asBoolean()&&hash(trusted).equals(text(req,"expectedRevision")),"必须先重新加载服务器草稿，核实在途结果");
        require(!r.has("call")||r.path("cancelRequested").asBoolean(),"SNAPSHOT_WRITE_BLOCKED: 旧写任务仅允许核实后安全取消，不能重放");
        long fence=r.path("fencingToken").asLong()+1;require(store.db.update("update ai_agent_lease set fencing_token=?,expires_at=? where workbook_id=? and run_id=?",fence,System.currentTimeMillis()+120000,r.path("workbookId").asLong(),id)==1,"任务租约已丢失");r.put("fencingToken",fence);
        if(r.has("call")){ObjectNode c=(ObjectNode)r.get("call");c.put("status","CANCELLED").put("resolution","server-draft-reloaded");store.call(r,c);r.remove(Arrays.asList("call","proposal"));}
        if(r.path("cancelRequested").asBoolean()){finish(r,"CANCELLED","在途调用已隔离，保留已保存步骤并停止");return view(r);}
        r.put("status","PAUSED");store.event(r,"RECONCILED","已核对服务器草稿并更新fencing token；A1 不重放旧任务，可安全取消");return view(r);
    });}
    public ObjectNode answer(String id,long user,ObjectNode req){return mutate(id,user,r->{require(Arrays.asList("WAITING_USER","PAUSED","EXECUTING").contains(r.path("status").asText()),"当前状态不能追加条件");String answer=text(req,"answer");require(answer.length()<=4000,"补充条件过长");((ArrayNode)r.path("constraints")).add(answer);if(blockUnsafeReplan(r))return view(r);r.put("status",r.has("plan")?"REPLANNING":"QUEUED");r.remove("question");store.event(r,"USER_ANSWER","已保存用户补充条件，等待修订未完成步骤");return view(r);});}
    /** A2 has no proof of safe compensation/effect reuse: retain facts and pause every historical write replan. */
    private boolean blockUnsafeReplan(ObjectNode r) {
        String reason=null;
        if(r.has("call"))reason="RESULT_UNKNOWN: 存在未核实调用，禁止重规划与重放";
        for(JsonNode step:r.path("completedSteps"))if(AgentPlan.WRITES.contains(step.path("tool").asText())||step.path("tool").asText().isEmpty())reason="REPLAN_UNSAFE: 已完成写步骤，尚不能证明重规划安全";
        for(JsonNode step:r.path("plan").path("steps"))if("SUCCEEDED".equals(step.path("status").asText())&&AgentPlan.WRITES.contains(step.path("tool").asText()))reason="REPLAN_UNSAFE: 当前计划包含已完成写入";
        for(Map<String,Object> row:store.db.queryForList("select status,call_json from ai_agent_tool_call where run_id=?",r.path("runId").asText())){
            String status=String.valueOf(row.get("status"));
            if(Arrays.asList("PENDING","CLAIMED","UNKNOWN").contains(status))reason="RESULT_UNKNOWN: 调用账本存在未完成结果，禁止自动重放";
            if("SUCCEEDED".equals(status)){JsonNode call=parse(String.valueOf(row.get("call_json")));if(AgentPlan.WRITES.contains(call.path("tool").asText())||!AgentPlan.TOOLS.contains(call.path("tool").asText()))reason="REPLAN_UNSAFE: 调用账本已有写入事实";}
        }
        for(String definition:store.db.queryForList("select definition_json from ai_agent_step where run_id=? and status='SUCCEEDED'",String.class,r.path("runId").asText())){
            JsonNode step=parse(definition);if(AgentPlan.WRITES.contains(step.path("tool").asText())||!AgentPlan.TOOLS.contains(step.path("tool").asText()))reason="REPLAN_UNSAFE: 步骤账本已有写入事实";
        }
        if(reason==null)return false;pause(r,reason+"；保留现有计划、输出和已保存内容，请核实或取消");return true;
    }
    private static boolean terminal(JsonNode r){return Arrays.asList("COMPLETED","CANCELLED","FAILED").contains(r.path("status").asText());}
    private void pause(ObjectNode r,String why){r.put("status","PAUSED").put("message",why);store.event(r,"PAUSED",why);}
    private void finish(ObjectNode r,String status,String why){r.put("status",status).put("message",why);store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",r.path("workbookId").asLong(),r.path("runId").asText());store.event(r,status,why);}
    private ObjectNode view(ObjectNode r){ObjectNode v=r.deepCopy();v.remove(Arrays.asList("initialSnapshot","expectedSnapshot","proposal","requestHash"));if(v.has("call")){ObjectNode c=(ObjectNode)v.get("call");c.remove("claimToken");}return v;}
    @Scheduled(fixedDelay=1500)
    public void tick(){if(!enabled())return;try{List<Map<String,Object>> runs=store.db.queryForList("select run_id,user_id from ai_agent_run where status in ('QUEUED','PREPARING','PLANNING','EXECUTING','REPLANNING','VERIFYING','CANCELLING') order by updated_at limit 20");for(Map<String,Object> row:runs){String id=String.valueOf(row.get("run_id"));long user=((Number)row.get("user_id")).longValue();try{ObjectNode job=mutate(id,user,this::advance);if(job!=null)plan(job,user);}catch(Exception e){LOG.warn("Agent run {} paused: {}",id,e.getMessage());try{mutate(id,user,r->{if(!terminal(r))pause(r,e.getMessage());return null;});}catch(Exception ignored){LOG.warn("Agent state unavailable: {}",id);}}}}catch(Exception e){LOG.warn("Agent scheduler unavailable; check migration: {}",e.getMessage());}}
    private ObjectNode advance(ObjectNode r){if(terminal(r))return null;
        if("CONTROLLED_WRITE".equals(r.path("kind").asText()))return null;
        if(r.path("call").path("transportVersion").asInt()==2){
            String state=r.path("call").path("status").asText();
            try{lease(r);}catch(Exception expired){if("CLAIMED".equals(state)){((ObjectNode)r.path("call")).put("status","UNKNOWN");store.call(r,(ObjectNode)r.path("call"));}pause(r,"RESULT_UNKNOWN: 受控写入租约待核实，不重新派发");}return null;
        }
        try{lease(r);}catch(Exception e){if(r.has("call")&&"CLAIMED".equals(r.path("call").path("status").asText())){((ObjectNode)r.get("call")).put("status","UNKNOWN");store.call(r,(ObjectNode)r.get("call"));}pause(r,"浏览器断线或租约过期；重新加载草稿后恢复，不能重放未知写入");return null;}
        require(System.currentTimeMillis()<r.path("deadline").asLong(),"任务时间预算已耗尽");sameRevision(r);
        String status=r.path("status").asText();
        if("CANCELLING".equals(status))return null;
        if("PLANNING".equals(status)){if(System.currentTimeMillis()-r.path("planningAt").asLong()>90000)pause(r,"模型调用中断，请恢复任务");return null;}
        if("QUEUED".equals(status)||"REPLANNING".equals(status)){
            if(blockUnsafeReplan(r))return null;
            require(r.path("modelCalls").asInt()<30&&r.path("tokens").asInt()<tokenLimit,"模型预算耗尽");
            if("REPLANNING".equals(status)){require(replanning&&r.path("replans").asInt()<3,"重规划次数耗尽或未启用");r.put("replans",r.path("replans").asInt()+1);}
            r.put("status","PLANNING").put("planningAt",System.currentTimeMillis()).put("planningToken",UUID.randomUUID().toString()).put("modelCalls",r.path("modelCalls").asInt()+1);store.event(r,"PLANNING","正在根据真实结构生成可执行计划");return r.deepCopy();
        }
        if(r.has("call")){ObjectNode inFlight=(ObjectNode)r.get("call");if("CLAIMED".equals(inFlight.path("status").asText())){inFlight.put("status","UNKNOWN");store.call(r,inFlight);}pause(r,"SNAPSHOT_WRITE_BLOCKED: 在途写入先核实并取消，禁止重放");return null;}
        AgentPlan.validate(r.path("plan"));
        JsonNode steps=r.path("plan").path("steps");int index=r.path("stepIndex").asInt();
        if(index>=steps.size()){require("SAVED".equals(r.path("persistenceStatus").asText()),"最终草稿尚未保存");for(JsonNode s:steps)require("SUCCEEDED".equals(s.path("status").asText()),"存在未验证步骤");r.put("status","VERIFYING");store.event(r,"VERIFYING","所有有效步骤验证通过，正在检查最终保存版本");sameRevision(r);finish(r,"COMPLETED","计划步骤及整体验收已通过，最终工作草稿已保存");return null;}
        ObjectNode step=(ObjectNode)steps.get(index);JsonNode args=AgentPlan.resolve(step.path("inputBindings"),(ObjectNode)r.path("outputs"));String tool=step.path("tool").asText();step.put("status","RUNNING");
        if(!businessTools)require(!Arrays.asList("build_dataset","join_tables","aggregate_table","derive_columns").contains(tool),"业务计算工具未启用");
        ObjectNode call=obj().put("callId",UUID.randomUUID().toString()).put("stepId",step.path("stepId").asText()).put("tool",tool).put("argsHash",hash(args)).put("status","PENDING");call.set("args",args);if(!("apply_actions".equals(tool)&&controlledWritesEnabled()))store.call(r,call);
        try{if("apply_actions".equals(tool)&&controlledWritesEnabled()){
                // No legacy proposal/receipt path is opened. The same controlled service also serves explicit action requests.
                controlled.prepareLocked(r,args.path("actions"),text(args,"overwrite"));
            }else if(AgentPlan.WRITES.contains(tool)){call.put("status","BLOCKED");store.call(r,call);step.put("status","BLOCKED");pause(r,"SNAPSHOT_WRITE_BLOCKED: A1 阻断完整 Snapshot 智能体写入，已保存结果保持不变");}
            else{ObjectNode out=tools.execute(r,tool,args,r.path("expectedSnapshot"));call.put("status","SUCCEEDED");call.set("result",out);store.call(r,call);completeStep(r,out);}
        }catch(IllegalArgumentException e){call.put("status","FAILED").put("error",e.getMessage());store.call(r,call);step.put("status","FAILED");r.put("lastError",e.getMessage());r.put("status",replanning&&r.path("replans").asInt()<3?"REPLANNING":"WAITING_USER");r.put("question",e.getMessage()+"。请补充字段或业务口径后继续。");store.event(r,"STEP_FAILED",e.getMessage());}
        saveStep(r,step);return null;
    }
    private void completeStep(ObjectNode r,ObjectNode output){ObjectNode step=(ObjectNode)r.path("plan").path("steps").get(r.path("stepIndex").asInt());step.put("status","SUCCEEDED");step.set("resultRefs",output);((ObjectNode)r.path("outputs")).set(step.path("stepId").asText(),output);((ArrayNode)r.path("completedSteps")).add(step.deepCopy());saveStep(r,step);r.put("stepIndex",r.path("stepIndex").asInt()+1);store.event(r,"STEP_SUCCEEDED",step.path("objective").asText()+"：程序验证通过");}
    private void saveStep(ObjectNode r,JsonNode step){store.db.update("insert into ai_agent_step values (?,?,?,?,?) on duplicate key update status=values(status),definition_json=values(definition_json)",r.path("runId").asText(),r.path("planVersion").asInt(),step.path("stepId").asText(),step.path("status").asText(),step.toString());}
    private void plan(ObjectNode job,long user){String id=job.path("runId").asText();try{
        ObjectNode context=obj().put("goal",job.path("goal").asText()).put("allowOverwrite",job.path("allowOverwrite").asBoolean());context.set("constraints",job.path("constraints"));context.set("catalog",AgentTools.catalog(job.path("expectedSnapshot")));context.set("completedSteps",job.path("completedSteps"));context.put("lastError",job.path("lastError").asText());
        require(context.toString().length()<=contextLimit,"上下文超过预算，请缩小目标或开始新会话；完整历史已保留");
        context.set("writeScopes",job.path("writeScopes"));
        List<Map<String,String>> messages=new ArrayList<>();Map<String,String> system=new HashMap<>();system.put("role","system");system.put("content",AgentPlan.CONTRACT+"重规划只生成剩余工作，不得重复已成功写入；已完成记录不可删除。当前计划必须独立可执行，需要的旧数据重新提取。"+(controlledWritesEnabled()?"B1仅允许apply_actions中的setCell/setValues标量值；不得生成公式、样式、清空或write_result。目标必须在writeScopes之内，每次最多1000格。":""));messages.add(system);Map<String,String> userMessage=new HashMap<>();userMessage.put("role","user");userMessage.put("content",context.toString());messages.add(userMessage);
        LlmResponse response=llm.chat(messages);JsonNode parsed=parse(response.getContent());
        mutate(id,user,r->{if(!"PLANNING".equals(r.path("status").asText())||!r.path("planningToken").equals(job.path("planningToken")))return null;
            r.put("tokens",r.path("tokens").asInt()+response.getTotalTokens());require(r.path("tokens").asInt()<=tokenLimit,"token预算耗尽");
            if(blockUnsafeReplan(r))return null;
            if(parsed.has("question")){r.put("status","WAITING_USER").put("question",text(parsed,"question"));store.event(r,"WAITING_USER",r.path("question").asText());return null;}
            AgentPlan.validate(parsed);if(r.has("plan"))for(JsonNode s:r.path("plan").path("steps"))if(!"SUCCEEDED".equals(s.path("status").asText())){((ObjectNode)s).put("status","SUPERSEDED");saveStep(r,s);}
            int version=r.path("planVersion").asInt()+1;ObjectNode p=parsed.deepCopy();p.put("planId",UUID.randomUUID().toString()).put("planVersion",version).put("changeReason",r.path("lastError").asText("初始计划"));for(JsonNode s:p.path("steps"))((ObjectNode)s).put("status","PENDING");
            r.set("plan",p);r.set("outputs",obj());r.put("planVersion",version).put("stepIndex",0).put("status","EXECUTING");r.remove("question");store.db.update("insert into ai_agent_plan values (?,?,?,now(3))",id,version,p.toString());for(JsonNode s:p.path("steps"))saveStep(r,s);store.event(r,"PLAN_READY","计划第"+version+"版已通过契约校验");return null;
        });
    }catch(Exception e){mutate(id,user,r->{if("PLANNING".equals(r.path("status").asText())&&r.path("planningToken").equals(job.path("planningToken")))pause(r,"计划未生成或未通过验证："+e.getMessage());return null;});}}
}
