package com.legacyrecon.api.controller;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.facts.Project;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.parser.api.ProjectConfig;
import com.legacyrecon.pipeline.FileScanner;
import com.legacyrecon.util.Json;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

/**
 * 04.3 项目管理生命周期端点（R22）：导入、查询、配置更新、软删除、归档。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final FactsStore facts;
    private final GraphStore graph;

    public ProjectController(FactsStore facts, GraphStore graph) {
        this.facts = facts;
        this.graph = graph;
    }

    public static class ImportRequest {
        public String root;
        public String name;
        public String language = "java";
        public ProjectConfig config;
    }

    @PostMapping
    public Map<String, Object> importProject(@RequestBody ImportRequest req) {
        Path root = Path.of(req.root).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(root)) {
            throw new IllegalArgumentException("root 不是有效目录：" + req.root);
        }
        String id = "p-" + UUID.randomUUID().toString().substring(0, 8);
        List<Path> files = FileScanner.listSourceFiles(root, req.language);
        ProjectConfig cfg = req.config != null ? req.config : new ProjectConfig();
        Map<String, Object> configJson = Json.fromJson(Json.toJson(cfg), LinkedHashMap.class);
        configJson.put("language", req.language);
        configJson.put("archived", false);
        facts.upsertProject(new Project(id, req.name == null ? root.getFileName().toString() : req.name,
                root.toString(), Json.toJson(configJson)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", req.name == null ? root.getFileName().toString() : req.name);
        out.put("rootPath", root.toString());
        out.put("language", req.language);
        out.put("fileCount", files.size());
        out.put("config", cfg);
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Project p = requireProject(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id);
        out.put("name", p.name);
        out.put("rootPath", p.rootPath);
        out.put("configJson", p.configJson);
        out.put("deleted", p.deletedAt != null);
        out.put("archived", p.archived());
        return out;
    }

    @GetMapping
    public List<Project> list() {
        return facts.listProjects().stream().filter(p -> p.deletedAt == null).toList();
    }

    @PutMapping("/{id}/config")
    public Map<String, Object> updateConfig(@PathVariable String id, @RequestBody ProjectConfig cfg) {
        Project p = requireProject(id);
        Map<String, Object> configJson = Json.fromJson(Json.toJson(cfg), LinkedHashMap.class);
        if (p.configJson != null) {
            Object archived = Json.fromJson(p.configJson, LinkedHashMap.class).get("archived");
            if (archived != null) {
                configJson.put("archived", archived);
            }
        }
        facts.updateConfig(id, Json.toJson(configJson));
        return Map.of("id", id, "updated", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        facts.softDelete(id);
        graph.cleanupProject(id);
        return Map.of("id", id, "deleted", true);
    }

    /** 归档：只读，禁止新 run；产物保留（R22）。 */
    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id) {
        Project p = requireProject(id);
        Map<String, Object> configJson = p.configJson == null
                ? new LinkedHashMap<>() : Json.fromJson(p.configJson, LinkedHashMap.class);
        configJson.put("archived", true);
        facts.updateConfig(id, Json.toJson(configJson));
        return Map.of("id", id, "archived", true);
    }

    Project requireProject(String id) {
        Project p = facts.getProject(id);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        return p;
    }
}