package com.ruoyi.ai.domain.dto;

/** 将草稿分支安全纳入 main 的并发基线。 */
public class BranchIntegrateRequest
{
    private String operationId;
    private String mainHeadCommitId;
    private Long mainVersion;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getMainHeadCommitId() { return mainHeadCommitId; }
    public void setMainHeadCommitId(String mainHeadCommitId) { this.mainHeadCommitId = mainHeadCommitId; }
    public Long getMainVersion() { return mainVersion; }
    public void setMainVersion(Long mainVersion) { this.mainVersion = mainVersion; }
}
