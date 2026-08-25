package com.legacyrecon.graph;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 03.3 洞察节点（ADR-007）。AI 独立建模，未经人工审核（approved）不进入生成层。
 */
public class Insight {

    public enum Kind { summary, business_rule, arch_role, tech_debt, data_flow_note }

    public enum Status { pending, approved, rejected, superseded }

    public String id;
    public String projectId;
    public String entityId;
    public Kind kind;
    /** 结构化 JSON */
    public Map<String, Object> content = new LinkedHashMap<>();
    public double confidence;
    /** 产生来源；规则引擎则 model=rule-engine */
    public String model;
    public String promptVersion;
    public Status status;
    /** 证据：实体 ID + 代码位置 + 相关代码摘要 */
    public String evidenceJson;
    public String codeChecksum;
    public String createdAt;
    public String updatedAt;

    public Insight() {
        this.createdAt = Instant.now().toString();
        this.updatedAt = this.createdAt;
    }

    public static String kindName(Enum<?> k) {
        return k.name();
    }
}