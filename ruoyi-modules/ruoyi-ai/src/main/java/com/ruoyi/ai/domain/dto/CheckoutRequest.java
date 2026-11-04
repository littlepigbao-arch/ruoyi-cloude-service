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
    private Long baseVersion;
    public Long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Long value) { this.baseVersion = value; }
    private Long expectedDraftVersion;
    public Long getExpectedDraftVersion() { return expectedDraftVersion; }
    public void setExpectedDraftVersion(Long value) { this.expectedDraftVersion = value; }
    private String loadedRevision;
    public String getLoadedRevision() { return loadedRevision; }
    public void setLoadedRevision(String value) { this.loadedRevision = value; }
    private Long expectedWorkbookVersion;
    public Long getExpectedWorkbookVersion() { return expectedWorkbookVersion; }
    public void setExpectedWorkbookVersion(Long value) { this.expectedWorkbookVersion = value; }
    private Long targetVersion;
    public Long getTargetVersion() { return targetVersion; }
    public void setTargetVersion(Long value) { this.targetVersion = value; }
    private String targetHeadCommitId;
    public String getTargetHeadCommitId() { return targetHeadCommitId; }
    public void setTargetHeadCommitId(String value) { this.targetHeadCommitId = value; }
    private Long expectedTargetDraftVersion;
    public Long getExpectedTargetDraftVersion() { return expectedTargetDraftVersion; }
    public void setExpectedTargetDraftVersion(Long value) { this.expectedTargetDraftVersion = value; }
    private String targetLoadedRevision;
    public String getTargetLoadedRevision() { return targetLoadedRevision; }
    public void setTargetLoadedRevision(String value) { this.targetLoadedRevision = value; }
}
