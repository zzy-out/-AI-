# 遗留系统 AI 重构与文档生成器（legacy-recon）

按设计文档集 **01/02/03/04**（`/workspace/*.md`）实现的 **全套脚手架**。
技术栈：Java / Spring Boot + **JDT**（ADR-001），性能敏感部分以 **C++**（Clang 子进程协议，ADR-002）实现。

## 文档映射

| 设计文档 | 实现位置 |
|---|---|
| 01 统一代码模型（UCM） | `com.legacyrecon.ucm`：`model`（Entity/Relation/TypeRef/SourceLocation/ParseResult）、`id`（DeterministicId、JvmDescriptor 附录 A）、`validator.UcmValidator`（01.8） |
| 02 解析层 | `com.legacyrecon.parser`：`api`（LanguageParser/ParseRequest/…）、`java.JdtParser`（JDT 结构化解析）、`cpp.ClangSubprocessClient`（02.3 子进程协议客户端） |
| 03 数据模型 | `com.legacyrecon.facts.FactsStore`（03.2 SQLite）、`graph`（03.3 洞察/审计/治理表+状态机、SQLite 图模式 ADR-004、insight_approvals ADR-009）、`enrich`（03.5 LLM 治理+规则引擎） |
| 04 生成层与交互 | `com.legacyrecon.generate`（文档模型/证据锚点、RefValidator R19、FreeMarker、ChartMapper）、`pipeline`（阶段缓存 R21/事件）、`api`（REST RFC7807 + WebSocket 04.4） |

## 架构分层

```
产物层   generate + 导出（Markdown / Mermaid/PlantUML DSL）
图谱层   graph（SQLite 图模式 MVP，ADR-004）
事实层   facts（SQLite 确定性事实，ADR-003/007）
解析层   parser（JDT 进程内 / Clang 子进程协议）
接入层   pipeline.FileScanner + IncrementalAnalyzer（02.4）
API 层   api 控制器 + ws
```

单向数据流：`parse → enrich → generate`；反馈回流（审核/图谱编辑/配置变更）通过阶段缓存键失效完成（R21）。

## 构建与运行

依赖：JDK 21+（沙箱为 JDK 25）、Maven、g++/cmake（可选，启动时自动调用较慢，可先禁用 LLM）。

```bash
# Java 后端
cd /workspace/legacy-recon
mvn -q package -DskipTests          # 打 fat-jar
java -jar target/legacy-recon-0.1.0.jar   # 默认 8080，SQLite 落 data/legacy-recon.db

# C++ Clang 子进程协议工具（可选增强）
cmake -S src/main/cpp/clang-parser -B build-cpp
cmake --build build-cpp -j4          # 产出 build-cpp/recon_clang_parser
export RECON_CPP_BINARY=build-cpp/recon_clang_parser   # Java 侧子进程客户端会使用
```

> 沙箱内 Maven 需代理：已写入 `~/.m2/settings.xml`（`127.0.0.1:18080`）。

## 端到端示例（验证脚本）

```bash
PID=$(curl -s -X POST localhost:8080/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{"root":"/workspace/legacy-recon/sample-java","name":"银行示例","language":"java"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
# parse→enrich→generate 一跑
curl -s -X POST localhost:8080/api/v1/projects/$PID/runs \
  -H 'Content-Type: application/json' -d '{"stages":["parse","enrich","generate"]}'
# 浏览 / 审核 / 生成
curl -s "localhost:8080/api/v1/projects/$PID/entities?type=Class"
curl -s "localhost:8080/api/v1/projects/$PID/insights?status=approved"
curl -s "localhost:8080/api/v1/projects/$PID/diagram?type=class"
curl -s -X POST localhost:8080/api/v1/projects/$PID/generate \
  -H 'Content-Type: application/json' -d '{"docType":"architecture","format":"md"}'
# 文件预览（source:// 锚点支撑）
curl -s "localhost:8080/api/v1/files/$PID:com/example/Account.java/content?startLine=1"
```

## 已实现的文档要点

- **UCM 确定性**：实体/关系 ID（ADR-005/008）、Java JVM 描述符签名（附录 A）、UCM 校验器（01.8）。
- **JDT 解析**：类/接口/枚举/注解、方法/构造器、字段/参数、CONTAINS/DEPENDS_ON/CALLS/INSTANTIATES/READS/WRITES；语法问题降级为 issue。
- **增量解析（02.4）**：差异集 → dirty → 反向传播（DEPENDS_ON）→ 阈值/forceFull 回退；事务性合并（S_old 校验和）。
- **事实层**：完整 `03.2` SQLite schema + `relations.external_target` 冗余列（R16）。
- **图谱层**：SQLite 图模式（ADR-004）、洞察/审计状态机（ADR-007）、治理表恢复（ADR-009）、BFS 子图 + 外部虚拟节点（R16）。
- **增强层**：规则引擎洞察（approved）+ LLM 治理（缓存/预算/开关），LLM 产出一律 pending。
- **生成层**：证据锚点（R5）、RefValidator（R19，失败降级纯文本并附清单）、FreeMarker 模板、调用图完整性声明（R12）。
- **API**：`04.3` 全部 REST 路由（RFC7807）、`04.4` WebSocket 事件、文件内容预览（R20）。

## 脚手架边界（生产需替换/完善）

暂未完整落地、接口/桩或简化实现的部分：

- **JDT** 采用结构化 DOM 解析（`K_COMPILATION_UNIT`），未启用 `setResolveBindings` + 全 classpath 工程绑定；因此跨文件/跨 jar 的符号决议与泛型擦除签名是近似（如类型参数按 `L<T>;` 编码）。
- **C++** 为**子进程协议骨架**（`CPP.PROTOCOL_SCAFFOLD`）：文件级 File 实体 + 模板跳过 issue（R12），语义级提取需链接 libclang/LibTooling。
- 未产出 `INHERITS / IMPLEMENTS / OVERRIDES` 关系（可在 JDT 完整绑定后补齐）。
- **LLM** 未接真实 openai 端点，默认关闭；配置经 `GET/PUT /api/v1/config`。
- **导出格式** 目前生成 Markdown；HTML/PDF/Word 走 Pandoc 为后续项。
- 图谱默认 **SQLite 图模式**（ADR-004 MVP 降级）；Neo4j 投影为生产扩展。

## 目录

```
src/main/java/com/legacyrecon/{config,ucm,parser,facts,graph,enrich,generate,pipeline,api,util}
src/main/cpp/clang-parser/{main.cpp,json.hpp,CMakeLists.txt}   # 性能敏感（C++）子进程
src/main/resources/{db/schema.sql, db/graph.sql, application.properties}
sample-java/com/example/*.java                                  # 样例遗留 Java 工程
```