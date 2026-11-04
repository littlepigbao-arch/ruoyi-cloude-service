package com.ruoyi.ai.domain.dto;

/** Explicit discard of exactly the working draft loaded by the caller. */
public class DraftDeleteRequest {
    private Long branchId,baseVersion,expectedDraftVersion;
    private String baseCommitId,loadedRevision;
    private Boolean confirmed;
    public Long getBranchId(){return branchId;}
    public void setBranchId(Long value){branchId=value;}
    public Long getBaseVersion(){return baseVersion;}
    public void setBaseVersion(Long value){baseVersion=value;}
    public Long getExpectedDraftVersion(){return expectedDraftVersion;}
    public void setExpectedDraftVersion(Long value){expectedDraftVersion=value;}
    public String getBaseCommitId(){return baseCommitId;}
    public void setBaseCommitId(String value){baseCommitId=value;}
    public String getLoadedRevision(){return loadedRevision;}
    public void setLoadedRevision(String value){loadedRevision=value;}
    public Boolean getConfirmed(){return confirmed;}
    public void setConfirmed(Boolean value){confirmed=value;}
}
