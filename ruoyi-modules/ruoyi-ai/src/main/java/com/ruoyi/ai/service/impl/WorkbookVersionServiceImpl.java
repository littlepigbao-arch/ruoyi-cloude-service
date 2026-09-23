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
        Map<String, Object> workingState = versionMapper.selectWorkingState(workbookId, branchId, auth.userId);
        if (workingState != null && workingState.get("content") != null &&
                !ContentHashUtils.sha256(stringValue(workingState.get("content"))).equals(workingState.get("contentHash")))
            return R.fail(422, "工作草稿完整性校验失败");
        result.put("workingState", workingState);
        result.put("contentHash", auth.workbook.getContentHash());
        result.put("workbookVersion", auth.workbook.getVersion());
        return R.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> saveWorkingState(Long workbookId, WorkingStateRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        if (request == null || request.getContent() == null) return R.fail("工作草稿内容不能为空");
        Map<String, Object> branch = resolveBranch(workbookId, request.getBranchId(), auth.userId);
        if (branch == null) return R.fail("分支不存在或无权访问");
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
        versionMapper.upsertWorkingState(state);
        return R.ok(versionMapper.selectWorkingState(workbookId, longValue(branch.get("branchId")), auth.userId));
    }

    @Override
    public R<Void> deleteWorkingState(Long workbookId, Long branchId)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        Map<String, Object> branch = resolveBranch(workbookId, branchId, auth.userId);
        if (branch == null) return R.fail("分支不存在或无权访问");
        versionMapper.deleteWorkingState(workbookId, longValue(branch.get("branchId")), auth.userId);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> commit(Long workbookId, CommitRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        String validationError = validateCommitRequest(request, false);
        if (validationError != null) return R.fail(validationError);
        Map<String, Object> prior = versionMapper.selectCommitByOperation(workbookId, request.getOperationId());
        if (prior != null) return R.ok(prior, "重复请求已返回首次提交结果");
        Map<String, Object> branch = resolveBranch(workbookId, request.getBranchId(), auth.userId);
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
        Map<String, Object> created = createCommit(auth.workbook, branch, request.getCommitMessage().trim(),
                request.getOperationId(), defaultValue(request.getSource(), "ai"),
                json(request.getPlannedActions(), "[]"), json(request.getExecutedChanges(), "[]"),
                json(request.getInverseChanges(), null), json(request.getDiffSummary(), "{}"),
                json(request.getKnowledgeRefs(), "[]"), json(request.getDataContext(), "{}"),
                json(request.getExecutionContext(), "{}"), auth.userId, auth.username, version,
                request.getContent(), defaultValue(request.getActionSchemaVersion(), ACTION_SCHEMA_VERSION));
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
    public R<Map<String, Object>> checkout(Long workbookId, Long branchId, CheckoutRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        if (request == null || request.getOperationId() == null || request.getOperationId().trim().isEmpty())
            return R.fail("operationId 不能为空");
        if (request.getCurrentBranchId() != null && !request.getCurrentBranchId().equals(auth.workbook.getCurrentBranchId()))
            return R.fail(409, "当前分支已经变化，请重新加载");
        if (request.getBaseCommitId() != null && !equalsNullable(auth.workbook.getHeadCommitId(), request.getBaseCommitId()))
            return R.fail(409, "当前 HEAD 已经变化，请重新加载");
        Map<String, Object> branch = resolveBranch(workbookId, branchId, auth.userId);
        if (branch == null) return R.fail("目标分支不存在或无权访问");
        Long targetBranchId = longValue(branch.get("branchId"));
        String targetHead = stringValue(branch.get("headCommitId"));
        Map<String, Object> workingState = versionMapper.selectWorkingState(workbookId, targetBranchId, auth.userId);
        String content;
        String contentHash;
        if (workingState != null && workingState.get("content") != null)
        {
            content = stringValue(workingState.get("content"));
            contentHash = stringValue(workingState.get("contentHash"));
        }
        else
        {
            Map<String, Object> snapshot = versionMapper.selectSnapshotByCommit(workbookId, targetHead, auth.userId);
            if (snapshot == null) return R.fail("目标分支快照不存在");
            content = stringValue(snapshot.get("content"));
            contentHash = stringValue(snapshot.get("contentHash"));
        }
        if (!ContentHashUtils.sha256(content).equals(contentHash)) return R.fail(422, "目标分支内容完整性校验失败");
        workbookMapper.updateVersionPointers(workbookId, auth.userId, targetBranchId, targetHead, content, contentHash, auth.username);
        Map<String, Object> result = new HashMap<>(branch);
        result.put("content", content);
        result.put("contentHash", contentHash);
        result.put("workingState", workingState);
        result.put("workbookVersion", auth.workbook.getVersion() == null ? 1L : auth.workbook.getVersion() + 1L);
        return R.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> fastForwardToMain(Long workbookId, Long branchId, BranchIntegrateRequest request)
    {
        Auth auth = authWorkbook(workbookId);
        if (auth.error != null) return R.fail(auth.error);
        if (request == null || request.getOperationId() == null || request.getOperationId().trim().isEmpty())
            return R.fail("operationId 不能为空");
        Map<String, Object> draft = resolveBranch(workbookId, branchId, auth.userId);
        if (draft == null || "main".equals(draft.get("branchName"))) return R.fail("请选择有效的草稿分支");
        if (versionMapper.selectWorkingState(workbookId, branchId, auth.userId) != null)
            return R.fail("草稿分支还有未提交变化，请先提交");
        Map<String, Object> main = versionMapper.selectMainBranch(workbookId, auth.userId);
        Long mainVersion = longValue(main.get("version"));
        String mainHead = stringValue(main.get("headCommitId"));
        if (request.getMainVersion() == null || !mainVersion.equals(request.getMainVersion()) ||
                !equalsNullable(mainHead, request.getMainHeadCommitId()))
            return R.fail(409, "main 已发生变化，请重新加载后比较");
        String draftHead = stringValue(draft.get("headCommitId"));
        if (!isAncestor(workbookId, mainHead, draftHead, auth.userId))
            return R.fail(409, "main 与草稿已经分叉，必须进入 squash 和冲突选择流程，系统不会静默覆盖");
        Map<String, Object> snapshot = versionMapper.selectSnapshotByCommit(workbookId, draftHead, auth.userId);
        if (snapshot == null) return R.fail("草稿 HEAD 快照不存在");
        String content = stringValue(snapshot.get("content"));
        String hash = stringValue(snapshot.get("contentHash"));
        if (!ContentHashUtils.sha256(content).equals(hash)) return R.fail(422, "草稿快照完整性校验失败");
        int moved = versionMapper.updateBranchHead(workbookId, longValue(main.get("branchId")), auth.userId, mainVersion, draftHead);
        if (moved == 0) return R.fail(409, "main 版本冲突，请重新加载");
        workbookMapper.updateVersionPointers(workbookId, auth.userId, longValue(main.get("branchId")), draftHead,
                content, hash, auth.username);
        Map<String, Object> result = versionMapper.selectMainBranch(workbookId, auth.userId);
        result.put("content", content);
        result.put("contentHash", hash);
        result.put("workbookVersion", auth.workbook.getVersion() == null ? 1L : auth.workbook.getVersion() + 1L);
        return R.ok(result);
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
                workbook.getContent(), ACTION_SCHEMA_VERSION);
    }

    private Map<String, Object> createCommit(AiWorkbook workbook, Map<String, Object> branch, String message,
                                              String operationId, String source, String plannedJson,
                                              String changesJson, String inverseJson, String diffJson,
                                              String knowledgeJson, String dataJson, String executionJson,
                                              Long userId, String username, Long expectedVersion,
                                              String content, String schemaVersion)
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
        commit.put("validationJson", "{\"hashVerified\":true}");
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
        return commit;
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
            AiWorkbook workbook = workbookMapper.selectById(workbookId, userId);
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
