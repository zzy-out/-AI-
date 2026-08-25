package com.legacyrecon.enrich;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.graph.Insight;
import com.legacyrecon.graph.Insight.Kind;
import com.legacyrecon.graph.Insight.Status;
import com.legacyrecon.ucm.model.Entity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 03.4/3.5 增强层：规则引擎洞察（确定性，直接 approved）+ LLM 治理。
 * 规则引擎产出 model=rule-engine，无需人工审核；LLM 产出一律 pending。
 */
@Component
public class EnrichmentService {

    private final FactsStore facts;
    private final GraphStore graph;
    private final LlmGovernance llm;

    public EnrichmentService(FactsStore facts, GraphStore graph, LlmGovernance llm) {
        this.facts = facts;
        this.graph = graph;
        this.llm = llm;
    }

    /** 实体确定性 checksum：qualifiedName+signature+location（用于 superseded 判定，03.4）。 */
    public static String checksum(Entity e) {
        String raw = e.qualifiedName + "|" + (e.signature == null ? "" : e.signature)
                + "|" + (e.location == null ? "" : e.location.file + ":" + e.location.startLine);
        return sha256(raw);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 运行 enrichment：
     * 1) 对代码校验和变化的实体，将其关联洞察 superseded（03.4）；
     * 2) 生成规则引擎洞察（approved）；
     * 3) 如启用 LLM，经治理（缓存/预算/并发）为高优先级实体生成 pending 洞察。
     */
    public EnrichSummary enrich(String projectId) {
        EnrichSummary summary = new EnrichSummary();
        List<Entity> entities = facts.searchEntities(projectId, null, null, 20000, 0);

        // 1) 过期判定
        int stale = 0;
        for (Insight in : graph.listInsights(projectId, null, null, null)) {
            Entity e = facts.getEntity(projectId, in.entityId);
            String cur = e == null ? "" : checksum(e);
            if (e == null || !cur.equals(in.codeChecksum)) {
                graph.audit(projectId, in.id, com.legacyrecon.graph.AuditRecord.Action.supersede, "system", null);
                stale++;
            }
        }
        summary.superseded = stale;

        // 2) 规则引擎洞察（approved）
        int ruleId = 0;
        for (Entity e : entities) {
            if (e.type == com.legacyrecon.ucm.model.EntityType.Method
                    || e.type == com.legacyrecon.ucm.model.EntityType.Class) {
                if (e.docComment == null || e.docComment.isBlank()) {
                    Insight in = new Insight();
                    in.id = "rule-" + projectId + "-" + (ruleId++);
                    in.projectId = projectId;
                    in.entityId = e.id;
                    in.kind = Kind.tech_debt;
                    in.content.put("rule", "缺失文档注释；建议补充 javadoc 以支撑文档生成");
                    in.confidence = 1.0;
                    in.model = "rule-engine";
                    in.promptVersion = "rule-v1";
                    in.status = Status.approved;                 // 规则引擎确定性，直接 approved
                    in.codeChecksum = checksum(e);
                    in.evidenceJson = "{\"entityId\":\"" + e.id + "\",\"location\":\"" + (e.location == null ? "" : e.location) + "\"}";
                    graph.upsertInsight(in);
                    graph.audit(projectId, in.id, com.legacyrecon.graph.AuditRecord.Action.auto_approve, "rule", null);
                    summary.ruleGenerated++;
                }
            }
        }

        // 3) LLM pending 洞察（可选）
        if (llm.isEnabled()) {
            llm.generatePending(projectId, entities, summary);
        }
        graph.touchGraph(projectId);
        return summary;
    }

    public static class EnrichSummary {
        public int superseded;
        public int ruleGenerated;
        public int llmGenerated;
        public int llmSkippedBudget;
    }
}