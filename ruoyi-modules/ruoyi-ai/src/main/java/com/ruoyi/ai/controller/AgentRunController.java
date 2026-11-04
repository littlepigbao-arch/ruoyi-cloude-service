package com.ruoyi.ai.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.AgentRunService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/agent")
public class AgentRunController {
    private final AgentRunService service;
    public AgentRunController(AgentRunService service){this.service=service;}
    @GetMapping("/capabilities") public R<?> capabilities(){java.util.Map<String,Object> c=new java.util.HashMap<>();c.put("enabled",service.enabled());c.put("recoveryAvailable",service.recoveryAvailable());c.put("snapshotWritesEnabled",false);c.put("snapshotRiskStatus","BLOCKED_NOT_FIXED");c.put("controlledWritesEnabled",service.controlledWritesEnabled());c.put("ordinaryAiControlledEnabled",service.controlledWritesEnabled());c.put("controlledActions",com.ruoyi.ai.agent.WriteManifest.availableActions(service.formatActionsEnabled(),service.filterActionsEnabled(),service.formulaActionsEnabled(),service.mergeActionsEnabled(),service.structureActionsEnabled(),service.sheetActionsEnabled(),service.sheetRenameActionsEnabled(),service.sheetCopyActionsEnabled(),service.sheetDeleteActionsEnabled()));c.put("controlledClearModes",java.util.Arrays.asList("content"));c.put("scalarAliasSingleCellOnly",true);c.put("scalarAliasNullSupported",false);c.put("controlledMaxCells",1000);c.put("controlledWriteTransport",2);c.put("creationIdentityLookup",true);c.put("resultWritesEnabled",service.resultWritesEnabled());c.put("resultSheetWritesEnabled",service.resultSheetWritesEnabled());c.put("readDisplayEnabled",service.readDisplayEnabled());c.put("agentTools",service.availableTools());return R.ok(c);}
    @PostMapping("/controlled-writes") public R<?> controlledCreate(@RequestBody ObjectNode req){return R.ok(service.createControlled(req,SecurityUtils.getUserId()));}
    @GetMapping("/workspace/{id}/result-artifacts") public R<?> resultArtifacts(@PathVariable long id){return R.ok(service.resultArtifacts(id,SecurityUtils.getUserId()));}
    @GetMapping("/action-capabilities") public R<?> actionCapabilities(){java.util.Map<String,Object> c=new java.util.HashMap<>();c.put("controlledValues",com.ruoyi.ai.agent.WriteManifest.VALUE_ACTIONS);c.put("readDisplay",com.ruoyi.ai.agent.WriteManifest.READ_DISPLAY_ACTIONS);c.put("otherWrites","blocked");c.put("mixedGroups","blocked");return R.ok(c);}
    @PostMapping("/runs/{id}/controlled-claims") public R<?> controlledClaim(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.claimControlled(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/controlled-results") public R<?> controlledResults(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.receiptControlled(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/controlled-uncertain") public R<?> controlledUncertain(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.uncertainControlled(id,SecurityUtils.getUserId(),req));}
    @GetMapping("/workspace/{id}") public R<?> workspace(@PathVariable long id){return R.ok(service.workspace(id,SecurityUtils.getUserId()));}
    @PostMapping("/runs") public R<?> create(@RequestBody ObjectNode req){return R.ok(service.create(req,SecurityUtils.getUserId()));}
    @PostMapping("/runs/resolve-create") public R<?> resolveCreate(@RequestBody ObjectNode req){return R.ok(service.resolveCreation(req,SecurityUtils.getUserId()));}
    @GetMapping("/runs/active") public R<?> active(@RequestParam long workbookId){return R.ok(service.active(workbookId,SecurityUtils.getUserId()));}
    @GetMapping("/runs/{id}") public R<?> get(@PathVariable String id){return R.ok(service.get(id,SecurityUtils.getUserId()));}
    @GetMapping("/runs/{id}/read-display-results") public R<?> readDisplayResults(@PathVariable String id){return R.ok(service.readDisplayResults(id,SecurityUtils.getUserId()));}
    @GetMapping("/runs/{id}/events") public R<?> events(@PathVariable String id,@RequestParam(defaultValue="0") long after){return R.ok(service.events(id,SecurityUtils.getUserId(),after));}
    @PostMapping("/runs/{id}/tool-claims") public R<?> claim(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.claim(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/tool-results") public R<?> results(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.receipt(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/lease-heartbeat") public R<?> heartbeat(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.heartbeat(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/cancel") public R<?> cancel(@PathVariable String id){return R.ok(service.cancel(id,SecurityUtils.getUserId()));}
    @PostMapping("/runs/{id}/resume") public R<?> resume(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.resume(id,SecurityUtils.getUserId(),req));}
    @PostMapping("/runs/{id}/answers") public R<?> answer(@PathVariable String id,@RequestBody ObjectNode req){return R.ok(service.answer(id,SecurityUtils.getUserId(),req));}
    @ExceptionHandler(IllegalArgumentException.class) public R<?> badRequest(IllegalArgumentException e){return R.fail(409,e.getMessage());}
}
