package com.legacyrecon.generate.model;

import com.legacyrecon.ucm.model.SourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 04.1 证据锚点。三类生成来源（template/llm/human）都携带 entityRefs 与 evidenceRefs，保证全文档可追溯（R5）。
 */
public class EvidenceRef {
    public String entityId;
    public SourceLocation location;
    public String quote;

    public EvidenceRef() {
    }

    public EvidenceRef(String entityId, SourceLocation location, String quote) {
        this.entityId = entityId;
        this.location = location;
        this.quote = quote;
    }
}