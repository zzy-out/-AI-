package com.legacyrecon.api.controller;

import com.legacyrecon.pipeline.PipelineService;
import com.legacyrecon.pipeline.PipelineService.RunRequest;
import com.legacyrecon.pipeline.PipelineService.RunResult;
import com.legacyrecon.facts.FactsStore;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 04.3 运行端点：触发管道、查询运行状态与统计。runs 支持 clientRequestId 幂等。
 */
@RestController
@RequestMapping("/api/v1/projects/{id}/runs")
public class RunController {

    private final PipelineService pipeline;
    private final FactsStore facts;

    public RunController(PipelineService pipeline, FactsStore facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @PostMapping
    public RunResult run(@PathVariable String id, @RequestBody(required = false) RunRequest req) {
        RunRequest r = req == null ? new RunRequest() : req;
        if (r.stages == null || r.stages.isEmpty()) {
            r.stages = java.util.List.of("parse", "enrich", "generate");
        }
        return pipeline.run(id, r);
    }

    @GetMapping
    public java.util.List<java.util.Map<String, Object>> list(@PathVariable String id,
                                                              @RequestParam(defaultValue = "50") int limit) {
        facts.getProject(id); // 项目不存在时抛 IllegalArgumentException
        return facts.listRuns(id, limit);
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable String id, @PathVariable String runId) {
        Map<String, Object> run = facts.getRun(id, runId);
        if (run == null) {
            throw new IllegalArgumentException("运行不存在：" + runId);
        }
        return run;
    }
}