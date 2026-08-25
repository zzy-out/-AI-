package com.legacyrecon.ucm.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 01.2 ParseResult 顶层结构。解析器输出经 UCM 校验器校验后入库（03 事实层）。
 */
public class ParseResult {
    public String schemaVersion = "1.0";
    public String language;
    public List<Entity> entities = new ArrayList<>();
    public List<Relation> relations = new ArrayList<>();
    /** key 规则：类型用 qualifiedName；可执行实体用 qualifiedName#signature */
    public Map<String, String> symbolTable = new LinkedHashMap<>();
    /** filePath -> entityId[] */
    public Map<String, List<String>> fileIndex = new LinkedHashMap<>();
    public List<ParseIssue> issues = new ArrayList<>();
    public Map<String, Object> stats = new LinkedHashMap<>();

    public ParseResult() {
    }

    public ParseResult(String language) {
        this.language = language;
    }

    public void addStats(String key, Object value) {
        stats.put(key, value);
    }
}