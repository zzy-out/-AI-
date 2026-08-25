package com.legacyrecon.facts;

import java.time.Instant;

/** 项目记录（03.2 projects 表），跨图谱/API 层使用。 */
public class Project {
    public String id;
    public String name;
    public String rootPath;
    public String configJson;
    public String createdAt;
    public String deletedAt;

    public Project() {
    }

    public Project(String id, String name, String rootPath, String configJson) {
        this.id = id;
        this.name = name;
        this.rootPath = rootPath;
        this.configJson = configJson;
        this.createdAt = Instant.now().toString();
    }

    public boolean archived() {
        return configJson != null && configJson.contains("\"archived\":true");
    }
}