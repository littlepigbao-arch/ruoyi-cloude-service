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
    private static final boolean B5_FORMAT_OPEN=true; // B5 verified subset; still requires the controlled-write switch.
    @Value("${ai.agent.b6-filter-enabled:false}") private boolean b6FilterEnabled;
    @Value("${ai.agent.b7-formula-enabled:false}") private boolean b7FormulaEnabled;
    @Value("${ai.agent.b8-effect-enabled:false}") private boolean b8EffectEnabled;
    @Value("${ai.agent.b8-merge-enabled:false}") private boolean b8MergeEnabled;
    @Value("${ai.agent.b8-structure-enabled:false}") private boolean b8StructureEnabled;
    @Value("${ai.agent.b8-sheet-enabled:false}") private boolean b8SheetEnabled;
    @Value("${ai.agent.b8-sheet-delete-enabled:false}") private boolean b8SheetDeleteEnabled;
    @Value("${ai.agent.b8-sheet-copy-enabled:false}") private boolean b8SheetCopyEnabled;
    @Value("${ai.agent.b8-sheet-rename-enabled:false}") private boolean b8SheetRenameEnabled;
    @Value("${ai.agent.b9-result-enabled:false}") private boolean b9ResultEnabled;
    @Value("${ai.agent.b9-result-sheet-enabled:false}") private boolean b9ResultSheetEnabled;
    private final AgentStore store;private final StableEffectLedger effects;private final AgentWriteGuard guard;private final AiWorkbookMapper workbooks;
    private final WorkbookVersionMapper versions;private final TransactionTemplate tx;
    @Value("${ai.agent.controlled-writes-enabled:false}") private boolean enabled;
    public ControlledWriteService(AgentStore store,AgentWriteGuard guard,AiWorkbookMapper workbooks,WorkbookVersionMapper versions,PlatformTransactionManager manager){this.store=store;this.effects=store==null?null:new StableEffectLedger(store.db);this.guard=guard;this.workbooks=workbooks;this.versions=versions;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public boolean enabled(){return enabled;}
    boolean b5FormatOpen(){return B5_FORMAT_OPEN;}
    public boolean formatActionsEnabled(){return enabled()&&b5FormatOpen();}
    public boolean filterActionsEnabled(){return enabled()&&b6FilterEnabled;}
    public boolean formulaActionsEnabled(){return enabled()&&b7FormulaEnabled;}
    public boolean mergeActionsEnabled(){return enabled()&&b8EffectEnabled&&b8MergeEnabled;}
    public boolean structureActionsEnabled(){return enabled()&&b8EffectEnabled&&b8StructureEnabled;}
    public boolean sheetActionsEnabled(){return enabled()&&b8EffectEnabled&&b8SheetEnabled;}
    public boolean sheetDeleteActionsEnabled(){return enabled()&&b8EffectEnabled&&b8SheetDeleteEnabled;}
    public boolean sheetCopyActionsEnabled(){return enabled()&&b8EffectEnabled&&b8SheetCopyEnabled;}
    public boolean sheetRenameActionsEnabled(){return enabled()&&b8EffectEnabled&&b8SheetRenameEnabled;}
    public boolean resultWritesEnabled(){return enabled()&&b8EffectEnabled&&b9ResultEnabled;}
    public boolean resultSheetWritesEnabled(){return resultWritesEnabled()&&sheetActionsEnabled()&&b9ResultSheetEnabled;}
    void authorizeResultSheet(ObjectNode r,JsonNode snapshot){require(resultSheetWritesEnabled(),"AI_CAPABILITY_BLOCKED: B9 新 Sheet 写回未开放");ResultSheetManifest.preview(snapshot,r.path("resultSheetAuthorization"),r.path("writeScopes"));}
    /** A repeated claim never receives the manifest or claim token again. */
    static ObjectNode repeatClaimReply(JsonNode call){require("CLAIMED".equals(call.path("status").asText()),"RESULT_UNKNOWN: 调用不能重放");return obj().put("callId",call.path("callId").asText()).put("status","CLAIMED").put("replayAllowed",false);}
    /** Ledger status alone is insufficient evidence of a committed controlled receipt. */
    static boolean hasSavedReceiptEvidence(JsonNode c){
        JsonNode m=c.path("manifest"),result=c.path("result"),version=m.path("expectedDraftVersion");
        if(!WriteManifest.isControlledTransport(c.path("transportVersion"))||!WriteManifest.isControlledTransport(m.path("transportVersion"))||!WriteManifest.isControlledTransport(result.path("transportVersion"))
            ||!"SUCCEEDED".equals(c.path("status").asText())||!"SAVED".equals(c.path("persistenceStatus").asText())
            ||!"SAVED".equals(result.path("persistenceStatus").asText())||!"PASSED".equals(result.path("validation").asText())
            ||!c.path("callId").isTextual()||c.path("callId").asText().isEmpty()||!c.path("callId").equals(m.path("callId"))||!c.path("callId").equals(result.path("callId"))
            ||!c.path("claimToken").isTextual()||c.path("claimToken").asText().isEmpty()
            ||!c.path("receiptHash").isTextual()||c.path("receiptHash").asText().isEmpty()
            ||!c.path("argsHash").isTextual()||!c.path("argsHash").asText().equals(WriteManifest.digest(m))
            ||!m.path("fencingToken").isIntegralNumber()||!m.path("loadedRevision").isTextual()||m.path("loadedRevision").asText().isEmpty()
            ||!result.path("loadedRevision").isTextual()||result.path("loadedRevision").asText().isEmpty()
            ||!version.isIntegralNumber()||version.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE))<0||version.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE-1))>0)return false;
        boolean format=m.has("patch"),filter=m.has("filterPatch"),formula=m.has("formulaPatch"),merge=m.has("mergePatch"),structure=m.has("structurePatch");
        JsonNode change=result.path("executedChange"),payload=format?m.path("patch"):filter?m.path("filterPatch"):formula?m.path("formulaPatch"):merge?m.path("mergePatch"):structure?m.path("structurePatch"):m.path("cells");
        return (!c.has("effectId")||c.path("effectId").equals(m.path("effectId"))&&c.path("effectId").equals(result.path("effectId")))
            &&WriteManifest.sameIntegral(result.path("draftVersion"),version.longValue()+1)
            &&result.path("sourceRevision").isTextual()&&!result.path("sourceRevision").asText().isEmpty()
            &&m.path("actions").isArray()&&result.path("actions").equals(m.path("actions"))
            &&(format||filter||formula||merge||structure?payload.isObject():payload.isArray())
            &&change.isObject()&&"1.0".equals(change.path("schemaVersion").asText())
            &&"ai".equals(change.path("source").asText())
            &&(format?"controlled-format":filter?"controlled-filter":formula?"controlled-formula":merge?"controlled-merge":structure?"controlled-structure":"controlled-values").equals(change.path("kind").asText())
            &&c.path("callId").equals(change.path("operationId"))
            &&m.path("loadedRevision").equals(change.path("beforeHash"))
            &&result.path("loadedRevision").equals(change.path("afterHash"))
            &&(!m.has("resultSource")||m.path("resultSource").equals(change.path("resultSource")))
            &&payload.equals(change.path(format?"formatPatch":filter?"filterPatch":formula?"formulaPatch":merge?"mergePatch":structure?"structurePatch":"cells"));
    }
    /** Used before scheduler dispatch, including after process restart. */
    static boolean ownsLifecycle(JsonNode run){return "CONTROLLED_WRITE".equals(run.path("kind").asText())||WriteManifest.isControlledTransport(run.path("call").path("transportVersion"));}
    /** No dispatch or replay. Caller holds workbook and run locks; works with entry switches off. */
    public boolean reconcileLifecycleLocked(ObjectNode r){
        if(!ownsLifecycle(r))return false;
        if(Arrays.asList("COMPLETED","FAILED","CANCELLED").contains(r.path("status").asText()))return true;
        JsonNode stateCall=r.path("call");if(!stateCall.isObject()){r.put("status","PAUSED").put("message","RESULT_UNKNOWN: 缺少调用事实，请核实；不派发");return true;}
        List<String> rows=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,r.path("runId").asText(),stateCall.path("callId").asText());
        if(rows.size()!=1){r.put("status","PAUSED").put("message","RESULT_UNKNOWN: 调用账本缺失，请核实；不派发");return true;}
        ObjectNode c=(ObjectNode)parse(rows.get(0));String callStatus=c.path("status").asText();
        if(c.has("effectId")){if("SUCCEEDED".equals(callStatus))effects.assertPersisted(r.path("runId").asText(),c);else effects.assertStatus(c.path("effectId").asText(),r.path("runId").asText(),c.path("callId").asText(),"PENDING".equals(callStatus)?"PREPARED":"CLAIMED".equals(callStatus)?"CLAIMED":"UNKNOWN");}
        if("SUCCEEDED".equals(callStatus)){
            if(!stateCall.path("callId").equals(c.path("callId"))||!hasSavedReceiptEvidence(c)){
                r.set("call",c);r.put("status","PAUSED").put("persistenceStatus","UNKNOWN")
                    .put("message","RESULT_UNKNOWN: 账本成功状态缺少完整保存回执，请核实；不派发");return true;
            }
            r.set("lastControlledResult",c.path("result"));r.put("persistenceStatus","SAVED");
            r.set("call",c.deepCopy());r.set("lastControlledManifest",c.path("manifest"));
            // Atomic receipt normally completed the run already; an inconsistent state requires review.
            r.put("status",r.path("cancelRequested").asBoolean()?"CANCELLING":"PAUSED").put("message","RESULT_UNKNOWN: 账本已有保存事实，禁止重做；请载入服务器草稿核实");return true;
        }
        long now=System.currentTimeMillis();boolean deadline=r.has("deadline")&&now>=r.path("deadline").asLong(),cancel=r.path("cancelRequested").asBoolean();
        boolean validLease=!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong(),now).isEmpty();
        if(!deadline&&!cancel&&validLease)return true;
        if("PENDING".equals(callStatus)&&!c.has("claimToken")){
            if(c.has("effectId"))effects.transition(c.path("effectId").asText(),r.path("runId").asText(),c.path("callId").asText(),"PREPARED","CANCELLED",null,null,null);
            c.put("status","CANCELLED").put("resolution","never-claimed");store.call(r,c);r.set("call",c);
            r.put("status",cancel?"CANCELLED":"FAILED").put("message",cancel?"任务已停止；未领取调用未执行，已保存事实保留":"受控任务已超时或租约失效；未领取调用未执行");
            store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=? and fencing_token=?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong());
            store.event(r,"CONTROLLED_STOPPED",r.path("message").asText());return true;
        }
        if(!c.has("isolationFence")){
            if(c.has("effectId")&&"CLAIMED".equals(callStatus))effects.transition(c.path("effectId").asText(),r.path("runId").asText(),c.path("callId").asText(),"CLAIMED","UNKNOWN",null,null,null);
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
        require(WriteManifest.sameIntegral(binding.path("expectedDraftVersion"),((Number)current.get("draftVersion")).longValue())&&text(binding,"loadedRevision").equals(current.get("loadedRevision"))&&WriteManifest.sameIntegral(binding.path("expectedBranchVersion"),((Number)branch.get("version")).longValue()),"WRITE_VERSION_CONFLICT: 旧版本拒绝写入");
    }
    private ObjectNode controlledManifest(JsonNode snapshot,JsonNode actions,JsonNode scopes,String activeSheetId,String overwrite,boolean allowOverwrite){
        if(StructureManifest.isStructureGroup(actions)){
            String type=actions.get(0).path("type").asText();require("createSheet".equals(type)?sheetActionsEnabled():"renameSheet".equals(type)?sheetRenameActionsEnabled():"copySheet".equals(type)?sheetCopyActionsEnabled():"deleteSheet".equals(type)?sheetDeleteActionsEnabled():structureActionsEnabled(),"AI_CAPABILITY_BLOCKED: B8c 结构写入尚未开放");
            require("confirmed".equals(overwrite)&&allowOverwrite,"WRITE_POLICY_DENIED: Sheet 结构变更必须明确授权");
            return StructureManifest.preview(snapshot,actions.get(0),scopes);
        }
        if(MergeManifest.isMergeGroup(actions)){
            require(mergeActionsEnabled(),"AI_CAPABILITY_BLOCKED: B8b 合并写入尚未开放");
            require("confirmed".equals(overwrite)&&allowOverwrite,"WRITE_POLICY_DENIED: 合并或拆分必须明确授权");
            return MergeManifest.preview(snapshot,actions.get(0),scopes);
        }
        if(FormulaManifest.isFormulaGroup(actions)){
            require(formulaActionsEnabled(),"AI_CAPABILITY_BLOCKED: B7 公式写入尚未开放");
            require("confirmed".equals(overwrite)&&allowOverwrite,"WRITE_POLICY_DENIED: 公式写入必须明确授权");
            return FormulaManifest.preview(snapshot,actions.get(0),scopes);
        }
        if(FilterManifest.isFilterGroup(actions)){
            require(filterActionsEnabled(),"AI_CAPABILITY_BLOCKED: B6 筛选资源写入尚未开放");
            require("confirmed".equals(overwrite)&&allowOverwrite,"WRITE_POLICY_DENIED: 筛选资源修改必须明确授权");
            return FilterManifest.preview(snapshot,actions.get(0),scopes);
        }
        if(WriteManifest.isB5FormatGroup(actions)){
            require(b5FormatOpen(),"AI_CAPABILITY_BLOCKED: B5 样式与尺寸写入尚未开放");
            require("confirmed".equals(overwrite)&&allowOverwrite,"WRITE_POLICY_DENIED: 格式覆盖必须明确授权");
            return WriteManifest.previewB5Format(snapshot,actions.get(0),scopes);
        }
        return WriteManifest.create(snapshot,actions,scopes,activeSheetId,overwrite,allowOverwrite);
    }
    /** Only recent, current-version, completed read-only results are offered; selection is revalidated on create. */
    public ArrayNode resultArtifacts(long workbook,long user){require(resultWritesEnabled(),"AI_CAPABILITY_BLOCKED: B9 结果选择未开放");return tx.execute(status->{
        guard.lock(workbook,user);AiWorkbook book=workbooks.selectByIdForUpdate(workbook,user);ObjectNode binding=obj().put("userId",user).put("workbookId",workbook).put("branchId",book.getCurrentBranchId());
        Map<String,Object> state=current(binding);JsonNode snapshot=parse(String.valueOf(state.get("content")));binding.put("revision",hash(snapshot));ArrayNode out=arr();
        List<Map<String,Object>> candidates=store.db.queryForList("select a.artifact_id,a.run_id from ai_analysis_artifact a join ai_agent_run r on r.run_id=a.run_id and r.user_id=a.user_id where a.user_id=? and r.workbook_id=? and r.branch_id=? and r.status='COMPLETED' and a.source_revision=? order by a.created_at desc,a.artifact_id limit 30",user,workbook,book.getCurrentBranchId(),binding.path("revision").asText());
        for(Map<String,Object> candidate:candidates){try{
            ObjectNode source=store.get(String.valueOf(candidate.get("run_id"))),record=store.artifactRecord(source,String.valueOf(candidate.get("artifact_id"))),selection=obj().put("sourceRunId",text(source,"runId"));
            for(String key:Arrays.asList("resultRef","sourceRevision","dataDigest","lineageDigest"))selection.set(key,record.path(key));SelectedResultSource.record(store,binding,selection,snapshot);
            ObjectNode item=obj().put("goal",source.path("goal").asText()).put("rowCount",record.path("data").path("rows").size()+1).put("columnCount",record.path("data").path("columns").size());item.set("selection",selection);item.set("columns",record.path("data").path("columns"));out.add(item);
        }catch(IllegalArgumentException ineligible){/* An old, non-dataset or untracked Artifact is not a selectable result. */}}
        return out;
    });}
    private ObjectNode selectedManifest(ObjectNode r,ObjectNode req,JsonNode snapshot){
        require(resultWritesEnabled()&&"ordinary-ai".equals(r.path("entryPoint").asText()),"AI_CAPABILITY_BLOCKED: B9 普通入口未开放");require(!req.has("actions"),"RESULT_WRITE_INVALID: 结果选择不能混入 actions");
        if(req.has("resultSheetAuthorization")){
            require(!req.has("resultTarget"),"RESULT_SHEET_INVALID: 新 Sheet 与已有目标不能混合");r.set("resultSheetAuthorization",req.path("resultSheetAuthorization").deepCopy());authorizeResultSheet(r,snapshot);
            ObjectNode args=obj().put("overwrite","confirmed");args.set("newSheet",r.path("resultSheetAuthorization"));args.set("dataset",obj().put("resultRef",text(req.path("resultArtifact"),"resultRef")).put("sourceRevision",text(req.path("resultArtifact"),"sourceRevision")));
            require("confirmed".equals(req.path("overwrite").asText()),"RESULT_SHEET_AUTH_REQUIRED: 新 Sheet 需明确确认");return SelectedResultSource.prepare(store,r,req.path("resultArtifact"),args,snapshot);
        }
        JsonNode target=req.path("resultTarget");require(target.isObject()&&target.size()==2,"RESULT_WRITE_INVALID: 需要明确目标 Sheet 与区域");
        ObjectNode args=obj().put("sheetId",text(target,"sheetId")).put("range",text(target,"range")).put("overwrite",text(req,"overwrite"));
        ObjectNode dataset=obj().put("resultRef",text(req.path("resultArtifact"),"resultRef")).put("sourceRevision",text(req.path("resultArtifact"),"sourceRevision"));args.set("dataset",dataset);
        return SelectedResultSource.prepare(store,r,req.path("resultArtifact"),args,snapshot);
    }
    public ObjectNode create(ObjectNode req,long user){WriteManifest.checkTransport(req);require(enabled,"CONTROLLED_DISABLED: 新受控写入入口未开启");require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED");require(!req.has("resultSheetAuthorization")||req.has("resultArtifact"),"RESULT_SHEET_INVALID: 结构结果授权必须绑定真实来源");require(!req.has("resultTarget")||req.has("resultArtifact"),"RESULT_WRITE_INVALID: 目标缺少来源");String requestId=text(req,"requestId");require(requestId.length()<=80,"请求ID过长");long workbook=req.path("workbookId").asLong();String requestHash=WriteManifest.digest(req);
        return tx.execute(status->{guard.lock(workbook,user);List<String> ids=store.db.queryForList("select run_id from ai_agent_run where user_id=? and request_id=?",String.class,user,requestId);
            if(!ids.isEmpty()){ObjectNode existing=store.locked(ids.get(0));require("CONTROLLED_WRITE".equals(existing.path("kind").asText())&&requestHash.equals(existing.path("requestHash").asText()),"WRITE_IDEMPOTENCY_CONFLICT: 相同请求ID参数不同");return view(existing);}
            ObjectNode r=obj().put("runId",UUID.randomUUID().toString()).put("requestHash",requestHash).put("requestId",requestId).put("userId",user).put("workbookId",workbook).put("branchId",req.path("branchId").asLong()).put("version",0).put("eventSeq",0).put("kind","CONTROLLED_WRITE").put("transportVersion",2).put("status","EXECUTING").put("goal","受控值写入").put("persistenceStatus","NOT_SAVED").put("deadline",System.currentTimeMillis()+900000);
            String entry=req.path("entryPoint").asText("explicit");require(req.path("goal").asText("").length()<=8000,"请求过长");if(req.hasNonNull("goal"))r.put("goal",req.path("goal").asText());require(Arrays.asList("explicit","ordinary-ai","plan-agent").contains(entry),"WRITE_ENTRY_INVALID");r.put("entryPoint",entry);
            if(req.hasNonNull("conversationId")){require(!store.db.queryForList("select conversation_id from ai_conversation where conversation_id=? and user_id=? and workbook_id=?",req.path("conversationId").asText(),user,workbook).isEmpty(),"WRITE_FORBIDDEN: 会话与工作簿不匹配");r.set("conversationId",req.get("conversationId"));}
            r.set("constraints",arr());r.set("outputs",obj());r.set("completedSteps",arr());r.set("writeScopes",req.path("writeScopes").deepCopy());r.put("activeSheetId",req.path("activeSheetId").asText());r.put("allowOverwrite","confirmed".equals(req.path("overwrite").asText()));
            r.set("expectedDraftVersion",req.path("expectedDraftVersion"));r.set("expectedBranchVersion",req.path("expectedBranchVersion"));r.set("loadedRevision",req.path("loadedRevision"));
            Map<String,Object> state=current(r);sameVersion(r,state);JsonNode snapshot=parse(String.valueOf(state.get("content")));r.put("revision",hash(snapshot));
            // Validate the whole group before reserving a lease or creating any call.
            ObjectNode manifest=req.has("resultArtifact")?selectedManifest(r,req,snapshot):controlledManifest(snapshot,req.path("actions"),r.path("writeScopes"),r.path("activeSheetId").asText(),text(req,"overwrite"),r.path("allowOverwrite").asBoolean());
            store.db.update("insert ignore into ai_agent_lease(workbook_id) values(?)",workbook);String id=r.path("runId").asText();require(store.db.update("update ai_agent_lease set run_id=?,fencing_token=fencing_token+1,expires_at=? where workbook_id=? and run_id is null",id,System.currentTimeMillis()+120000,workbook)==1,"WRITE_LEASE_CONFLICT: 已有活动写入");r.put("fencingToken",store.db.queryForObject("select fencing_token from ai_agent_lease where workbook_id=?",Long.class,workbook));
            store.db.update("insert into ai_agent_run(run_id,user_id,workbook_id,branch_id,request_id,request_hash,status,state_json,row_version,updated_at,created_at) values(?,?,?,?,?,?,'EXECUTING',?,0,now(3),now(3))",id,user,workbook,r.path("branchId").asLong(),requestId,requestHash,r.toString());
            attach(r,manifest,state,"values");store.event(r,"CONTROLLED_READY","受控值清单已校验，等待客户端原位执行");store.save(r);return view(r);
        });
    }
    /** Called by the existing scheduler while workbook and run row locks are already held. */
    public ObjectNode prepareLocked(ObjectNode r,JsonNode actions,String overwrite){require(enabled,"CONTROLLED_DISABLED");Map<String,Object> state=current(r);sameVersion(r,state);ObjectNode m=controlledManifest(parse(String.valueOf(state.get("content"))),actions,r.path("writeScopes"),r.path("activeSheetId").asText(),overwrite,r.path("allowOverwrite").asBoolean());return attach(r,m,state,r.path("planVersion").asInt()+":"+r.path("stepIndex").asInt());}
    public ObjectNode prepareResultLocked(ObjectNode r,JsonNode args){require(resultWritesEnabled(),"AI_CAPABILITY_BLOCKED: B9 结果写回未开放");Map<String,Object> state=current(r);sameVersion(r,state);if(args.has("newSheet"))authorizeResultSheet(r,parse(String.valueOf(state.get("content"))));ObjectNode m=new AgentTools(store).prepareResultWrite(r,args,parse(String.valueOf(state.get("content"))));m.set("resultWriteArgs",args.deepCopy());return attach(r,m,state,r.path("planVersion").asInt()+":"+r.path("stepIndex").asInt());}
    private void recheckResultSource(ObjectNode r,ObjectNode manifest,JsonNode snapshot){if(!manifest.has("resultSource"))return;require(resultWritesEnabled(),"AI_CAPABILITY_BLOCKED: B9 保存已关闭");if(manifest.path("resultSheet").asBoolean())authorizeResultSheet(r,snapshot);ObjectNode recomputed=manifest.has("selectedResult")?SelectedResultSource.prepare(store,r,manifest.path("selectedResult"),manifest.path("resultWriteArgs"),snapshot):new AgentTools(store).prepareResultWrite(r,manifest.path("resultWriteArgs"),snapshot);for(String field:Arrays.asList("resultSource","actions","cells","structurePatch","structureReadback","resultSheet","writeScopes","overwrite"))require(WriteManifest.digest(recomputed.path(field)).equals(WriteManifest.digest(manifest.path(field))),"SOURCE_STALE: 冻结来源或结果清单已变化: "+field);}
    private ObjectNode attach(ObjectNode r,ObjectNode m,Map<String,Object> state,String step){String callId=UUID.nameUUIDFromBytes((r.path("runId").asText()+":"+step).getBytes(StandardCharsets.UTF_8)).toString();
        require(store.db.queryForList("select call_id from ai_agent_tool_call where run_id=? and call_id=?",String.class,r.path("runId").asText(),callId).isEmpty(),"RESULT_UNKNOWN: 已有稳定调用，不能重新派发");
        m.put("callId",callId).put("fencingToken",r.path("fencingToken").asLong()).put("workbookId",r.path("workbookId").asLong()).put("branchId",r.path("branchId").asLong()).put("loadedRevision",String.valueOf(state.get("loadedRevision"))).put("expectedDraftVersion",((Number)state.get("draftVersion")).longValue()).put("expectedBranchVersion",r.path("expectedBranchVersion").asLong());
        if(b8EffectEnabled)m.put("effectId",effects.reserve(r.path("runId").asText(),callId,m));
        ObjectNode c=obj().put("callId",callId).put("transportVersion",2).put("tool","apply_actions").put("status","PENDING").put("argsHash",WriteManifest.digest(m));if(m.has("effectId"))c.set("effectId",m.path("effectId"));c.set("manifest",m);r.set("call",c);store.call(r,c);return c;
    }
    private ObjectNode mutate(String id,long user,Function<ObjectNode,ObjectNode> fn){ObjectNode preview=store.get(id);require(preview.path("userId").asLong()==user,"WRITE_FORBIDDEN");return tx.execute(status->{guard.lock(preview.path("workbookId").asLong(),user);ObjectNode r=store.locked(id),before=r.deepCopy();ObjectNode out=fn.apply(r);if(!r.equals(before))store.save(r);return out;});}
    private void lease(ObjectNode r){require(!store.db.queryForList("select workbook_id from ai_agent_lease where workbook_id=? and run_id=? and fencing_token=? and expires_at>?",r.path("workbookId").asLong(),r.path("runId").asText(),r.path("fencingToken").asLong(),System.currentTimeMillis()).isEmpty(),"RESULT_UNKNOWN: 写租约待核实，不得自动重放");}
    /** Compare the actual draft row returned by the mapper, not just the CAS update count. */
    static boolean savedDraftMatches(Map<String,Object> saved,Map<String,Object> expected,long version){
        if(saved==null||expected==null||version<0||!(expected.get("content") instanceof byte[])||!(saved.get("content") instanceof String))return false;
        String content=new String((byte[])expected.get("content"),StandardCharsets.UTF_8),hash=ContentHashUtils.sha256(content);
        if(!Objects.equals(expected.get("contentHash"),hash)||!Objects.equals(saved.get("content"),content)||!Objects.equals(saved.get("contentHash"),hash))return false;
        if(!(saved.get("version") instanceof Number)||((Number)saved.get("version")).longValue()!=version)return false;
        for(String key:Arrays.asList("workbookId","branchId","ownerUserId")){
            if(!(saved.get(key) instanceof Number)||!(expected.get(key) instanceof Number)||((Number)saved.get(key)).longValue()!=((Number)expected.get(key)).longValue())return false;
        }
        for(String key:Arrays.asList("baseCommitId","draftActionsJson","executedChangesJson"))if(!Objects.equals(saved.get(key),expected.get(key)))return false;
        return true;
    }
    public ObjectNode claim(String id,long user,ObjectNode req){WriteManifest.checkTransport(req);return mutate(id,user,r->{require(!r.has("deadline")||System.currentTimeMillis()<r.path("deadline").asLong(),"RESULT_UNKNOWN: 任务超时，不得领取");lease(r);require("EXECUTING".equals(r.path("status").asText()),"RESULT_UNKNOWN: 当前任务不可派发");ObjectNode c=(ObjectNode)r.path("call");require(WriteManifest.isControlledTransport(c.path("transportVersion")),"SNAPSHOT_WRITE_BLOCKED: 旧调用不可走受控入口");require(WriteManifest.sameIntegral(req.path("fencingToken"),r.path("fencingToken").asLong()),"WRITE_FENCE_STALE");
        if(!"PENDING".equals(c.path("status").asText()))return repeatClaimReply(c);
        require(WriteManifest.digest(c.path("manifest")).equals(c.path("argsHash").asText()),"RESULT_UNKNOWN: 调用清单摘要不符，禁止派发");Map<String,Object> claimState=current(r);sameVersion((ObjectNode)c.path("manifest"),claimState);recheckResultSource(r,(ObjectNode)c.path("manifest"),parse(String.valueOf(claimState.get("content"))));if(c.has("effectId"))effects.transition(c.path("effectId").asText(),id,c.path("callId").asText(),"PREPARED","CLAIMED",null,null,null);c.put("status","CLAIMED").put("claimToken",UUID.randomUUID().toString()).put("clientId",text(req,"clientId"));store.call(r,c);store.event(r,"CONTROLLED_CLAIMED","客户端已领取受控清单，禁止再次执行同一调用");
        return ((ObjectNode)c.path("manifest")).deepCopy().put("claimToken",c.path("claimToken").asText()).put("argsHash",c.path("argsHash").asText());
    });}
    public ObjectNode receipt(String id,long user,ObjectNode req){return receipt(id,user,req,null);}
    public ObjectNode receipt(String id,long user,ObjectNode req,BiConsumer<ObjectNode,ObjectNode> completed){return mutate(id,user,r->{
        require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED: 受控回执不接收完整 Snapshot");WriteManifest.requireTransport(req);req.fieldNames().forEachRemaining(k->require(Arrays.asList("transportVersion","callId","effectId","claimToken","argsHash","fencingToken","ok","cells","formatReadback","filterReadback","formulaReadback","mergeReadback","structureReadback","error").contains(k),"WRITE_READBACK_INVALID: 非授权回执字段"));
        require(req.path("ok").isBoolean()&&(!req.path("ok").asBoolean()||!req.has("error")),"WRITE_READBACK_INVALID: 回执状态必须为布尔值，成功回执不能携带错误");
        List<String> recorded=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,id,text(req,"callId"));require(recorded.size()==1,"WRITE_CALL_NOT_FOUND");ObjectNode c=(ObjectNode)parse(recorded.get(0));require(WriteManifest.isControlledTransport(c.path("transportVersion")),"SNAPSHOT_WRITE_BLOCKED");require(c.path("claimToken").asText().equals(text(req,"claimToken"))&&c.path("argsHash").asText().equals(text(req,"argsHash"))&&WriteManifest.sameIntegral(req.path("fencingToken"),c.path("manifest").path("fencingToken").asLong())&&WriteManifest.digest(c.path("manifest")).equals(c.path("argsHash").asText()),"WRITE_RECEIPT_IDENTITY_INVALID");String receiptHash=WriteManifest.digest(req);
        require(c.has("effectId")?c.path("effectId").isTextual()&&c.path("effectId").equals(req.path("effectId"))&&c.path("effectId").equals(c.path("manifest").path("effectId")):!req.has("effectId"),"EFFECT_IDENTITY_CONFLICT: 回执效果身份不一致");
        if("SUCCEEDED".equals(c.path("status").asText())){require(hasSavedReceiptEvidence(c)&&"SAVED".equals(r.path("persistenceStatus").asText())&&r.path("lastControlledResult").equals(c.path("result")),"RESULT_UNKNOWN: 保存账本与任务状态不一致");if(c.has("effectId"))effects.assertPersisted(id,c);require(receiptHash.equals(c.path("receiptHash").asText()),"WRITE_IDEMPOTENCY_CONFLICT: 重复回执不一致");return view(r);}
        require(!r.has("deadline")||System.currentTimeMillis()<r.path("deadline").asLong(),"RESULT_UNKNOWN: 任务超时，晚到回执不得新增保存");
        require(WriteManifest.sameIntegral(req.path("fencingToken"),r.path("fencingToken").asLong()),"WRITE_FENCE_STALE");
        require("EXECUTING".equals(r.path("status").asText())&&"CLAIMED".equals(c.path("status").asText())&&r.path("call").path("callId").equals(c.path("callId")),"RESULT_UNKNOWN: 调用未核实或已取消");lease(r);
        if(!req.path("ok").asBoolean()){if(c.has("effectId"))effects.transition(c.path("effectId").asText(),id,c.path("callId").asText(),"CLAIMED","UNKNOWN",null,null,null);c.put("status","UNKNOWN");store.call(r,c);r.set("call",c);r.put("status","PAUSED").put("persistenceStatus","NOT_SAVED").put("message","RESULT_UNKNOWN: 执行失败或回读失败，核实后取消；不重放");store.event(r,"CONTROLLED_UNKNOWN",r.path("message").asText());return view(r);}
        ObjectNode m=(ObjectNode)c.path("manifest");boolean format=m.has("patch"),filter=m.has("filterPatch"),formula=m.has("formulaPatch"),merge=m.has("mergePatch"),structure=m.has("structurePatch");require(!format||b5FormatOpen(),"AI_CAPABILITY_BLOCKED: B5 保存仍关闭");require(!filter||filterActionsEnabled(),"AI_CAPABILITY_BLOCKED: B6 保存仍关闭");require(!formula||formulaActionsEnabled(),"AI_CAPABILITY_BLOCKED: B7 保存仍关闭");require(!merge||mergeActionsEnabled(),"AI_CAPABILITY_BLOCKED: B8b 保存仍关闭");String structureType=m.path("structurePatch").path("type").asText();require(!structure||("createSheet".equals(structureType)?sheetActionsEnabled():"renameSheet".equals(structureType)?sheetRenameActionsEnabled():"copySheet".equals(structureType)?sheetCopyActionsEnabled():"deleteSheet".equals(structureType)?sheetDeleteActionsEnabled():structureActionsEnabled()),"AI_CAPABILITY_BLOCKED: B8c 保存仍关闭");Map<String,Object> state=current(r);sameVersion(m,state);ObjectNode trusted=parse(String.valueOf(state.get("content"))).deepCopy(),next;
        recheckResultSource(r,m,trusted);
        if(format){require(!req.has("cells")&&req.has("formatReadback"),"B5_READBACK_INVALID: 仅接收格式回读");WriteManifest.validateB5FormatReceipt(m,req.path("formatReadback"));next=WriteManifest.applyB5Format(trusted,m);}
        else if(filter){require(!req.has("cells")&&!req.has("formatReadback")&&req.has("filterReadback"),"B6_READBACK_INVALID: 仅接收筛选回读");FilterManifest.validateReceipt(m,req.path("filterReadback"));next=FilterManifest.apply(trusted,m);}
        else if(formula){require(!req.has("cells")&&!req.has("formatReadback")&&!req.has("filterReadback")&&req.has("formulaReadback"),"B7_READBACK_INVALID: 仅接收公式回读");FormulaManifest.validateReceipt(m,req.path("formulaReadback"));next=FormulaManifest.apply(trusted,m);}
        else if(merge){require(!req.has("cells")&&!req.has("formatReadback")&&!req.has("filterReadback")&&!req.has("formulaReadback")&&req.has("mergeReadback"),"B8_MERGE_READBACK_INVALID: 仅接收合并回读");MergeManifest.validateReceipt(m,req.path("mergeReadback"));next=MergeManifest.apply(trusted,m);}
        else if(structure){require(!req.has("cells")&&!req.has("formatReadback")&&!req.has("filterReadback")&&!req.has("formulaReadback")&&!req.has("mergeReadback")&&req.has("structureReadback"),"B8C_STRUCTURE_READBACK_INVALID: 仅接收结构回读");StructureManifest.validateReceipt(m,req.path("structureReadback"));next=m.path("resultSheet").asBoolean()?ResultSheetManifest.apply(trusted,m):StructureManifest.apply(trusted,m);}
        else{require(!req.has("formatReadback")&&!req.has("filterReadback")&&!req.has("formulaReadback")&&!req.has("mergeReadback")&&!req.has("structureReadback"),"WRITE_READBACK_INVALID: 值回执不能携带资源字段");WriteManifest.validateReceipt(m,req.path("cells"));next=WriteManifest.apply(trusted,m);}
        String content=next.toString(),loaded=ContentHashUtils.sha256(content);
        @SuppressWarnings("unchecked") Map<String,Object> branch=(Map<String,Object>)state.get("branch");@SuppressWarnings("unchecked") Map<String,Object> prior=(Map<String,Object>)state.get("draft");
        ArrayNode changes=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("executedChangesJson"))),actions=prior==null?arr():(ArrayNode)parse(String.valueOf(prior.get("draftActionsJson")));
        ObjectNode change=obj().put("schemaVersion","1.0").put("source","ai").put("kind",format?"controlled-format":filter?"controlled-filter":formula?"controlled-formula":merge?"controlled-merge":structure?"controlled-structure":"controlled-values").put("operationId",c.path("callId").asText()).put("runId",id).put("beforeHash",m.path("loadedRevision").asText()).put("afterHash",loaded).put("needsSnapshotDiff",true);if(c.has("effectId"))change.set("effectId",c.path("effectId"));if(format)change.set("formatPatch",m.path("patch"));else if(filter)change.set("filterPatch",m.path("filterPatch"));else if(formula)change.set("formulaPatch",m.path("formulaPatch"));else if(merge)change.set("mergePatch",m.path("mergePatch"));else if(structure)change.set("structurePatch",m.path("structurePatch"));else change.set("cells",m.path("cells"));if(m.has("resultSource"))change.set("resultSource",m.path("resultSource"));changes.add(change);actions.addAll((ArrayNode)m.path("actions"));
        Map<String,Object> save=new HashMap<>();save.put("workbookId",r.path("workbookId").asLong());save.put("branchId",r.path("branchId").asLong());save.put("ownerUserId",user);save.put("baseCommitId",branch.get("headCommitId"));save.put("draftActionsJson",actions.toString());save.put("executedChangesJson",changes.toString());save.put("content",content.getBytes(StandardCharsets.UTF_8));save.put("contentHash",loaded);
        long expected=m.path("expectedDraftVersion").asLong();require(expected>=-1&&expected<Long.MAX_VALUE,"WRITE_VERSION_CONFLICT: 草稿版本无效");save.put("expectedDraftVersion",expected);save.put("expectedContentHash",m.path("loadedRevision").asText());
        if(expected<0)require(versions.insertWorkingState(save)==1,"WRITE_VERSION_CONFLICT: 首次草稿冲突");else require(versions.updateWorkingStateCas(save)==1,"WRITE_VERSION_CONFLICT: CAS 拒绝旧草稿");
        require(savedDraftMatches(versions.selectWorkingStateForUpdate(r.path("workbookId").asLong(),r.path("branchId").asLong(),user),save,expected+1),"RESULT_UNKNOWN: CAS 后草稿回读与可信补丁不一致");
        ObjectNode result=obj().put("transportVersion",2).put("callId",c.path("callId").asText()).put("loadedRevision",loaded).put("draftVersion",expected+1).put("sourceRevision",hash(next)).put("validation","PASSED").put("persistenceStatus","SAVED");if(c.has("effectId"))result.set("effectId",c.path("effectId"));
        result.set("executedChange",change);result.set("actions",m.path("actions"));
        if(c.has("effectId"))effects.transition(c.path("effectId").asText(),id,c.path("callId").asText(),"CLAIMED","PERSISTED",loaded,receiptHash,result);
        c.put("status","SUCCEEDED").put("receiptHash",receiptHash).put("persistenceStatus","SAVED");c.set("result",result);store.call(r,c);r.set("lastControlledResult",result);r.set("lastControlledManifest",m);r.put("revision",hash(next)).put("loadedRevision",loaded).put("expectedDraftVersion",expected+1).put("persistenceStatus","SAVED");
        if("CONTROLLED_WRITE".equals(r.path("kind").asText())){r.put("status","COMPLETED");store.db.update("update ai_agent_lease set run_id=null,expires_at=0 where workbook_id=? and run_id=?",r.path("workbookId").asLong(),id);}else{require(completed!=null,"CONTROLLED_COMPLETION_REQUIRED");r.set("expectedSnapshot",next);completed.accept(r,result);}
        r.remove("call");store.event(r,"CONTROLLED_SAVED","目标值已独立验证并 CAS 保存，未创建正式 Commit");return view(r);
    });}
    /** A lost receipt may have committed. Serialize reconciliation with receipt/CAS using the same locks. */
    public ObjectNode uncertain(String id,long user,ObjectNode req){return mutate(id,user,r->{
        require(!req.has("snapshot"),"SNAPSHOT_WRITE_BLOCKED");List<String> rows=store.db.queryForList("select call_json from ai_agent_tool_call where run_id=? and call_id=?",String.class,id,text(req,"callId"));require(rows.size()==1,"WRITE_CALL_NOT_FOUND");ObjectNode c=(ObjectNode)parse(rows.get(0));
        require(WriteManifest.isControlledTransport(c.path("transportVersion"))&&c.path("claimToken").asText().equals(text(req,"claimToken"))&&c.path("argsHash").asText().equals(text(req,"argsHash"))&&WriteManifest.sameIntegral(req.path("fencingToken"),c.path("manifest").path("fencingToken").asLong()),"WRITE_RECEIPT_IDENTITY_INVALID");
        require(c.has("effectId")?c.path("effectId").equals(req.path("effectId")):!req.has("effectId"),"EFFECT_IDENTITY_CONFLICT: 核实效果身份不一致");
        if("SUCCEEDED".equals(c.path("status").asText())){require(hasSavedReceiptEvidence(c)&&"SAVED".equals(r.path("persistenceStatus").asText())&&r.path("lastControlledResult").equals(c.path("result")),"RESULT_UNKNOWN: 保存账本与任务状态不一致");if(c.has("effectId"))effects.assertPersisted(id,c);return view(r);}
        require(Arrays.asList("CLAIMED","UNKNOWN").contains(c.path("status").asText())&&r.path("call").path("callId").equals(c.path("callId")),"RESULT_UNKNOWN: 调用不可重新派发");
        if(c.has("effectId")&&"CLAIMED".equals(c.path("status").asText()))effects.transition(c.path("effectId").asText(),id,c.path("callId").asText(),"CLAIMED","UNKNOWN",null,null,null);c.put("status","UNKNOWN");store.call(r,c);r.set("call",c);if(!"CANCELLING".equals(r.path("status").asText()))r.put("status","PAUSED");r.put("persistenceStatus","NOT_SAVED").put("message","RESULT_UNKNOWN: 未核实保存结果，保持保护；仅允许核实或取消，不自动重放");store.event(r,"CONTROLLED_UNKNOWN",r.path("message").asText());return view(r);
    });}
    private ObjectNode view(ObjectNode r){ObjectNode out=r.deepCopy();out.remove(Arrays.asList("initialSnapshot","expectedSnapshot","proposal","requestHash"));if(out.path("call").isObject())((ObjectNode)out.path("call")).remove("claimToken");return out;}
}
