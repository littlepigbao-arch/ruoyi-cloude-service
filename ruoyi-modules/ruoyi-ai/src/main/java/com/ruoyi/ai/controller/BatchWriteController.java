package com.ruoyi.ai.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.BatchWriteService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;

/** C2 read/verify endpoints: batch journal with actual progress, and server-authoritative reconcile. */
@RestController
@RequestMapping("/ai/agent")
public class BatchWriteController {
    private final BatchWriteService batches;
    public BatchWriteController(BatchWriteService batches){this.batches=batches;}
    @GetMapping("/effects/{effectId}/batches") public R<?> batches(@PathVariable String effectId){return R.ok(batches.view(effectId,SecurityUtils.getUserId()));}
    /** Reconcile only verifies UNKNOWN batches against server state; a client can never declare a rollback here. */
    @PostMapping("/effects/{effectId}/reconcile") public R<?> reconcile(@PathVariable String effectId,@RequestBody ObjectNode req){return R.ok(batches.reconcile(effectId,SecurityUtils.getUserId(),req));}
    @ExceptionHandler(IllegalArgumentException.class) public R<?> badRequest(IllegalArgumentException e){return R.fail(409,e.getMessage());}
}
