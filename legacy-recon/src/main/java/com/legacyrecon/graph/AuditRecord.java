package com.legacyrecon.graph;

import java.time.Instant;

/**
 * 03.3 审计节点。audit chain 永久保留（回滚 = 状态转移 + 审计记录，不做物理删除）。
 */
public class AuditRecord {
    public enum Action { approve, modify, reject, auto_approve, supersede }

    public String id;
    public String projectId;
    public String insightId;
    public Action action;
    public String beforeJson;
    public String afterJson;
    /** human | rule | system */
    public String actor;
    public String at;

    public AuditRecord() {
        this.at = Instant.now().toString();
    }
}