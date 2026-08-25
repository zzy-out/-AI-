# 04 生成层与交互层接口

## 4.1 文档模型与证据锚点（R5）

~~~json
{
  "meta": {
    "title": "架构说明文档",
    "projectId": "p1",
    "runId": "r42",
    "generatedAt": "2026-01-01T00:00:00Z",
    "generatorVersion": "0.3.0"
  },
  "sections": [
    {
      "id": "sec-1",
      "title": "总体架构",
      "kind": "llm",
      "content": "本系统采用分层架构……",
      "entityRefs": ["java:com.example.App", "java:com.example.core"],
      "evidenceRefs": [
        {
          "entityId": "java:com.example.App",
          "location": { "file": "src/main/java/com/example/App.java", "startLine": 1, "endLine": 2 },
          "quote": "public class App {"
        }
      ],
      "templateId": null,
      "promptVersion": "summary-v3"
    }
  ]
}
~~~

规则：

- `kind` 三选一：`template`（模板渲染）/`llm`（模型生成）/`human`（人工撰写）。三者都携带 `entityRefs` 与 `evidenceRefs`，保证全文档可追溯（R5）。
- `kind=llm` 的段落只允许引用 `status=approved` 的洞察与确定性事实；引用关系写进 `entityRefs`。
- 一致性：一次生成绑定单个 `runId` 事实快照，保证文档内部自洽与可复现。

引用校验器 RefValidator（R19）：

- 渲染/导出前，对每个 `entityRefs` 与 `evidenceRefs` 条目校验：
  - 实体 ID 必须存在于该 `runId` 快照的事实层中；
  - 引用洞察时其 `status` 必须为 `approved`；
  - `evidenceRefs.location` 必须与实体实际位置一致（行号落在实体区间内）。
- 校验失败 → 自动重试一次（将失败清单回传给模型要求修正）→ 仍失败则该段落标记 `validationError`，该引用降级渲染为纯文本（不带链接），并在文档末尾附"未通过校验的引用清单"。
- 可选后处理：LLM 文本中内嵌的实体名（类名/方法名等）经正则匹配事实层实体后自动转为锚点链接，进一步提升追溯性（默认开启，可配）。

锚点渲染与 source:// 行为（R20）：

- Markdown 输出：证据渲染为 `[↗ path#L12-L20](source://path#L12-L20)`。
- Web 端：拦截 `source://` 链接，调用文件内容 API 弹出代码预览浮层（高亮行范围）。
- 桌面 / IDE 插件：尝试打开本地文件并跳转到行号。
- PDF / Word：无超链接能力，降级为脚注「文件路径:起始行-结束行」+ 静态代码块（截取证据区间），保证离线可读。

## 4.2 模板与图表生成

- 模板引擎 FreeMarker；模板库：项目概述、模块说明、API 文档、数据字典；模板数据绑定由图谱查询结果提供，绑定的实体自动进入 `entityRefs`。
- 图表 DSL：Mermaid / PlantUML。UCM 关系 → 图类型映射：

| 输入 | 图类型 |
|---|---|
| `DEPENDS_ON`（模块级） | 组件图 / 分层图 |
| `INHERITS` + `IMPLEMENTS` + `CONTAINS` | 类图 |
| `CALLS` | 调用图 / 时序图 |
| 控制流（解析层可选扩展） | 流程图 |

- 渲染：SVG / PNG 嵌入文档；节点数超过上限（默认 200）时按模块聚类折叠，防止大图不可读。
- 调用图完整性声明（R12）：若本次解析存在 `CPP.TEMPLATE_INSTANTIATION_SKIPPED`（或 `templatePolicy != full`），生成的调用图/依赖文档必须显式标注"调用图不完整：模板实例化未记录（跳过 N 处）"，N 取自 `stats.skippedTemplateInstantiations`。
- 文档格式转换：Markdown → HTML/PDF/Word 走 Pandoc。

## 4.3 REST API（v1）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/projects` | 导入项目（root、name、language、config），触发接入层扫描 |
| GET | `/api/v1/projects/{id}` | 项目信息与扫描清单 |
| PUT | `/api/v1/projects/{id}/config` | 更新项目配置（语言、构建选项、LLM 开关等） |
| DELETE | `/api/v1/projects/{id}` | 软删除（`projects.deleted_at` 标记，级联清理洞察与审计） |
| POST | `/api/v1/projects/{id}/archive` | 归档（只读，禁止新 run；产物保留） |
| POST | `/api/v1/projects/{id}/runs` | 触发管道：`{stages:["parse","enrich","generate"], forceFull?, clientRequestId?}` |
| GET | `/api/v1/projects/{id}/runs/{runId}` | 运行状态与统计 |
| GET | `/api/v1/projects/{id}/entities?type=&q=&limit=&offset=` | 实体搜索 |
| GET | `/api/v1/projects/{id}/relations?sourceId=&type=&externalTarget=` | 关系查询（`externalTarget` 支持外部依赖查询） |
| GET | `/api/v1/projects/{id}/graph?entityId=&depth=2` | 子图查询（图谱浏览器用） |
| GET | `/api/v1/projects/{id}/insights?status=&kind=&entityId=` | 洞察列表（审核界面用） |
| PATCH | `/api/v1/projects/{id}/insights/{insightId}` | 审核：`{action: approve|modify|reject, content?}`；成功时 `insightVersion` +1 |
| POST | `/api/v1/projects/{id}/generate` | 生成文档：`{docType: architecture|module|api|data_dict, format: md|html|pdf|docx}` |
| GET | `/api/v1/projects/{id}/exports/{exportId}` | 下载导出产物 |
| GET | `/api/v1/files/{fileId}/content?startLine=&endLine=` | 文件内容（行范围），支撑 `source://` 预览浮层 |
| GET/PUT | `/api/v1/config` | 查看 / 修改 LLM 配置（openai/类 openai 的 baseUrl、model、key、开关、预算） |

约定：

- 错误统一 RFC 7807 `problem+json`。
- `runs` 与 `generate` 支持 `clientRequestId` 幂等。
- 多项目隔离：所有资源挂载在 `projects/{id}` 之下，图谱/事实层按 `project_id` 分区（R8）；软删除与归档遵循 R22。

## 4.4 WebSocket 事件

端点：`/api/v1/ws/projects/{id}`

| 事件 | 载荷 |
|---|---|
| `pipeline.stage.started` / `pipeline.stage.finished` | `{stage, runId}` |
| `pipeline.stage.error` | `{stage, runId, errorCode, message}`（R23：解析失败、LLM 批量失败、校验失败等实时通知） |
| `parse.file.progress` | `{done, total}` |
| `enrichment.insight.pending` | `{count}`（待审核增量） |
| `run.completed` | `{runId, stats}` |
| `export.ready` | `{exportId, format}` |

## 4.5 阶段缓存与迭代触发

- 阶段输入哈希：每阶段记录输入摘要（上游输出哈希 + 配置哈希）。`runs` 请求中未变更的阶段命中缓存则跳过。
- generation 阶段输入哈希（R21）= 上游输出哈希 + 配置哈希 + **图谱最后修改时间** + **项目级 `insightVersion`**；人工审核洞察或编辑图谱后缓存必然失效，保证重算。
- 反馈回流的最小重算路径：

| 用户操作 | 重跑范围 |
|---|---|
| 审核洞察（approve/modify/reject） | enrichment 下游 + generation（`insightVersion` +1 使 generation 缓存失效） |
| 人工编辑图谱属性 | generation（图谱修改时间戳使缓存失效） |
| 修改 LLM 配置 / prompt 版本 | enrichment（LLM 部分）+ generation |
| 源码变化 | parse（增量或全量）→ 下游全部 |

## 4.6 分阶段验收标准（R9）

阶段 1（MVP，Java 闭环）：

- 功能：项目扫描 → JDT 解析 → UCM 入库 → 图谱浏览（节点/边/搜索/子图）→ 模板文档（项目概述、模块说明）导出 Markdown。
- 性能：10 万行 Java 全量解析 < 5 分钟（8 核）；单文件变更增量重解析 < 30 秒。
- 质量：黄金样本（≥ 20 个 Java 文件，覆盖泛型、继承、接口、匿名类、注解、重载）实体/关系准确率 ≥ 95%；含语法错误样例可恢复且 issues 完整。
- 确定性：同一项目连续全量 3 次，实体 ID 集合与关系集合完全一致。
- 增量正确性（R14）：修改被多模块依赖的核心类/头文件，验证受影响实体全部重解析、未波及实体 ID 稳定；增量与全量结果一致性 ≥ 99%（抽样对比）。
- 闭环：导入 → 解析 → 浏览 → 导出 全流程 Web 可用。

阶段 2（AI 增强）：LLM 摘要/业务规则提取接入；审核界面与状态机闭环（含 `insightVersion` 缓存失效验证）；类图/调用图生成；`approved` 洞察在文档中带证据锚点；引用准确率 ≥ 95%（抽样 100 条 `entityRefs`，能正确跳转或通过 RefValidator）；增量解析优化达标。

阶段 3（C/C++）：Clang 子进程协议落地；`compile_commands.json` 项目端到端可用；宏/模板按 02 文档策略处理。模板验收：黄金样本含模板类/宏，验证 `whitelist` 实例化调用关系被记录、跳过项在 issues 与文档完整性声明中如实标注；宏展开位置全部为 spelling location。性能与质量基线对齐阶段 1。

阶段 4（高级重构与生态）：重构方案生成引擎（问题识别 → 候选策略 → 风险排序）；VS Code / IntelliJ 插件；Python、C# 扩展。
