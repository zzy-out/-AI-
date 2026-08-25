package com.legacyrecon.facts;

import com.legacyrecon.ucm.model.*;
import com.legacyrecon.util.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 03.2 事实层（SQLite）。确定性数据的唯一权威；
 * 确定性事实表永不被 AI 改写（ADR-007）。
 */
@Repository
public class FactsStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FactsStore(JdbcTemplate jdbc, DataSourceTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm, new DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_REQUIRED));
    }

    /** 建表（幂等，classpath:db/schema.sql） */
    public void initSchema() {
        com.legacyrecon.util.DbScripts.run(jdbc, "db/schema.sql");
    }

    // ---------------- 项目 ----------------

    public void upsertProject(Project p) {
        jdbc.update("INSERT OR REPLACE INTO projects(id,name,root_path,config_json,created_at,deleted_at) VALUES(?,?,?,?,?,?)",
                p.id, p.name, p.rootPath, p.configJson, p.createdAt, p.deletedAt);
    }

    public Project getProject(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,root_path,config_json,created_at,deleted_at FROM projects WHERE id=?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Project p = new Project();
        p.id = str(row.get("id"));
        p.name = str(row.get("name"));
        p.rootPath = str(row.get("root_path"));
        p.configJson = str(row.get("config_json"));
        p.createdAt = str(row.get("created_at"));
        p.deletedAt = str(row.get("deleted_at"));
        return p;
    }

    public List<Project> listProjects() {
        return jdbc.queryForList("SELECT id,name,root_path,config_json,created_at,deleted_at FROM projects ORDER BY created_at").stream()
                .map(row -> {
                    Project p = new Project();
                    p.id = str(row.get("id"));
                    p.name = str(row.get("name"));
                    p.rootPath = str(row.get("root_path"));
                    p.configJson = str(row.get("config_json"));
                    p.createdAt = str(row.get("created_at"));
                    p.deletedAt = str(row.get("deleted_at"));
                    return p;
                }).collect(Collectors.toList());
    }

    public void softDelete(String id) {
        jdbc.update("UPDATE projects SET deleted_at=? WHERE id=?", Instant.now().toString(), id);
        // 级联清理洞察与审计（R17）——由图谱层清理
    }

    public void updateConfig(String id, String configJson) {
        jdbc.update("UPDATE projects SET config_json=? WHERE id=?", configJson, id);
    }

    // ---------------- 运行 ----------------

    public void insertRun(String runId, String projectId, String trigger, String stagesJson, String status) {
        jdbc.update("INSERT INTO runs(id,project_id,trigger,stages,status,started_at) VALUES(?,?,?,?,?,?)",
                runId, projectId, trigger, stagesJson, status, Instant.now().toString());
    }

    public void updateRun(String runId, String status, String statsJson) {
        jdbc.update("UPDATE runs SET status=?, stats_json=?, finished_at=? WHERE id=?",
                status, statsJson, Instant.now().toString(), runId);
    }

    /** 返回运行记录（status/stages/stats）。 */
    public Map<String, Object> getRun(String projectId, String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,trigger,stages,status,stats_json,started_at,finished_at FROM runs WHERE project_id=? AND id=?",
                projectId, runId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("id", row.get("id"));
        out.put("trigger", row.get("trigger"));
        out.remove("trigger_row");
        return out;
    }

    /** 返回项目运行历史（新 → 旧，limit 条）。 */
    public List<Map<String, Object>> listRuns(String projectId, int limit) {
        return jdbc.queryForList(
                        "SELECT id,trigger,stages,status,stats_json,started_at,finished_at "
                                + "FROM runs WHERE project_id=? ORDER BY started_at DESC LIMIT " + Math.max(1, Math.min(limit, 200)),
                        projectId)
                .stream()
                .map(row -> {
                    Map<String, Object> out = new LinkedHashMap<>(row);
                    out.put("id", row.get("id"));
                    return out;
                })
                .collect(Collectors.toList());
    }

    /** 返回 S_old：文件路径 -> checksum（02.4 增量算法持久状态）。 */
    public Map<String, String> getChecksums(String projectId) {
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT path,checksum FROM files WHERE project_id=?", projectId)) {
            out.put(str(row.get("path")), str(row.get("checksum")));
        }
        return out;
    }

    public void clearProjectData(String projectId) {
        jdbc.update("DELETE FROM parse_issues WHERE run_id IN (SELECT id FROM runs WHERE project_id=?)", projectId);
        jdbc.update("DELETE FROM relations WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM entities WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM files WHERE project_id=?", projectId);
    }

    /**
     * 事务性合并一批（02.4 步骤 7）：按 affectedFile 删除旧实体/关系，插入新结果；同步 files 校验和。
     * 未受影响文件的数据原样保留——确定性 ID 保证其稳定。
     */
    public void mergeBatch(String projectId, String runId,
                           Map<String, String> newChecksums,
                           Set<String> affectedFiles,
                           Set<String> deletedFiles,
                           List<Entity> entities, List<Relation> relations, List<ParseIssue> issues) {
        tx.executeWithoutResult(status -> {
            // 1) 删除受影响文件的旧实体与关系（按 file_id 批量）
            for (String path : affectedFiles) {
                String fileId = fileId(projectId, path);
                for (String eid : jdbc.queryForList("SELECT id FROM entities WHERE file_id=?", String.class, fileId)) {
                    jdbc.update("DELETE FROM relations WHERE source_id=? OR target_id=?", eid, eid);
                }
                jdbc.update("DELETE FROM entities WHERE file_id=?", fileId);
            }
            // 2) 删除文件：删 files 行 + 实体 + 关系
            for (String path : deletedFiles) {
                String fileId = fileId(projectId, path);
                for (String eid : jdbc.queryForList("SELECT id FROM entities WHERE file_id=?", String.class, fileId)) {
                    jdbc.update("DELETE FROM relations WHERE source_id=? OR target_id=?", eid, eid);
                }
                jdbc.update("DELETE FROM entities WHERE file_id=?", fileId);
                jdbc.update("DELETE FROM files WHERE id=?", fileId);
            }
            // 3) 更新 files 表（affected 中现存文件）
            for (String path : affectedFiles) {
                if (!deletedFiles.contains(path)) {
                    String checksum = newChecksums.get(path);
                    String fileId = fileId(projectId, path);
                    if (checksum == null) {
                        continue;
                    }
                    jdbc.update("INSERT OR REPLACE INTO files(id,project_id,path,language,module_id,checksum) VALUES(?,?,?,?,?,?)",
                            fileId, projectId, path, "java", null, checksum);
                }
            }
            // 4) 插入新实体
            for (Entity e : entities) {
                try {
                    jdbc.update("INSERT OR REPLACE INTO entities(" +
                                    "id,project_id,file_id,type,name,qualified_name,signature,language," +
                                    "start_line,start_col,end_line,end_col,modifiers_json,type_ref_json,doc_comment,metadata_json,updated_run_id,updated_at" +
                                    ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            e.id, projectId, fileId(projectId, e.location != null ? e.location.file : "unknown"),
                            e.type.name(), e.name, e.qualifiedName, e.signature, e.language,
                            e.location != null ? e.location.startLine : 0,
                            e.location != null ? e.location.startCol : 0,
                            e.location != null ? e.location.endLine : 0,
                            e.location != null ? e.location.endCol : 0,
                            Json.toJson(e.modifiers), e.typeRef != null ? Json.toJson(e.typeRef) : null,
                            e.docComment, Json.toJson(e.metadata), runId, Instant.now().toString());
                } catch (Exception ignore) {
                    // 单实体失败不阻止整体（隔离失败）
                }
            }
            // 5) 插入新关系
            for (Relation rel : relations) {
                try {
                    jdbc.update("INSERT OR REPLACE INTO relations(" +
                                    "id,project_id,type,source_id,target_id,external_target,location_json,metadata_json" +
                                    ") VALUES(?,?,?,?,?,?,?,?)",
                            rel.id, projectId, rel.type.name(), rel.sourceId, rel.targetId,
                            rel.metadata.get("externalTarget") != null ? String.valueOf(rel.metadata.get("externalTarget")) : null,
                            rel.location != null ? Json.toJson(rel.location) : null,
                            Json.toJson(rel.metadata));
                } catch (Exception ignore) {
                }
            }
            // 6) parse_issues
            jdbc.update("DELETE FROM parse_issues WHERE run_id=?", runId);
            for (ParseIssue issue : issues) {
                jdbc.update("INSERT INTO parse_issues(run_id,severity,code,message,file_id,line,col) VALUES(?,?,?,?,?,?,?)",
                        runId, issue.severity.name(), issue.code, issue.message,
                        issue.location != null ? fileId(projectId, issue.location.file) : null,
                        issue.location != null ? issue.location.startLine : null,
                        issue.location != null ? issue.location.startCol : null);
            }
        });
    }

    // ---------------- 查询 ----------------

    public List<Entity> searchEntities(String projectId, String type, String q, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM entities WHERE project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type=?");
            args.add(type);
        }
        if (q != null && !q.isEmpty()) {
            sql.append(" AND (qualified_name LIKE ? OR name LIKE ?)");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        args.add(Math.min(limit, 1000));
        args.add(offset);
        List<Entity> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
            out.add(rowToEntity(row));
        }
        return out;
    }

    public List<Relation> queryRelations(String projectId, String sourceId, String type, String externalTarget) {
        StringBuilder sql = new StringBuilder("SELECT * FROM relations WHERE project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (sourceId != null && !sourceId.isEmpty()) {
            sql.append(" AND source_id=?");
            args.add(sourceId);
        }
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type=?");
            args.add(type);
        }
        if (externalTarget != null && !externalTarget.isEmpty()) {
            sql.append(" AND external_target LIKE ?");
            args.add("%" + externalTarget + "%");
        }
        sql.append(" LIMIT 5000");
        List<Relation> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
            out.add(rowToRelation(row));
        }
        return out;
    }

    public Relation getRelation(String projectId, String id) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM relations WHERE project_id=? AND id=?", projectId, id)) {
            return rowToRelation(row);
        }
        return null;
    }

    public Entity getEntity(String projectId, String id) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM entities WHERE project_id=? AND id=?", projectId, id)) {
            return rowToEntity(row);
        }
        return null;
    }

    public List<String> getFileIds(String projectId) {
        return jdbc.queryForList("SELECT id FROM files WHERE project_id=?", String.class, projectId);
    }

    /** 读取文件路径相关实体所在行范围（支撑字符内容预览由接入层提供）。 */
    public List<String> fileChecksums(String projectId) {
        return jdbc.queryForList("SELECT checksum FROM files WHERE project_id=?", String.class, projectId);
    }

    private Entity rowToEntity(Map<String, Object> row) {
        Entity e = new Entity();
        e.id = str(row.get("id"));
        e.type = EntityType.valueOf(str(row.get("type")));
        e.name = str(row.get("name"));
        e.qualifiedName = str(row.get("qualified_name"));
        e.signature = str(row.get("signature"));
        e.language = str(row.get("language"));
        e.location = new SourceLocation(str(row.get("file_id")).replace(prefix(row.get("project_id")) + ":", ""),
                intv(row.get("start_line")), intv(row.get("start_col")),
                intv(row.get("end_line")), intv(row.get("end_col")));
        if (row.get("modifiers_json") != null) {
            e.modifiers = Json.fromJson(str(row.get("modifiers_json")), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        }
        if (row.get("type_ref_json") != null) {
            try {
                e.typeRef = Json.MAPPER.readValue(str(row.get("type_ref_json")), TypeRef.class);
            } catch (Exception ex) {
                e.typeRef = null;
            }
        }
        e.docComment = str(row.get("doc_comment"));
        if (row.get("metadata_json") != null) {
            try {
                e.metadata = Json.MAPPER.readValue(str(row.get("metadata_json")), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ex) {
                e.metadata = new LinkedHashMap<>();
            }
        }
        return e;
    }

    private Relation rowToRelation(Map<String, Object> row) {
        Relation r = new Relation();
        r.id = str(row.get("id"));
        r.type = RelationType.valueOf(str(row.get("type")));
        r.sourceId = str(row.get("source_id"));
        r.targetId = str(row.get("target_id"));
        if (row.get("location_json") != null) {
            try {
                r.location = Json.MAPPER.readValue(str(row.get("location_json")), SourceLocation.class);
            } catch (Exception ex) {
                r.location = null;
            }
        }
        if (row.get("metadata_json") != null) {
            try {
                r.metadata = Json.MAPPER.readValue(str(row.get("metadata_json")), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ex) {
                r.metadata = new LinkedHashMap<>();
            }
        }
        return r;
    }

    private String prefix(Object projectId) {
        return String.valueOf(projectId);
    }

    private static String fileId(String projectId, String path) {
        return projectId + ":" + path;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intv(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o == null ? 0 : Integer.parseInt(String.valueOf(o));
    }
}