# 遗留系统 AI 重构与文档生成器 — 细化设计文档集

> 版本 v0.3 · 本文档集对原始设计文稿中第 3.2 / 3.3 / 3.5 / 6 章进行细化与修订。总体架构、分层划分与技术选型主体不变；本文档集补齐可执行的接口契约、数据模型、算法与验收标准。两者冲突时，以本目录文档为准。
>
> v0.3 为第二轮评审（潜在风险与改进建议）修订版：全部 13 条意见采纳为 R10–R23，并新增 ADR-008 / ADR-009。

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-ucm.md](./01-ucm.md) | 统一代码模型（UCM）：Entity / Relation 规范、TypeRef、确定性 ID、符号表、校验规则、signature ABNF 附录 |
| [02-parser-incremental.md](./02-parser-incremental.md) | 解析层接口契约、JDT 实现细则、Clang 子进程协议、增量解析算法 |
| [03-data-model.md](./03-data-model.md) | SQLite 事实存储、图数据库模型、AI 洞察与审核状态机、LLM 调用治理 |
| [04-generation-api.md](./04-generation-api.md) | 文档与图表生成模型、证据锚点、REST / WebSocket API、分阶段验收标准 |

## 修订记录（v0.1 → v0.2）

| 编号 | 修订点 | 对应评审意见 |
|---|---|---|
| R1 | UCM 增加 `typeRef` / `genericParameters` / `docComment`；`ParseResult` 增加 `symbolTable` 与 `fileIndex` | 意见 1 |
| R2 | 明确 Java classpath 获取策略（Maven / Gradle Tooling API）；定义 Clang 子进程 JSON 协议 | 意见 2、3 |
| R3 | 补充宏记录（自定义 `PPCallbacks`）与模板降级策略 | 意见 3 |
| R4 | AI 洞察改为独立节点建模 + `pending/approved/rejected` 状态机 + 审计记录，不直接改写确定性事实 | 意见 4 |
| R5 | 证据锚点机制扩展至全部生成内容（template / llm / human 三类来源） | 意见 5 |
| R6 | 中间存储收敛为 SQLite；Parquet 移入超大规模扩展选项 | 意见 6 |
| R7 | 定义 dirty-set 传播 + 阈值全量回退的增量解析算法 | 意见 7 |
| R8 | 风险表补充 LLM 成本控制与多项目隔离 | 意见 8 |
| R9 | 为每个阶段补充量化验收标准 | 意见 9 |

## 修订记录（v0.2 → v0.3）

| 编号 | 修订点 | 对应评审意见 |
|---|---|---|
| R10 | signature 规范化以 ABNF 附录落定：Java 用 JVM 描述符（泛型按擦除），C++ 用 Clang canonical 类型序列化；Validator 增加格式校验 | 1.1 |
| R11 | 关系 ID 改为 `srcId:TYPE:targetId@startLine:startCol`（位置参与），同位置多条按（列, 参数索引）升序子编号，消除遍历顺序依赖 | 1.2 |
| R12 | 模板策略增加 `whitelist`（高频模板实例化记录）；跳过时产出 `CPP.TEMPLATE_INSTANTIATION_SKIPPED` issue；生成层标注调用图完整性 | 1.3 |
| R13 | 宏展开位置一律回溯 spelling location；`Macro` 实体记录宏体原文；展开点 → 宏定义建立 `REFERENCES` 关系 | 1.4 |
| R14 | 反向传播关系集加入 `REFERENCES`；增加保守性声明；宏/模板密集文件自动加深传播或强制全量 | 2.1 |
| R15 | 全量回退判定支持扇出加权（`entity-weight`）与手动触发，策略可配置 | 2.2 |
| R16 | 事实层 `relations` 增加 `external_target` 冗余列 + 索引；图谱层支持 `:External` 虚拟节点（频率阈值可配） | 3.1 |
| R17 | SQLite `insights` 表增加 `project_id`，项目删除时级联清理 | 3.2 |
| R18 | 事实层增加治理表 `insight_approvals`（已批准洞察快照），图谱重建后据此恢复 approved 状态 | 3.3 |
| R19 | 生成层增加引用校验器 RefValidator（实体存在性 + approved 状态校验，失败自动重试）；验收增加引用准确率 ≥ 95% | 4.1 |
| R20 | 定义 `source://` 链接三端行为（Web 预览浮层 / 桌面打开本地文件 / PDF·Word 降级脚注）；新增文件内容预览 API | 4.2 |
| R21 | generation 阶段输入哈希纳入图谱最后修改时间与洞察审核版本号（insightVersion） | 4.3 |
| R22 | 项目管理生命周期端点：软删除、配置更新、归档 | 5.1 |
| R23 | WebSocket 增加 `pipeline.stage.error` 事件 | 5.2 |

## 架构决策记录（ADR）

| 编号 | 决策 | 状态 |
|---|---|---|
| ADR-001 | 后端采用 Java / Spring Boot；JDT 作为进程内库解析 Java | 已接受 |
| ADR-002 | Clang 解析器以独立子进程运行，通过 stdin/stdout 的 JSON 协议交换 UCM 片段 | 已接受 |
| ADR-003 | 事实层（UCM）存储采用 SQLite；Parquet 仅作为超大规模扩展选项 | 已接受 |
| ADR-004 | 图谱存储默认 Neo4j；MVP 允许降级为 SQLite 图模式（单机简化部署） | 已接受 |
| ADR-005 | 实体 ID 按确定性规则生成（语言 + 全限定名 + 签名，位置兜底），跨运行稳定 | 已接受 |
| ADR-006 | 增量解析采用 dirty-set 反向传播，波及面超过阈值（可配：文件数比例或扇出加权）时回退全量 | 已接受 |
| ADR-007 | AI 洞察独立建模（`Insight` 节点），未经人工审核不进入生成层；确定性事实（entities/relations）永不被 AI 改写 | 已接受 |
| ADR-008 | 关系 ID 由 `srcId:TYPE:targetId@startLine:startCol` 构成（位置参与），同位置多条按（列, 参数索引）升序子编号，消除解析/遍历顺序依赖 | 已接受 |
| ADR-009 | 已批准洞察的快照持久化于事实层治理表 `insight_approvals`，与确定性事实物理隔离（不同表），图谱重建后据此恢复 approved 状态 | 已接受 |

## 与原稿的关系

- 保留：五层管道架构、单向数据流 + 反馈回流、语言路线（Java → C/C++）、技术选型表。
- 细化：第 3.2 章 → 01、02 文档；第 3.3 章 → 03 文档；第 3.4 / 3.5 章 → 04 文档；第 6 章路线图 → 04 文档的分阶段验收标准。
- 变更：见上表 R1–R9、R10–R23。
