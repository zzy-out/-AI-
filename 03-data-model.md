# 03 数据模型与存储

## 3.1 存储分层

~~~text
┌ 产物层   文件系统：文档 / 图表 / 导出（docx/pdf/svg）
├ 图谱层   Neo4j（默认）或 SQLite 图模式（MVP 降级）：事实投影 + 洞察 + 审计
└ 事实层   SQLite：UCM 实体/关系（确定性） + 治理表（已批准洞察快照，ADR-009） + 运行记录
~~~

原则：

- 事实层是确定性数据的唯一权威；确定性事实表（`entities` / `relations`）永不被 AI 改写（ADR-007）。
- 已批准洞察的快照存放于事实层**治理表**（`insight_approvals`），与确定性事实物理隔离（不同表），用于图谱重建恢复（ADR-009，见 3.6）。
- 图谱层 = 事实的投影 + 洞察 + 审计；可由事实层随时重建投影。

## 3.2 事实层（SQLite）Schema

~~~sql
CREATE TABLE projects (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  root_path   TEXT NOT NULL,
  config_json TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  deleted_at  TEXT                  -- 软删除标记（R22）
);

CREATE TABLE runs (
  id          TEXT PRIMARY KEY,
  project_id  TEXT NOT NULL REFERENCES projects(id),
  trigger     TEXT NOT NULL,           -- "full" | "incremental" | "regenerate"
  stages      TEXT NOT NULL,           -- JSON: 各阶段状态与耗时
  status      TEXT NOT NULL,           -- running | success | failed
  stats_json  TEXT,
  started_at  TEXT NOT NULL,
  finished_at TEXT
);

CREATE TABLE files (
  id         TEXT PRIMARY KEY,         -- project_id + ":" + path
  project_id TEXT NOT NULL REFERENCES projects(id),
  path       TEXT NOT NULL,
  language   TEXT NOT NULL,
  module_id  TEXT,
  checksum   TEXT NOT NULL,
  size       INTEGER,
  mtime      INTEGER
);

CREATE TABLE modules (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL REFERENCES projects(id),
  name       TEXT NOT NULL,
  kind       TEXT,                     -- maven | gradle | cmake | directory
  path       TEXT
);

CREATE TABLE entities (
  id              TEXT PRIMARY KEY,    -- 确定性 ID（ADR-005）
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

CREATE TABLE relations (
  id            TEXT PRIMARY KEY,      -- 含位置（ADR-008）
  project_id    TEXT NOT NULL,
  type          TEXT NOT NULL,
  source_id     TEXT NOT NULL REFERENCES entities(id),
  target_id     TEXT,                  -- null = 外部目标
  external_target TEXT,                -- 冗余列：target_id 为空时的外部全限定名（R16）
  location_json TEXT,
  metadata_json TEXT
);

CREATE TABLE parse_issues (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id   TEXT NOT NULL REFERENCES runs(id),
  severity TEXT NOT NULL,
  code     TEXT NOT NULL,
  message  TEXT NOT NULL,
  file_id  TEXT,
  line     INTEGER,
  col      INTEGER
);

CREATE INDEX idx_entities_file  ON entities(file_id);
CREATE INDEX idx_entities_qname ON entities(project_id, qualified_name);
CREATE INDEX idx_relations_src  ON relations(project_id, source_id);
CREATE INDEX idx_relations_tgt  ON relations(project_id, target_id);
CREATE INDEX idx_relations_ext  ON relations(project_id, external_target);
CREATE INDEX idx_relations_type ON relations(project_id, type);
CREATE INDEX idx_issues_run     ON parse_issues(run_id);
CREATE INDEX idx_files_project  ON files(project_id, checksum);
~~~

设计说明：

- 当前状态模型：`entities/relations` 只存"最新一次成功运行"的结果，`updated_run_id` 标记来源；历史全量快照（runs 维度）作为后续扩展，MVP 不做。
- 增量合并以 `file_id` 为粒度在单事务内"删除受影响文件 + 插入新结果"（见 02 文档 2.4）。
- 开启 WAL 模式；批量插入按文件分批事务（每批 500 文件）。
- `files.checksum` 即增量算法中的 `S_old`（见 02 文档 2.4）。
- `relations.external_target` 为 `metadata.externalTarget` 的冗余列（写入时同步），支撑"所有调用 `java.lang.String` 的方法"这类外部依赖查询，避免扫描 metadata JSON（R16）。

## 3.3 图谱层模型（Neo4j）

节点：

- UCM 实体按 `type` 映射为标签（`:Project`、`:Module`、`:File`、`:Class` 等），公共属性：`id`（唯一约束）、`name`、`qualifiedName`、`language`、`locationJson`。
- 关系与 UCM `relations.type` 同名一一对应。

外部虚拟节点（R16）：

- 对高频外部目标（按出现次数 topN，默认 1000，可配 0 = 关闭）建立 `:External` 虚拟节点：`id` 以 `ext:` 前缀命名（不参与实体 ID 体系、不进入 symbolTable），属性含 `qualifiedName`。
- `CALLS` / `REFERENCES` 等关系照常连接到虚拟节点，使外部依赖可在图谱中查询；未达阈值的低频外部目标不建边，走事实层 `external_target` 列查询。
- 投影重建时虚拟节点由事实层按当前频率重新生成。

约束与索引：

~~~cypher
CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE;
CREATE INDEX qname IF NOT EXISTS FOR (e:Entity) ON (e.qualifiedName);
~~~

（实现时以实际标签集建立等价约束。）

洞察节点（ADR-007）：

| 字段 | 说明 |
|---|---|
| `id` | 洞察唯一 ID |
| `entityId` | 关联实体 |
| `kind` | `summary` \| `business_rule` \| `arch_role` \| `tech_debt` \| `data_flow_note` |
| `content` | 结构化 JSON，如 `{ "rule": "金额必须大于0", "evidence": [...] }` |
| `confidence` | 0–1 |
| `model` / `promptVersion` | 产生来源（规则引擎则 `model=rule-engine`） |
| `status` | `pending` \| `approved` \| `rejected` \| `superseded` |
| `evidenceJson` | 证据：实体 ID + 代码位置 + 相关代码摘要 |
| `codeChecksum` | 产生该洞察时的实体代码校验和（用于过期判定） |
| `createdAt` / `updatedAt` | 时间戳 |

- 关系：`(entity)-[:HAS_INSIGHT]->(insight)`。
- 规则引擎产出的洞察（确定性）`status` 直接为 `approved` 且 `model=rule-engine`，无需人工审核；LLM 产出一律 `pending`。

审计节点：

| 字段 | 说明 |
|---|---|
| `id` / `insightId` | 审计记录 ID / 目标洞察 |
| `action` | `approve` \| `modify` \| `reject` \| `auto_approve` \| `supersede` |
| `before` / `after` | 变更前后内容 JSON（reject 时 after 为空） |
| `actor` | `human` \| `rule` \| `system` |
| `at` | 时间戳 |

- 关系：`(insight)-[:HAS_AUDIT]->(audit)`。

## 3.4 洞察审核状态机

~~~text
规则引擎(确定性) ──────────────────────────────► approved
LLM 产出 ──► pending ──批准──────────────────► approved
              │  │
              │  └─修改后批准─────────────────► approved（before/after 留审计）
              └─丢弃────────────────────────► rejected
approved ──代码变更(codeChecksum 不匹配)──────► superseded（触发重新分析）
~~~

规则：

- 只有 `status=approved` 的洞察允许被生成层引用（见 04 文档）。
- `superseded`：实体代码校验和变化后，其全部关联洞察被标记过期，pending 待重分析；历史审计链永久保留。
- 回滚 = 状态转移 + 审计记录，不做物理删除。
- 每次审核操作使项目级 `insightVersion` 加 1，作为 generation 阶段缓存键的一部分（R21，见 04 文档 4.5）。

MVP 降级（ADR-004）：无 Neo4j 部署时，用 SQLite 追加以下表实现同构模型（实体/关系即图，BFS 遍历子图）：

~~~sql
CREATE TABLE insights (
  id             TEXT PRIMARY KEY,
  project_id     TEXT NOT NULL REFERENCES projects(id),   -- R17：显式归属，级联清理
  entity_id      TEXT NOT NULL REFERENCES entities(id),
  kind           TEXT NOT NULL,
  content_json   TEXT NOT NULL,
  confidence     REAL,
  model          TEXT,
  prompt_version TEXT,
  status         TEXT NOT NULL,
  evidence_json  TEXT,
  code_checksum  TEXT,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL
);

CREATE TABLE audit_records (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL REFERENCES projects(id),
  insight_id TEXT NOT NULL REFERENCES insights(id),
  action     TEXT NOT NULL,
  before_json TEXT,
  after_json  TEXT,
  actor      TEXT NOT NULL,
  at         TEXT NOT NULL
);
~~~

- 项目软删除（`projects.deleted_at`）时级联清理该项目的洞察与审计记录（R17）。
- 实体删除导致 `entity_id` 悬空的洞察：保持挂起并标记 `superseded`，不物理删除（保留审计链）。

## 3.5 LLM 调用治理

- 调度：优先级队列。重要度评分 = 实体类型权重 × 扇出度 × 变更热度；入口类、核心业务方法、高扇出函数优先。
- 批量异步：并发上限与速率限制可配；每批次结果写回前统一做 JSON schema 校验（失败 → 重试一次 → 记 `LLM.PARSE_ERROR` 降级）。
- 缓存：键 = `hash(entityId, codeChecksum, promptVersion, model)`；命中直接复用。
- 失败降级：可配置开关（全局 / 按项目 / 按 kind），指数退避重试（上限 3 次），超时可配。
- 成本预算：每项目每日 token 预算；超限后仅运行确定性规则，界面提示。
- 提示版本：`promptVersion` 变化 → 缓存失效 → 重新生成并将旧洞察 `supersede`。
- 配置：支持 openai 与类 openai 兼容接口（用户可配置 baseUrl / model / key / 开关 / 预算），接口见 04 文档。

## 3.6 事实层治理表：insight_approvals（ADR-009，R18）

动机：审计链只存于图谱层时，图谱降级/重建会丢失 approved 状态。治理表在事实层持久化"已批准洞察"的权威快照。

~~~sql
CREATE TABLE insight_approvals (
  id            TEXT PRIMARY KEY,
  project_id    TEXT NOT NULL REFERENCES projects(id),
  insight_id    TEXT NOT NULL,
  entity_id     TEXT NOT NULL REFERENCES entities(id),
  kind          TEXT NOT NULL,
  content_json  TEXT NOT NULL,          -- 批准/修改后的最终内容
  actor         TEXT NOT NULL,          -- human | rule
  approved_at   TEXT NOT NULL
);

CREATE INDEX idx_approvals_project ON insight_approvals(project_id);
CREATE INDEX idx_approvals_entity  ON insight_approvals(entity_id);
~~~

规则：

- `approve` / `modify`（含规则引擎 `auto_approve`）动作成功时，同步 upsert 本表；`reject` / `supersede` 时删除对应行。
- 图谱层重建投影后，据此表恢复全部 `approved` 洞察；未在此表中的洞察一律视为 pending（或按 `superseded` 处理）。
- 边界：本表只存"已批准"终态快照与最小审计字段，完整审计链（含 before/after 与拒绝历史）仍在图谱层审计节点；与确定性事实表物理隔离，不违反 ADR-007。
- 待审核队列本身不持久化于此表——pending 洞察存于图谱层（或降级模式下的 `insights` 表），重建后按需重跑 enrichment 恢复。
