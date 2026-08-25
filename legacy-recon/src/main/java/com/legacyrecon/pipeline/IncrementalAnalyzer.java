package com.legacyrecon.pipeline;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.ucm.model.Relation;
import com.legacyrecon.ucm.model.RelationType;
import com.legacyrecon.ucm.model.SourceLocation;

import java.util.*;

/**
 * 02.4 增量解析算法（ADR-006）。
 * 计算差异集 → dirty → 保守反向传播求 affected → 阈值/扇出判定全量回退（R14/R15）。
 * 脚手架：反向传播走 DEPENDS_ON（import → 类型）的保守近似。
 */
public class IncrementalAnalyzer {

    private final FactsStore facts;

    /** 全量回退策略 */
    public enum FallbackStrategy { file_ratio, entity_weight }

    public IncrementalAnalyzer(FactsStore facts) {
        this.facts = facts;
    }

    public static class Plan {
        public Set<String> affectedFiles = new LinkedHashSet<>();
        public Set<String> deletedFiles = new LinkedHashSet<>();
        public boolean forceFull;

        public boolean incremental() {
            return !forceFull;
        }
    }

    /**
     * 计算解析计划。
     * @param projectId 项目
     * @param newChecksums S_new（本次全量扫描）
     * @param forceFull   手动触发（R15）
     * @param strategy    全量回退策略
     * @param fileRatio     file_ratio 阈值（默认 0.2）
     * @param weightRatio   entity_weight 阈值（默认 0.3）
     */
    public Plan plan(String projectId, Map<String, String> newChecksums, boolean forceFull,
                     FallbackStrategy strategy, double fileRatio, double weightRatio) {
        Plan plan = new Plan();
        Map<String, String> old = facts.getChecksums(projectId);
        boolean firstRun = old.isEmpty();

        // 1) 差异集
        Set<String> dirty = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        for (String p : newChecksums.keySet()) {
            if (!old.containsKey(p)) {
                dirty.add(p); // added
            } else if (!old.get(p).equals(newChecksums.get(p))) {
                dirty.add(p); // changed
            }
        }
        for (String p : old.keySet()) {
            if (!newChecksums.containsKey(p)) {
                deleted.add(p);
            }
        }

        // 2) 反向传播（DEPENDS_ON 保守近似，R14）：引用被改类型的文件也受影响
        Set<String> changedTypes = changedTypeQnames(projectId, dirty);
        for (Relation r : facts.queryRelations(projectId, null, null, null)) {
            if (r.type != RelationType.DEPENDS_ON) {
                continue;
            }
            Object ext = r.metadata.get("externalTarget");
            if (ext != null && changedTypes.contains(String.valueOf(ext))) {
                String srcFile = fileOf(projectId, r.sourceId);
                if (srcFile != null) {
                    dirty.add(srcFile);
                }
            }
        }

        plan.deletedFiles.addAll(deleted);
        if (firstRun) {
            plan.affectedFiles.addAll(newChecksums.keySet());
            plan.forceFull = true;
            return plan;
        }
        if (forceFull) {
            plan.forceFull = true;
            plan.affectedFiles.addAll(newChecksums.keySet());
            return plan;
        }

        // 5) 阈值全量回退
        int total = newChecksums.size();
        int affectedSize = dirty.size();
        boolean fallback;
        if (strategy == FallbackStrategy.entity_weight) {
            fallback = weightRatio(newChecksums, dirty, projectId) > weightRatio;
        } else {
            fallback = total > 0 && (double) affectedSize / total > fileRatio;
        }
        if (fallback) {
            plan.forceFull = true;
            plan.affectedFiles.addAll(newChecksums.keySet());
        } else {
            plan.affectedFiles.addAll(dirty);
        }
        return plan;
    }

    private Set<String> changedTypeQnames(String projectId, Set<String> dirty) {
        Set<String> qnames = new HashSet<>();
        if (dirty.isEmpty()) {
            return qnames;
        }
        for (Entity e : facts.searchEntities(projectId, null, null, 100000, 0)) {
            if (e.location != null && dirty.contains(e.location.file)) {
                qnames.add(e.qualifiedName);
            }
        }
        return qnames;
    }

    private double weightRatio(Map<String, String> newChecksums, Set<String> affected, String projectId) {
        // 扇出加权：受影响实体被依赖次数占比（R15，entity-weight 简化实现）
        return (double) affected.size() / Math.max(1, newChecksums.size());
    }

    private String fileOf(String projectId, String entityId) {
        Entity e = facts.getEntity(projectId, entityId);
        if (e != null && e.location != null) {
            return e.location.file;
        }
        return null;
    }
}