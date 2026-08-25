# 01 统一代码模型（UCM）规范

## 1.1 设计目标与约束

- 语言无关：首批 Java、C/C++，扩展点预留 Python、C#。
- 确定性：UCM 只包含解析器可确定产生的事实；AI 结果不进 UCM，而是以 `Insight` 形式挂在图谱层（见 03 文档，ADR-007）。
- 可序列化：JSON 文本；根对象带 `schemaVersion`。
- 增量友好：实体 ID 跨运行稳定（ADR-005），关系 ID 与遍历顺序无关（ADR-008），使增量合并无需全局比对。
- 可追溯：每个实体与关系均带 `SourceLocation`。

## 1.2 顶层结构（ParseResult）

~~~json
{
  "schemaVersion": "1.0",
  "language": "Java",
  "entities": [],
  "relations": [],
  "symbolTable": {},
  "fileIndex": {},
  "issues": [],
  "stats": {}
}
~~~

## 1.3 Entity

字段定义：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 全局唯一、跨运行稳定（见 1.5） |
| `type` | enum | 是 | 见 1.4 类型清单 |
| `name` | string | 是 | 短名，如 `deposit` |
| `qualifiedName` | string | 是 | 全限定名，如 `com.example.AccountService.deposit` |
| `signature` | string | 否 | 可执行实体的规范化签名，语法见附录 A |
| `language` | enum | 是 | `Java` \| `C++` \| `C` |
| `location` | SourceLocation | 是 | 见 1.6 |
| `modifiers` | string[] | 否 | `public`、`static`、`virtual`、`final` 等 |
| `typeRef` | TypeRef | 否 | 变量/字段/参数/返回值的类型 |
| `genericParameters` | TypeParameter[] | 否 | 泛型声明（名称 + 上界） |
| `typeArguments` | TypeRef[] | 否 | 泛型实参，如 `List<String>` 的 `String` |
| `docComment` | string | 否 | 关联文档注释原始文本（不解析），Java 取 Javadoc，C/C++ 取 `/** */` |
| `metadata` | object | 否 | 语言特有信息，如 `{anonymous: true}`、`{templateInstantiation: ...}` |

### 1.3.1 TypeRef

类型引用有两种归宿：项目内类型（存在实体）与项目外类型（JDK / 标准库 / 第三方依赖）。

~~~json
{
  "kind": "class",
  "name": "java.lang.String",
  "entityId": null,
  "external": true,
  "arrayDimensions": 0
}
~~~

- `kind`：`primitive` \| `class` \| `interface` \| `enum` \| `struct` \| `union` \| `array` \| `pointer` \| `reference` \| `typeParameter` \| `unknown`（C/C++ 使用 pointer/reference，Java 使用 `arrayDimensions` 表示数组）。
- `external=true` 时 `entityId` 必须为空；项目内类型 `entityId` 必须指向真实实体。
- 绑定失败且无法判定外部性时：`kind=unknown` 并产出对应 `ParseIssue`。

### 1.3.2 类型清单

~~~text
Project, Module, File,
Class, Interface, Struct, Union, Enum, EnumConstant,
Annotation, TypeParameter, Namespace, Macro,
Function, Method, Constructor, Destructor,
Field, Variable, Parameter, LocalVariable
~~~

注：`Module` 由接入层按构建文件/目录划分产生；`Macro` 仅在开启宏记录时产生（见 02 文档 2.3）。

## 1.4 Relation

字段定义：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 确定性生成（见 1.5），与遍历顺序无关 |
| `type` | enum | 是 | 见下表 |
| `sourceId` | string | 是 | 使用方实体 ID |
| `targetId` | string | 否 | 被使用方实体 ID；目标在项目外时为 null |
| `location` | SourceLocation | 否 | 调用点 / 声明点 |
| `metadata` | object | 否 | 如 `{virtual: true}`、`{externalTarget: "..."}` |

关系类型与方向约定（统一从"使用方"指向"被使用方"）：

| 类型 | source → target | 说明 |
|---|---|---|
| `CONTAINS` | Project→Module→File→类型→成员；Namespace→类型 | 结构归属 |
| `CALLS` | Function/Method/Constructor → Function/Method/Constructor | 调用；`metadata.virtual` 标记虚调用 |
| `INSTANTIATES` | Method/Function → Class/Struct | `new` / 对象构造 |
| `INHERITS` | Class → Class；Struct → Struct | 继承 |
| `IMPLEMENTS` | Class → Interface | 接口实现 |
| `OVERRIDES` | Method → Method | 重写 |
| `READS` / `WRITES` | Method/Function → Field/Variable | 字段读写 |
| `THROWS` | Method/Function → Class | 异常声明 |
| `DEPENDS_ON` | File→File；Module→Module | import / include 引入 |
| `REFERENCES` | 任意 → 任意 | 兜底类型引用；宏展开点 → 宏定义亦用此类型（见 02 文档 2.3） |

外部目标约定：当 target 在项目外（如调用 JDK 方法）时，`targetId=null`，`metadata.externalTarget` 记录其全限定名 + 签名。UCM 校验器据此不报悬空引用。事实层对 `externalTarget` 的冗余列与图谱层虚拟节点见 03 文档（R16）。

## 1.5 确定性 ID 规则（ADR-005 / ADR-008）

| 实体 | 规则 | 示例 |
|---|---|---|
| 类型实体 | `lang:qualifiedName` | `java:com.example.AccountService` |
| 可执行实体 | `lang:qualifiedName#signature`（signature 语法见附录 A） | `java:com.example.AccountService.deposit#(D)V` |
| 字段 | `lang:qualifiedName.field` | `java:com.example.AccountService.balance` |
| 参数 | 父 ID + `.param(index)`（index 从 1 起，与源码顺序一致） | 父方法第 1 个参数 |
| 局部 / 匿名 / 宏 | `lang:filePath:startLine:startCol`（位置兜底） | `java:src/main/java/com/example/Foo.java:12:9` |
| 关系 | `srcId:TYPE:targetId@startLine:startCol`；targetId 为空时该段记 `ext`；同位置多条按（列, 参数索引）升序追加子序号 `#n` | `...:CALLS:ext@8:9`、`...:WRITES:...balance@9:9` |

约束：

- ID 仅含 ASCII 可打印字符；签名中参数类型用全限定名（Java 用 JVM 描述符，见附录 A）；重载靠签名区分。
- ID 是不透明字符串，任何组件不得解析其内部结构做语义推断；需要语义信息时使用实体/关系字段。
- 关系 ID 中位置取自该关系的 `location`（必填，无位置的实现视为错误）；同位置多条按（起始列, 参数索引）升序编号，与解析/遍历顺序无关（ADR-008）。
- 实体重命名等价于旧 ID 删除 + 新 ID 创建；跨运行的关系迁移由增强层按位置最近邻启发式完成，并在 `stats` 中记录迁移数量。
- 同一运行内 ID 必须唯一，校验器强制执行。

## 1.6 SourceLocation 约定

~~~json
{
  "file": "src/main/java/com/example/AccountService.java",
  "startLine": 3,
  "startCol": 1,
  "endLine": 5,
  "endCol": 2
}
~~~

- 行/列均从 1 开始；区间为半开 [start, end)。
- `file` 必须是接入层文件清单中的相对路径。
- 声明性实体（类、方法）覆盖完整声明区间；关系上的 location 覆盖引用点（调用表达式、import 行等）。
- 宏展开产生的位置一律回溯 spelling location（用户可见源码位置），不得使用 expansion location（见 02 文档 2.3，R13）。

## 1.7 symbolTable 与 fileIndex

- `symbolTable`：`Map<key, entityId>`。key 规则：类型实体用 `qualifiedName`；可执行实体用 `qualifiedName#signature`。供上层按名检索、避免重复建索引。
- `fileIndex`：`Map<filePath, entityId[]>`。增量解析时按文件删除/写入实体（见 02 文档 2.4）。

## 1.8 校验规则（UCM Validator）

解析器输出入库前必须通过以下校验，失败项按严重级别降级（ERROR 阻止入库，WARNING 记录）：

1. `id` 全局唯一（ERROR）。
2. 关系端点：`sourceId` 必须存在；`targetId` 为空时 `metadata.externalTarget` 必须存在，否则 `targetId` 必须存在（ERROR）。
3. `CONTAINS` 不构成环，且形成以 Project 为根的森林（WARNING，破坏性的循环边丢弃并记 issue）。
4. 每个 `File` 实体在 `fileIndex` 中可定位（WARNING）。
5. `schemaVersion` 与入库侧期望一致（ERROR）。
6. `typeRef.entityId` 非空时指向存在实体（WARNING，悬空时降级为 `unknown`）。
7. signature 格式校验（R10）：Java 必须匹配附录 A 的 JVM 描述符语法（ERROR）；C/C++ 按附录 A ABNF 宽松校验（WARNING，无法规范化时允许 `?` 占位但须同步产出 issue）。
8. 关系 `id` 必须携带 `@startLine:startCol` 且与其 `location` 一致（ERROR，ADR-008）。

## 1.9 示例

源码：

~~~java
package com.example;
/** 账户服务。 */
public class AccountService {
    private double balance;
    /** 存入金额，必须为正。 */
    public void deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount <= 0");
        balance += amount;
    }
}
~~~

对应 UCM（节选，省略 metadata；注意 signature 使用 JVM 描述符）：

~~~json
{
  "schemaVersion": "1.0",
  "language": "Java",
  "entities": [
    {
      "id": "java:src/main/java/com/example/AccountService.java",
      "type": "File",
      "name": "AccountService.java",
      "qualifiedName": "com.example.AccountService",
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 1, "startCol": 1, "endLine": 11, "endCol": 1 }
    },
    {
      "id": "java:com.example.AccountService",
      "type": "Class",
      "name": "AccountService",
      "qualifiedName": "com.example.AccountService",
      "modifiers": ["public"],
      "docComment": "账户服务。",
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 4, "startCol": 1, "endLine": 11, "endCol": 2 }
    },
    {
      "id": "java:com.example.AccountService.balance",
      "type": "Field",
      "name": "balance",
      "qualifiedName": "com.example.AccountService.balance",
      "modifiers": ["private"],
      "typeRef": { "kind": "primitive", "name": "double" },
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 5, "startCol": 5, "endLine": 5, "endCol": 20 }
    },
    {
      "id": "java:com.example.AccountService.deposit#(D)V",
      "type": "Method",
      "name": "deposit",
      "qualifiedName": "com.example.AccountService.deposit",
      "signature": "(D)V",
      "modifiers": ["public"],
      "docComment": "存入金额，必须为正。",
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 7, "startCol": 5, "endLine": 10, "endCol": 6 }
    },
    {
      "id": "java:com.example.AccountService.deposit#(D)V.param(1)",
      "type": "Parameter",
      "name": "amount",
      "qualifiedName": "com.example.AccountService.deposit.amount",
      "typeRef": { "kind": "primitive", "name": "double" },
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 7, "startCol": 25, "endLine": 7, "endCol": 39 }
    }
  ],
  "relations": [
    { "id": "java:src/main/java/com/example/AccountService.java:CONTAINS:java:com.example.AccountService@1:1", "type": "CONTAINS", "sourceId": "java:src/main/java/com/example/AccountService.java", "targetId": "java:com.example.AccountService", "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 1, "startCol": 1, "endLine": 1, "endCol": 1 } },
    { "id": "java:com.example.AccountService:CONTAINS:java:com.example.AccountService.balance@5:5", "type": "CONTAINS", "sourceId": "java:com.example.AccountService", "targetId": "java:com.example.AccountService.balance", "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 5, "startCol": 5, "endLine": 5, "endCol": 5 } },
    { "id": "java:com.example.AccountService:CONTAINS:java:com.example.AccountService.deposit#(D)V@7:5", "type": "CONTAINS", "sourceId": "java:com.example.AccountService", "targetId": "java:com.example.AccountService.deposit#(D)V", "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 7, "startCol": 5, "endLine": 7, "endCol": 5 } },
    {
      "id": "java:com.example.AccountService.deposit#(D)V:CALLS:ext@8:9",
      "type": "CALLS",
      "sourceId": "java:com.example.AccountService.deposit#(D)V",
      "targetId": null,
      "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 8, "startCol": 9, "endLine": 8, "endCol": 58 },
      "metadata": { "externalTarget": "java.lang.IllegalArgumentException.<init>#(Ljava/lang/String;)V" }
    },
    { "id": "java:com.example.AccountService.deposit#(D)V:WRITES:java:com.example.AccountService.balance@9:9", "type": "WRITES", "sourceId": "java:com.example.AccountService.deposit#(D)V", "targetId": "java:com.example.AccountService.balance", "location": { "file": "src/main/java/com/example/AccountService.java", "startLine": 9, "startCol": 9, "endLine": 9, "endCol": 24 } }
  ],
  "symbolTable": {
    "com.example.AccountService": "java:com.example.AccountService",
    "com.example.AccountService.deposit#(D)V": "java:com.example.AccountService.deposit#(D)V"
  },
  "fileIndex": {
    "src/main/java/com/example/AccountService.java": [
      "java:src/main/java/com/example/AccountService.java",
      "java:com.example.AccountService",
      "java:com.example.AccountService.balance",
      "java:com.example.AccountService.deposit#(D)V",
      "java:com.example.AccountService.deposit#(D)V.param(1)"
    ]
  },
  "issues": [],
  "stats": { "fileCount": 1, "entityCount": 5, "relationCount": 5, "durationMs": 42 }
}
~~~

## 1.10 校验与扩展

- 校验器为独立组件：解析器输出 → 校验 → 入库（03 文档事实层）。任何语言解析器都必须先过校验器。
- 新增语言时先扩展类型清单与 TypeRef 的 `kind`、定义其 signature 规范化语法（附录 A 增补），并同步校验器与图谱投影规则。

## 附录 A signature 规范化规则（ABNF，R10）

Java —— JVM 描述符：

~~~text
JavaSignature    = "(" [ParamDescriptor *ParamDescriptor] ")" ReturnDescriptor
ParamDescriptor  = FieldDescriptor
ReturnDescriptor = FieldDescriptor
FieldDescriptor  = BaseType | ObjectType | ArrayType
BaseType         = "B" | "C" | "D" | "F" | "I" | "J" | "S" | "Z"
                   ; V 仅允许出现在 ReturnDescriptor
ObjectType       = "L" BinaryName ";"
BinaryName       = Identifier ("/" Identifier) *("$" Identifier)
ArrayType        = "[" FieldDescriptor
~~~

- 泛型按类型擦除（erasure）编码：`public <T> T get(T t)` → `(Ljava/lang/Object;)Ljava/lang/Object;`；泛型信息存 `genericParameters` / `typeArguments`，不进 signature。
- varargs 与数组同形（`int...` → `[I`），用 `metadata.varargs=true` 区分。
- 构造器 qualifiedName 以 `<init>` 结尾，signature 为 JVM 构造器描述符；静态初始化块 qualifiedName 以 `<clinit>` 结尾，signature 为 `()V`。
- 内部类用 `$` 分隔（`com.example.Foo$Bar`）；匿名类无稳定签名，使用位置兜底 ID。
- 数组协变不影响描述符：`String[]` → `[Ljava/lang/String;`。

C/C++ —— Clang canonical 类型序列化：

~~~text
CppSignature     = [TemplateHeader] "(" [CppType *("," CppType)] ")" "->" CppType MemberQualifiers
TemplateHeader   = "template<" [TemplateParam *("," TemplateParam)] "> "
TemplateParam    = ("T#" Pos) | ("NT#" Pos)
                   ; 类型参数 / 非类型参数，Pos 为声明序（从 0 起）
CppType          = [CppQuals] CppTypeSpec
CppQuals         = ("const " | "volatile ")     ; 规范序 const 在前，至多各一次
CppTypeSpec      = Builtin
                 | ("::" QualifiedName) [TemplateArgs]
                 | CppType "*"
                 | CppType "&"
                 | CppType "&&"
                 | CppType "[" [Bound] "]"
                 | "(" CppSignature ")"         ; 函数指针
                 | "..."
                 | "?"                          ; 无法规范化（须同步产出 issue）
QualifiedName    = Identifier *("::" Identifier)
TemplateArgs     = "<" CppType *("," CppType) ">"
Builtin          = "void" | "bool" | "char" | "signed char" | "unsigned char"
                 | "short" | "unsigned short" | "int" | "unsigned int"
                 | "long" | "unsigned long" | "long long" | "unsigned long long"
                 | "float" | "double" | "long double" | "wchar_t"
                 | "char16_t" | "char32_t" | "auto" | "decltype(auto)"
MemberQualifiers = "" | " const" | " &" | " &&" | " const &" | " const &&"
~~~

- 类型一律取 Clang canonical type（`QualType::getCanonicalType()`）序列化，typedef 展开到 canonical 形式，保证同一类型跨 TU 字符串一致。
- 成员函数限定符进入 `MemberQualifiers`：`void f() const & ` → `() -> void const &`。
- 默认参数不参与签名。
- 模板参数按声明位置索引（`T#0` / `NT#0`）；参数表增删必然导致签名变化——这是源码变更，ID 变化可接受。
- 变长参数用 `...`（`int printf(const char*, ...)` → `(::char const *, ...) -> int`）。
- 无法规范化的类型（不完整类型、依赖名等）用 `?` 占位并产出 WARNING issue；该签名仍保持确定性（同一输入同一输出）。
- Itanium ABI mangled name 不作为 ID 依据（受匿名命名空间 `<abi:...>` 等标记影响，跨 TU 不稳定），仅作调试字段 `metadata.mangledName`。

边界案例速查：

| 场景 | 编码 |
|---|---|
| 重载 | 参数描述符不同 → signature 不同 |
| Java 泛型方法 | 擦除后描述符 |
| Java varargs / 数组 / 内部类 | `[I` / `[L...;` / `$` 分隔 |
| C++ const 成员函数 | `MemberQualifiers` 追加 ` const` |
| C++ 引用限定符 | ` & ` / ` && ` |
| C++ 默认参数 | 忽略 |
| C++ 模板函数 | `TemplateHeader` 前缀 + 位置索引模板参数 |
| C++ 函数指针参数 | `(CppSignature)` 嵌套 |
| C/C++ 变长参数 | `...` |
