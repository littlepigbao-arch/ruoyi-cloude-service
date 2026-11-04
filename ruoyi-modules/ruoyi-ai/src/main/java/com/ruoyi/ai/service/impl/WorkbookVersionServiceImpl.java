package com.ruoyi.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.domain.dto.CommitRequest;
import com.ruoyi.ai.domain.dto.BranchRequest;
import com.ruoyi.ai.domain.dto.CheckoutRequest;
import com.ruoyi.ai.domain.dto.BranchIntegrateRequest;
import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.ai.mapper.AiWorkbookMapper;
import com.ruoyi.ai.mapper.WorkbookVersionMapper;
import com.ruoyi.ai.service.WorkbookVersionService;
import com.ruoyi.ai.util.ContentHashUtils;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkbookVersionServiceImpl implements WorkbookVersionService
{
    private static final String ACTION_SCHEMA_VERSION = "2.0";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkbookVersionMapper versionMapper;

    @Autowired
    private com.ruoyi.ai.agent.AgentWriteGuard agentWriteGuard;

    @Autowired
    private AiWorkbookMapper workbookMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initializeWorkbook(AiWorkbook workbook, Long userId, String username)
    {
        if (versionMapper.selectMainBranch(workbook.getWorkbookId(), userId) != null) return;
        if (workbook.getContent() == null) workbook.setContent("{}");
        Map<String, Object> branch = new HashMap<>();
        branch.put("workbookId", workbook.getWorkbookId());
        branch.put("branchName", "main");
        branch.put("headCommitId", null);
        branch.put("ownerUserId", userId);
        versionMapper.insertBranch(branch);

        String operationId = "init-" + UUID.randomUUID().toString();
        createCommit(workbook, branch, "初始化工作簿", operationId, "system",
                "[]", "[]", null, "{}", "[]", "{}", "{}", userId, username, 0L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> status(Long workbookId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        Map<String, Object> branch = resolveBranch(workbookId, auth.workbook.getCurrentBranchId(), auth.userId);
        if (branch == null)
        {
            branch = versionMapper.selectMainBranch(workbookId, auth.userId);
            if (branch == null)
            {
                initializeWorkbook(auth.workbook, auth.userId, auth.username);
                branch = versionMapper.selectMainBranch(workbookId, auth.userId);
                auth.workbook = workbookMapper.selectById(workbookId, auth.userId);
            }
        }
        if (branch == null) return R.fail("版本分支尚未初始化");
        Long branchId = longValue(branch.get("branchId"));
        Map<String, Object> result = new HashMap<>(branch);
        Map<String, Object> workingState = versionMapper.selectWorkingStateForUpdate(workbookId, branchId, auth.userId);
        if (workingState != null && workingState.get("content") != null &&
                !ContentHashUtils.sha256(stringValue(workingState.get("content"))).equals(workingState.get("contentHash")))
            return R.fail(422, "工作草稿完整性校验失败");
        result.put("workingState", workingState);
        result.put("contentHash", auth.workbook.getContentHash());
        result.put("workbookVersion", auth.workbook.getVersion());
        String loadedContent = workingState == null ? auth.workbook.getContent() : stringValue(workingState.get("content"));
        result.put("content", loadedContent);
        result.put("loadedRevision", ContentHashUtils.sha256(loadedContent));
        result.put("workbookId", workbookId);
        return R.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> saveWorkingState(Long workbookId, WorkingStateRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        agentWriteGuard.manual(workbookId, auth.userId);
        if (request == null || request.getContent() == null) return R.fail("工作草稿内容不能为空");
        try {
            com.fasterxml.jackson.databind.JsonNode snapshot = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(request.getContent());
            if (snapshot == null || !snapshot.isObject()) return R.fail(422, "工作草稿必须是有效 JSON 对象");
        } catch (Exception invalid) { return R.fail(422, "工作草稿 JSON 不完整或含多余内容"); }
        if (request.getBranchId() == null || !request.getBranchId().equals(auth.workbook.getCurrentBranchId())) return R.fail(409, "草稿保存必须指定当前活动分支，请重新核实");
        Map<String, Object> branch = resolveBranch(workbookId, request.getBranchId(), auth.userId);
        if (branch == null) return R.fail("分支不存在或无权访问");
        branch = versionMapper.selectBranchForUpdate(workbookId, longValue(branch.get("branchId")), auth.userId);
        if (branch == null) return R.fail("分支不存在或无权访问");
        Map<String, Object> previousDraft = versionMapper.selectWorkingStateForUpdate(workbookId, longValue(branch.get("branchId")), auth.userId);
        if (previousDraft != null && !ContentHashUtils.sha256(stringValue(previousDraft.get("content"))).equals(stringValue(previousDraft.get("contentHash")))) return R.fail(422, "已有草稿完整性校验失败");
        if (previousDraft != null && !equalsNullable(stringValue(previousDraft.get("baseCommitId")), stringValue(branch.get("headCommitId")))) return R.fail(409, "已有草稿基线与 HEAD 不一致，请先核实");
        Long draftVersion = previousDraft == null ? -1L : longValue(previousDraft.get("version"));
        if (!draftVersion.equals(request.getExpectedDraftVersion())) return R.fail(409, "草稿版本已变化，请重新打开文档后再保存");
        String loadedContent = previousDraft == null ? auth.workbook.getContent() : stringValue(previousDraft.get("content"));
        String loadedRevision = ContentHashUtils.sha256(loadedContent);
        if (previousDraft == null && request.getLoadedRevision() == null)
            return R.fail(409, "首次草稿保存缺少页面加载版本，请保留编辑并升级客户端");
        // Older clients with an explicit draft version remain safe. A missing
        // version is never replaced by a freshly read server version.
        if (request.getLoadedRevision() != null && !loadedRevision.equals(request.getLoadedRevision()))
            return R.fail(409, "页面加载内容已变化，请保留本地编辑并重新核实");
        if (!longValue(branch.get("version")).equals(request.getBaseVersion())) return R.fail(409, "分支版本已变化");
        if (!equalsNullable(stringValue(branch.get("headCommitId")), request.getBaseCommitId()))
        {
            return R.fail(409, "草稿基线已变化，请重新比较后保存");
        }
        Map<String, Object> state = new HashMap<>();
        state.put("workbookId", workbookId);
        state.put("branchId", longValue(branch.get("branchId")));
        state.put("ownerUserId", auth.userId);
        state.put("baseCommitId", request.getBaseCommitId());
        state.put("draftActionsJson", json(request.getPlannedActions(), "[]"));
        state.put("executedChangesJson", json(request.getExecutedChanges(), "[]"));
        state.put("content", request.getContent().getBytes(StandardCharsets.UTF_8));
        state.put("contentHash", ContentHashUtils.sha256(request.getContent()));
        state.put("expectedDraftVersion", request.getExpectedDraftVersion());
        state.put("expectedContentHash", previousDraft == null ? loadedRevision : previousDraft.get("contentHash"));
        try {
            int saved = previousDraft == null ? versionMapper.insertWorkingState(state) : versionMapper.updateWorkingStateCas(state);
            if (saved != 1) return R.fail(409, "草稿并发冲突，请保留本地编辑");
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            return R.fail(409, "草稿已被另一页面创建，请保留本地编辑");
        }
        Map<String, Object> saved = versionMapper.selectWorkingStateForUpdate(workbookId, longValue(branch.get("branchId")), auth.userId);
        saved.put("loadedRevision", state.get("contentHash"));
        return R.ok(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> deleteWorkingState(Long workbookId, Long branchId)
    {
        com.ruoyi.ai.domain.dto.DraftDeleteRequest request=new com.ruoyi.ai.domain.dto.DraftDeleteRequest();request.setBranchId(branchId);
        return deleteWorkingState(workbookId,request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> deleteWorkingState(Long workbookId,com.ruoyi.ai.domain.dto.DraftDeleteRequest request) {
        Auth auth=authWorkbook(workbookId);if(auth.error!=null)return R.fail(auth.error);
        agentWriteGuard.manual(workbookId,auth.userId);
        if(request==null || !Boolean.TRUE.equals(request.getConfirmed()) || request.getBranchId()==null || request.getBaseVersion()==null)return R.fail(409,"丢弃需要明确确认和实际加载的分支/草稿版本");
        if(!request.getBranchId().equals(auth.workbook.getCurrentBranchId()))return R.fail(409,"活动分支已变化，未丢弃草稿");
        Map<String,Object> branch=versionMapper.selectBranchForUpdate(workbookId,request.getBranchId(),auth.userId);
        if(branch==null)return R.fail("分支不存在或无权访问");
        if(!request.getBaseVersion().equals(longValue(branch.get("version"))) || !equalsNullable(request.getBaseCommitId(),stringValue(branch.get("headCommitId"))))return R.fail(409,"HEAD 已变化，未丢弃草稿");
        Map<String,Object> draft=versionMapper.selectWorkingStateForUpdate(workbookId,request.getBranchId(),auth.userId);
        String error=draftBindingError(auth.workbook,draft,request.getExpectedDraftVersion(),request.getLoadedRevision());if(error!=null)return R.fail(409,error);
        if(draft==null)return R.ok();
        Map<String,Object> head=versionMapper.selectSnapshotByCommit(workbookId,stringValue(branch.get("headCommitId")),auth.userId);
        if(head==null)return R.fail(409,"缺少可恢复的 HEAD 快照，未丢弃草稿");
        String content=stringValue(head.get("content")),hash=ContentHashUtils.sha256(content);
        if(!hash.equals(stringValue(head.get("contentHash"))))return R.fail(422,"HEAD 快照完整性校验失败，未丢弃草稿");
        // Advance the branch generation even though HEAD is unchanged. Draft rows
        // restart at version zero after recreation; old requests must not match
        // a later draft with the same content and version.
        if(versionMapper.updateBranchHead(workbookId,request.getBranchId(),auth.userId,request.getBaseVersion(),stringValue(branch.get("headCommitId")))!=1)return R.fail(409,"分支已经变化，未丢弃");
        Map<String,Object> binding=new HashMap<>();binding.put("workbookId",workbookId);binding.put("branchId",request.getBranchId());binding.put("userId",auth.userId);binding.put("expectedDraftVersion",request.getExpectedDraftVersion());binding.put("loadedRevision",request.getLoadedRevision());
        if(versionMapper.deleteWorkingStateCas(binding)!=1)throw new IllegalStateException("草稿已经变化，丢弃事务回滚");
        if(workbookMapper.updateVersionPointers(workbookId,auth.userId,request.getBranchId(),stringValue(branch.get("headCommitId")),content,hash,auth.username)!=1)throw new IllegalStateException("丢弃后的 HEAD 恢复失败，事务回滚");
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> commit(Long workbookId, CommitRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        agentWriteGuard.manual(workbookId, auth.userId);
        String validationError = validateCommitRequest(request, false);
        if (validationError != null) return R.fail(validationError);
        Map<String, Object> prior = versionMapper.selectCommitByOperation(workbookId, request.getOperationId());
        String requestHash = com.ruoyi.ai.agent.WriteManifest.digest(objectMapper.valueToTree(request));
        if (prior != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode binding = objectMapper.readTree(stringValue(prior.get("validationJson")));
                if (!requestHash.equals(binding.path("requestHash").asText()) || !binding.path("hashVerified").asBoolean() || !binding.path("branchVersionAfter").isIntegralNumber() || !binding.path("workbookVersionAfter").isIntegralNumber()) return R.fail(409, "operationId 已被其他参数或旧请求占用，或原提交身份不完整，请核实原提交");
                prior.put("created", false);prior.put("branchVersion", binding.path("branchVersionAfter").asLong());prior.put("workbookVersion", binding.path("workbookVersionAfter").asLong());
                return R.ok(prior, "重复请求已返回首次提交事实");
            } catch (Exception invalid) { return R.fail(409, "历史提交缺少可核实的请求身份，请核实原提交"); }
        }
        if (request.getBranchId() == null || request.getExpectedDraftVersion() == null || request.getExpectedDraftVersion() < -1 || request.getLoadedRevision() == null || request.getLoadedRevision().isEmpty()) return R.fail(409, "缺少实际加载的分支/草稿版本，请保留编辑并升级客户端");
        if (!request.getBranchId().equals(auth.workbook.getCurrentBranchId())) return R.fail(409, "活动分支已变化，请保留编辑并重新加载");
        Map<String, Object> branch = versionMapper.selectBranchForUpdate(workbookId, request.getBranchId(), auth.userId);
        if (branch == null) return R.fail("分支不存在或无权访问");
        Long version = longValue(branch.get("version"));
        if (request.getBaseVersion() == null || !version.equals(request.getBaseVersion()))
        {
            return R.fail(409, "分支版本冲突，请保留草稿并重新加载最新版本");
        }
        if (!equalsNullable(stringValue(branch.get("headCommitId")), request.getBaseCommitId()))
        {
            return R.fail(409, "baseCommitId 与当前 HEAD 不一致");
        }
        Map<String, Object> draft = versionMapper.selectWorkingStateForUpdate(workbookId, request.getBranchId(), auth.userId);
        String draftError = draftBindingError(auth.workbook, draft, request);
        if (draftError != null) return R.fail(409, draftError);
        Map<String, Object> created = createCommit(auth.workbook, branch, request.getCommitMessage().trim(),
                request.getOperationId(), defaultValue(request.getSource(), "ai"),
                json(request.getPlannedActions(), "[]"), json(request.getExecutedChanges(), "[]"),
                json(request.getInverseChanges(), null), json(request.getDiffSummary(), "{}"),
                json(request.getKnowledgeRefs(), "[]"), json(request.getDataContext(), "{}"),
                json(request.getExecutionContext(), "{}"), auth.userId, auth.username, version,
                request.getContent(), defaultValue(request.getActionSchemaVersion(), ACTION_SCHEMA_VERSION), requestHash);
        return R.ok(created);
    }

    @Override
    public R<List<Map<String, Object>>> commits(Long workbookId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        return R.ok(versionMapper.selectCommits(workbookId, auth.userId));
    }

    @Override
    public R<Map<String, Object>> commitDetail(Long workbookId, String commitId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        Map<String, Object> commit = versionMapper.selectCommit(workbookId, commitId, auth.userId);
        return commit == null ? R.fail("提交不存在或无权访问") : R.ok(commit);
    }

    @Override
    public R<Map<String, Object>> snapshot(Long workbookId, String commitId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        Map<String, Object> snapshot = versionMapper.selectSnapshotByCommit(workbookId, commitId, auth.userId);
        if (snapshot == null) return R.fail("版本快照不存在");
        String content = stringValue(snapshot.get("content"));
        if (!ContentHashUtils.sha256(content).equals(snapshot.get("contentHash")))
        {
            return R.fail(422, "版本快照完整性校验失败");
        }
        return R.ok(snapshot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> restore(Long workbookId, String commitId, CommitRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        if (request == null || request.getOperationId() == null || request.getOperationId().trim().isEmpty())
        {
            return R.fail("operationId 不能为空");
        }
        Map<String, Object> snapshotResult = versionMapper.selectSnapshotByCommit(workbookId, commitId, auth.userId);
        if (snapshotResult == null) return R.fail("目标版本快照不存在");
        String content = stringValue(snapshotResult.get("content"));
        if (!ContentHashUtils.sha256(content).equals(snapshotResult.get("contentHash")))
        {
            return R.fail(422, "目标版本快照完整性校验失败");
        }
        request.setContent(content);
        request.setSource("revert");
        request.setCommitMessage(defaultValue(request.getCommitMessage(), "恢复到版本 " + commitId));
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> change = new HashMap<>();
        change.put("kind", "snapshot-replace");
        change.put("targetCommitId", commitId);
        change.put("source", "revert");
        changes.add(change);
        request.setExecutedChanges(changes);
        // A retry may return an immutable earlier fact; it must not consume a newer draft.
        if (versionMapper.selectCommitByOperation(workbookId, request.getOperationId()) != null) return commit(workbookId, request);
        agentWriteGuard.manual(workbookId, auth.userId);
        if(request.getBranchId()==null || !request.getBranchId().equals(auth.workbook.getCurrentBranchId())) return R.fail(409,"活动分支已变化，请重新核实后恢复");
        Map<String,Object> branch=versionMapper.selectBranchForUpdate(workbookId,request.getBranchId(),auth.userId);
        if(branch==null)return R.fail("分支不存在或无权访问");
        Map<String,Object> draft=versionMapper.selectWorkingStateForUpdate(workbookId,request.getBranchId(),auth.userId);
        String draftError=draftBindingError(auth.workbook,draft,request);if(draftError!=null)return R.fail(409,draftError);
        if(draft!=null){Map<String,Object> head=versionMapper.selectSnapshotByCommit(workbookId,stringValue(branch.get("headCommitId")),auth.userId);
            if(head==null || !equalsNullable(stringValue(draft.get("contentHash")),stringValue(head.get("contentHash"))))return R.fail(409,"当前分支有未提交草稿，请先提交或核实保留后再恢复");}
        return commit(workbookId, request);
    }

    @Override
    public R<List<Map<String, Object>>> branches(Long workbookId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        return R.ok(versionMapper.selectBranches(workbookId, auth.userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createBranch(Long workbookId, BranchRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        agentWriteGuard.manual(workbookId, auth.userId);
        if (request == null || request.getOperationId() == null || request.getOperationId().trim().isEmpty())
            return R.fail("operationId 不能为空");
        String name = request.getBranchName() == null ? "" : request.getBranchName().trim();
        if (!name.startsWith("draft/")) name = "draft/" + name;
        if (!name.matches("draft/[A-Za-z0-9._\\-\\u4e00-\\u9fa5]{1,100}"))
            return R.fail("草稿分支名称只能包含中英文、数字、点、横线和下划线");
        if (versionMapper.selectBranchByName(workbookId, name, auth.userId) != null)
            return R.fail("分支名称已存在");
        String fromCommitId = defaultValue(request.getFromCommitId(), auth.workbook.getHeadCommitId());
        if (fromCommitId == null) return R.fail("没有可作为分支起点的提交");
        Map<String, Object> fromCommit = versionMapper.selectCommit(workbookId, fromCommitId, auth.userId);
        if (fromCommit == null) return R.fail("分支起点提交不存在或无权访问");
        Map<String, Object> branch = new HashMap<>();
        branch.put("workbookId", workbookId);
        branch.put("branchName", name);
        branch.put("headCommitId", fromCommitId);
        branch.put("ownerUserId", auth.userId);
        versionMapper.insertBranch(branch);
        return R.ok(versionMapper.selectBranch(workbookId, longValue(branch.get("branchId")), auth.userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> checkout(Long workbookId, Long branchId, CheckoutRequest request) {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        agentWriteGuard.manual(workbookId, auth.userId);
        if (request == null || request.getOperationId() == null || request.getOperationId().trim().isEmpty()) return R.fail("operationId 不能为空");
        if (branchId == null || request.getCurrentBranchId() == null || request.getBaseVersion() == null || request.getExpectedWorkbookVersion() == null)
            return R.fail(409, "切换缺少实际加载的源分支和工作簿版本，请升级客户端");
        if (!request.getCurrentBranchId().equals(auth.workbook.getCurrentBranchId()) || !request.getExpectedWorkbookVersion().equals(auth.workbook.getVersion()))
            return R.fail(409, "活动分支或工作簿版本已变化，请重新核实");
        if (branchId.equals(request.getCurrentBranchId())) return R.fail(409, "目标已是活动分支，请读取状态核实");
        Map<String,Object> source = versionMapper.selectBranchForUpdate(workbookId, request.getCurrentBranchId(), auth.userId);
        if (source == null || !request.getBaseVersion().equals(longValue(source.get("version"))) || !equalsNullable(request.getBaseCommitId(), stringValue(source.get("headCommitId"))))
            return R.fail(409, "源分支 HEAD 或版本已变化");
        Map<String,Object> sourceDraft = versionMapper.selectWorkingStateForUpdate(workbookId, request.getCurrentBranchId(), auth.userId);
        String error = draftBindingError(auth.workbook, sourceDraft, request.getExpectedDraftVersion(), request.getLoadedRevision());
        if (error != null) return R.fail(409, error);
        Map<String,Object> branch = versionMapper.selectBranchForUpdate(workbookId, branchId, auth.userId);
        if (branch == null) return R.fail("目标分支不存在或无权访问");
        String targetHead = stringValue(branch.get("headCommitId"));
        if (request.getTargetVersion() == null || !request.getTargetVersion().equals(longValue(branch.get("version"))) || !equalsNullable(request.getTargetHeadCommitId(), targetHead))
            return R.fail(409, "目标分支 HEAD 或版本已变化或缺失");
        Map<String,Object> draft = versionMapper.selectWorkingStateForUpdate(workbookId, branchId, auth.userId);
        Map<String,Object> snapshot = draft == null ? versionMapper.selectSnapshotByCommit(workbookId, targetHead, auth.userId) : draft;
        if (snapshot == null || snapshot.get("content") == null) return R.fail(409, "目标分支内容不存在");
        String content = stringValue(snapshot.get("content")), hash = ContentHashUtils.sha256(content);
        if (!hash.equals(stringValue(snapshot.get("contentHash")))) return R.fail(422, "目标分支内容完整性校验失败");
        if (draft != null && !equalsNullable(stringValue(draft.get("baseCommitId")), targetHead)) return R.fail(409, "目标草稿基线与 HEAD 不一致，请先核实");
        long draftVersion = draft == null ? -1L : longValue(draft.get("version"));
        if (request.getExpectedTargetDraftVersion() == null || request.getExpectedTargetDraftVersion() != draftVersion || !hash.equals(request.getTargetLoadedRevision()))
            return R.fail(409, "目标草稿已变化或缺少已核实版本");
        if (workbookMapper.updateVersionPointers(workbookId, auth.userId, branchId, targetHead, content, hash, auth.username) != 1)
            throw new IllegalStateException("分支切换指针更新失败，事务回滚");
        Map<String,Object> result = new HashMap<>(branch);
        result.put("content", content); result.put("contentHash", hash); result.put("loadedRevision", hash);
        result.put("workingState", draft); result.put("workbookVersion", auth.workbook.getVersion() + 1);
        return R.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String,Object>> fastForwardToMain(Long workbookId,Long branchId,BranchIntegrateRequest request) {
        Auth auth=authWorkbook(workbookId);if(auth.error!=null)return R.fail(auth.error);
        agentWriteGuard.manual(workbookId,auth.userId);
        if(request==null || request.getOperationId()==null || request.getOperationId().trim().isEmpty())return R.fail("operationId 不能为空");
        if(branchId==null || !branchId.equals(auth.workbook.getCurrentBranchId()))return R.fail(409,"活动源分支已变化，请重新核实");
        Map<String,Object> source=versionMapper.selectBranchForUpdate(workbookId,branchId,auth.userId);
        if(source==null || "main".equals(source.get("branchName")))return R.fail("请选择有效草稿分支");
        String sourceHead=stringValue(source.get("headCommitId"));
        if(request.getSourceVersion()==null || !request.getSourceVersion().equals(longValue(source.get("version"))) || request.getSourceHeadCommitId()==null || !request.getSourceHeadCommitId().equals(sourceHead))return R.fail(409,"源分支版本已变化或缺失");
        if(versionMapper.selectWorkingStateForUpdate(workbookId,branchId,auth.userId)!=null)return R.fail(409,"源分支还有未提交草稿，请先提交或核实丢弃");
        Map<String,Object> main=versionMapper.selectMainBranch(workbookId,auth.userId);if(main==null)return R.fail("main 不存在");
        Long mainId=longValue(main.get("branchId"));main=versionMapper.selectBranchForUpdate(workbookId,mainId,auth.userId);
        long mainVersion=longValue(main.get("version"));String mainHead=stringValue(main.get("headCommitId"));
        if(request.getMainVersion()==null || request.getMainVersion()!=mainVersion || !equalsNullable(request.getMainHeadCommitId(),mainHead))return R.fail(409,"main HEAD 或版本已变化");
        Map<String,Object> mainDraft=versionMapper.selectWorkingStateForUpdate(workbookId,mainId,auth.userId),mainSnapshot=versionMapper.selectSnapshotByCommit(workbookId,mainHead,auth.userId);
        if(mainSnapshot==null || !ContentHashUtils.sha256(stringValue(mainSnapshot.get("content"))).equals(stringValue(mainSnapshot.get("contentHash"))))return R.fail(422,"main HEAD 快照不完整，未合入");
        String mainContent=mainDraft==null?stringValue(mainSnapshot.get("content")):stringValue(mainDraft.get("content")),loaded=ContentHashUtils.sha256(mainContent);long draftVersion=mainDraft==null?-1L:longValue(mainDraft.get("version"));
        if(mainDraft!=null && !loaded.equals(stringValue(mainDraft.get("contentHash"))))return R.fail(422,"main 草稿完整性校验失败");
        if(request.getExpectedMainDraftVersion()==null || request.getExpectedMainDraftVersion()!=draftVersion || !loaded.equals(request.getMainLoadedRevision()))return R.fail(409,"main 草稿已变化或缺少加载版本，未合入");
        if(mainDraft!=null && (!loaded.equals(stringValue(mainSnapshot.get("contentHash"))) || !emptyAudit(mainDraft.get("draftActionsJson")) || !emptyAudit(mainDraft.get("executedChangesJson"))))return R.fail(409,"main 有未提交草稿，请先提交或明确处理，未合入");
        if(!isAncestor(workbookId,mainHead,sourceHead,auth.userId))return R.fail(409,"main 与源分支已经分叉，未合入");
        Map<String,Object> snapshot=versionMapper.selectSnapshotByCommit(workbookId,sourceHead,auth.userId);if(snapshot==null)return R.fail("源 HEAD 快照不存在");
        String content=stringValue(snapshot.get("content")),hash=ContentHashUtils.sha256(content);if(!hash.equals(stringValue(snapshot.get("contentHash"))))return R.fail(422,"源 HEAD 快照不完整，未合入");
        if(versionMapper.updateBranchHead(workbookId,mainId,auth.userId,mainVersion,sourceHead)!=1)return R.fail(409,"main 版本冲突");
        if(mainDraft!=null){Map<String,Object> binding=new HashMap<>();binding.put("workbookId",workbookId);binding.put("branchId",mainId);binding.put("userId",auth.userId);binding.put("expectedDraftVersion",draftVersion);binding.put("loadedRevision",loaded);if(versionMapper.deleteWorkingStateCas(binding)!=1)throw new IllegalStateException("main 草稿已变化，合入事务回滚");}
        if(workbookMapper.updateVersionPointers(workbookId,auth.userId,mainId,sourceHead,content,hash,auth.username)!=1)throw new IllegalStateException("main 指针更新失败，合入事务回滚");
        Map<String,Object> result=versionMapper.selectMainBranch(workbookId,auth.userId);result.put("content",content);result.put("contentHash",hash);result.put("loadedRevision",hash);result.put("workingState",null);result.put("workbookVersion",auth.workbook.getVersion()==null?1L:auth.workbook.getVersion()+1);return R.ok(result);
    }

    private boolean emptyAudit(Object value) {
        try {com.fasterxml.jackson.databind.JsonNode node=objectMapper.readTree(stringValue(value));return node.isArray()&&node.size()==0;}
        catch(Exception invalid){return false;}
    }

    private boolean isAncestor(Long workbookId, String ancestorId, String descendantId, Long userId)
    {
        String cursor = descendantId;
        int guard = 0;
        while (cursor != null && guard++ < 10000)
        {
            if (cursor.equals(ancestorId)) return true;
            Map<String, Object> commit = versionMapper.selectCommit(workbookId, cursor, userId);
            if (commit == null) return false;
            cursor = stringValue(commit.get("parentCommitId"));
        }
        return ancestorId == null && cursor == null;
    }

    private Map<String, Object> createCommit(AiWorkbook workbook, Map<String, Object> branch, String message,
                                              String operationId, String source, String plannedJson,
                                              String changesJson, String inverseJson, String diffJson,
                                              String knowledgeJson, String dataJson, String executionJson,
                                              Long userId, String username, Long expectedVersion)
    {
        return createCommit(workbook, branch, message, operationId, source, plannedJson, changesJson, inverseJson,
                diffJson, knowledgeJson, dataJson, executionJson, userId, username, expectedVersion,
                workbook.getContent(), ACTION_SCHEMA_VERSION, null);
    }

    private Map<String, Object> createCommit(AiWorkbook workbook, Map<String, Object> branch, String message,
                                              String operationId, String source, String plannedJson,
                                              String changesJson, String inverseJson, String diffJson,
                                              String knowledgeJson, String dataJson, String executionJson,
                                              Long userId, String username, Long expectedVersion,
                                              String content, String schemaVersion, String requestHash)
    {
        String commitId = UUID.randomUUID().toString();
        String snapshotId = UUID.randomUUID().toString();
        String parentCommitId = stringValue(branch.get("headCommitId"));
        Map<String, Object> parentCommit = parentCommitId == null ? null
                : versionMapper.selectCommit(workbook.getWorkbookId(), parentCommitId, userId);
        String beforeHash = parentCommit == null
                ? defaultValue(workbook.getContentHash(), ContentHashUtils.sha256(workbook.getContent()))
                : stringValue(parentCommit.get("afterHash"));
        String afterHash = ContentHashUtils.sha256(content);
        Map<String, Object> commit = new HashMap<>();
        commit.put("commitId", commitId);
        commit.put("workbookId", workbook.getWorkbookId());
        commit.put("branchId", longValue(branch.get("branchId")));
        commit.put("parentCommitId", parentCommitId);
        commit.put("secondParentId", null);
        commit.put("commitNo", versionMapper.selectNextCommitNo(workbook.getWorkbookId()));
        commit.put("commitMessage", message);
        commit.put("source", source);
        commit.put("authorUserId", userId);
        commit.put("authorName", username);
        commit.put("operationId", operationId);
        commit.put("actionSchemaVersion", schemaVersion);
        commit.put("plannedActionsJson", plannedJson);
        commit.put("executedChangesJson", changesJson);
        commit.put("inverseChangesJson", inverseJson);
        commit.put("diffSummaryJson", diffJson);
        commit.put("knowledgeRefsJson", knowledgeJson);
        commit.put("dataContextJson", dataJson);
        commit.put("executionContextJson", executionJson);
        commit.put("beforeHash", beforeHash);
        commit.put("afterHash", afterHash);
        Map<String,Object> validation = new HashMap<>();validation.put("hashVerified",true);
        if(requestHash!=null){validation.put("requestHash",requestHash);validation.put("branchVersionAfter",expectedVersion+1);validation.put("workbookVersionAfter",workbook.getVersion()==null?1L:workbook.getVersion()+1);}
        commit.put("validationJson", json(validation,"{}"));
        commit.put("snapshotId", snapshotId);
        versionMapper.insertCommit(commit);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("snapshotId", snapshotId);
        snapshot.put("workbookId", workbook.getWorkbookId());
        snapshot.put("commitId", commitId);
        snapshot.put("snapshotType", "full");
        snapshot.put("content", content.getBytes(StandardCharsets.UTF_8));
        snapshot.put("contentHash", afterHash);
        versionMapper.insertSnapshot(snapshot);
        int moved = versionMapper.updateBranchHead(workbook.getWorkbookId(), longValue(branch.get("branchId")),
                userId, expectedVersion, commitId);
        if (moved == 0) throw new IllegalStateException("分支版本冲突，提交事务已回滚");
        workbookMapper.updateVersionPointers(workbook.getWorkbookId(), userId, longValue(branch.get("branchId")),
                commitId, content, afterHash, username);
        versionMapper.deleteWorkingState(workbook.getWorkbookId(), longValue(branch.get("branchId")), userId);
        commit.put("created", true);
        commit.put("branchVersion", expectedVersion + 1);
        commit.put("workbookVersion", workbook.getVersion() == null ? 1L : workbook.getVersion() + 1);
        return commit;
    }

    private String draftBindingError(AiWorkbook book,Map<String,Object> draft,CommitRequest request) {
        return draftBindingError(book,draft,request.getExpectedDraftVersion(),request.getLoadedRevision());
    }

    private String draftBindingError(AiWorkbook book,Map<String,Object> draft,Long expected,String loaded) {
        if(expected==null || expected < -1 || loaded==null || loaded.isEmpty())return "缺少实际加载的草稿版本，请保留编辑并升级客户端";
        long actual=draft==null?-1L:longValue(draft.get("version"));
        String content=draft==null?book.getContent():stringValue(draft.get("content")),hash=ContentHashUtils.sha256(content);
        if(draft!=null && !hash.equals(stringValue(draft.get("contentHash"))))return "工作草稿完整性校验失败，请保留编辑并核实";
        if(expected!=actual || !hash.equals(loaded))return "草稿或页面加载内容已变化，请保留本地编辑并重新核实";
        return null;
    }

    private String validateCommitRequest(CommitRequest request, boolean allowEmptyChanges)
    {
        if (request == null) return "提交内容不能为空";
        if (request.getCommitMessage() == null || request.getCommitMessage().trim().isEmpty()) return "提交说明不能为空";
        if (request.getCommitMessage().trim().length() > 500) return "提交说明不能超过500个字符";
        if (request.getOperationId() == null || request.getOperationId().trim().isEmpty()) return "operationId 不能为空";
        if (request.getContent() == null) return "工作簿内容不能为空";
        if (!allowEmptyChanges && (request.getExecutedChanges() == null || request.getExecutedChanges().isEmpty())) return "没有实际变化，不能提交";
        return null;
    }

    private Map<String, Object> resolveBranch(Long workbookId, Long branchId, Long userId)
    {
        return branchId == null ? versionMapper.selectMainBranch(workbookId, userId)
                : versionMapper.selectBranch(workbookId, branchId, userId);
    }

    private Auth authWorkbook(Long workbookId)
    {
        try
        {
            Long userId = SecurityUtils.getUserId();
            // Current read before any consistent reads: avoids stale RR views.
            AiWorkbook workbook = workbookMapper.selectByIdForUpdate(workbookId, userId);
            if (workbook == null) return new Auth("工作簿不存在或无权访问");
            return new Auth(userId, SecurityUtils.getUsername(), workbook);
        }
        catch (Throwable e)
        {
            return new Auth("未登录或登录已过期");
        }
    }

    private String json(Object value, String fallback)
    {
        if (value == null) return fallback;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("版本数据无法序列化", e); }
    }

    private static Long longValue(Object value)
    {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private static String defaultValue(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
    private static boolean equalsNullable(String left, String right) { return left == null ? right == null : left.equals(right); }

    private static class Auth
    {
        private Long userId;
        private String username;
        private AiWorkbook workbook;
        private String error;
        private Auth(String error) { this.error = error; }
        private Auth(Long userId, String username, AiWorkbook workbook)
        { this.userId = userId; this.username = username; this.workbook = workbook; }
    }
}
