package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.domain.dto.CommitRequest;
import com.ruoyi.ai.domain.dto.BranchRequest;
import com.ruoyi.ai.domain.dto.CheckoutRequest;
import com.ruoyi.ai.domain.dto.BranchIntegrateRequest;
import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.common.core.domain.R;
import java.util.List;
import java.util.Map;

public interface WorkbookVersionService
{
    void initializeWorkbook(AiWorkbook workbook, Long userId, String username);
    R<Map<String, Object>> status(Long workbookId);
    R<Map<String, Object>> saveWorkingState(Long workbookId, WorkingStateRequest request);
    R<Void> deleteWorkingState(Long workbookId, Long branchId);
    R<Map<String, Object>> commit(Long workbookId, CommitRequest request);
    R<List<Map<String, Object>>> commits(Long workbookId);
    R<Map<String, Object>> commitDetail(Long workbookId, String commitId);
    R<Map<String, Object>> snapshot(Long workbookId, String commitId);
    R<Map<String, Object>> restore(Long workbookId, String commitId, CommitRequest request);
    R<List<Map<String, Object>>> branches(Long workbookId);
    R<Map<String, Object>> createBranch(Long workbookId, BranchRequest request);
    R<Map<String, Object>> checkout(Long workbookId, Long branchId, CheckoutRequest request);
    R<Map<String, Object>> fastForwardToMain(Long workbookId, Long branchId, BranchIntegrateRequest request);
}
