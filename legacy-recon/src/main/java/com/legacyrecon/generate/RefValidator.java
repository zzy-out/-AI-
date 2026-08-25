package com.legacyrecon.generate;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.generate.model.DocumentModel;
import com.legacyrecon.generate.model.Section;
import com.legacyrecon.graph.Insight;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.ucm.model.Entity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 04.1 RefValidator（R19）。渲染/导出前校验每个 entityRefs 与 evidenceRefs：
 * 实体存在 + 引用洞察须 approved + 位置落在实体区间内。失败自动重试由生成层处理；
 * 本轮直接返回失败清单，供调用方决定重试或降级。
 */
@Component
public class RefValidator {

    private final FactsStore facts;
    private final GraphStore graph;

    public RefValidator(FactsStore facts, GraphStore graph) {
        this.facts = facts;
        this.graph = graph;
    }

    /** 校验单个 Section，返回失败清单（空 = 全部通过）。 */
    public List<String> validateSection(String projectId, String runId, Section s) {
        List<String> failures = new ArrayList<>();
        for (String ref : s.entityRefs) {
            Entity e = facts.getEntity(projectId, ref);
            if (e == null) {
                failures.add("实体不存在（runId=" + runId + "）：" + ref);
            }
        }
        for (var ev : s.evidenceRefs) {
            Entity e = facts.getEntity(projectId, ev.entityId);
            if (e == null) {
                failures.add("证据实体不存在：" + ev.entityId);
                continue;
            }
            if (ev.location != null && e.location != null) {
                boolean inRange = ev.location.startLine >= e.location.startLine
                        && ev.location.startLine < e.location.endLine;
                if (!inRange) {
                    failures.add("证据位置不在实体区间内：" + ev.entityId + " " + ev.location + " vs " + e.location);
                }
            }
            // 洞察引用：evidence 若显式引用洞察 ID（以 insight: 前缀）须 approved
            if (ev.entityId.startsWith("insight:")) {
                Insight in = graph.getInsight(projectId, ev.entityId.substring("insight:".length()));
                if (in == null || in.status != Insight.Status.approved) {
                    failures.add("引用的洞察非 approved：" + ev.entityId);
                }
            }
        }
        return failures;
    }

    /** 校验整份文档，返回失败的 section id 列表。 */
    public List<String> validateDocument(String projectId, String runId, DocumentModel doc) {
        List<String> failedSections = new ArrayList<>();
        for (Section s : doc.sections) {
            List<String> failures = validateSection(projectId, runId, s);
            if (!failures.isEmpty()) {
                s.validationError = String.join("\n", failures);
                failedSections.add(s.id);
            }
        }
        return failedSections;
    }
}