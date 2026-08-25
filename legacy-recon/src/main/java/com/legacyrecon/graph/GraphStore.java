package com.legacyrecon.graph;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.util.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 03. 图谱层（SQLite 图模式，ADR-004 MVP 降级）。
 * 洞察 / 审计 / 治理表 / insightVersion。由事实层可随时重建投影。
 */
@Repository
public class GraphStore {

    private final JdbcTemplate jdbc;
    private final FactsStore facts;

    public GraphStore(JdbcTemplate jdbc, FactsStore facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    public void initSchema() {
        com.legacyrecon.util.DbScripts.run(jdbc, "db/graph.sql");
    }

    // ---------------- 洞察 ----------------

    public void upsertInsight(Insight i) {
        jdbc.update("INSERT OR REPLACE INTO insights(id,project_id,entity_id,kind,content_json,confidence,model,prompt_version,status,evidence_json,code_checksum,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                i.id, i.projectId, i.entityId, i.kind.name(), Json.toJson(i.content), i.confidence,
                i.model, i.promptVersion, i.status.name(), i.evidenceJson, i.codeChecksum,
                i.createdAt, Instant.now().toString());
    }

    public Insight getInsight(String projectId, String id) {
        List<Insight> list = query(
                "SELECT * FROM insights WHERE project_id=? AND id=?", projectId, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Insight> listInsights(String projectId, String status, String kind, String entityId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM insights WHERE project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (kind != null && !kind.isEmpty()) {
            sql.append(" AND kind=?");
            args.add(kind);
        }
        if (entityId != null && !entityId.isEmpty()) {
            sql.append(" AND entity_id=?");
            args.add(entityId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT 1000");
        return query(sql.toString(), args.toArray());
    }

    private List<Insight> query(String sql, Object... args) {
        List<Insight> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Insight i = new Insight();
            i.id = s(row.get("id"));
            i.projectId = s(row.get("project_id"));
            i.entityId = s(row.get("entity_id"));
            try {
                i.kind = Insight.Kind.valueOf(s(row.get("kind")));
            } catch (Exception ex) {
                i.kind = Insight.Kind.summary;
            }
            try {
                i.content = Json.MAPPER.readValue(s(row.get("content_json")),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                        });
            } catch (Exception ex) {
                i.content = new LinkedHashMap<>();
            }
            i.confidence = row.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
            i.model = s(row.get("model"));
            i.promptVersion = s(row.get("prompt_version"));
            try {
                i.status = Insight.Status.valueOf(s(row.get("status")));
            } catch (Exception ex) {
                i.status = Insight.Status.pending;
            }
            i.evidenceJson = s(row.get("evidence_json"));
            i.codeChecksum = s(row.get("code_checksum"));
            i.createdAt = s(row.get("created_at"));
            i.updatedAt = s(row.get("updated_at"));
            out.add(i);
        }
        return out;
    }

    // ---------------- 审核状态机（03.4） ----------------

    public static final class AuditResult {
        public Insight insight;
        public int newInsightVersion;
    }

    /**
     * 执行审核动作。approve/modify（含规则 auto_approve）成功后 upsert 治理表；
     * reject/supersede 删除治理行；每次操作 insightVersion+1（R21）。
     */
    public AuditResult audit(String projectId, String insightId, AuditRecord.Action action,
                             String actor, String contentJson) {
        Insight i = getInsight(projectId, insightId);
        if (i == null) {
            return null;
        }
        String before = Json.toJson(i.content);

        switch (action) {
            case approve, auto_approve -> {
                i.status = Insight.Status.approved;
                if (contentJson != null) {
                    i.content = Json.fromJson(contentJson,
                            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                            });
                }
                upsertInsight(i);
                upsertApproval(projectId, insightId, i, actor);
            }
            case modify -> {
                i.status = Insight.Status.approved;
                if (contentJson != null) {
                    i.content = Json.fromJson(contentJson,
                            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                            });
                }
                upsertInsight(i);
                upsertApproval(projectId, insightId, i, actor);
            }
            case reject -> {
                i.status = Insight.Status.rejected;
                upsertInsight(i);
                deleteApproval(projectId, insightId);
            }
            case supersede -> {
                i.status = Insight.Status.superseded;
                upsertInsight(i);
                deleteApproval(projectId, insightId);
            }
            case create, degrade -> {
                // create/degrade：只记录审计，不改变状态与治理表
            }
            default -> {
            }
        }
        recordAudit(projectId, insightId, action, actor, before, contentJson);
        return result(i);
    }

    private AuditResult result(Insight i) {
        AuditResult r = new AuditResult();
        r.insight = i;
        r.newInsightVersion = bumpInsightVersion(i.projectId);
        return r;
    }

    private void recordAudit(String projectId, String insightId, AuditRecord.Action action,
                             String actor, String before, String after) {
        jdbc.update("INSERT INTO audit_records(id,project_id,insight_id,action,before_json,after_json,actor,at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), projectId, insightId, action.name(), before, after, actor, Instant.now().toString());
    }

    private void upsertApproval(String projectId, String insightId, Insight i, String actor) {
        jdbc.update("INSERT OR REPLACE INTO insight_approvals(id,project_id,insight_id,entity_id,kind,content_json,actor,approved_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), projectId, insightId, i.entityId, i.kind.name(),
                Json.toJson(i.content), actor, Instant.now().toString());
    }

    private void deleteApproval(String projectId, String insightId) {
        jdbc.update("DELETE FROM insight_approvals WHERE project_id=? AND insight_id=?", projectId, insightId);
    }

    public List<Map<String, Object>> listAudits(String projectId, String insightId) {
        if (insightId != null) {
            return jdbc.queryForList("SELECT * FROM audit_records WHERE project_id=? AND insight_id=? ORDER BY at", projectId, insightId);
        }
        return jdbc.queryForList("SELECT * FROM audit_records WHERE project_id=? ORDER BY at", projectId);
    }

    // ---------------- insightVersion / 图谱时间戳（R21） ----------------

    public int getInsightVersion(String projectId) {
        List<Integer> rows = jdbc.queryForList("SELECT insight_version FROM project_meta WHERE project_id=?", Integer.class, projectId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public int bumpInsightVersion(String projectId) {
        jdbc.update("INSERT INTO project_meta(project_id,insight_version,graph_mtime) VALUES(?,1,?) " +
                        "ON CONFLICT(project_id) DO UPDATE SET insight_version=insight_version+1, graph_mtime=excluded.graph_mtime",
                projectId, Instant.now().toString());
        return getInsightVersion(projectId);
    }

    public void touchGraph(String projectId) {
        jdbc.update("INSERT INTO project_meta(project_id,insight_version,graph_mtime) VALUES(?,0,?) " +
                        "ON CONFLICT(project_id) DO UPDATE SET graph_mtime=excluded.graph_mtime",
                projectId, Instant.now().toString());
    }

    public String getGraphMtime(String projectId) {
        List<String> rows = jdbc.queryForList("SELECT graph_mtime FROM project_meta WHERE project_id=?", String.class, projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 治理表恢复（ADR-009） ----------------

    /** 图谱重建后据此恢复 approved 状态：表中洞察一律 approved，未在表中的视为 pending/superseded。 */
    public void restoreApproved(String projectId) {
        Set<String> approvedIds = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT insight_id FROM insight_approvals WHERE project_id=?", projectId)) {
            approvedIds.add(s(row.get("insight_id")));
        }
        for (Insight i : listInsights(projectId, null, null, null)) {
            if (approvedIds.contains(i.id) && i.status != Insight.Status.approved) {
                i.status = Insight.Status.approved;
                upsertInsight(i);
            } else if (!approvedIds.contains(i.id) && i.status == Insight.Status.approved) {
                i.status = Insight.Status.superseded;
                upsertInsight(i);
            }
        }
    }

    /** 项目删除级联清理洞察与审计（R17）。 */
    public void cleanupProject(String projectId) {
        jdbc.update("DELETE FROM audit_records WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM insights WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM insight_approvals WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM project_meta WHERE project_id=?", projectId);
    }

    private static String s(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}