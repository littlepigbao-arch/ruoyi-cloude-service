package com.ruoyi.ai.domain.dto;

/** 分支自动保存草稿。 */
public class WorkingStateRequest
{
    private Long branchId;
    private String baseCommitId;
    private Long baseVersion;
    private String content;
    private Object plannedActions;
    private Object executedChanges;

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getBaseCommitId() { return baseCommitId; }
    public void setBaseCommitId(String baseCommitId) { this.baseCommitId = baseCommitId; }
    public Long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Long baseVersion) { this.baseVersion = baseVersion; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Object getPlannedActions() { return plannedActions; }
    public void setPlannedActions(Object plannedActions) { this.plannedActions = plannedActions; }
    public Object getExecutedChanges() { return executedChanges; }
    public void setExecutedChanges(Object executedChanges) { this.executedChanges = executedChanges; }
}
