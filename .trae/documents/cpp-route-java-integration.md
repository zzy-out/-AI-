# 计划（下一步）：C++ 解析链路 Java 侧收尾 —— 客户端补全 + 语言路由 + 端到端验证

> 承接 [cpp-parser-libclang-enhancement.md](file:///workspace/.trae/documents/cpp-parser-libclang-enhancement.md)。libclang 语义提取（实体/关系/宏 R13/模板策略 R12/INHERITS）已在 [main.cpp](file:///workspace/legacy-recon/src/main/cpp/clang-parser/main.cpp) 完成并构建通过（`build-cpp/recon_clang_parser` 已存在，sample-cpp 已就绪）。本计划完成剩余 4 项：Java 客户端补全、cpp 路由打通、端到端验证、README 更新。

## 现状分析

| 项 | 现状 | 依据 |
|---|---|---|
| C++ 子进程解析器 | ✅ 已完成：libclang 语义提取、宏/模板策略、INHERITS（CXXBaseSpecifier L535）、fallback 模式 | [main.cpp](file:///workspace/legacy-recon/src/main/cpp/clang-parser/main.cpp) |
| C++ 构建 | ✅ `build-cpp/recon_clang_parser` 已产出（RECON_HAS_LIBCLANG 生效） | 已验证 |
| sample-cpp | ✅ include/account.h + src/account.cpp 已就绪 | 已验证 |
| Java 客户端 | ❌ `compileCommands` 硬编码空列表；options 仅传 recordMacros/templatePolicy，缺 fallbackIncludeDirs/fallbackDefines/standard/templateWhitelist | [ClangSubprocessClient.java:69-79](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L69-L79) |
| 语言路由 | ❌ `PipelineService.lang()` 硬编码返回 `"java"`；解析器为单 Bean 注入 | [PipelineService.java:199-202](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/PipelineService.java#L199-L202) |
| Bean 冲突 | ❌ `@Bean languageParser`（JdtParser，非 @Component）+ `@Component ClangSubprocessClient` 双 Bean，构造器注入单个 `LanguageParser` 无 @Primary → 启动 NoUniqueBeanDefinitionException 隐患 | [LegacyReconApplication.java:27-30](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/LegacyReconApplication.java#L27-L30)、[ClangSubprocessClient.java:18](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L18) |
| language 字段 | 项目导入时写在 configJson 顶层（`configJson.put("language", ...)`），但 `ProjectConfig` 反序列化看不到；`run()` 中已有原始 `project.configJson` 可读 | [ProjectController.java:46](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/api/controller/ProjectController.java#L46)、[PipelineService.java:93](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/PipelineService.java#L93) |
| 二进制默认路径 | 默认 `build/recon_clang_parser`（相对路径），实际构建产物在 `build-cpp/` → e2e 会找不到二进制 | [ClangSubprocessClient.java:22-25](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L22-L25) |
| FileScanner | ✅ 已支持 .cpp/.cc/.h/.hpp | [FileScanner.java:38-40](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/FileScanner.java#L38-L40) |

## 变更内容

### 1. ClangSubprocessClient 补全协议字段

**修改** [ClangSubprocessClient.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java)：

- **compileCommands**：替换 L69 的 `Collections.emptyList()`。若 `request.config.cpp.compileCommandsPath` 非空：用 `Json.fromJson(content, new TypeReference<List<Map<String,Object>>>() {})` 解析该文件，过滤仅保留 `directory`+`file` 能匹配本批 `absolutePath` 的条目；文件不存在/解析失败 → 保持空列表 + `out.issues.add(ParseIssue.warning("CPP.COMPILE_DB_MISSING", ...))`。
- **options 增传**：`fallbackIncludeDirs`、`fallbackDefines`、`standard`、`templateWhitelist`（来自 `request.config.cpp`，含默认值兜底），与 recordMacros/templatePolicy 并列。
- **二进制路径解析**：默认路径依次尝试 `RECON_CPP_BINARY` 环境变量 → `build-cpp/recon_clang_parser` → `build/recon_clang_parser`（取第一个 `Files.exists` 的），解析在 `parse()` 内对 `binaryPath` 做（保持字段可 set 覆盖）。

### 2. 打通 cpp 语言路由（同时消除双 Bean 冲突）

- **修改** [PipelineService.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/PipelineService.java)：
  - 构造器 `LanguageParser parser` → `List<LanguageParser> parsers`，构建 `Map<String, LanguageParser> parsersByLang`（key = `parser.language()`）；
  - `lang(config)` → `lang(Project project)`：从 `project.configJson` 原始 JSON 顶层读 `language`（`Json.fromJson(project.configJson, Map.class).get("language")`），默认 `"java"`；调用点 L94 同步改；
  - L118 `parser.parse(parseReq)` → `parsersByLang.get(lang).parse(parseReq)`；未知语言抛 `IllegalArgumentException`。
- **修改** [LegacyReconApplication.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/LegacyReconApplication.java)：删除 `languageParser()` @Bean（L26-30）。
- **修改** [JdtParser.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/java/JdtParser.java)：类上加 `@Component`（与 ClangSubprocessClient 一致，由容器扫描注册）。

### 3. 构建与端到端验证

1. `mvn -q package -DskipTests`（Java 编译 + 上下文测试不跑）。
2. 应用启动验证（Bean 冲突修复、双解析器注册）。
3. C++ 端到端（启动应用后）：
   - `POST /api/v1/projects`（root=`<abs>/sample-cpp`, language=cpp）→ fileCount=2；
   - `POST /api/v1/projects/{id}/runs`（parse+enrich+generate）；
   - `GET /api/v1/projects/{id}/entities?type=Class` 出现 `banking::Account`/`banking::SavingsAccount`；relations 含 INHERITS（SavingsAccount→Account）、CALLS、REFERENCES（宏展开）；
   - `GET /generate` 产出非空 Markdown。
4. Java 回归：sample-java 端到端流程与之前一致（README 验证脚本），确认路由改动无回归。

### 4. 更新 README

**修改** [README.md](file:///workspace/legacy-recon/README.md)：脚手架边界中 C++ 条目改为"libclang 语义级提取（实体/关系/宏/模板策略）"；标注已知边界（READS/WRITES 不区分、IMPLEMENTS/OVERRIDES 不产出、实例化默认跳过）；构建章节补 libclang 链接与 `build-cpp` 说明；目录树补 sample-cpp；快速验证补 cpp 项目示例命令。

## 假设与决策

1. compile_commands.json 按 `file` 字段与本批 absolutePath 后缀匹配（绝对路径直接相等或以 file 结尾），无需规范化到 realpath。
2. language 存 configJson 顶层（导入时已写入），不改 ProjectConfig 类——避免序列化兼容问题。
3. JdtParser 改 @Component 后由组件扫描注册，LegacyReconApplication 的 @Bean 删除，Spring Boot 测试上下文（如有直接注入 LanguageParser 的测试）需同步检查——验证阶段跑 `mvn test` 确认。
4. 二进制路径多候选解析仅在默认值路径上做，显式 setBinaryPath/环境变量优先。

## 验证步骤

见"变更内容 3"（构建 → 启动 → cpp e2e → Java 回归 → README 与实际能力一致性核对）。
