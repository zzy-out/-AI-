package com.legacyrecon.ucm.model;

/**
 * Relation（01.4）。id 为确定性生成（ADR-008），与遍历顺序无关。
 * source=使用方，target=被使用方；targetId 为空表示外部目标，见 metadata.externalTarget。
 */
public class Relation {
    public String id;
    public RelationType type;
    public String sourceId;
    public String targetId;
    public SourceLocation location;
    public java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();

    public Relation() {
    }
}