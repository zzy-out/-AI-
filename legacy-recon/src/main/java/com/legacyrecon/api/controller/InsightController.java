package com.legacyrecon.api.controller;

import com.legacyrecon.graph.AuditRecord;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.graph.Insight;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 04.3/3.4 洞察审核端点。PATCH 成功时 insightVersion+1，使 generation 缓存失效（R21）。
 */
@RestController
@RequestMapping("/api/v1/projects/{id}/insights")
public class InsightController {

    private final GraphStore graph;

    public InsightController(GraphStore graph) {
        this.graph = graph;
    }

    @GetMapping
    public List<Insight> list(@PathVariable("id") String projectId,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String kind,
                              @RequestParam(required = false) String entityId) {
        return graph.listInsights(projectId, status, kind, entityId);
    }

    public static class AuditRequest {
        /** approve | modify | reject | auto_approve | supersede */
        public String action;
        /** modify 时的内容 JSON 字符串 */
        public String content;
    }

    @PatchMapping("/{insightId}")
    public Map<String, Object> audit(@PathVariable("id") String projectId,
                                     @PathVariable String insightId,
                                     @RequestBody AuditRequest req) {
        AuditRecord.Action action;
        try {
            action = AuditRecord.Action.valueOf(req.action == null ? "approve" : req.action);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 action：" + req.action);
        }
        GraphStore.AuditResult r = graph.audit(projectId, insightId, action, "human", req.content);
        if (r == null) {
            throw new IllegalArgumentException("洞察不存在：" + insightId);
        }
        return Map.of("insightId", insightId, "status", r.insight.status.name(), "insightVersion", r.newInsightVersion);
    }
}