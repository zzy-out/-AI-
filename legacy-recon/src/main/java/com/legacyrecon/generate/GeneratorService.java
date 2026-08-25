package com.legacyrecon.generate;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.generate.model.DocumentModel;
import com.legacyrecon.generate.model.Section;
import com.legacyrecon.graph.GraphProjector;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.Relation;
import com.legacyrecon.ucm.model.RelationType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 04.2 生成层：模板渲染、图表 DSL、Markdown 导出、RefValidator（R19）、导出产物管理。
 */
@Component
public class GeneratorService {

    private final FactsStore facts;
    private final GraphProjector graph;
    private final TemplateEngine templates;
    private final RefValidator refValidator;
    private final Map<String, byte[]> exports = new ConcurrentHashMap<>();
    private final Map<String, ExportArtifact> artifacts = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public GeneratorService(FactsStore facts, GraphProjector graph, TemplateEngine templates,
                            RefValidator refValidator) {
        this.facts = facts;
        this.graph = graph;
        this.templates = templates;
        this.refValidator = refValidator;
    }

    public static class ExportArtifact {
        public String exportId;
        public String projectId;
        public String runId;
        public DocType docType;
        public String format;
        public String title;
        public DocumentModel model;
        public String markdown;
        public List<String> validationFailures = new ArrayList<>();
    }

    /**
     * 生成文档：模板渲染 + 关系映射，绑定 entityRefs/evidenceRefs，最后过 RefValidator。
     * 阶段缓存（R21）：generation 输入哈希含 insightVersion 与 graph_mtime，由 pipeline 负责判重。
     */
    public ExportArtifact generate(String projectId, String runId, DocType docType, String format) {
        ExportArtifact art = new ExportArtifact();
        art.exportId = Long.toHexString(seq.incrementAndGet()) + "-" + Math.abs(runId.hashCode());
        art.projectId = projectId;
        art.runId = runId;
        art.docType = docType;
        art.format = format;

        List<Entity> entities = facts.searchEntities(projectId, null, null, 10000, 0);
        List<Entity> types = entities.stream()
                .filter(e -> e.type == com.legacyrecon.ucm.model.EntityType.Class
                        || e.type == com.legacyrecon.ucm.model.EntityType.Interface
                        || e.type == com.legacyrecon.ucm.model.EntityType.Enum
                        || e.type == com.legacyrecon.ucm.model.EntityType.Annotation)
                .toList();
        List<Relation> relations = graph.projectRelations(projectId);

        DocumentModel doc = new DocumentModel();
        doc.meta.projectId = projectId;
        doc.meta.runId = runId;
        doc.meta.generatedAt = Instant.now().toString();

        int i = 0;
        switch (docType) {
            case architecture -> {
                doc.meta.title = "架构说明文档（" + projectId + "）";
                doc.sections.add(architectureSection(++i, types, entities, relations));
            }
            case module -> {
                doc.meta.title = "模块说明文档";
                doc.sections.add(moduleSection(++i, types));
            }
            case api -> {
                doc.meta.title = "API 文档";
                doc.sections.add(apiSection(++i, types, entities));
            }
            case data_dict -> {
                doc.meta.title = "数据字典";
                doc.sections.add(dataDictSection(++i, entities));
            }
        }

        // 引用校验（R19）
        art.validationFailures.addAll(refValidator.validateDocument(projectId, runId, doc));
        art.model = doc;
        art.title = doc.meta.title;

        String md = renderMarkdown(doc);
        art.markdown = md;
        artifacts.put(art.exportId, art);
        exports.put(art.exportId, md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return art;
    }

    // ---------------- 模板段落 ----------------

    private Section architectureSection(int id, List<Entity> types, List<Entity> entities, List<Relation> relations) {
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entity t : types) {
            rows.add(Map.of("qualifiedName", t.qualifiedName, "type", t.type.name(), "doc", t.docComment == null ? "" : t.docComment));
        }
        data.put("types", rows);
        data.put("typeCount", rows.size());
        data.put("relationCount", relations.size());
        String content = templates.render(
                "## 总体架构\n\n本项目共 ${typeCount} 个类型、${relationCount} 条关系。\n\n" +
                        "| 类型 | 全限定名 | 说明 |\n|---|---|---|\n" +
                        "<#list types as t>| ${t.type} | `${t.qualifiedName}` | ${t.doc} |\n</#list>", data, "architecture");

        Section s = new Section();
        s.id = "sec-" + id;
        s.title = "总体架构";
        s.kind = "template";
        s.content = content;
        s.templateId = "project-overview";
        for (Entity t : types) {
            s.entityRefs.add(t.id);
            s.evidenceRefs.add(new com.legacyrecon.generate.model.EvidenceRef(t.id, t.location, t.docComment == null ? t.name : t.docComment));
        }
        return s;
    }

    private Section moduleSection(int id, List<Entity> types) {
        Map<String, Object> data = new HashMap<>();
        List<String> names = types.stream().map(t -> t.qualifiedName).toList();
        data.put("types", names);
        String content = templates.render(
                "## 模块说明\n\n本模块包含以下类型：\n\n<#list types as t>- `${t}`\n</#list>", data, "module");
        Section s = new Section();
        s.id = "sec-" + id;
        s.title = "模块说明";
        s.kind = "template";
        s.content = content;
        s.templateId = "module";
        return s;
    }

    private Section apiSection(int id, List<Entity> types, List<Entity> entities) {
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> methods = new ArrayList<>();
        for (Entity t : types) {
            for (Entity m : entities) {
                if ((m.type == com.legacyrecon.ucm.model.EntityType.Method
                        || m.type == com.legacyrecon.ucm.model.EntityType.Constructor)
                        && m.qualifiedName.startsWith(t.qualifiedName + ".")) {
                    methods.add(Map.of("owner", t.qualifiedName, "sig", m.qualifiedName + "  " + (m.signature == null ? "" : m.signature),
                            "doc", m.docComment == null ? "" : m.docComment));
                }
            }
        }
        data.put("methods", methods);
        String content = templates.render(
                "## API 文档\n\n<#list methods as m>- **${m.sig}** — ${m.doc}\n</#list>", data, "api");
        Section s = new Section();
        s.id = "sec-" + id;
        s.title = "API";
        s.kind = "template";
        s.content = content;
        s.templateId = "api";
        return s;
    }

    private Section dataDictSection(int id, List<Entity> entities) {
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Entity m : entities) {
            if (m.type == com.legacyrecon.ucm.model.EntityType.Field) {
                fields.add(Map.of("qualifiedName", m.qualifiedName,
                        "type", m.typeRef == null ? "" : m.typeRef.name));
            }
        }
        data.put("fields", fields);
        String content = templates.render(
                "## 数据字典\n\n| 字段 | 类型 |\n|---|---|\n<#list fields as f>| `${f.qualifiedName}` | `${f.type}` |\n</#list>", data, "data_dict");
        Section s = new Section();
        s.id = "sec-" + id;
        s.title = "数据字典";
        s.kind = "template";
        s.content = content;
        s.templateId = "data_dict";
        return s;
    }

    private String renderMarkdown(DocumentModel doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.meta.title).append("\n\n");
        sb.append("> 项目 `").append(doc.meta.projectId).append("` · runId `").append(doc.meta.runId)
                .append("` · 生成于 ").append(doc.meta.generatedAt).append(" · 生成器 v").append(doc.meta.generatorVersion).append("\n\n");
        for (Section s : doc.sections) {
            sb.append(s.content).append("\n\n");
            if (!s.entityRefs.isEmpty()) {
                sb.append("__引用实体：__ ");
                for (String ref : s.entityRefs) {
                    sb.append("`").append(shortName(ref)).append("` ");
                }
                sb.append("\n");
            }
            if (s.validationError != null) {
                sb.append("> ⚠ 未通过校验的引用：\n").append("> ```\n").append(s.validationError).append("\n> ```\n\n");
            }
            sb.append("\n");
        }
        if (doc.sections.stream().anyMatch(x -> x.validationError != null)) {
            sb.append("## 附录：未通过校验的引用清单\n\n");
            for (Section s : doc.sections) {
                if (s.validationError != null) {
                    sb.append("- ").append(s.id).append("：\n  ").append(s.validationError.replace("\n", "\n  ")).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String shortName(String id) {
        int idx = id.lastIndexOf('.');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    public ExportArtifact getArtifact(String exportId) {
        return artifacts.get(exportId);
    }

    public byte[] getExportBytes(String exportId) {
        return exports.get(exportId);
    }

    /** 类图/调用图独立产物（供图谱浏览器）。 */
    public String diagram(String projectId, String diagramType) {
        List<Relation> relations = graph.projectRelations(projectId);
        List<Entity> entities = facts.searchEntities(projectId, null, null, 10000, 0);
        if ("call".equals(diagramType)) {
            int skipped = skippedTemplates(projectId);
            return ChartMapper.callGraph(relations, skipped, 200);
        }
        return ChartMapper.classDiagram(entities, relations, 200);
    }

    private int skippedTemplates(String projectId) {
        // R12：模板实例化跳过计数由解析 stats 提供；脚手架读取最近 run 的 stats（图形层投影时预留），此处返回 0。
        return 0;
    }
}