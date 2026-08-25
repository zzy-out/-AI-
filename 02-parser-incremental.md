# 02 解析层接口与增量解析

## 2.1 接口契约

~~~java
public interface LanguageParser {
    String language();                       // "java" | "c" | "cpp"
    ParseResult parse(ParseRequest request);
}
~~~

~~~java
public class ParseRequest {
    List<SourceFile> files;        // 待解析文件；content 为 null 表示删除
    ProjectConfig config;          // 语言相关配置
    IncrementalInfo incremental;   // 可空；见 2.4
    Path workingDir;               // 项目根目录
}

public class SourceFile {
    String path;                   // 相对项目根的路径
    String checksum;               // 内容 SHA-256，接入层计算
    String content;                // UTF-8 文本；null 表示该文件已删除
}

public class ProjectConfig {
    String encoding = "UTF-8";
    JavaConfig java;               // 仅 Java 解析器使用
    CppConfig cpp;                 // 仅 C/C++ 解析器使用
}

public class JavaConfig {
    List<String> sourceRoots;             // 如 ["src/main/java"]
    String classpathStrategy;             // "maven" | "gradle" | "manual"
    List<String> manualClasspath;         // strategy=manual 时生效
}

public class CppConfig {
    String compileCommandsPath;           // compile_commands.json 路径，可空
    List<String> fallbackIncludeDirs;     // 无编译数据库时的试探路径
    List<String> fallbackDefines;
    String standard;                      // "c11" | "c++17" ...
    String templatePolicy = "declarations";   // "declarations" | "whitelist" | "full" | "off"
    List<String> templateWhitelist;       // templatePolicy=whitelist 时的模板清单
}

public class ParseResult {
    String schemaVersion = "1.0";
    String language;
    List<Entity> entities;
    List<Relation> relations;
    Map<String, String> symbolTable;
    Map<String, List<String>> fileIndex;
    List<ParseIssue> issues;
    Map<String, Object> stats;
}

public class ParseIssue {
    enum Severity { ERROR, WARNING, INFO }
    Severity severity;
    String code;                  // 稳定错误码，如 "JAVA.UNRESOLVED_SYMBOL"
    String message;
    SourceLocation location;      // 可空
}
~~~

语义约定：

- 纯函数：同输入必同输出（ID 确定性），不得读写全局状态。
- 隔离失败：单个文件解析失败不得导致整体失败；该文件产出空实体集 + ERROR issue。
- 幂等：`content=null` 的文件仅用于让解析器更新内部依赖视图，不产出实体。

## 2.2 Java 解析器（JDT）实现细则

工程级绑定是关键：`setResolveBindings(true)` 依赖完整 classpath。实现步骤：

1. 由 `JavaConfig.sourceRoots + classpath` 构建一个临时的 `IJavaProject`（JDT Core 工程模型），classpath 按策略获取：
   - `maven`：执行 `mvn -q dependency:build-classpath` 输出到临时文件并解析；失败则解析 `pom.xml` 依赖坐标并查本地仓库（`~/.m2/repository`）兜底。
   - `gradle`：通过 Gradle Tooling API 连接构建，取真实 compile classpath；失败降级 `manual`。
   - `manual`：直接使用用户提供的 jar 列表。
2. 使用 `ASTParser` + `FileASTRequestor` 批量解析，绑定结果经 `IJavaProject` 关联。
3. AST 遍历：提取类/接口/枚举/注解、方法/构造器、字段、参数、局部变量；记录 `SourceLocation`、修饰符、Javadoc。
4. 绑定转 TypeRef：`IBinding` → `ITypeBinding`；jar 内类型标 `external=true`，不建实体；泛型记录 `genericParameters` 与 `typeArguments`。
5. 关系提取：`CALLS`（含 `virtual` 标记）、`INSTANTIATES`、`INHERITS`、`IMPLEMENTS`、`OVERRIDES`、`READS/WRITES`（变量访问点）、`THROWS`、`DEPENDS_ON`（import）。
6. 签名规范化：方法/构造器 signature 一律按 01 文档附录 A 的 JVM 描述符规则输出（泛型擦除、`<init>` / `<clinit>`、varargs 标记）。
7. 错误恢复：JDT 语法错误节点可恢复遍历；`IProblem` 按严重级别映射为 `ParseIssue`（ERROR 对应语法/绑定失败，WARNING 对应其余），不中断整体解析。
8. 线程安全：`ASTParser` 实例不可跨线程共享——每个工作线程持有自己的 parser；`IJavaProject` 只读共享。并行粒度为文件级。

## 2.3 C/C++ 解析器（Clang）子进程协议（ADR-002）

动机：Clang 为 C++ 实现，与 JVM 后端进程隔离，崩溃/内存问题不影响主进程。

传输约定：

- 请求：子进程 stdin 读入单个 JSON 对象（UTF-8），一次请求对应一批翻译单元（TU）。
- 响应：stdout 输出单个 JSON 对象（按文件分组）；stderr 仅作日志。
- 单次请求内 `files` 数量可配（默认 64），驱动进程负责切批与并行调度。

请求：

~~~json
{
  "schemaVersion": "1.0",
  "files": [ { "path": "src/core/engine.cpp", "absolutePath": "/abs/src/core/engine.cpp" } ],
  "compileCommands": [
    {
      "file": "src/core/engine.cpp",
      "directory": "/abs/build",
      "arguments": ["clang++", "-std=c++17", "-Iinclude", "-c", "src/core/engine.cpp"],
      "output": "build/engine.o"
    }
  ],
  "mode": "cpp",
  "options": { "recordMacros": true, "templatePolicy": "whitelist", "templateWhitelist": ["std::vector", "std::map", "boost::optional"] }
}
~~~

响应：

~~~json
{
  "files": [
    { "path": "src/core/engine.cpp", "entities": [], "relations": [], "issues": [] }
  ],
  "stats": { "tuCount": 1, "durationMs": 120, "skippedTemplateInstantiations": 3 }
}
~~~

退出码约定：`0` 全部成功；`1` 部分成功（响应中带 issues）；`2` 协议/环境错误（如编译数据库格式非法）。

隔离与降级：

- 每 TU 超时（默认 120s）与内存上限；超时 TU 标记 `CPP.TIMEOUT` issue，其余 TU 结果保留。
- 子进程崩溃：驱动进程按批重试一次，再失败则整批降级为"语法级"解析（仅 AST 结构，不做语义绑定）并记 issue。
- 宏记录（R13）：`recordMacros=true` 时注册自定义 `PPCallbacks`：
  - 记录宏定义（位置、宏体原始文本、参数列表）为 `Macro` 实体，宏体原文存 `metadata.macroBody`；
  - 宏展开产生的一切 `SourceLocation` 一律回溯 **spelling location**（Clang `SourceManager` 的展开点用户可见位置），禁止使用 expansion location，保证位置指向用户可见源码；
  - 展开点所在实体 → 宏定义实体建立 `REFERENCES` 关系（location 为展开点 spelling 位置），供审计与增量传播使用（见 2.4）。
- 模板策略（R12）：
  - `declarations`（默认）：仅记录模板声明与实参信息；
  - `whitelist`：对白名单模板（标准容器、常用 Boost、项目内高频模板——按实例化次数 topN 可配）记录实例化体与调用关系，平衡精度与开销；
  - `full`：记录全部实例化体（实验性，开销大）；
  - `off`：跳过模板体。
  - 跳过实例化时产出 INFO 级 issue `CPP.TEMPLATE_INSTANTIATION_SKIPPED` 并计入 `stats.skippedTemplateInstantiations`；生成层据此在文档中标注"调用图不完整（模板实例化未记录）"（见 04 文档）。
- 编译数据库缺失降级：使用 `fallbackIncludeDirs/fallbackDefines` 试探解析，并在 issues 中标注 `CPP.FALLBACK_MODE`，警告语义绑定可能不完整。
- 语言模式：按扩展名与 `mode` 字段自动切换 C / C++。
- 签名规范化：按 01 文档附录 A 的 C++ ABNF 输出（canonical 类型序列化、默认参数忽略、成员限定符、模板参数位置索引）。

## 2.4 增量解析算法（ADR-006）

持久状态：上次成功运行的文件校验和集合 `S_old`（存于事实层，见 03 文档）。

输入：`S_new`（本次全量扫描的校验和）。

步骤：

1. 计算差异集：
   - `added` = `S_new` 中新增路径；
   - `deleted` = `S_old` 中存在、`S_new` 中消失的路径；
   - `changed` = 路径相同但校验和不同的文件。
2. `dirty = added ∪ changed ∪ deleted`。
3. 反向传播求受波及集合 `affected`（R14）：从 `dirty` 出发，沿 `DEPENDS_ON`、`CALLS`、`INHERITS`、`IMPLEMENTS`、`OVERRIDES`、`REFERENCES`（含宏展开点 → 宏定义）在**图内反向**（被依赖方向）扩散；传播深度默认 2（可配）。C/C++ 因宏与重载决议，默认深度与阈值可单独调大。
4. 复杂特性自适应（R14）：若 dirty/affected 文件中宏引用密度超过阈值（默认：含宏展开的语句占比 > 30%）或模板实例化密集，传播深度 +1 并按加权策略重新判定，必要时强制全量。
5. 全量回退判定（R15）：支持两种可配策略——
   - `file-ratio`（默认）：`|affected| / |S_new| > 20%` 则全量；
   - `entity-weight`：受影响实体扇出加权占比 `Σ(受影响实体被依赖次数+1) / Σ(全部实体被依赖次数+1) > 30%` 则全量（核心头文件变更即使文件数少也会触发）；
   - 另有手动 `forceFull=true`。
6. 增量执行：仅解析 `affected` 中现存的文件（`added + changed` 送新内容；`deleted` 不送）；解析器内部依赖视图（符号表）以"上次结果 + 本次变更"重建。
7. 合并（事务）：
   - 按 `fileIndex` 删除 `affected` 覆盖的所有旧实体与关系；
   - 插入本次新产出的实体与关系；
   - 未波及文件的数据原样保留——确定性 ID（ADR-005 / ADR-008）保证其稳定。
8. `issues` 仅来自 `affected` 文件；`stats` 与 `S_old` 全量刷新。

保守性声明（R14）：

- 反向传播沿的是**保守近似**的关系集，语义级变化可能超出边覆盖范围：宏跨 TU 隐式传播、条件编译、Java 注解处理、全局变量初始化顺序、重载决议变化等无法完全由依赖边刻画。
- 因此增量解析**不承诺**与全量结果 100% 一致；设计目标是"高概率一致 + 快速反馈"，由 UCM 校验器的悬空引用告警与增量一致性验收测试（见 04 文档 4.6）兜底。
- 检测到宏/模板密集等高风险特征时自动收紧策略（步骤 4）；用户可随时 `forceFull`。
- 重命名文件 = 删除 + 新增，视作 dirty 正常处理。

## 2.5 容错约定汇总

| 场景 | 行为 |
|---|---|
| 语法错误 | 恢复模式遍历，跳过错误节点子树，产出 ERROR issue，其余正常 |
| 未解析符号 / 绑定失败 | `typeRef.kind=unknown` + WARNING issue |
| 单文件致命错误 | 该文件空实体集 + ERROR issue，不影响其他文件 |
| 非法 UTF-8 | 接入层已统一编码；仍非法则文件级 ERROR issue |
| 子进程（Clang）崩溃 | 批级重试一次，再失败降级语法级解析 |
| 模板实例化被跳过 | `CPP.TEMPLATE_INSTANTIATION_SKIPPED` INFO issue + stats 计数 |
| 宏展开位置 | 一律回溯 spelling location，禁止 expansion location |

## 2.6 并行与性能约定

- 并行粒度：Java 文件级（注意 parser 实例线程隔离）；C/C++ TU 级（驱动切批）。
- 并行度：默认 `min(CPU 核数, 可配上限)`。
- `stats` 必含：`fileCount / entityCount / relationCount / issueCount / durationMs / parsePerFileMs`（C/C++ 另含 `skippedTemplateInstantiations`）。
- 性能目标（阶段 1 验收）：10 万行 Java 全量 < 5 分钟（单机 8 核，见 04 文档）。
