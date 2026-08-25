package com.legacyrecon.api.controller;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.graph.GraphProjector;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.Relation;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 04.3 浏览端点：实体搜索、关系查询（含 externalTarget 外部依赖查询）、子图（图谱浏览器）。
 */
@RestController
@RequestMapping("/api/v1/projects/{id}")
public class BrowseController {

    private final FactsStore facts;
    private final GraphProjector graph;

    public BrowseController(FactsStore facts, GraphProjector graph) {
        this.facts = facts;
        this.graph = graph;
    }

    @GetMapping("/entities")
    public List<Entity> entities(@PathVariable("id") String projectId,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "100") int limit,
                                 @RequestParam(defaultValue = "0") int offset) {
        return facts.searchEntities(projectId, type, q, limit, offset);
    }

    @GetMapping("/relations")
    public List<Relation> relations(@PathVariable("id") String projectId,
                                    @RequestParam(required = false) String sourceId,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String externalTarget) {
        return facts.queryRelations(projectId, sourceId, type, externalTarget);
    }

    @GetMapping("/graph")
    public GraphProjector.Subgraph graph(@PathVariable("id") String projectId,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(defaultValue = "2") int depth) {
        return graph.subgraph(projectId, entityId, depth);
    }
}