package com.legacyrecon.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.legacyrecon.enrich.EnrichmentService;
import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.facts.Project;
import com.legacyrecon.generate.DocType;
import com.legacyrecon.generate.GeneratorService;
import com.legacyrecon.generate.GeneratorService.ExportArtifact;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.parser.api.*;
import com.legacyrecon.ucm.model.ParseResult;
import com.legacyrecon.ucm.validator.UcmValidator;
import com.legacyrecon.util.Json;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 01-04 五层管道编排：parse → enrich → generate。
 * 单向数据流 + 反馈回流（R21：generation 输入哈希含 insightVersion 与 graph_mtime）。
 */
@Service
public class PipelineService {

    private final FactsStore facts;
    private final GraphStore graph;
    private final IncrementalAnalyzer analyzer;
    private final LanguageParser parser;
    private final EnrichmentService enrichment;
    private final GeneratorService generator;
    private final ApplicationEventPublisher events;
    private final UcmValidator validator = new UcmValidator();

    /** 阶段缓存：projectId -> 上次 generate 输入哈希（R21） */
    private final Map<String, String> genCacheHash = new ConcurrentHashMap<>();
    private final Map<String, ExportArtifact> cachedGen = new ConcurrentHashMap<>();

    public PipelineService(FactsStore facts, GraphStore graph, IncrementalAnalyzer analyzer,
                           LanguageParser parser, EnrichmentService enrichment,
                           GeneratorService generator, ApplicationEventPublisher events) {
        this.facts = facts;
        this.graph = graph;
        this.analyzer = analyzer;
        this.parser = parser;
        this.enrichment = enrichment;
        this.generator = generator;
        this.events = events;
    }

    // ---------------- 项目导入 ----------------

    public Project importProject(String id, String name, String rootPath, ProjectConfig config) {
        Project p = new Project(id, name, rootPath, Json.toJson(config));
        facts.upsertProject(p);
        return p;
    }

    // ---------------- 管道运行 ----------------

    public static class RunRequest {
        public List<String> stages = List.of("parse", "enrich", "generate");
        public boolean forceFull;
        public String clientRequestId;
    }

    public static class RunResult {
        public String runId;
        public String projectId;
        public String status;
        public Map<String, String> stageErrors = new LinkedHashMap<>();
        public int entityCount;
        public int relationCount;
        public int issueCount;
        public ExportArtifact artifact;
    }

    public RunResult run(String projectId, RunRequest req) {
        RunResult result = new RunResult();
        String runId = UUID.randomUUID().toString();
        result.runId = runId;
        result.projectId = projectId;
        String trigger = req.forceFull ? "full" : "incremental";

        Project project = facts.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }
        ProjectConfig config = Json.fromJson(project.configJson, ProjectConfig.class);
        String lang = lang(config);

        facts.insertRun(runId, projectId, trigger, Json.toJson(Map.of()),
                "running");
        emit(type("stage_started"), projectId, runId).stage("parse");

        try {
            // ===== parse =====
            Path root = Path.of(project.rootPath);
            Map<String, String> sNew = new LinkedHashMap<>();
            for (Path rel : FileScanner.listSourceFiles(root, lang)) {
                sNew.put(rel.toString(), FileScanner.checksum(root, rel));
            }
            IncrementalAnalyzer.Plan plan = analyzer.plan(projectId, sNew, req.forceFull,
                    IncrementalAnalyzer.FallbackStrategy.file_ratio, 0.2, 0.3);

            ParseRequest parseReq = new ParseRequest();
            parseReq.workingDir = root;
            parseReq.config = config;
            for (String path : plan.affectedFiles) {
                parseReq.files.add(new SourceFile(path, sNew.get(path), FileScanner.read(root, Path.of(path))));
            }
            List<String> deleted = new ArrayList<>(plan.deletedFiles);
            long t0 = System.nanoTime();
            ParseResult pr = parser.parse(parseReq);
            parseReq.files.addAll(deleted.stream().map(d -> new SourceFile(d, null, null)).toList());

            // 校验（01.8）
            var validation = validator.validate(pr, UcmValidator.SCHEMA_VERSION);
            long durMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
            pr.issues.addAll(validation.issues);

            // 事务入库（02.4 步骤 7）
            facts.mergeBatch(projectId, runId, sNew, plan.affectedFiles, plan.deletedFiles,
                    pr.entities, pr.relations, pr.issues);
            result.entityCount = pr.entities.size();
            result.relationCount = pr.relations.size();
            result.issueCount = pr.issues.size();

            events.publishEvent(emit(type("stage_finished"), projectId, runId).stage("parse")
                    .payload(Map.of("entities", pr.entities.size(), "relations", pr.relations.size(),
                            "affected", plan.affectedFiles.size(), "deleted", deleted.size(),
                            "durationMs", durMs)));

            // ===== enrich =====
            if (req.stages.contains("enrich")) {
                events.publishEvent(emit(type("stage_started"), projectId, runId).stage("enrich"));
                var summary = enrichment.enrich(projectId);
                events.publishEvent(emit(type("stage_finished"), projectId, runId).stage("enrich")
                        .payload(Map.of("ruleGenerated", summary.ruleGenerated, "llmGenerated", summary.llmGenerated,
                                "superseded", summary.superseded)));
                if (summary.llmGenerated > 0) {
                    events.publishEvent(emit(type("insight_pending"), projectId, runId)
                            .payload(Map.of("count", summary.llmGenerated)));
                }
            }

            // ===== generate =====
            if (req.stages.contains("generate")) {
                result.artifact = generateWithCache(project, runId, lang);
                if (result.artifact != null) {
                    events.publishEvent(emit(type("export_ready"), projectId, runId)
                            .payload(Map.of("exportId", result.artifact.exportId, "format", result.artifact.format)));
                }
            }

            result.status = "success";
            facts.updateRun(runId, "success", Json.toJson(Map.of(
                    "entityCount", result.entityCount, "relationCount", result.relationCount,
                    "issueCount", result.issueCount, "trigger", trigger,
                    "affected", plan.affectedFiles.size())));
            events.publishEvent(emit(type("run_completed"), projectId, runId)
                    .payload(Map.of("status", "success", "stats", result)));
            return result;
        } catch (Exception e) {
            result.status = "failed";
            result.stageErrors.put("parse", e.getMessage());
            facts.updateRun(runId, "failed", null);
            events.publishEvent(emit(type("stage_error"), projectId, runId).stage(req.stages.isEmpty() ? "pipeline" : req.stages.get(0))
                    .message(e.getMessage(), "PIPELINE.ERROR"));
            return result;
        }
    }

    /**
     * generate 阶段 + 缓存（R21）：输入哈希 = 图谱最后修改时间 + insightVersion + 配置。
     * 审核洞察或编辑图谱后哈希必然变化，缓存失效触发重算。
     */
    private ExportArtifact generateWithCache(Project project, String runId, String lang) {
        String mtime = graph.getGraphMtime(project.id);
        int version = graph.getInsightVersion(project.id);
        String inputHash = Integer.toHexString(((mtime == null ? "" : mtime) + "|" + version + "|" + lang).hashCode());
        if (inputHash.equals(genCacheHash.get(project.id))) {
            ExportArtifact cached = cachedGen.get(project.id);
            if (cached != null) {
                cached.runId = runId;
                return cached;
            }
        }
        ExportArtifact art = generator.generate(project.id, runId, DocType.architecture, "md");
        genCacheHash.put(project.id, inputHash);
        cachedGen.put(project.id, art);
        return art;
    }

    private String lang(ProjectConfig config) {
        // MVP 为 Java 闭环（阶段 1）；C/C++（Clang 子进程）由 LanguageParser 路由扩展。
        return "java";
    }

    private PipelineStageEvent emit(PipelineStageEvent.Type type, String projectId, String runId) {
        return new PipelineStageEvent(type, projectId, runId);
    }

    private static PipelineStageEvent.Type type(String name) {
        return PipelineStageEvent.Type.valueOf(name);
    }

    /** 手动重跑某个阶段（供 API 迭代触发）。 */
    public RunResult trigger(String projectId, RunRequest req) {
        return run(projectId, req);
    }
}