package com.ruoyi.ai.controller;

import com.ruoyi.ai.domain.dto.CommitRequest;
import com.ruoyi.ai.domain.dto.BranchRequest;
import com.ruoyi.ai.domain.dto.CheckoutRequest;
import com.ruoyi.ai.domain.dto.BranchIntegrateRequest;
import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.ai.service.WorkbookVersionService;
import com.ruoyi.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai/workbook/{workbookId}")
public class WorkbookVersionController
{
    @Autowired
    private WorkbookVersionService versionService;

    @GetMapping("/status")
    public R<Map<String, Object>> status(@PathVariable Long workbookId) { return versionService.status(workbookId); }

    @PutMapping("/working-state")
    public R<Map<String, Object>> saveWorkingState(@PathVariable Long workbookId, @RequestBody WorkingStateRequest request)
    { return versionService.saveWorkingState(workbookId, request); }

    @DeleteMapping("/working-state")
    public R<Void> deleteWorkingState(@PathVariable Long workbookId, @RequestParam(required = false) Long branchId)
    { return versionService.deleteWorkingState(workbookId, branchId); }

    @PostMapping("/commits")
    public R<Map<String, Object>> commit(@PathVariable Long workbookId, @RequestBody CommitRequest request)
    { return versionService.commit(workbookId, request); }

    @GetMapping("/commits")
    public R<List<Map<String, Object>>> commits(@PathVariable Long workbookId) { return versionService.commits(workbookId); }

    @GetMapping("/commits/{commitId}")
    public R<Map<String, Object>> commitDetail(@PathVariable Long workbookId, @PathVariable String commitId)
    { return versionService.commitDetail(workbookId, commitId); }

    @GetMapping("/versions/{commitId}/snapshot")
    public R<Map<String, Object>> snapshot(@PathVariable Long workbookId, @PathVariable String commitId)
    { return versionService.snapshot(workbookId, commitId); }

    @PostMapping("/restore/{commitId}")
    public R<Map<String, Object>> restore(@PathVariable Long workbookId, @PathVariable String commitId,
                                          @RequestBody CommitRequest request)
    { return versionService.restore(workbookId, commitId, request); }

    @GetMapping("/branches")
    public R<List<Map<String, Object>>> branches(@PathVariable Long workbookId)
    { return versionService.branches(workbookId); }

    @PostMapping("/branches")
    public R<Map<String, Object>> createBranch(@PathVariable Long workbookId, @RequestBody BranchRequest request)
    { return versionService.createBranch(workbookId, request); }

    @PostMapping("/branches/{branchId}/checkout")
    public R<Map<String, Object>> checkout(@PathVariable Long workbookId, @PathVariable Long branchId,
                                           @RequestBody CheckoutRequest request)
    { return versionService.checkout(workbookId, branchId, request); }

    @PostMapping("/branches/{branchId}/fast-forward-to-main")
    public R<Map<String, Object>> fastForwardToMain(@PathVariable Long workbookId, @PathVariable Long branchId,
                                                     @RequestBody BranchIntegrateRequest request)
    { return versionService.fastForwardToMain(workbookId, branchId, request); }
}
