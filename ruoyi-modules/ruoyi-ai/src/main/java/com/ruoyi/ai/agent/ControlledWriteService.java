package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.mapper.*;
import com.ruoyi.ai.util.ContentHashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Existing workbook/run locks, lease, immutable call manifest and draft CAS in one transaction. */
@Service
public class ControlledWriteService {
    private final AgentStore store;private final AgentWriteGuard guard;private final AiWorkbookMapper workbooks;
    private final WorkbookVersionMapper versions;private final TransactionTemplate tx;
    @Value("${ai.agent.controlled-writes-enabled:false}") private boolean enabled;
    public ControlledWriteService(AgentStore store,AgentWriteGuard guard,AiWorkbookMapper workbooks,WorkbookVersionMapper versions,PlatformTransactionManager manager){this.store=store;this.guard=guard;this.workbooks=workbooks;this.versions=versions;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public boolean enabled(){return enabled;}
    /** No dispatch or replay. Caller holds workbook and run locks; works with entry switches off. */
    public boolean reconcileLifecycleLocked(ObjectNode r){
        boolean direct="CONTROLLED_WRITE".equals(r.path("kind").asText());
        if(!direct&&!WriteManifest.isControlledTransport(r.path("call").path("transportVersion")))return false;
        if(Arrays.asList("COMPLETED","FAILED","CANCELLED").contains(r.path("status").asText()))return true;
        JsonNode stateCall=r.path("call");if(!stateCall.isObject()){r.put("status","PAUSED").put("message","RESULT_UNKNOWN: 缺少调用事实，请核实；不派发");return true;}
        List<String> rows=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,r.path("runId").asText(),stateCall.path("callId").asText());
        if(rows.size()!=1){r.put("status","PAUSED").put("message","RESULT_UNKNOWN: 调用账本缺失，请核实；不派发");return true;}
        ObjectNode c=(ObjectNode)parse(rows.get(0));String callStatus=c.path("status").asText();
        if("SUCCEEDED".equals(callStatus)){
            r.set("lastControlledResult",c.path("result"));r.put("persistenceStatus","SAVED");
            r.set("call",c.deepCopy());r.set("lastControlledManifest",c.path("manifest"));
            // Atomic receipt normally completed the run already; an inconsistent state requires review.
            r.put("status",r.path("cancelRequested").asBoolean()?"CANCELLING":"PAUSED").put("message","RESULT_UNKNOWN: 账本已有保存事实，禁止重做；请载入服务器草稿核实");return true;
        }
        long now=System.currentTimeMillis();boolean deadline=r.has("deadline")&&now>=r.path("deadline").asLong(),cancel=r.path("cancelRequested").asBoolean();
        boolean validLease=!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong(),now).isEmpty();
        if(!deadline&&!cancel&&validLease)return true;
        if("PENDING".equals(callStatus)&&!c.has("claimToken")){
            c.put("status","CANCELLED").put("resolution","never-claimed");store.call(r,c);r.set("call",c);
            r.put("status",cancel?"CANCELLED":"FAILED").put("message",cancel?"任务已停止；未领取调用未执行，已保存事实保留":"受控任务已超时或租约失效；未领取调用未执行");
            store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=? and fencing_token=?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong());
            store.event(r,"CONTROLLED_STOPPED",r.path("message").asText());return true;
        }
        if(!c.has("isolationFence")){
            long old=r.path("fencingToken").asLong();
            if(store.db.update("update ai_agent_lease set fencing_token=fencing_token+1 where workbook_id=? and run_id=? and fencing_token=?",r.path("workbookId").asLong(),r.path("runId").asText(),old)==1)r.put("fencingToken",old+1);
            c.put("isolationFence",r.path("fencingToken").asLong()).put("status","UNKNOWN");store.call(r,c);store.event(r,"CONTROLLED_UNKNOWN","超时、取消或租约失效：隔离在途调用，禁止旧执行者保存或重新派发");
        }
        r.set("call",c);r.put("status",cancel?"CANCELLING":"PAUSED").put("message","RESULT_UNKNOWN: 在途结果需核实；保留调用、输出和租约归属，不自动重放");return true;
    }
    private Map<String,Object> current(ObjectNode r) {
        long w=r.path("workbookId").asLong(),b=r.path("branchId").asLong(),u=r.path("userId").asLong();AiWorkbook book=workbooks.selectByIdForUpdate(w,u);
        require(book!=null&&Objects.equals(book.getCurrentBranchId(),b),"WRITE_VERSION_CONFLICT: 工作簿或分支已变化");Map<String,Object> branch=versions.selectBranchForUpdate(w,b,u);require(branch!=null,"WRITE_FORBIDDEN: 分支无权访问");
        Map<String,Object> draft=versions.selectWorkingStateForUpdate(w,b,u);String content=draft==null?book.getContent():String.valueOf(draft.get("content"));String loaded=ContentHashUtils.sha256(content);
        require(draft==null||loaded.equals(draft.get("contentHash")),"WRITE_VERSION_CONFLICT: 草稿完整性错误");
        Map<String,Object> state=new HashMap<>();state.put("content",content);state.put("loadedRevision",loaded);state.put("draftVersion",draft==null?-1L:((Number)draft.get("version")).longValue());state.put("branch",branch);state.put("draft",draft);return state;
    }
    @SuppressWarnings("unchecked") private void sameVersion(ObjectNode binding,Map<String,Object> current){
        Map<String,Object> branch=(Map<String,Object>)current.get("branch");
        require(binding.path("expectedDraftVersion").isIntegralNumber()&&binding.path("expectedBranchVersion").isIntegralNumber()&&binding.path("loadedRevision").isTextual(),"WRITE_VERSION_REQUIRED: 缺少实际已加载版本");
        require(binding.path("expectedDraftVersion").asLong()==((Number)current.get("draftVersion")).longValue()&&text(binding,"loadedRevision").equals(current.get("loadedRevision"))&&binding.path("expectedBranchVersion").asLong()==((Number)branch.get("version")).longValue(),"WRITE_VERSION_CONFLICT: 旧版本拒绝写入");
    }
    public ObjectNode create(ObjectNode req,long user){WriteManifest.checkTransport(req);require(enabled,"CONTROLLED_DISABLED: 新受控写入入口未开启");require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED");String requestId=text(req,"requestId");require(requestId.length()<=80,"请求ID过长");long workbook=req.path("workbookId").asLong();String requestHash=WriteManifest.digest(req);
        return tx.execute(status->{guard.lock(workbook,user);List<String> ids=store.db.queryForList("select run_id from ai_agent_run where user_id=? and request_id=?",String.class,user,requestId);
            if(!ids.isEmpty()){ObjectNode existing=store.locked(ids.get(0));require("CONTROLLED_WRITE".equals(existing.path("kind").asText())&&requestHash.equals(existing.path("requestHash").asText()),"WRITE_IDEMPOTENCY_CONFLICT: 相同请求ID参数不同");return view(existing);}
            ObjectNode r=obj().put("runId",UUID.randomUUID().toString()).put("requestHash",requestHash).put("requestId",requestId).put("userId",user).put("workbookId",workbook).put("branchId",req.path("branchId").asLong()).put("version",0).put("eventSeq",0).put("kind","CONTROLLED_WRITE").put("transportVersion",2).put("status","EXECUTING").put("goal","受控值写入").put("persistenceStatus","NOT_SAVED").put("deadline",System.currentTimeMillis()+900000);
            String entry=req.path("entryPoint").asText("explicit");require(req.path("goal").asText("").length()<=8000,"请求过长");if(req.hasNonNull("goal"))r.put("goal",req.path("goal").asText());require(Arrays.asList("explicit","ordinary-ai","plan-agent").contains(entry),"WRITE_ENTRY_INVALID");r.put("entryPoint",entry);
            if(req.hasNonNull("conversationId")){require(!store.db.queryForList("select conversation_id from ai_conversation where conversation_id=? and user_id=? and workbook_id=?",req.path("conversationId").asText(),user,workbook).isEmpty(),"WRITE_FORBIDDEN: 会话与工作簿不匹配");r.set("conversationId",req.get("conversationId"));}
            r.set("constraints",arr());r.set("outputs",obj());r.set("completedSteps",arr());r.set("writeScopes",req.path("writeScopes").deepCopy());r.put("activeSheetId",req.path("activeSheetId").asText());r.put("allowOverwrite","confirmed".equals(req.path("overwrite").asText()));
            r.set("expectedDraftVersion",req.path("expectedDraftVersion"));r.set("expectedBranchVersion",req.path("expectedBranchVersion"));r.set("loadedRevision",req.path("loadedRevision"));
            Map<String,Object> state=current(r);sameVersion(r,state);JsonNode snapshot=parse(String.valueOf(state.get("content")));r.put("revision",hash(snapshot));
            // Validate the whole group before reserving a lease or creating any call.
            ObjectNode manifest=WriteManifest.create(snapshot,req.path("actions"),r.path("writeScopes"),r.path("activeSheetId").asText(),text(req,"overwrite"),r.path("allowOverwrite").asBoolean());
            store.db.update("insert ignore into ai_agent_lease(workbook_id) values(?)",workbook);String id=r.path("runId").asText();require(store.db.update("update ai_agent_lease set run_id=?,fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id is null",id,System.currentTimeMillis()+120000,workbook)==1,"WRITE_LEASE_CONFLICT: 已有活动写入");r.put("fencingToken",store.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook));
            store.db.update("insert into ai_agent_run values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))",id,user,workbook,r.path("branchId").asLong(),requestId,requestHash,r.toString());
            attach(r,manifest,state,"values");store.event(r,"CONTROLLED_READY","受控值清单已校验，等待客户端原位执行");store.save(r);return view(r);
        });
    }
    /** Called by the existing scheduler while workbook and run row locks are already held. */
    public ObjectNode prepareLocked(ObjectNode r,JsonNode actions,String overwrite){require(enabled,"CONTROLLED_DISABLED");Map<String,Object> state=current(r);sameVersion(r,state);ObjectNode m=WriteManifest.create(parse(String.valueOf(state.get("content"))),actions,r.path("writeScopes"),r.path("activeSheetId").asText(),overwrite,r.path("allowOverwrite").asBoolean());return attach(r,m,state,r.path("planVersion").asInt()+":"+r.path("stepIndex").asInt());}
    private ObjectNode attach(ObjectNode r,ObjectNode m,Map<String,Object> state,String step){String callId=UUID.nameUUIDFromBytes((r.path("runId").asText()+":"+step).getBytes(StandardCharsets.UTF_8)).toString();
        require(store.db.queryForList("select call_id from ai_agent_tool_call where run_id=? and call_id=?",String.class,r.path("runId").asText(),callId).isEmpty(),"RESULT_UNKNOWN: 已有稳定调用，不能重新派发");
        m.put("callId",callId).put("fencingToken",r.path("fencingToken").asLong()).put("workbookId",r.path("workbookId").asLong()).put("branchId",r.path("branchId").asLong()).put("loadedRevision",String.valueOf(state.get("loadedRevision"))).put("expectedDraftVersion",((Number)state.get("draftVersion")).longValue()).put("expectedBranchVersion",r.path("expectedBranchVersion").asLong());
        ObjectNode c=obj().put("callId",callId).put("transportVersion",2).put("tool","apply_actions").put("status","PENDING").put("argsHash",WriteManifest.digest(m));c.set("manifest",m);r.set("call",c);store.call(r,c);return c;
    }
    private ObjectNode mutate(String id,long user,Function<ObjectNode,ObjectNode> fn){ObjectNode preview=store.get(id);require(preview.path("userId").asLong()==user,"WRITE_FORBIDDEN");return tx.execute(status->{guard.lock(preview.path("workbookId").asLong(),user);ObjectNode r=store.locked(id),before=r.deepCopy();ObjectNode out=fn.apply(r);if(!r.equals(before))store.save(r);return out;});}
    private void lease(ObjectNode r){require(!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong(),System.currentTimeMillis()).isEmpty(),"RESULT_UNKNOWN: 写租约待核实，不得自动重放");}
    public ObjectNode claim(String id,long user,ObjectNode req){WriteManifest.checkTransport(req);return mutate(id,user,r->{require(!r.has("deadline")||System.currentTimeMillis()<r.path("deadline").asLong(),"RESULT_UNKNOWN: 任务超时，不得领取");lease(r);require("EXECUTING".equals(r.path("status").asText()),"RESULT_UNKNOWN: 当前任务不可派发");ObjectNode c=(ObjectNode)r.path("call");require(WriteManifest.isControlledTransport(c.path("transportVersion")),"SNAPSHOT_WRITE_BLOCKED: 旧调用不可走受控入口");require(req.path("fencingToken").asLong()==r.path("fencingToken").asLong(),"WRITE_FENCE_STALE");
        if(!"PENDING".equals(c.path("status").asText())){require("CLAIMED".equals(c.path("status").asText()),"RESULT_UNKNOWN: 调用不能重放");return obj().put("callId",c.path("callId").asText()).put("status","CLAIMED").put("replayAllowed",false);}
        sameVersion((ObjectNode)c.path("manifest"),current(r));c.put("status","CLAIMED").put("claimToken",UUID.randomUUID().toString()).put("clientId",text(req,"clientId"));store.call(r,c);store.event(r,"CONTROLLED_CLAIMED","客户端已领取受控清单，禁止再次执行同一调用");
        return ((ObjectNode)c.path("manifest")).deepCopy().put("claimToken",c.path("claimToken").asText()).put("argsHash",c.path("argsHash").asText());
    });}
    public ObjectNode receipt(String id,long user,ObjectNode req){return receipt(id,user,req,null);}
    public ObjectNode receipt(String id,long user,ObjectNode req,BiConsumer<ObjectNode,ObjectNode> completed){return mutate(id,user,r->{
        require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED: 受控回执不接收完整 Snapshot");WriteManifest.requireTransport(req);req.fieldNames().forEachRemaining(k->require(Arrays.asList("transportVersion","callId","claimToken","argsHash","fencingToken","ok","cells","error").contains(k),"WRITE_READBACK_INVALID: 非授权回执字段"));
        List<String> recorded=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,id,text(req,"callId"));require(recorded.size()==1,"WRITE_CALL_NOT_FOUND");ObjectNode c=(ObjectNode)parse(recorded.get(0));require(WriteManifest.isControlledTransport(c.path("transportVersion")),"SNAPSHOT_WRITE_BLOCKED");require(c.path("claimToken").asText().equals(text(req,"claimToken"))&&c.path("argsHash").asText().equals(text(req,"argsHash"))&&c.path("manifest").path("fencingToken").asLong()==req.path("fencingToken").asLong(),"WRITE_RECEIPT_IDENTITY_INVALID");String receiptHash=WriteManifest.digest(req);
        if("SUCCEEDED".equals(c.path("status").asText())){require(receiptHash.equals(c.path("receiptHash").asText()),"WRITE_IDEMPOTENCY_CONFLICT: 重复回执不一致");return view(r);}
        require(!r.has("deadline")||System.currentTimeMillis()<r.path("deadline").asLong(),"RESULT_UNKNOWN: 任务超时，晚到回执不得新增保存");
        require(req.path("fencingToken").asLong()==r.path("fencingToken").asLong(),"WRITE_FENCE_STALE");
        require("EXECUTING".equals(r.path("status").asText())&&"CLAIMED".equals(c.path("status").asText())&&r.path("call").path("callId").equals(c.path("callId")),"RESULT_UNKNOWN: 调用未核实或已取消");lease(r);
        if(!req.path("ok").asBoolean()){c.put("status","UNKNOWN");store.call(r,c);r.set("call",c);r.put("status","PAUSED").put("persistenceStatus","NOT_SAVED").put("message","RESULT_UNKNOWN: 执行失败或回读失败，核实后取消；不重放");store.event(r,"CONTROLLED_UNKNOWN",r.path("message").asText());return view(r);}
        ObjectNode m=(ObjectNode)c.path("manifest");Map<String,Object> state=current(r);sameVersion(m,state);WriteManifest.validateReceipt(m,req.path("cells"));ObjectNode trusted=parse(String.valueOf(state.get("content"))).deepCopy();ObjectNode next=WriteManifest.apply(trusted,m);String content=next.toString(),loaded=ContentHashUtils.sha256(content);
        @SuppressWarnings("unchecked") Map<String,Object> branch=(Map<String,Object>)state.get("branch");@SuppressWarnings("unchecked") Map<String,Object> prior=(Map<String,Object>)state.get("draft");
        ArrayNode changes=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("executedChangesJson"))),actions=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("draftActionsJson")));
        ObjectNode change=obj().put("schemaVersion","1.0").put("source","ai").put("kind","controlled-values").put("operationId",c.path("callId").asText()).put("runId",id).put("beforeHash",m.path("loadedRevision").asText()).put("afterHash",loaded).put("needsSnapshotDiff",true);change.set("cells",m.path("cells"));changes.add(change);actions.addAll((ArrayNode)m.path("actions"));
        Map<String,Object> save=new HashMap<>();save.put("workbookId",r.path("workbookId").asLong());save.put("branchId",r.path("branchId").asLong());save.put("ownerUserId",user);save.put("baseCommitId",branch.get("headCommitId"));save.put("draftActionsJson",actions.toString());save.put("executedChangesJson",changes.toString());save.put("content",content.getBytes(StandardCharsets.UTF_8));save.put("contentHash",loaded);
        long expected=m.path("expectedDraftVersion").asLong();save.put("expectedDraftVersion",expected);save.put("expectedContentHash",m.path("loadedRevision").asText());
        if(expected<0)require(versions.insertWorkingState(save)==1,"WRITE_VERSION_CONFLICT: 首次草稿冲突");else require(versions.updateWorkingStateCas(save)==1,"WRITE_VERSION_CONFLICT: CAS 拒绝旧草稿");
        ObjectNode result=obj().put("transportVersion",2).put("callId",c.path("callId").asText()).put("loadedRevision",loaded).put("draftVersion",expected+1).put("sourceRevision",hash(next)).put("validation","PASSED").put("persistenceStatus","SAVED");
        result.set("executedChange",change);result.set("actions",m.path("actions"));
        c.put("status","SUCCEEDED").put("receiptHash",receiptHash).put("persistenceStatus","SAVED");c.set("result",result);store.call(r,c);r.set("lastControlledResult",result);r.set("lastControlledManifest",m);r.put("revision",hash(next)).put("loadedRevision",loaded).put("expectedDraftVersion",expected+1).put("persistenceStatus","SAVED");
        if("CONTROLLED_WRITE".equals(r.path("kind").asText())){r.put("status","COMPLETED");store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",r.path("workbookId").asLong(),id);}else{require(completed!=null,"CONTROLLED_COMPLETION_REQUIRED");r.set("expectedSnapshot",next);completed.accept(r,result);}
        r.remove("call");store.event(r,"CONTROLLED_SAVED","目标值已独立验证并 CAS 保存，未创建正式 Commit");return view(r);
    });}
    /** A lost receipt may have committed. Serialize reconciliation with receipt/CAS using the same locks. */
    public ObjectNode uncertain(String id,long user,ObjectNode req){return mutate(id,user,r->{
        require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED");List<String> rows=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,id,text(req,"callId"));require(rows.size()==1,"WRITE_CALL_NOT_FOUND");ObjectNode c=(ObjectNode)parse(rows.get(0));
        require(WriteManifest.isControlledTransport(c.path("transportVersion"))&&c.path("claimToken").asText().equals(text(req,"claimToken"))&&c.path("argsHash").asText().equals(text(req,"argsHash"))&&c.path("manifest").path("fencingToken").asLong()==req.path("fencingToken").asLong(),"WRITE_RECEIPT_IDENTITY_INVALID");
        if("SUCCEEDED".equals(c.path("status").asText()))return view(r);
        require(Arrays.asList("CLAIMED","UNKNOWN").contains(c.path("status").asText())&&r.path("call").path("callId").equals(c.path("callId")),"RESULT_UNKNOWN: 调用不可重新派发");
        c.put("status","UNKNOWN");store.call(r,c);r.set("call",c);if(!"CANCELLING".equals(r.path("status").asText()))r.put("status","PAUSED");r.put("persistenceStatus","NOT_SAVED").put("message","RESULT_UNKNOWN: 未核实保存结果，保持保护；仅允许核实或取消，不自动重放");store.event(r,"CONTROLLED_UNKNOWN",r.path("message").asText());return view(r);
    });}
    private ObjectNode view(ObjectNode r){ObjectNode out=r.deepCopy();out.remove(Arrays.asList("initialSnapshot","expectedSnapshot","proposal","requestHash"));if(out.path("call").isObject())((ObjectNode)out.path("call")).remove("claimToken");return out;}
}
