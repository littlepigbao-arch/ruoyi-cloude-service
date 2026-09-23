package com.ruoyi.ai.domain.dto;

/** 切换个人分支时的并发校验信息。 */
public class CheckoutRequest
{
    private String operationId;
    private Long currentBranchId;
    private String baseCommitId;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public Long getCurrentBranchId() { return currentBranchId; }
    public void setCurrentBranchId(Long currentBranchId) { this.currentBranchId = currentBranchId; }
    public String getBaseCommitId() { return baseCommitId; }
    public void setBaseCommitId(String baseCommitId) { this.baseCommitId = baseCommitId; }
}
