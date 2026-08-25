# 计划：C++ 解析器增强 —— libclang 语义级提取（阶段 3）

## 概要

将 C++ 解析器从协议骨架（仅 File 实体 + 启发式计数）升级为基于 **libclang C API** 的语义级提取：产出完整 UCM 实体（Class/Struct/Function/Method/Macro/Namespace 等）与关系（CONTAINS/CALLS/INHERITS/REFERENCES 等），实现 R12 模板策略、R13 宏记录（spelling location）、编译数据库与 fallback 降级（02.3 规范），并打通 Java 侧 cpp 项目路由。

## 现状分析

| 项 | 现状 | 依据 |
|---|---|---|
| C++ 子进程 | 协议骨架：仅 File 实体 + `CPP.PROTOCOL_SCAFFOLD` issue + 启发式函数计数 | [main.cpp](file:///workspace/legacy-recon/src/main/cpp/clang-parser/main.cpp#L84-L129) |
| CMake | 未链接 libclang | [CMakeLists.txt](file:///workspace/legacy-recon/src/main/cpp/clang-parser/CMakeLists.txt) |
| libclang 运行库 | **存在**：`/usr/lib/llvm-18/lib/libclang.so.1 → libclang-18.so.18`；CMake 配置在 `/usr/lib/llvm-18/lib/cmake/clang/` | 已验证 |
| clang-c 头文件 | **缺失**：`/usr/include/clang-c/` 与 `/usr/lib/llvm-18/include/` 均不存在，全盘 `find` 无结果，apt 无 libclang-18-dev 缓存 | 已验证 |
| Java 客户端 | `compileCommands` 硬编码空列表；未传 fallbackIncludeDirs/fallbackDefines/standard/templateWhitelist | [ClangSubprocessClient.java:69-79](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L69-L79) |
| 语言路由 | `PipelineService.lang()` 硬编码返回 `"java"`，cpp 项目无法路由到 Clang 解析器 | [PipelineService.java:199-202](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/PipelineService.java#L199-L202) |
| Bean 冲突（潜在 Bug） | `LanguageParser` 有两个 Bean：`@Bean languageParser`（JdtParser）+ `@Component ClangSubprocessClient`，而 `PipelineService` 构造器注入单个 `LanguageParser`，无 `@Primary` → 启动时 NoUniqueBeanDefinitionException | [LegacyReconApplication.java:27-30](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/LegacyReconApplication.java#L27-L30)、[ClangSubprocessClient.java:18](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L18) |
| language 字段 | 项目导入时 `language` 存于 configJson 顶层（`configJson.put("language", req.language)`），但 `PipelineService` 用 `ProjectConfig` 反序列化看不到它 | [ProjectController.java:46](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/api/controller/ProjectController.java#L46) |
| FileScanner | 已支持 cpp/c 扩展名（.cpp/.cc/.h/.hpp），无需改动 | [FileScanner.java:38-40](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/FileScanner.java#L38-L40) |
| UCM 类型 | `EntityType`/`RelationType` 枚举已含全部 C++ 所需类型（Macro/Function/Method/Constructor/Destructor/Namespace/Union…；CALLS/INHERITS/REFERENCES…），无需改动 | [EntityType.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/ucm/model/EntityType.java)、[RelationType.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/ucm/model/RelationType.java) |
| 协议契约 | 响应 entities/relations 为 JSON map，字段须匹配 `Entity`/`Relation`（id/type/name/qualifiedName/language/location/signature/metadata；id/type/sourceId/targetId/location/metadata）；关系 ID 按 ADR-008 `srcId:TYPE:targetId@line:col`；SourceLocation 半开区间行/列 1 起 | [ClangSubprocessClient.mergeResponse](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java#L128-L177)、[DeterministicId.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/ucm/id/DeterministicId.java) |

## 变更内容

### 1. 引入 clang-c 头文件（vendored）

**新增** `src/main/cpp/clang-parser/clang-c/`（vendored，Apache-2.0 许可，来自 llvm-project release/18.x）：

- 主路径：执行时通过代理用 `curl` 从 `https://raw.githubusercontent.com/llvm/llvm-project/release/18.x/clang/include/clang-c/` 下载所需头文件（Index.h 及其 include 的 CXString.h、CXCursor.h、CXDiagnostic.h、CXSourceLocation.h、CXType.h、CXErrorCode.h、CXCompilationDatabase.h、CXFile.h、Platform.h、Documentation.h、CXPlatformAvailability.h、CXTranslationUnit.h），并在目录内放 LICENSE 说明（Apache-2.0 + LLVM exception）。
- 回退路径（下载失败时）：手写 `clang_c_minimal.h`，仅声明用到的子集（opaque 结构体指针、`CXCursorKind`/`CXDiagnosticSeverity` 等枚举值、`clang_createIndex`/`clang_parseTranslationUnit2`/`clang_visitChildren`/`clang_getCursorSpelling`/`clang_getSpellingLocation` 等函数原型）。libclang C API ABI 稳定，与 libclang-18.so 兼容。

### 2. 重写 C++ 解析器 main.cpp（libclang 语义提取）

**修改** [main.cpp](file:///workspace/legacy-recon/src/main/cpp/clang-parser/main.cpp)，保留协议骨架作为 `#ifndef RECON_HAS_LIBCLANG` 降级路径（当前行为不变）；`#ifdef RECON_HAS_LIBCLANG` 走 libclang 实现：

- **编译参数**：按 `path` 匹配请求中 `compileCommands` 条目（arguments 去掉 argv[0] 与文件路径参数）；无匹配则用 fallback 参数（`-std=<standard>` + `-I<fallbackIncludeDirs>` + `-D<fallbackDefines>`）并产出 `CPP.FALLBACK_MODE` WARNING issue（02.3 降级约定）。
- **TU 解析**：`clang_createIndex(0,0)` + `clang_parseTranslationUnit2`，flags 含 `CXTranslationUnit_DetailedPreprocessingRecord`（宏记录所需）。
- **实体提取**（递归 visitor，`clang_visitChildren`）：
  - `Namespace`（Namespace）、`StructDecl`（Struct）、`ClassDecl/ClassTemplate`（Class）、`UnionDecl`（Union）、`EnumDecl`（Enum）、`EnumConstantDecl`（EnumConstant）、`FieldDecl`（Field）、`VarDecl`（Variable）、`FunctionDecl/FunctionTemplate`（Function）、`CXXMethod`（Method）、`Constructor`（Constructor）、`Destructor`（Destructor）、`MacroDefinition`（Macro，`metadata.macroBody` 存宏体原文、参数列表存 `metadata.macroParams`）。
  - qualifiedName：沿 `clang_getCursorSemanticParent` 链拼接；语言按扩展名/mode 给 `C++`/`C`。
  - 确定性 ID（对齐 `DeterministicId` 约定）：类型/字段 `cpp:<qualifiedName>`；可执行实体 `cpp:<qualifiedName>#<signature>`（signature 用 `clang_getCursorDisplayName` 规范化近似）；Macro 用位置兜底 `cpp:<file>:<line>:<col>`；File 保持 `cpp:<path>`。
  - 父子 `CONTAINS` 关系（semantic parent → child）。
- **关系提取**：
  - `CXXBaseSpecifier` → INHERITS（struct 继承 class 语义不区分 IMPLEMENTS，C++ 统一 INHERITS）；
  - `CallExpr` → CALLS（`clang_getCursorReferenced` 解析目标；目标在本批文件中已知 → 目标确定性 ID；未知/外部 → targetId 空（ext），`metadata.externalTarget` 记拼写）；
  - `MacroExpansion` → REFERENCES（展开点→宏定义实体），**位置一律 `clang_getSpellingLocation`**（R13，禁止 expansion location）；
  - `TypeRef/TemplateRef` → DEPENDS_ON；`DeclRefExpr`（变量/字段）→ REFERENCES（不做 READS/WRITES 区分，记为已知边界）。
- **模板策略（R12）**：`declarations`（默认）仅记录模板声明；`whitelist` 按 `templateWhitelist` 名单记录实例化体；`full` 全记录；`off` 跳过模板体。跳过时产出 INFO `CPP.TEMPLATE_INSTANTIATION_SKIPPED` 并计入 `stats.skippedTemplateInstantiations`。
- **诊断映射**：`clang_getNumDiagnostics` → 按 severity 映射 ERROR/WARNING issue；TU 致命错误 → 该文件空实体集 + ERROR issue（单文件隔离，02.1 语义约定）。
- **退出码/协议/stats**：维持现状（0/1/2；stats 含 tuCount/durationMs/skippedTemplateInstantiations）；`--self-test`/`--count` 模式保留。

### 3. CMakeLists 链接 libclang（可选依赖、优雅降级）

**修改** [CMakeLists.txt](file:///workspace/legacy-recon/src/main/cpp/clang-parser/CMakeLists.txt)：

- `find_library(RECON_LIBCLANG NAMES libclang-18.so.18 libclang.so.1 libclang HINTS /usr/lib/llvm-18/lib /usr/lib/x86_64-linux-gnu)`；
- 找到 → `target_compile_definitions(recon_clang_parser PRIVATE RECON_HAS_LIBCLANG)` + `target_link_libraries` + `target_include_directories(${CMAKE_CURRENT_SOURCE_DIR})`（vendored clang-c）；找不到 → 仅编译骨架路径并 message(WARNING)。
- LLVM 版本兼容：libclang C API 稳定，不做版本强绑定。

### 4. Java 客户端补全协议字段

**修改** [ClangSubprocessClient.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/cpp/ClangSubprocessClient.java)：

- `compileCommands`：读取 `CppConfig.compileCommandsPath` 指向的 `compile_commands.json`（Jackson 解析为 `List<Map>`），按文件路径过滤仅保留本批文件的条目，替代硬编码空列表；文件不存在/解析失败 → 保持空 + WARNING issue `CPP.COMPILE_DB_MISSING`。
- `options` 增传：`fallbackIncludeDirs`、`fallbackDefines`、`standard`、`templateWhitelist`（均来自 `request.config.cpp`）。
- `mergeResponse` 无需改动（已是通用 map 合并）。

### 5. 打通 cpp 语言路由（顺带修复 Bean 冲突）

- **修改** [PipelineService.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/pipeline/PipelineService.java)：
  - 构造器参数 `LanguageParser parser` → `List<LanguageParser> parsers`，构建 `Map<String, LanguageParser>`（key=`parser.language()`，即 "java"/"cpp"）；
  - `lang(config)` 改为从 `project.configJson` 原始 JSON 顶层读 `language` 字段（默认 "java"）——该字段由 ProjectController 导入时写入；
  - `parser.parse(...)` → `parsersByLang.get(lang).parse(...)`，未知语言抛 IllegalArgumentException。
- **修改** [LegacyReconApplication.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/LegacyReconApplication.java)：删除 `languageParser()` @Bean。
- **修改** [JdtParser.java](file:///workspace/legacy-recon/src/main/java/com/legacyrecon/parser/java/JdtParser.java)：加 `@Component`（与 ClangSubprocessClient 一致）。
- 此改动同时消除双 Bean 歧义（NoUniqueBeanDefinitionException 隐患）。

### 6. 新增 C++ 样例工程

**新增** `legacy-recon/sample-cpp/`（3-4 个文件，约 100 行）：

- `include/account.h`（Namespace + Class + 字段 + 方法声明 + 宏定义 `ACCOUNT_API` + 模板函数声明）
- `src/account.cpp`（方法实现、CallExpr、宏展开点、类继承示例 `SavingsAccount : Account`）
- 覆盖验证点：Class/Namespace/Method/Constructor/Macro 实体、CONTAINS/INHERITS/CALLS/REFERENCES 关系、模板跳过 issue。

### 7. README 更新

**修改** [README.md](file:///workspace/legacy-recon/README.md)：脚手架边界中 C++ 条目从"子进程协议骨架"改为"libclang 语义级提取（实体/关系/宏/模板策略）"，标注已知边界（READS/WRITES 不区分、IMPLEMENT/OVERRIDES 不产出、实例化默认跳过）；构建章节补充 libclang 链接说明；目录树补 sample-cpp。

## 假设与决策

1. **选 libclang C API**（`libclang.so.18`）而非 LibTooling：C API ABI 稳定、vendored 头即可编译、无需完整 LLVM 开发环境；沙箱已有运行库。02.3 提到的 "PPCallbacks" 在 C API 中以 `DetailedPreprocessingRecord` + MacroDefinition/MacroExpansion cursor 等价实现。
2. **头文件获取**：优先 curl 下载官方头文件（走代理，前面 git push/gh 已验证 GitHub 可达）；失败则手写最小声明子集。两路径都在仓库内 vendor，构建不依赖网络。
3. **C++ 统一 INHERITS**：不产出 IMPLEMENTS/OVERRIDES（C++ 无 interface 区分、虚覆盖判定复杂度不值当），记为已知边界——与 README 现有"未产出 INHERITS/IMPLEMENTS/OVERRIDES"的 Java 侧先例风格一致（Java 侧绑定模式已补齐，C++ 侧保守）。
4. **READS/WRITES 不区分**：libclang cursor 层无直接读写语义，统一 REFERENCES，避免误报。
5. **签名规范化**：用 `clang_getCursorDisplayName` 近似 01 附录 A 的 canonical 序列化（完整 canonical 类型序列化留待后续），保证 ID 确定性（同输入同输出即可满足 ADR-005）。
6. **协议兼容**：响应 JSON 结构、退出码、stats 字段与现有协议完全一致，Java `mergeResponse` 零改动。

## 验证步骤

1. **构建 C++**：`cmake -S src/main/cpp/clang-parser -B build-cpp && cmake --build build-cpp -j4` → 确认链接 libclang-18 成功（RECON_HAS_LIBCLANG 生效；若链接失败确认走骨架降级 + WARNING）。
2. **协议直测**：`echo '<json 请求>' | ./build-cpp/recon_clang_parser`，对 sample-cpp 断言：出现 Class/Namespace/Method/Macro 实体、CONTAINS/INHERITS/CALLS/REFERENCES 关系、宏展开关系 location 为 spelling 位置、`CPP.TEMPLATE_INSTANTIATION_SKIPPED` issue、退出码 0/1。
3. **Java 构建**：`mvn -q package -DskipTests` 通过。
4. **启动修复验证**：应用正常启动（Bean 冲突修复）。
5. **C++ 端到端**：`POST /api/v1/projects`（root=sample-cpp, language=cpp）→ `POST /runs`（parse+enrich+generate）→ `GET /entities?type=Class`、`GET /insights`、`GET /generate` 均有合理产出。
6. **Java 回归**：sample-java 端到端流程与之前一致（README 验证脚本）。
7. **文档**：README 变更与实际能力一致。
