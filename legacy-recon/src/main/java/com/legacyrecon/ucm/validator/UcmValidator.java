package com.legacyrecon.ucm.validator;

import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.ParseIssue;
import com.legacyrecon.ucm.model.ParseResult;
import com.legacyrecon.ucm.model.Relation;
import com.legacyrecon.ucm.model.RelationType;
import com.legacyrecon.ucm.id.JvmDescriptor;

import java.util.*;

/**
 * 01.8 UCM Validator。解析器输出入库前必须通过；失败项按严重级别降级
 * （ERROR 阻止入库，WARNING 记录）。
 */
public final class UcmValidator {

    public static final String SCHEMA_VERSION = "1.0";

    public static class Result {
        public final List<ParseIssue> issues = new ArrayList<>();
        /** 存在 ERROR 级问题时为 false（阻止入库） */
        public boolean blocking;

        public boolean add(ParseIssue issue) {
            issues.add(issue);
            if (issue.severity == ParseIssue.Severity.ERROR) {
                blocking = true;
            }
            return true;
        }
    }

    public Result validate(ParseResult pr, String expectedSchemaVersion) {
        Result r = new Result();
        if (expectedSchemaVersion == null) {
            expectedSchemaVersion = SCHEMA_VERSION;
        }
        final String expect = expectedSchemaVersion;

        // 规则 5：schemaVersion 一致性（ERROR）
        if (!Objects.equals(pr.schemaVersion, expect)) {
            r.add(ParseIssue.error("UCM.VERSION_MISMATCH",
                    "schemaVersion 应为 " + expect + "，实际为 " + pr.schemaVersion, null));
        }

        Set<String> ids = new HashSet<>();
        Map<String, Entity> byId = new HashMap<>();
        // 规则 1：id 全局唯一（ERROR）
        for (Entity e : pr.entities) {
            if (e.id == null || e.id.isEmpty()) {
                r.add(ParseIssue.error("UCM.EMPTY_ID", "实体缺少 id：" + e.qualifiedName, e.location));
                continue;
            }
            if (!ids.add(e.id)) {
                r.add(ParseIssue.error("UCM.DUP_ID", "重复实体 id：" + e.id, e.location));
            }
            byId.put(e.id, e);
        }

        // 规则 2：关系端点（ERROR）
        for (Relation rel : pr.relations) {
            if (!byId.containsKey(rel.sourceId)) {
                r.add(ParseIssue.error("UCM.DANGLING_SOURCE", "关系 source 不存在：" + rel.sourceId, rel.location));
            }
            boolean hasTarget = rel.targetId != null && !rel.targetId.isEmpty();
            if (!hasTarget) {
                Object ext = rel.metadata == null ? null : rel.metadata.get("externalTarget");
                if (ext == null || ext.toString().isEmpty()) {
                    r.add(ParseIssue.error("UCM.EXT_MISSING",
                            "关系 targetId 为空但缺少 metadata.externalTarget：" + rel.id, rel.location));
                }
            } else if (!byId.containsKey(rel.targetId)) {
                r.add(ParseIssue.error("UCM.DANGLING_TARGET", "关系 target 不存在：" + rel.targetId, rel.location));
            }
        }

        // 规则 8：关系 id 携带 @startLine:startCol 且与其 location 一致（ERROR, ADR-008）
        for (Relation rel : pr.relations) {
            if (rel.location == null) {
                r.add(ParseIssue.error("UCM.LOCATION_REQUIRED", "关系缺少 location：" + rel.id, null));
                continue;
            }
            String locMarker = "@" + rel.location.startLine + ":" + rel.location.startCol;
            if (rel.id == null || !rel.id.contains(locMarker)) {
                r.add(ParseIssue.error("UCM.REL_ID_LOC_MISMATCH",
                        "关系 id 未携带或与 location 不一致：" + rel.id + "，期望含 " + locMarker, rel.location));
            }
        }

        // 规则 7：signature 格式校验（Java：JVM 描述符 ERROR）
        for (Entity e : pr.entities) {
            if (e.signature == null || e.signature.isEmpty()) {
                continue;
            }
            if ("Java".equals(e.language)) {
                if (!isValidJvmDescriptor(e.signature)) {
                    r.add(ParseIssue.error("UCM.SIGNATURE_INVALID",
                            "Java signature 非法：'" + e.signature + "' (" + e.id + ")", e.location));
                }
            }
        }

        // 规则 3：CONTAINS 无环（WARNING；破坏性环边丢弃并记 issue）
        Collection<Relation> contains = pr.relations.stream()
                .filter(x -> x.type == RelationType.CONTAINS && x.targetId != null)
                .toList();
        Map<String, List<String>> parentByChild = new HashMap<>();
        Map<String, String> childOf = new HashMap<>();
        for (Relation c : contains) {
            if (Objects.equals(c.sourceId, c.targetId)) {
                r.add(ParseIssue.warning("UCM.CONTAINS_CYCLE", "CONTAINS 自环：" + c.id, c.location));
                continue;
            }
            childOf.putIfAbsent(c.targetId, c.sourceId);
            parentByChild.computeIfAbsent(c.sourceId, k -> new ArrayList<>()).add(c.targetId);
        }
        for (String child : childOf.keySet()) {
            Set<String> seen = new HashSet<>();
            String cur = child;
            while (cur != null) {
                if (!seen.add(cur)) {
                    r.add(ParseIssue.warning("UCM.CONTAINS_CYCLE",
                            "CONTAINS 构成环，破坏性环边被丢弃：" + child, null));
                    break;
                }
                cur = parentByChild.containsKey(cur) ? childOf.get(cur) : null;
            }
        }

        // 规则 4：每个 File 实体在 fileIndex 中可定位（WARNING）
        for (Entity e : pr.entities) {
            if (e.type == com.legacyrecon.ucm.model.EntityType.File) {
                List<String> idx = pr.fileIndex.get(e.qualifiedName);
                boolean found = idx != null && idx.contains(e.id);
                if (!found && e.location != null) {
                    boolean byFile = pr.fileIndex.get(e.location.file) != null
                            && pr.fileIndex.get(e.location.file).contains(e.id);
                    if (!byFile) {
                        r.add(ParseIssue.warning("UCM.FILE_NOT_INDEXED", "File 实体未在 fileIndex 中定位：" + e.id, e.location));
                    }
                }
            }
        }

        // 规则 6：typeRef.entityId 非空时指向存在实体（WARNING；悬空降级 unknown）
        for (Entity e : pr.entities) {
            if (e.typeRef != null && e.typeRef.entityId != null && !e.typeRef.entityId.isEmpty()) {
                if (!byId.containsKey(e.typeRef.entityId)) {
                    r.add(ParseIssue.warning("UCM.TYPEREF_DANGLING",
                            "typeRef.entityId 指向不存在实体，按 unknown 降级：" + e.typeRef.entityId, e.location));
                    e.typeRef.kind = "unknown";
                    e.typeRef.entityId = null;
                } else if (e.typeRef.external) {
                    r.add(ParseIssue.warning("UCM.TYPEREF_EXTERNAL_CONFLICT",
                            "typeRef.external=true 但 entityId 非空：" + e.typeRef.entityId, e.location));
                }
            }
        }

        return r;
    }

    private boolean isValidJvmDescriptor(String sig) {
        if (sig == null || sig.isEmpty() || sig.charAt(0) != '(') {
            return false;
        }
        int close = sig.indexOf(')');
        if (close < 0) {
            return false;
        }
        // 参数段
        String params = sig.substring(1, close);
        if (!params.isEmpty()) {
            String p = params;
            while (!p.isEmpty()) {
                char c = p.charAt(0);
                if ("V".indexOf(c) >= 0) {
                    return false; // V 不能作参数
                }
                if ("ZBCDFIJS".indexOf(c) >= 0) {
                    p = p.substring(1);
                } else if (c == 'L') {
                    int semi = p.indexOf(';');
                    if (semi < 0) {
                        return false;
                    }
                    p = p.substring(semi + 1);
                } else if (c == '[') {
                    p = p.substring(1);
                } else {
                    return false;
                }
            }
        }
        // 返回段（允许 V）
        String ret = sig.substring(close + 1);
        return ret.equals("V") || JvmDescriptor.isValid(ret);
    }
}