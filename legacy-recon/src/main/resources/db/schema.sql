-- 03.2 事实层（SQLite）Schema。确定性事实的单一权威；治理表与确定性事实物理隔离（ADR-009）。
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS projects (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  root_path   TEXT NOT NULL,
  config_json TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  deleted_at  TEXT                  -- 软删除标记（R22）
);

CREATE TABLE IF NOT EXISTS runs (
  id          TEXT PRIMARY KEY,
  project_id  TEXT NOT NULL REFERENCES projects(id),
  trigger     TEXT NOT NULL,           -- "full" | "incremental" | "regenerate"
  stages      TEXT NOT NULL,           -- JSON
  status      TEXT NOT NULL,           -- running | success | failed
  stats_json  TEXT,
  started_at  TEXT NOT NULL,
  finished_at TEXT
);

CREATE TABLE IF NOT EXISTS files (
  id         TEXT PRIMARY KEY,         -- project_id + ":" + path
  project_id TEXT NOT NULL REFERENCES projects(id),
  path       TEXT NOT NULL,
  language   TEXT NOT NULL,
  module_id  TEXT,
  checksum   TEXT NOT NULL,
  size       INTEGER,
  mtime      INTEGER
);

CREATE TABLE IF NOT EXISTS modules (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL REFERENCES projects(id),
  name       TEXT NOT NULL,
  kind       TEXT,
  path       TEXT
);

CREATE TABLE IF NOT EXISTS entities (
  id              TEXT PRIMARY KEY,
  project_id      TEXT NOT NULL,
  file_id         TEXT NOT NULL REFERENCES files(id),
  type            TEXT NOT NULL,
  name            TEXT NOT NULL,
  qualified_name  TEXT NOT NULL,
  signature       TEXT,
  language        TEXT NOT NULL,
  start_line      INTEGER NOT NULL,
  start_col       INTEGER NOT NULL,
  end_line        INTEGER NOT NULL,
  end_col         INTEGER NOT NULL,
  modifiers_json  TEXT,
  type_ref_json   TEXT,
  doc_comment     TEXT,
  metadata_json   TEXT,
  updated_run_id  TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS relations (
  id              TEXT PRIMARY KEY,
  project_id      TEXT NOT NULL,
  type            TEXT NOT NULL,
  source_id       TEXT NOT NULL REFERENCES entities(id),
  target_id       TEXT,
  external_target TEXT,                -- 冗余列（R16）
  location_json   TEXT,
  metadata_json   TEXT
);

CREATE TABLE IF NOT EXISTS parse_issues (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id   TEXT NOT NULL REFERENCES runs(id),
  severity TEXT NOT NULL,
  code     TEXT NOT NULL,
  message  TEXT NOT NULL,
  file_id  TEXT,
  line     INTEGER,
  col      INTEGER
);

CREATE INDEX IF NOT EXISTS idx_entities_file  ON entities(file_id);
CREATE INDEX IF NOT EXISTS idx_entities_qname ON entities(project_id, qualified_name);
CREATE INDEX IF NOT EXISTS idx_relations_src  ON relations(project_id, source_id);
CREATE INDEX IF NOT EXISTS idx_relations_tgt  ON relations(project_id, target_id);
CREATE INDEX IF NOT EXISTS idx_relations_ext  ON relations(project_id, external_target);
CREATE INDEX IF NOT EXISTS idx_relations_type ON relations(project_id, type);
CREATE INDEX IF NOT EXISTS idx_issues_run     ON parse_issues(run_id);
CREATE INDEX IF NOT EXISTS idx_files_project  ON files(project_id, checksum);