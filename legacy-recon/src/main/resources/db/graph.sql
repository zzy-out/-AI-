-- 03.3 图谱层 MVP 降级（ADR-004）：SQLite 追加表，与事实层同库但逻辑隔离。
-- 洞察节点（03.3） + 审计节点（03.3） + 项目级元数据（insightVersion, 图谱修改时间，R21）。
CREATE TABLE IF NOT EXISTS insights (
  id             TEXT PRIMARY KEY,
  project_id     TEXT NOT NULL,
  entity_id      TEXT NOT NULL,
  kind           TEXT NOT NULL,
  content_json   TEXT NOT NULL,
  confidence     REAL,
  model          TEXT,
  prompt_version TEXT,
  status         TEXT NOT NULL,          -- pending | approved | rejected | superseded
  evidence_json  TEXT,
  code_checksum  TEXT,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_insights_project ON insights(project_id);
CREATE INDEX IF NOT EXISTS idx_insights_entity ON insights(project_id, entity_id);
CREATE INDEX IF NOT EXISTS idx_insights_status ON insights(project_id, status);

CREATE TABLE IF NOT EXISTS audit_records (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  insight_id TEXT NOT NULL,
  action     TEXT NOT NULL,            -- approve | modify | reject | auto_approve | supersede
  before_json TEXT,
  after_json  TEXT,
  actor      TEXT NOT NULL,            -- human | rule | system
  at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_insight ON audit_records(insight_id);

-- 治理表：已批准洞察快照（ADR-009 / R18），与确定性事实物理隔离。
CREATE TABLE IF NOT EXISTS insight_approvals (
  id            TEXT PRIMARY KEY,
  project_id    TEXT NOT NULL,
  insight_id    TEXT NOT NULL,
  entity_id     TEXT NOT NULL,
  kind          TEXT NOT NULL,
  content_json  TEXT NOT NULL,
  actor         TEXT NOT NULL,
  approved_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_approvals_project ON insight_approvals(project_id);
CREATE INDEX IF NOT EXISTS idx_approvals_entity  ON insight_approvals(entity_id);

-- 项目级元数据：insightVersion（R21）、graph_mtime（R21）、external 虚拟节点阈值快照等。
CREATE TABLE IF NOT EXISTS project_meta (
  project_id      TEXT PRIMARY KEY,
  insight_version INTEGER NOT NULL DEFAULT 0,
  graph_mtime     TEXT
);