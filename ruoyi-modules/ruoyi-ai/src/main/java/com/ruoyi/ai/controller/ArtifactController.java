package com.ruoyi.ai.controller;

import com.ruoyi.ai.agent.data.ArtifactService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;

/** C1a read-only artifact endpoints: metadata plus one-chunk-per-request keyset paging; no full-content endpoint. */
@RestController
@RequestMapping("/ai/agent")
public class ArtifactController {
    private final ArtifactService artifacts;
    public ArtifactController(ArtifactService artifacts){this.artifacts=artifacts;}
    @GetMapping("/artifacts/{id}") public R<?> metadata(@PathVariable String id){return R.ok(artifacts.readArtifactMetadata(id,SecurityUtils.getUserId()));}
    @GetMapping("/artifacts/{id}/chunks") public R<?> chunks(@PathVariable String id,@RequestParam(required=false) Long cursor){return R.ok(artifacts.readChunkPage(id,SecurityUtils.getUserId(),cursor));}
    @ExceptionHandler(IllegalArgumentException.class) public R<?> badRequest(IllegalArgumentException e){return R.fail(409,e.getMessage());}
}
