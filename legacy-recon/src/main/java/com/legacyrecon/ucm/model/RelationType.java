package com.legacyrecon.ucm.model;

/**
 * 01.4 关系类型与方向约定（统一从"使用方"指向"被使用方"）。
 */
public enum RelationType {
    CONTAINS,
    CALLS,
    INSTANTIATES,
    INHERITS,
    IMPLEMENTS,
    OVERRIDES,
    READS,
    WRITES,
    THROWS,
    DEPENDS_ON,
    REFERENCES
}