package com.ruoyi.ai.controller;

import com.ruoyi.ai.agent.ExcelImportService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C3 workbook import endpoints. Authorization is content-addressed: the upload itself creates the
 * job keyed by the sha256 of the received bytes; follow-up data reads must present that hash. No
 * endpoint accepts a file path or filesystem reference. The preview response is bounded by design
 * (metadata + limited rows, never the full source workbook).
 */
@RestController
@RequestMapping("/ai/agent/import")
public class WorkbookImportController {
    private final ExcelImportService imports;
    public WorkbookImportController(ExcelImportService imports){this.imports=imports;}
    @PostMapping("/jobs") public R<?> create(@RequestParam("file") MultipartFile file,@RequestParam String requestId) throws IOException {
        ObjectNode request=obj().put("requestId",requestId).put("fileName",file.getOriginalFilename()==null?"import.xlsx":file.getOriginalFilename());
        return R.ok(imports.create(request,SecurityUtils.getUserId(),file.getBytes()));
    }
    @GetMapping("/jobs/{id}") public R<?> view(@PathVariable String id){return R.ok(imports.view(id,SecurityUtils.getUserId()));}
    @GetMapping("/jobs") public R<?> byRequest(@RequestParam String requestId){return R.ok(imports.findByRequestId(requestId,SecurityUtils.getUserId()));}
    /** Bounded preview: metadata plus at most {@code rows} row records (1..200) - never the full source. */
    @GetMapping("/jobs/{id}/preview") public R<?> preview(@PathVariable String id,@RequestParam String sourceHash,@RequestParam(defaultValue="50") int rows){
        return R.ok(imports.preview(id,SecurityUtils.getUserId(),sourceHash,rows));
    }
    @ExceptionHandler(IllegalArgumentException.class) public R<?> badRequest(IllegalArgumentException e){return R.fail(409,e.getMessage());}
}
