package com.legacyrecon.generate;

import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.Relation;
import com.legacyrecon.ucm.model.RelationType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 04.2 图表 DSL：UCM 关系 → Mermaid / PlantUML。节点超限时由调用方聚类折叠。
 */
public final class ChartMapper {

    private ChartMapper() {
    }

    /**
     * 类图：INHERITS + IMPLEMENTS + CONTAINS。
     * 返回 Mermaid classDiagram 文本。
     */
    public static String classDiagram(List<Entity> entities, List<Relation> relations, int nodeLimit) {
        StringBuilder sb = new StringBuilder("classDiagram\n");
        Map<String, Entity> types = new LinkedHashMap<>();
        for (Entity e : entities) {
            if (e.type == com.legacyrecon.ucm.model.EntityType.Class
                    || e.type == com.legacyrecon.ucm.model.EntityType.Interface
                    || e.type == com.legacyrecon.ucm.model.EntityType.Enum) {
                types.putIfAbsent(e.qualifiedName, e);
            }
        }
        int count = 0;
        for (Entity t : types.values()) {
            if (count >= nodeLimit) {
                break;
            }
            sb.append("  class ").append(quote(safe(t.name))).append(" {\n");
            for (Entity m : entities) {
                if ((m.type == com.legacyrecon.ucm.model.EntityType.Method
                        || m.type == com.legacyrecon.ucm.model.EntityType.Field
                        || m.type == com.legacyrecon.ucm.model.EntityType.Constructor)
                        && m.qualifiedName.startsWith(t.qualifiedName + ".")) {
                    sb.append("    ").append(safe(m.type == com.legacyrecon.ucm.model.EntityType.Field ? m.name : m.name + "()"))
                            .append("\n");
                    count++;
                }
                if (count >= nodeLimit) {
                    break;
                }
            }
            sb.append("  }\n");
            count++;
        }
        for (Relation r : relations) {
            String srcName = shortType(r.sourceId);
            String tgtName = shortType(r.targetId == null ? (String) r.metadata.getOrDefault("externalTarget", "") : r.targetId);
            if (r.type == RelationType.INHERITS) {
                sb.append("  ").append(srcName).append(" <|-- ").append(tgtName).append(" : inherits\n");
            } else if (r.type == RelationType.IMPLEMENTS) {
                sb.append("  ").append(srcName).append(" <|.. ").append(tgtName).append(" : implements\n");
            }
        }
        return sb.toString();
    }

    /** 调用图：CALLS。返回 Mermaid flowchart 文本。 */
    public static String callGraph(List<Relation> relations, int skippedTemplate, int nodeLimit) {
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        int count = 0;
        for (Relation r : relations) {
            if (r.type != RelationType.CALLS) {
                continue;
            }
            if (count >= nodeLimit) {
                break;
            }
            String src = sanitize(r.sourceId);
            String tgt = sanitize(r.targetId == null
                    ? String.valueOf(r.metadata.getOrDefault("externalTarget", "external"))
                    : r.targetId);
            sb.append("  ").append(src).append(" --> ").append(tgt).append("\n");
            count++;
        }
        // R12 完整性声明：模板实例化被跳过时显式标注调用图不完整
        if (skippedTemplate > 0) {
            sb.append("\n  subgraph note[调用图不完整]\n    注释: 模板实例化未记录（跳过 ")
                    .append(skippedTemplate).append(" 处）\n  end\n");
        }
        return sb.toString();
    }

    private static String shortType(String id) {
        if (id == null || id.isEmpty()) {
            return "external";
        }
        int hash = id.indexOf('#');
        String base = hash >= 0 ? id.substring(0, hash) : id;
        int idx = base.lastIndexOf('.');
        return safe(idx >= 0 ? base.substring(idx + 1) : base);
    }

    private static String sanitize(String id) {
        String b = shortType(id);
        return b.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String quote(String s) {
        return s;
    }

    private static String safe(String s) {
        return s.replaceAll("[\\[\\]()\\s]", "");
    }
}