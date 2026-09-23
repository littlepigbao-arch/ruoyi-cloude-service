package com.ruoyi.ai.domain.dto;

/** 创建个人草稿分支。 */
public class BranchRequest
{
    private String branchName;
    private String fromCommitId;
    private String operationId;

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getFromCommitId() { return fromCommitId; }
    public void setFromCommitId(String fromCommitId) { this.fromCommitId = fromCommitId; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
}
