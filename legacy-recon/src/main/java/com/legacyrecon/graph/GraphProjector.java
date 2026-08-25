package com.legacyrecon.graph;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.Relation;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 03.3 图谱层 = 事实的投影 + 洞察 + 审计；可由事实层随时重建投影。
 * 本类提供实体/关系投影查询与 BFS 子图（图谱浏览器用），以及外部虚拟节点（R16）的建模。
 */
@Component
public class GraphProjector {

    public static final int EXTERNAL_THRESHOLD_DEFAULT = 1000;

    private final FactsStore facts;

    public GraphProjector(FactsStore facts) {
        this.facts = facts;
    }

    public List<Entity> projectEntities(String projectId) {
        return facts.searchEntities(projectId, null, null, 100000, 0);
    }

    public List<Relation> projectRelations(String projectId) {
        return facts.queryRelations(projectId, null, null, null);
    }

    /** 子图查询：以 entityId 为中心，BFS 深度 depth（04.3 /graph），外部目标虚拟节点（R16）。 */
    public Subgraph subgraph(String projectId, String entityId, int depth) {
        Subgraph sg = new Subgraph();
        Map<String, Entity> allEntities = new HashMap<>();
        for (Entity e : projectEntities(projectId)) {
            allEntities.put(e.id, e);
        }
        Map<String, Relation> allRelations = new HashMap<>();
        for (Relation r : projectRelations(projectId)) {
            allRelations.put(r.id, r);
        }

        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> dist = new HashMap<>();
        if (entityId != null && allEntities.containsKey(entityId)) {
            queue.add(entityId);
            dist.put(entityId, 0);
        } else if (entityId != null) {
            throw new IllegalArgumentException("实体不存在：" + entityId);
        }
        // entityId 为空时返回全图（限深 1 的文档中心外省略）
        if (entityId == null) {
            sg.entities.addAll(allEntities.values());
            sg.relations.addAll(allRelations.values());
            return sg;
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = dist.get(cur);
            Entity e = allEntities.get(cur);
            if (e != null && !visited.contains(cur)) {
                visited.add(cur);
                sg.entities.add(e);
            }
            if (d >= depth) {
                continue;
            }
            for (Relation r : allRelations.values()) {
                if (cur.equals(r.sourceId)) {
                    String tgt = r.targetId;
                    if (tgt != null && allEntities.containsKey(tgt)) {
                        if (!dist.containsKey(tgt)) {
                            dist.put(tgt, d + 1);
                            queue.add(tgt);
                        }
                        sg.relations.add(r);
                    } else {
                        // 外部虚拟节点（R16）：以 externalTarget 命名
                        String extBucket = bucket(r);
                        if (!dist.containsKey(extBucket)) {
                            dist.put(extBucket, d + 1);
                            queue.add(extBucket);
                            sg.external.add(extBucket);
                        }
                        sg.relations.add(r);
                    }
                }
            }
        }
        return sg;
    }

    private String bucket(Relation r) {
        Object ext = r.metadata.get("externalTarget");
        return "ext:" + (ext == null ? "external" : ext);
    }

    /** 子图数据结构。external 为外部虚拟节点名（R16）。 */
    public static class Subgraph {
        public final List<Entity> entities = new ArrayList<>();
        public final List<Relation> relations = new ArrayList<>();
        public final List<String> external = new ArrayList<>();
    }
}