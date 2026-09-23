package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface WorkbookVersionMapper
{
    Map<String, Object> selectMainBranch(@Param("workbookId") Long workbookId, @Param("userId") Long userId);
    Map<String, Object> selectBranch(@Param("workbookId") Long workbookId, @Param("branchId") Long branchId, @Param("userId") Long userId);
    List<Map<String, Object>> selectBranches(@Param("workbookId") Long workbookId, @Param("userId") Long userId);
    Map<String, Object> selectBranchByName(@Param("workbookId") Long workbookId, @Param("branchName") String branchName, @Param("userId") Long userId);
    int insertBranch(Map<String, Object> branch);
    long selectNextCommitNo(@Param("workbookId") Long workbookId);
    Map<String, Object> selectCommitByOperation(@Param("workbookId") Long workbookId, @Param("operationId") String operationId);
    Map<String, Object> selectCommit(@Param("workbookId") Long workbookId, @Param("commitId") String commitId, @Param("userId") Long userId);
    List<Map<String, Object>> selectCommits(@Param("workbookId") Long workbookId, @Param("userId") Long userId);
    int insertCommit(Map<String, Object> commit);
    int insertSnapshot(Map<String, Object> snapshot);
    Map<String, Object> selectSnapshotByCommit(@Param("workbookId") Long workbookId, @Param("commitId") String commitId, @Param("userId") Long userId);
    int updateBranchHead(@Param("workbookId") Long workbookId, @Param("branchId") Long branchId,
                         @Param("userId") Long userId, @Param("expectedVersion") Long expectedVersion,
                         @Param("headCommitId") String headCommitId);
    int upsertWorkingState(Map<String, Object> state);
    Map<String, Object> selectWorkingState(@Param("workbookId") Long workbookId, @Param("branchId") Long branchId, @Param("userId") Long userId);
    int deleteWorkingState(@Param("workbookId") Long workbookId, @Param("branchId") Long branchId, @Param("userId") Long userId);
}
