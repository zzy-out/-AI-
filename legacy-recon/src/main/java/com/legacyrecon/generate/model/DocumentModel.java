package com.legacyrecon.generate.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 04.1 文档模型。一次生成绑定单个 runId 事实快照，保证内部自洽与可复现。
 */
public class DocumentModel {
    public static class Meta {
        public String title;
        public String projectId;
        public String runId;
        public String generatedAt;
        public String generatorVersion = "0.1.0";
    }

    public Meta meta = new Meta();
    public List<Section> sections = new ArrayList<>();

    public DocumentModel() {
    }
}