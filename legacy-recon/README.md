# 遗留系统 AI 重构与文档生成器（legacy-recon）

按设计文档集 **01/02/03/04**（`/workspace/*.md`）实现的 **全套脚手架**。
技术栈：Java / Spring Boot + **JDT**（ADR-001），性能敏感部分以 **C++**（Clang 子进程协议，ADR-002）实现。

## 文档映射

| 设计文档 | 实现位置 |
|---|---|
| 01 统一代码模型（UCM） | `com.legacyrecon.ucm`：`model`（Entity/Relation/TypeRef/SourceLocation/ParseResult）、`id`（DeterministicId、JvmDescriptor 附录 A）、`validator.UcmValidator`（01.8） |
| 02 解析层 | `com.legacyrecon.parser`：`api`（LanguageParser/ParseRequest/…）、`java.JdtParser`（JDT 绑定模式 + DOM 兜底解析）、`cpp.ClangSubprocessClient`（02.3 子进程协议客户端） |
| 03 数据模型 | `com.legacyrecon.facts.FactsStore`（03.2 SQLite）、`graph`（03.3 洞察/审计/治理表+状态机、SQLite 图模式 ADR-004、insight_approvals ADR-009）、`enrich`（03.5 LLM 治理+规则引擎：`LlmClient` 重试/限流/熔断/预算） |
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

## LLM 增强配置

默认关闭。通过 `GET/PUT /api/v1/config` 配置（对应 `LlmConfig`）：

```bash
curl -s -X PUT localhost:8080/api/v1/config \
  -H 'Content-Type: application/json' \
  -d '{
    "enabled": true,
    "baseUrl": "https://api.openai.com/v1",
    "model": "gpt-4o-mini",
    "apiKey": "sk-...",
    "dailyTokenBudget": 1000000,
    "concurrencyLimit": 4,
    "retryLimit": 3,
    "circuitBreakerFailures": 10,
    "degradeOnLLMError": true
  }'
```

主要治理参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `dailyTokenBudget` | 0（不限） | 单日 token 预算（UTC 自然日），超出后拒绝调用 |
| `concurrencyLimit` | 4 | 并发信号量限流 |
| `retryLimit` / `backoffBaseMs` | 3 / 300ms | 重试次数与指数退避基数（可重试状态码：408/425/429/500/502/503/504） |
| `circuitBreakerFailures` | 10 | 连续失败达到阈值后熔断，连续成功即恢复 |
| `overallTimeoutMs` / `connectTimeoutMs` / `readTimeoutMs` | 60s / 5s / 45s | 分层超时控制 |
| `temperature` / `topP` / `maxTokens` | 0.2 / 0.95 / 1024 | 生成参数（低温度保证稳定输出） |
| `promptVersion*` | `*-prod` | 各洞察类型 Prompt 版本号（随配置可追溯） |
| `degradeOnLLMError` | true | LLM 出错时降级继续流水线，而非中断 |

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
- **JDT 绑定模式**：优先 `setResolveBindings(true)` + `setEnvironment()` 一次性解析全部文件，从 ITypeBinding/IMethodBinding 抽取 **INHERITS / IMPLEMENTS / OVERRIDES** 跨文件关系；绑定失败自动回退 DOM 模式（结构化解析，无跨文件符号决议）。
- **增量解析（02.4）**：差异集 → dirty → 反向传播（DEPENDS_ON）→ 阈值/forceFull 回退；事务性合并（S_old 校验和）。
- **事实层**：完整 `03.2` SQLite schema + `relations.external_target` 冗余列（R16）。
- **图谱层**：SQLite 图模式（ADR-004）、洞察/审计状态机（ADR-007）、治理表恢复（ADR-009）、BFS 子图 + 外部虚拟节点（R16）。
- **增强层**：规则引擎洞察（approved）+ LLM 治理（缓存/预算/开关），LLM 产出一律 pending。
- **LLM 客户端（生产级）**：OpenAI 兼容 chat completions 实现 —— 指数退避重试（可配置重试状态码 408/425/429/5xx）、并发信号量限流、连续失败熔断、单日 token 预算（UTC 自然日）、整体/连接/读超时分层控制；多种洞察类型（summary / arch-role / tech-debt / business-rule）结构化 JSON 解析，Prompt 版本化管理；LLM 错误可配置降级（`degradeOnLLMError`）。
- **生成层**：证据锚点（R5）、RefValidator（R19，失败降级纯文本并附清单）、FreeMarker 模板、调用图完整性声明（R12）。
- **API**：`04.3` 全部 REST 路由（RFC7807）、`04.4` WebSocket 事件、文件内容预览（R20）。

## 脚手架边界（生产需替换/完善）

暂未完整落地、接口/桩或简化实现的部分：

- **JDT**：绑定模式已启用（`setResolveBindings` + `setEnvironment` 全工程绑定，含 INHERITS/IMPLEMENTS/OVERRIDES 跨文件关系）；classpath 获取仍依赖显式配置，泛型擦除签名为近似（如类型参数按 `L<T>;` 编码）。绑定失败自动回退 DOM 模式。
- **C++** 为**子进程协议骨架**（`CPP.PROTOCOL_SCAFFOLD`）：文件级 File 实体 + 模板跳过 issue（R12），语义级提取需链接 libclang/LibTooling。
- **LLM**：客户端为生产级实现（重试/限流/熔断/预算），默认关闭；经 `GET/PUT /api/v1/config` 配置 baseUrl / model / apiKey 后启用。
- **导出格式** 目前生成 Markdown；HTML/PDF/Word 走 Pandoc 为后续项。
- 图谱默认 **SQLite 图模式**（ADR-004 MVP 降级）；Neo4j 投影为生产扩展。

## 目录

```
src/main/java/com/legacyrecon/{config,ucm,parser,facts,graph,enrich,generate,pipeline,api,util}
src/main/cpp/clang-parser/{main.cpp,json.hpp,CMakeLists.txt}   # 性能敏感（C++）子进程
src/main/resources/{db/schema.sql, db/graph.sql, application.properties}
sample-java/com/example/*.java                                  # 样例遗留 Java 工程
```