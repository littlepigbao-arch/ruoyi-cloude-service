package com.ruoyi.ai.domain.dto;

import java.util.List;
import java.util.Map;

/** 创建工作簿提交请求。 */
public class CommitRequest
{
    private Long branchId;
    private String commitMessage;
    private String operationId;
    private String baseCommitId;
    private Long baseVersion;
    private String content;
    private String source;
    private String actionSchemaVersion;
    private Object plannedActions;
    private List<Map<String, Object>> executedChanges;
    private Object inverseChanges;
    private Object diffSummary;
    private Object knowledgeRefs;
    private Object dataContext;
    private Object executionContext;

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getCommitMessage() { return commitMessage; }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getBaseCommitId() { return baseCommitId; }
    public void setBaseCommitId(String baseCommitId) { this.baseCommitId = baseCommitId; }
    public Long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Long baseVersion) { this.baseVersion = baseVersion; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getActionSchemaVersion() { return actionSchemaVersion; }
    public void setActionSchemaVersion(String actionSchemaVersion) { this.actionSchemaVersion = actionSchemaVersion; }
    public Object getPlannedActions() { return plannedActions; }
    public void setPlannedActions(Object plannedActions) { this.plannedActions = plannedActions; }
    public List<Map<String, Object>> getExecutedChanges() { return executedChanges; }
    public void setExecutedChanges(List<Map<String, Object>> executedChanges) { this.executedChanges = executedChanges; }
    public Object getInverseChanges() { return inverseChanges; }
    public void setInverseChanges(Object inverseChanges) { this.inverseChanges = inverseChanges; }
    public Object getDiffSummary() { return diffSummary; }
    public void setDiffSummary(Object diffSummary) { this.diffSummary = diffSummary; }
    public Object getKnowledgeRefs() { return knowledgeRefs; }
    public void setKnowledgeRefs(Object knowledgeRefs) { this.knowledgeRefs = knowledgeRefs; }
    public Object getDataContext() { return dataContext; }
    public void setDataContext(Object dataContext) { this.dataContext = dataContext; }
    public Object getExecutionContext() { return executionContext; }
    public void setExecutionContext(Object executionContext) { this.executionContext = executionContext; }
}
