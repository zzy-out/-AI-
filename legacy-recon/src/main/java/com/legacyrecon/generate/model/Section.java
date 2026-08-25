package com.legacyrecon.generate.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 04.1 Section。kind 三选一：template | llm | human。
 */
public class Section {
    public String id;
    public String title;
    public String kind;                     // template | llm | human
    public String content;
    public List<String> entityRefs = new ArrayList<>();
    public List<EvidenceRef> evidenceRefs = new ArrayList<>();
    public String templateId;
    public String promptVersion;
    /** 校验失败时置位，引用降级为纯文本（R19） */
    public String validationError;

    public Section() {
    }
}