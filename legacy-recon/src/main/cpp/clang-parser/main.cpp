// Clang 子进程协议实现（02.3 / ADR-002）。
//
// 传输约定：请求 JSON 从 stdin 读入（单个对象，一次请求对应一批翻译单元 TU）；
// 响应 JSON 写 stdout；stderr 仅作日志。
// 退出码：0=全部成功；1=部分成功（响应带 ERROR issues）；2=协议/环境错误。
//
// 编译开关 RECON_HAS_LIBCLANG（由 CMake 检测到 libclang 时定义）：
//   - 定义：libclang 语义级提取（实体/关系/宏记录 R13/模板策略 R12，02.3）。
//   - 未定义：退化为协议骨架（File 实体 + CPP.PROTOCOL_SCAFFOLD issue），保证可运行。
#include "json.hpp"

#ifdef RECON_HAS_LIBCLANG
#include "clang-c/Index.h"
#endif

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

// ---------------- 公共：请求解析 ----------------

struct Options {
    std::string templatePolicy = "declarations";
    std::vector<std::string> templateWhitelist;
    bool recordMacros = true;
    std::string standard = "c++17";
    std::vector<std::string> fallbackIncludeDirs;
    std::vector<std::string> fallbackDefines;
};

struct FileReq {
    std::string path;   // 相对项目根
    std::string abs;    // 绝对路径
};

static std::string readStdin() {
    std::stringstream ss;
    std::string line;
    while (std::getline(std::cin, line)) {
        ss << line << "\n";
    }
    return ss.str();
}

static std::string readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return "";
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static bool isCppPath(const std::string& path) {
    auto endsWith = [&](const char* suf) {
        size_t n = strlen(suf);
        return path.size() >= n && path.compare(path.size() - n, n, suf) == 0;
    };
    return endsWith(".cpp") || endsWith(".cc") || endsWith(".cxx") || endsWith(".hpp") ||
           endsWith(".hh") || endsWith(".hxx") || endsWith(".ipp") || endsWith(".tpp");
}

static bool isCPath(const std::string& path) {
    return path.size() >= 2 && path.substr(path.size() - 2) == ".c";
}

static std::vector<std::string> jsonStrArray(const recon::Json* j) {
    std::vector<std::string> out;
    if (j && j->type == recon::Json::Arr) {
        for (const auto& e : j->arr) out.push_back(e.asStr());
    }
    return out;
}

// 位置 JSON（01.6：行/列 1 起，半开区间 [start, end)，file 为相对路径）
static recon::Json makeLoc(const std::string& file, unsigned sl, unsigned sc, unsigned el, unsigned ec) {
    recon::Json loc = recon::Json::makeObj();
    loc("file") = recon::Json::makeStr(file);
    loc("startLine") = recon::Json::makeNum(sl);
    loc("startCol") = recon::Json::makeNum(sc);
    loc("endLine") = recon::Json::makeNum(el);
    loc("endCol") = recon::Json::makeNum(ec);
    return loc;
}

static recon::Json makeIssue(const char* severity, const char* code, const std::string& msg,
                             const std::string& file = "", unsigned sl = 0, unsigned sc = 0) {
    recon::Json issue = recon::Json::makeObj();
    issue("severity") = recon::Json::makeStr(severity);
    issue("code") = recon::Json::makeStr(code);
    issue("message") = recon::Json::makeStr(msg);
    if (!file.empty() && sl > 0) {
        issue("location") = makeLoc(file, sl, sc, sl, sc);
    }
    return issue;
}

#ifdef RECON_HAS_LIBCLANG

// ---------------- libclang 语义级提取 ----------------

static std::string cxstr(CXString s) {
    const char* p = clang_getCString(s);
    std::string out = p ? p : "";
    clang_disposeString(s);
    return out;
}

/** cursor 位置的 spelling 形式（R13：禁止 expansion location） */
struct SpellingLoc {
    std::string file;
    unsigned sl = 0, sc = 0, el = 0, ec = 0;
    bool inMain = false;
    bool valid = false;
};

/** 单 TU 提取上下文 */
struct TuCtx {
    CXTranslationUnit tu = nullptr;
    CXFile mainFile = nullptr;
    std::string relPath;    // 相对项目根（file 键 / location.file）
    std::string absPath;
    std::string rootDir;    // 项目根（绝对），用于把头文件绝对路径转相对
    std::string lang;       // "C++" | "C"
    const Options* opts = nullptr;

    recon::Json entities = recon::Json::makeArr();
    recon::Json relations = recon::Json::makeArr();
    recon::Json issues = recon::Json::makeArr();

    std::set<std::string> emittedEntityIds;      // 实体去重（同 ID 只发一次，如 namespace 重开）
    std::set<std::string> emittedRelationIds;    // 关系去重（ADR-008 确定性 ID）
    std::vector<std::string> autoIncludeDirs;    // fallback 模式自动 -I（本批文件所在目录）
    std::map<std::string, std::string> usrToId;  // USR → 本 TU 已产出实体 ID
    std::map<std::string, std::string> nameToId; // qualifiedName → ID（关系目标兜底解析）
    int skippedTemplates = 0;
    bool hasError = false;

    // 宏展开点关系延迟归属（R13：展开点所在实体 → 宏定义；预处理记录 cursor 无语义父，
    // 需在遍历结束后按位置查找最内层包含实体）
    struct MacroRefPending {
        SpellingLoc loc;
        std::string target;
        std::string externalName;
    };
    std::vector<MacroRefPending> pendingMacroRefs;
};

/** 绝对路径 → 相对项目根（不在根下则原样返回） */
static std::string toRel(TuCtx& c, const std::string& absPath) {
    if (c.rootDir.empty() || absPath.empty()) return absPath;
    std::string root = c.rootDir;
    if (!root.empty() && root.back() != '/') root += '/';
    if (absPath.rfind(root, 0) == 0) return absPath.substr(root.size());
    return absPath;
}

static SpellingLoc spellLoc(TuCtx& c, CXSourceRange r) {
    SpellingLoc out;
    CXSourceLocation b = clang_getRangeStart(r);
    CXSourceLocation e = clang_getRangeEnd(r);
    CXFile fb = nullptr, fe = nullptr;
    unsigned off1, off2;
    clang_getSpellingLocation(b, &fb, &out.sl, &out.sc, &off1);
    clang_getSpellingLocation(e, &fe, &out.el, &out.ec, &off2);
    if (fb) {
        out.file = toRel(c, cxstr(clang_getFileName(fb)));
        out.inMain = fb == c.mainFile;
    }
    out.valid = fb != nullptr;
    return out;
}

static SpellingLoc spellLoc(TuCtx& c, CXSourceLocation l) {
    SpellingLoc out;
    CXFile f = nullptr;
    unsigned off;
    clang_getSpellingLocation(l, &f, &out.sl, &out.sc, &off);
    if (f) {
        out.file = toRel(c, cxstr(clang_getFileName(f)));
        out.inMain = f == c.mainFile;
    }
    out.valid = f != nullptr;
    out.el = out.sl;
    out.ec = out.sc;
    return out;
}

/** 限定名：沿语义父链（namespace/class/struct/union/enum）拼接 */
static std::string qualifiedNameOf(CXCursor cursor) {
    std::vector<std::string> parts;
    parts.push_back(cxstr(clang_getCursorSpelling(cursor)));
    CXCursor p = clang_getCursorSemanticParent(cursor);
    while (true) {
        CXCursorKind k = clang_getCursorKind(p);
        if (k == CXCursor_Namespace || k == CXCursor_StructDecl || k == CXCursor_ClassDecl ||
            k == CXCursor_ClassTemplate || k == CXCursor_UnionDecl || k == CXCursor_EnumDecl) {
            std::string n = cxstr(clang_getCursorSpelling(p));
            if (n.empty()) break; // 匿名父（匿名 struct/union）——终止避免不稳定名
            parts.push_back(n);
            p = clang_getCursorSemanticParent(p);
        } else {
            break;
        }
    }
    std::reverse(parts.begin(), parts.end());
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) {
        if (i) out += "::";
        out += parts[i];
    }
    return out;
}

/** 可执行实体（函数/方法/构造/析构）规范化签名：取 canonical 声明的 displayName（声明/定义跨 TU 一致） */
static std::string signatureOf(CXCursor cursor) {
    CXCursor canon = clang_getCanonicalCursor(cursor);
    if (!clang_Cursor_isNull(canon)) cursor = canon;
    return cxstr(clang_getCursorDisplayName(cursor));
}

/** 实体确定性 ID（对齐 Java DeterministicId 约定）：
 *  类型/字段/变量/命名空间：cpp:qualifiedName
 *  可执行实体：cpp:qualifiedName#signature
 *  宏/匿名：位置兜底 cpp:file:line:col */
static std::string entityId(const std::string& qname,
                            const SpellingLoc& loc, bool executable, const std::string& sig) {
    if (qname.empty()) {
        return "cpp:" + loc.file + ":" + std::to_string(loc.sl) + ":" + std::to_string(loc.sc);
    }
    if (executable) return "cpp:" + qname + "#" + sig;
    return "cpp:" + qname;
}

/** 关系确定性 ID（ADR-008）：srcId:TYPE:targetId@line:col */
static std::string relationId(const std::string& src, const std::string& type,
                              const std::string& target, const SpellingLoc& loc) {
    std::string t = target.empty() ? "ext" : target;
    return src + ":" + type + ":" + t + "@" + std::to_string(loc.sl) + ":" + std::to_string(loc.sc);
}

static void emitEntity(TuCtx& c, const std::string& id, const std::string& type,
                       const std::string& name, const std::string& qname, const std::string& sig,
                       const SpellingLoc& loc, const std::vector<std::string>& modifiers = {},
                       const std::map<std::string, std::string>& metadata = {}) {
    if (id.empty() || c.emittedEntityIds.count(id)) return;
    c.emittedEntityIds.insert(id);
    recon::Json e = recon::Json::makeObj();
    e("id") = recon::Json::makeStr(id);
    e("type") = recon::Json::makeStr(type);
    e("name") = recon::Json::makeStr(name);
    e("qualifiedName") = recon::Json::makeStr(qname.empty() ? name : qname);
    if (!sig.empty()) e("signature") = recon::Json::makeStr(sig);
    e("language") = recon::Json::makeStr(c.lang);
    e("location") = makeLoc(loc.file, loc.sl, loc.sc, loc.el, loc.ec);
    recon::Json mods = recon::Json::makeArr();
    for (const auto& m : modifiers) mods.arr.push_back(recon::Json::makeStr(m));
    e("modifiers") = mods;
    if (!metadata.empty()) {
        recon::Json md = recon::Json::makeObj();
        for (const auto& kv : metadata) md(kv.first) = recon::Json::makeStr(kv.second);
        e("metadata") = md;
    }
    c.entities.arr.push_back(e);
}

/** emitEntity 的直接位置重载（File 实体等已知位置的场景） */
static void emitEntityAt(TuCtx& c, const std::string& id, const std::string& type,
                         const std::string& name, const std::string& qname,
                         const recon::Json& loc) {
    if (id.empty() || c.emittedEntityIds.count(id)) return;
    c.emittedEntityIds.insert(id);
    recon::Json e = recon::Json::makeObj();
    e("id") = recon::Json::makeStr(id);
    e("type") = recon::Json::makeStr(type);
    e("name") = recon::Json::makeStr(name);
    e("qualifiedName") = recon::Json::makeStr(qname.empty() ? name : qname);
    e("language") = recon::Json::makeStr(c.lang);
    e("location") = loc;
    e("modifiers") = recon::Json::makeArr();
    c.entities.arr.push_back(e);
}

static void emitRelation(TuCtx& c, const std::string& src, const std::string& type,
                         const std::string& target, const SpellingLoc& loc,
                         const std::map<std::string, std::string>& metadata = {}) {
    if (src.empty()) return;
    std::string id = relationId(src, type, target, loc);
    if (c.emittedRelationIds.count(id)) return;
    c.emittedRelationIds.insert(id);
    recon::Json r = recon::Json::makeObj();
    r("id") = recon::Json::makeStr(id);
    r("type") = recon::Json::makeStr(type);
    r("sourceId") = recon::Json::makeStr(src);
    if (!target.empty()) r("targetId") = recon::Json::makeStr(target);
    r("location") = makeLoc(loc.file, loc.sl, loc.sc, loc.el, loc.ec);
    if (!metadata.empty()) {
        recon::Json md = recon::Json::makeObj();
        for (const auto& kv : metadata) md(kv.first) = recon::Json::makeStr(kv.second);
        r("metadata") = md;
    }
    c.relations.arr.push_back(r);
}

/** 解析关系目标（引用 cursor）的确定性 ID：
 *  优先本 TU 已产出实体（usrToId）；否则按 qualifiedName 规则构造（跨文件确定性）；
 *  无法解析返回空串（→ ext）。 */
static std::string targetIdOf(TuCtx& c, CXCursor ref, std::string* externalName = nullptr) {
    if (clang_Cursor_isNull(ref)) return "";
    // 模板实例化（显式/隐式）→ 归一到模板声明（R12：实例化默认不产出独立实体，
    // 且模板声明与实例化的 displayName 签名不同，会造成 ID 漂移）
    CXCursor tmpl = clang_getSpecializedCursorTemplate(ref);
    if (!clang_Cursor_isNull(tmpl)) ref = tmpl;
    CXCursorKind k = clang_getCursorKind(ref);
    // 仅类型/可执行/变量/宏定义可作目标
    if (k == CXCursor_MacroDefinition) {
        SpellingLoc l = spellLoc(c, clang_getCursorLocation(ref));
        std::string id = "cpp:" + l.file + ":" + std::to_string(l.sl) + ":" + std::to_string(l.sc);
        if (externalName) *externalName = cxstr(clang_getCursorSpelling(ref));
        return id;
    }
    std::string usr = cxstr(clang_getCursorUSR(ref));
    if (!usr.empty()) {
        auto it = c.usrToId.find(usr);
        if (it != c.usrToId.end()) return it->second;
    }
    std::string qname = qualifiedNameOf(ref);
    if (qname.empty()) {
        if (externalName) *externalName = cxstr(clang_getCursorSpelling(ref));
        return "";
    }
    if (externalName) *externalName = cxstr(clang_getCursorSpelling(ref));
    auto it = c.nameToId.find(qname);
    if (it != c.nameToId.end()) return it->second;
    // 根目录外的声明（系统/第三方头文件）：不给确定性 ID（会成悬空目标），记为 ext
    {
        SpellingLoc rl = spellLoc(c, clang_getCursorLocation(ref));
        if (rl.valid && !rl.file.empty() && rl.file.rfind('/', 0) == 0) {
            return ""; // 绝对路径 = 未落在项目根下 → 外部目标
        }
    }
    bool executable = (k == CXCursor_FunctionDecl || k == CXCursor_CXXMethod ||
                       k == CXCursor_Constructor || k == CXCursor_Destructor ||
                       k == CXCursor_FunctionTemplate);
    if (executable) return "cpp:" + qname + "#" + signatureOf(ref);
    return "cpp:" + qname;
}

/** 表达式（CallExpr/DeclRefExpr/TypeRef…）所属容器 ID：
 *  semantic parent 对表达式不可靠（如 VarDecl 初始化器内的调用会归属局部变量），
 *  沿链向上爬到 函数/类/命名空间/TU 再解析。 */
static std::string containerSourceIdOf(TuCtx& c, CXCursor cursor) {
    CXCursor p = clang_getCursorSemanticParent(cursor);
    for (int guard = 0; guard < 16 && !clang_Cursor_isNull(p); guard++) {
        CXCursorKind pk = clang_getCursorKind(p);
        if (pk == CXCursor_TranslationUnit || pk == CXCursor_Namespace ||
            pk == CXCursor_ClassDecl || pk == CXCursor_ClassTemplate ||
            pk == CXCursor_StructDecl || pk == CXCursor_UnionDecl ||
            pk == CXCursor_FunctionDecl || pk == CXCursor_CXXMethod ||
            pk == CXCursor_Constructor || pk == CXCursor_Destructor ||
            pk == CXCursor_FunctionTemplate) {
            break;
        }
        CXCursor up = clang_getCursorSemanticParent(p);
        if (clang_Cursor_isNull(up) ||
            clang_getCursorKind(up) == clang_getCursorKind(p) &&
                clang_equalCursors(up, p)) {
            break;
        }
        p = up;
    }
    std::string src = targetIdOf(c, p);
    if (src.empty()) src = "cpp:" + c.relPath;
    return src;
}

static bool inWhitelist(TuCtx& c, const std::string& name) {
    for (const auto& w : c.opts->templateWhitelist) {
        if (name == w || name.rfind(w + "::", 0) == 0 || name.rfind(w + "<", 0) == 0) return true;
    }
    return false;
}

static bool recordInstantiation(TuCtx& c, const std::string& name) {
    const std::string& p = c.opts->templatePolicy;
    if (p == "full") return true;
    if (p == "whitelist") return inWhitelist(c, name);
    return false; // declarations / off：跳过实例化
}

/** cursor kind → UCM 实体类型名；空串表示不产出实体 */
static std::string entityKindName(CXCursorKind k, bool* executable) {
    *executable = false;
    switch (k) {
        case CXCursor_Namespace: return "Namespace";
        case CXCursor_StructDecl: return "Struct";
        case CXCursor_ClassDecl:
        case CXCursor_ClassTemplate: return "Class";
        case CXCursor_UnionDecl: return "Union";
        case CXCursor_EnumDecl: return "Enum";
        case CXCursor_EnumConstantDecl: return "EnumConstant";
        case CXCursor_FieldDecl: return "Field";
        case CXCursor_VarDecl: return "Variable"; // 函数内局部经 declareEntity 特判为 LocalVariable
        case CXCursor_FunctionDecl:
        case CXCursor_FunctionTemplate: *executable = true; return "Function";
        case CXCursor_CXXMethod: *executable = true; return "Method";
        case CXCursor_Constructor: *executable = true; return "Constructor";
        case CXCursor_Destructor: *executable = true; return "Destructor";
        default: return "";
    }
}

/** VarDecl 是否为函数内局部变量 */
static bool isLocalVarDecl(CXCursor cursor) {
    CXCursorKind pk = clang_getCursorKind(clang_getCursorSemanticParent(cursor));
    return pk == CXCursor_FunctionDecl || pk == CXCursor_CXXMethod ||
           pk == CXCursor_Constructor || pk == CXCursor_Destructor ||
           pk == CXCursor_FunctionTemplate;
}

static std::vector<std::string> cursorModifiers(CXCursor cursor) {
    std::vector<std::string> mods;
    CXCursorKind k = clang_getCursorKind(cursor);
    if (k == CXCursor_CXXMethod || k == CXCursor_Constructor || k == CXCursor_Destructor ||
        k == CXCursor_FunctionDecl || k == CXCursor_FunctionTemplate) {
        if (clang_Cursor_isFunctionInlined(cursor)) mods.push_back("inline");
        if ((k == CXCursor_CXXMethod) && clang_CXXMethod_isStatic(cursor)) mods.push_back("static");
        if ((k == CXCursor_CXXMethod) && clang_CXXMethod_isVirtual(cursor)) mods.push_back("virtual");
    }
    return mods;
}

/** 宏体原文（token 拼接，R13 metadata.macroBody） */
static std::string macroBodyText(TuCtx& c, CXCursor cursor) {
    CXToken* toks = nullptr;
    unsigned n = 0;
    CXSourceRange extent = clang_getCursorExtent(cursor);
    clang_tokenize(c.tu, extent, &toks, &n);
    std::string out;
    for (unsigned i = 0; i < n; i++) {
        if (i) out += " ";
        out += cxstr(clang_getTokenSpelling(c.tu, toks[i]));
    }
    if (toks) clang_disposeTokens(c.tu, toks, n);
    return out;
}

/** CONTAINS 来源 ID：语义父为 TU → File 实体 ID；否则父实体确定性 ID（父可能在头文件，按 qualifiedName 构造） */
static std::string containerIdOf(TuCtx& c, CXCursor cursor) {
    CXCursor p = clang_getCursorSemanticParent(cursor);
    CXCursorKind pk = clang_getCursorKind(p);
    if (pk == CXCursor_TranslationUnit) {
        return "cpp:" + c.relPath; // File 实体
    }
    bool exec = false;
    std::string kind = entityKindName(pk, &exec);
    if (kind.empty()) return "";
    std::string qname = qualifiedNameOf(p);
    if (qname.empty()) return "";
    return "cpp:" + qname;
}

static enum CXChildVisitResult visitCursor(CXCursor cursor, CXCursor parent, CXClientData data);

/** 声明类 cursor → 实体 + CONTAINS；返回实体 ID（空 = 未产出） */
static std::string declareEntity(TuCtx& c, CXCursor cursor) {
    CXCursorKind k = clang_getCursorKind(cursor);
    bool executable = false;
    std::string kind = entityKindName(k, &executable);
    if (kind.empty()) return "";

    // R12 模板策略：off 跳过模板声明体
    if (c.opts->templatePolicy == "off" &&
        (k == CXCursor_ClassTemplate || k == CXCursor_FunctionTemplate)) {
        c.skippedTemplates++;
        return "";
    }

    SpellingLoc loc = spellLoc(c, clang_getCursorExtent(cursor));
    if (!loc.inMain) return ""; // 只产出主文件实体（避免被 #include 头文件重复产出）

    std::string name = cxstr(clang_getCursorSpelling(cursor));
    std::string qname = qualifiedNameOf(cursor);
    std::string sig = executable ? signatureOf(cursor) : "";

    // 函数内局部变量：LocalVariable（qualifiedName 无语义，位置兜底 ID 保证确定性）
    if (k == CXCursor_VarDecl && isLocalVarDecl(cursor)) {
        std::string id = "cpp:" + loc.file + ":" + std::to_string(loc.sl) + ":" + std::to_string(loc.sc);
        emitEntity(c, id, "LocalVariable", name, name, "", loc);
        std::string usr = cxstr(clang_getCursorUSR(cursor));
        if (!usr.empty()) c.usrToId[usr] = id;
        std::string container = targetIdOf(c, clang_getCursorSemanticParent(cursor));
        if (!container.empty()) emitRelation(c, container, "CONTAINS", id, loc);
        return id;
    }

    std::string id = entityId(qname, loc, executable, sig);

    // 宏定义（R13）：位置兜底 ID + metadata.macroBody
    if (k == CXCursor_MacroDefinition) {
        // MacroDefinition 不经此路径（见 visitCursor），防御性返回
        return "";
    }

    std::map<std::string, std::string> metadata;
    if (k == CXCursor_ClassTemplate || k == CXCursor_FunctionTemplate) {
        metadata["template"] = "true";
    }
    emitEntity(c, id, kind, name, qname, sig, loc, cursorModifiers(cursor), metadata);

    std::string usr = cxstr(clang_getCursorUSR(cursor));
    if (!usr.empty()) c.usrToId[usr] = id;
    if (!qname.empty()) c.nameToId[qname] = id;

    // CONTAINS（父 → 本实体；位置取子实体位置）
    std::string container = containerIdOf(c, cursor);
    if (container.empty() && k != CXCursor_Namespace) {
        // containerIdOf 对可执行父类构造 ID 不含签名，用 targetIdOf 兜底
        container = targetIdOf(c, clang_getCursorSemanticParent(cursor));
    }
    if (!container.empty()) {
        emitRelation(c, container, "CONTAINS", id, loc);
    }
    return id;
}

static enum CXChildVisitResult visitCursor(CXCursor cursor, CXCursor parent, CXClientData data) {
    TuCtx& c = *static_cast<TuCtx*>(data);
    CXCursorKind k = clang_getCursorKind(cursor);
    SpellingLoc loc = spellLoc(c, clang_getCursorLocation(cursor));

    // ---- 宏（R13：记录宏定义 + 展开点 REFERENCES，位置一律 spelling） ----
    if (k == CXCursor_MacroDefinition) {
        if (!c.opts->recordMacros || !loc.inMain) return CXChildVisit_Continue;
        std::string name = cxstr(clang_getCursorSpelling(cursor));
        std::string id = "cpp:" + loc.file + ":" + std::to_string(loc.sl) + ":" + std::to_string(loc.sc);
        std::string display = cxstr(clang_getCursorDisplayName(cursor));
        std::map<std::string, std::string> metadata;
        metadata["macroBody"] = macroBodyText(c, cursor);
        size_t lp = display.find('(');
        if (lp != std::string::npos && display.find(')') != std::string::npos) {
            metadata["macroParams"] = display.substr(lp + 1, display.rfind(')') - lp - 1);
        }
        emitEntity(c, id, "Macro", name, name, "", loc, {}, metadata);
        return CXChildVisit_Continue;
    }
    if (k == CXCursor_MacroExpansion) {
        if (!c.opts->recordMacros || !loc.inMain) return CXChildVisit_Continue;
        // R13：展开点 → 宏定义 REFERENCES；预处理 cursor 无语义父，延迟到遍历结束按位置归属
        CXCursor ref = clang_getCursorReferenced(cursor);
        TuCtx::MacroRefPending p;
        p.loc = loc;
        p.target = targetIdOf(c, ref, &p.externalName);
        c.pendingMacroRefs.push_back(p);
        return CXChildVisit_Continue;
    }

    // ---- 模板实例化策略（R12）----
    // 说明：libclang C API 不区分 ClassTemplateSpecializationDecl（呈现为 ClassDecl），
    // 实例化跳过在声明级（off）与模板函数调用级（CallExpr→FunctionTemplate）控制。
    if (k == CXCursor_ClassTemplate && loc.inMain &&
        c.opts->templatePolicy != "full" && c.opts->templatePolicy != "off") {
        // declarations/whitelist：仅记录模板声明（实体照常产出），实例化体不计
        c.skippedTemplates++; // 统计口径：跳过该模板的实例化记录
    }

    // ---- 继承（C++ 统一 INHERITS；CXXBaseSpecifier 是 class/struct 的子 cursor） ----
    if (k == CXCursor_CXXBaseSpecifier) {
        // 注意：base specifier 的 semantic/lexical parent 均为 InvalidFile，
        // 来源必须用 visitor 回调的 parent（即派生类）。
        if (!loc.inMain) return CXChildVisit_Continue; // 跳过系统/第三方头文件中的继承
        CXCursor ref = clang_getCursorReferenced(cursor);
        std::string externalName;
        std::string target = targetIdOf(c, ref, &externalName);
        std::string src = targetIdOf(c, parent);
        if (src.empty()) src = "cpp:" + qualifiedNameOf(parent);
        std::map<std::string, std::string> md;
        if (target.empty() && !externalName.empty()) md["externalTarget"] = externalName;
        emitRelation(c, src, "INHERITS", target, loc, md);
        return CXChildVisit_Continue;
    }

    // ---- 声明实体（Namespace/Class/Struct/Function/Method/…） ----
    bool executable = false;
    std::string kind = entityKindName(k, &executable);
    if (!kind.empty()) {
        declareEntity(c, cursor);
        // 声明节点继续递归（方法体、类成员、namespace 内容等）
        return CXChildVisit_Recurse;
    }

    // ---- 引用与调用（仅主文件内的引用点产出关系） ----
    if (loc.inMain) {
        if (k == CXCursor_CallExpr) {
            CXCursor ref = clang_getCursorReferenced(cursor);
            std::string externalName;
            std::string target = targetIdOf(c, ref, &externalName);
            // 模板函数调用：按策略决定记录/跳过
            CXCursorKind rk = clang_getCursorKind(ref);
            if (rk == CXCursor_FunctionTemplate && !recordInstantiation(
                    c, cxstr(clang_getCursorSpelling(ref)))) {
                c.skippedTemplates++;
                return CXChildVisit_Recurse;
            }
            std::string src = containerSourceIdOf(c, cursor);
            std::map<std::string, std::string> md;
            if (target.empty() && !externalName.empty()) md["externalTarget"] = externalName;
            emitRelation(c, src, "CALLS", target, loc, md);
            return CXChildVisit_Recurse;
        }
        if (k == CXCursor_TypeRef || k == CXCursor_TemplateRef) {
            CXCursor ref = clang_getCursorReferenced(cursor);
            CXCursorKind rk = clang_getCursorKind(ref);
            // 模板参数（T 等）不产出关系（噪音）
            if (rk == CXCursor_TemplateTypeParameter || rk == CXCursor_NonTypeTemplateParameter) {
                return CXChildVisit_Recurse;
            }
            std::string externalName;
            std::string target = targetIdOf(c, ref, &externalName);
            std::string src = containerSourceIdOf(c, cursor);
            std::map<std::string, std::string> md;
            if (target.empty() && !externalName.empty()) md["externalTarget"] = externalName;
            emitRelation(c, src, "DEPENDS_ON", target, loc, md);
            return CXChildVisit_Recurse;
        }
        if (k == CXCursor_DeclRefExpr) {
            CXCursor ref = clang_getCursorReferenced(cursor);
            CXCursorKind rk = clang_getCursorKind(ref);
            // 局部变量引用不产出关系（噪音）；仅记录字段/静态成员/函数引用
            if (rk == CXCursor_VarDecl && isLocalVarDecl(ref)) {
                return CXChildVisit_Recurse;
            }
            if (rk == CXCursor_VarDecl || rk == CXCursor_FieldDecl || rk == CXCursor_FunctionDecl ||
                rk == CXCursor_CXXMethod || rk == CXCursor_FunctionTemplate) {
                std::string externalName;
                std::string target = targetIdOf(c, ref, &externalName);
                std::string src = containerSourceIdOf(c, cursor);
                std::map<std::string, std::string> md;
                if (target.empty() && !externalName.empty()) md["externalTarget"] = externalName;
                emitRelation(c, src, rk == CXCursor_VarDecl || rk == CXCursor_FieldDecl
                                            ? "REFERENCES" : "CALLS", target, loc, md);
            }
            return CXChildVisit_Recurse;
        }
        if (k == CXCursor_CXXNewExpr) {
            CXType t = clang_getCursorType(cursor);
            std::string typeName = cxstr(clang_getTypeSpelling(t));
            // new T → INSTANTIATES（目标为类实体，目标 ID 按 qualifiedName 约定）
            std::string target = "cpp:" + typeName;
            size_t lt = target.find('<');
            if (lt != std::string::npos) target = target.substr(0, lt);
            std::string src = containerSourceIdOf(c, cursor);
            emitRelation(c, src, "INSTANTIATES", target, loc);
            return CXChildVisit_Recurse;
        }
    }

    return CXChildVisit_Recurse;
}

/** 由 compile_commands 条目构造 clang 参数（去 argv[0]、-c、源文件、-o 输出） */
static std::vector<std::string> argsFromCompileCommand(const recon::Json* cc, const FileReq& f) {
    std::vector<std::string> args;
    if (!cc) return args;
    const recon::Json* arguments = cc->get("arguments");
    if (!arguments || arguments->type != recon::Json::Arr) return args;
    for (size_t i = 1; i < arguments->arr.size(); i++) {
        std::string tok = arguments->arr[i].asStr();
        if (tok == "-c") continue;
        if (tok == f.path || tok == f.abs) continue;
        if (!f.abs.empty() && !tok.empty() && tok[0] != '-' &&
            f.abs.size() > tok.size() && f.abs.compare(f.abs.size() - tok.size(), tok.size(), tok) == 0) {
            continue; // 以相对路径出现的源文件参数
        }
        if (tok == "-o") {
            i++; // 跳过输出文件
            continue;
        }
        args.push_back(tok);
    }
    return args;
}

/** 解析单个 TU 并填充 ctx；返回是否成功（致命错误 → false） */
static bool parseTu(TuCtx& c, const FileReq& f, const recon::Json* ccEntry) {
    // 编译参数：compile_commands 优先；缺失 → fallback（02.3 降级 + CPP.FALLBACK_MODE）
    std::vector<std::string> args;
    bool fallback = false;
    if (ccEntry) {
        args = argsFromCompileCommand(ccEntry, f);
    }
    if (args.empty()) {
        fallback = true;
        std::string stdFlag = "-std=" + c.opts->standard;
        if (isCPath(f.path)) stdFlag = "-std=c11";
        // 头文件默认按 C 处理，需显式指定 C++（含 compile_commands 命中场景之外的兜底）
        if (!isCPath(f.path) && f.path.size() >= 2 &&
            (f.path.substr(f.path.size() - 2) == ".h" ||
             f.path.substr(f.path.size() - 4) == ".hpp" ||
             f.path.substr(f.path.size() - 3) == ".hh")) {
            args.push_back("-x");
            args.push_back("c++");
        }
        args.push_back(stdFlag);
        for (const auto& d : c.opts->fallbackIncludeDirs) args.push_back("-I" + d);
        for (const auto& d : c.autoIncludeDirs) args.push_back("-I" + d);
        for (const auto& d : c.opts->fallbackDefines) args.push_back("-D" + d);
    }

    std::vector<const char*> cargs;
    for (const auto& a : args) cargs.push_back(a.c_str());

    CXIndex index = clang_createIndex(0, 0);
    CXTranslationUnit tu = nullptr;
    CXErrorCode err = clang_parseTranslationUnit2(
            index, f.abs.c_str(), cargs.data(), (int)cargs.size(), nullptr, 0,
            CXTranslationUnit_DetailedPreprocessingRecord | CXTranslationUnit_KeepGoing, &tu);
    if (err != CXError_Success || !tu) {
        c.issues.arr.push_back(makeIssue("ERROR", "CPP.TU_PARSE_FAILED",
                "TU 解析失败（code=" + std::to_string((int)err) + "）：" + f.path, f.path, 1, 1));
        c.hasError = true;
        if (tu) clang_disposeTranslationUnit(tu);
        clang_disposeIndex(index);
        return false;
    }

    c.tu = tu;
    CXSourceLocation loc = clang_getLocation(tu, clang_getFile(tu, f.abs.c_str()), 1, 1);
    c.mainFile = nullptr;
    {
        CXFile file = nullptr;
        unsigned l, co, off;
        clang_getSpellingLocation(loc, &file, &l, &co, &off);
        c.mainFile = file;
    }

    // 诊断 → issues（仅主文件诊断，最多 50 条）
    unsigned nDiag = clang_getNumDiagnostics(tu);
    int emittedDiag = 0;
    for (unsigned i = 0; i < nDiag && emittedDiag < 50; i++) {
        CXDiagnostic d = clang_getDiagnostic(tu, i);
        CXDiagnosticSeverity sev = clang_getDiagnosticSeverity(d);
        if (sev >= CXDiagnostic_Warning) {
            CXSourceLocation dl = clang_getDiagnosticLocation(d);
            CXFile df = nullptr;
            unsigned sl = 0, sc = 0, off;
            clang_getSpellingLocation(dl, &df, &sl, &sc, &off);
            bool inMain = df != nullptr && df == c.mainFile;
            if (inMain) {
                std::string rel = toRel(c, df ? cxstr(clang_getFileName(df)) : "");
                const char* sevStr = sev >= CXDiagnostic_Error ? "ERROR" : "WARNING";
                c.issues.arr.push_back(makeIssue(sevStr, "CPP.DIAGNOSTIC",
                        cxstr(clang_getDiagnosticSpelling(d)), rel, sl, sc));
                if (sev >= CXDiagnostic_Error) c.hasError = true;
                emittedDiag++;
            }
        }
        clang_disposeDiagnostic(d);
    }

    if (fallback) {
        c.issues.arr.push_back(makeIssue("WARNING", "CPP.FALLBACK_MODE",
                "无 compile_commands 条目，使用 fallback 参数解析（语义绑定可能不完整）：" + f.path,
                f.path, 1, 1));
    }

    // File 实体（UCM：lang:path 为 File 确定性 ID，01.5）
    {
        std::string name = f.path.substr(f.path.find_last_of('/') + 1);
        std::string content = readFile(f.abs);
        size_t nLines = (size_t)std::count(content.begin(), content.end(), '\n') + 1;
        std::string fileId = "cpp:" + f.path;
        emitEntityAt(c, fileId, "File", name, f.path, makeLoc(f.path, 1, 1, (unsigned)nLines, 1));
        c.nameToId[f.path] = fileId;
    }

    // 语义遍历
    CXCursor root = clang_getTranslationUnitCursor(tu);
    clang_visitChildren(root, visitCursor, &c);

    // R13 宏展开关系归属：找同文件内位置包含展开点的最内层实体（优先可执行实体）；
    // 无包含实体时归属 File 实体。
    for (const auto& p : c.pendingMacroRefs) {
        std::string src = "cpp:" + c.relPath;
        int bestSpan = 1 << 30;
        for (const auto& e : c.entities.arr) {
            const recon::Json* idJ = e.get("id");
            const recon::Json* locJ = e.get("location");
            if (!idJ || !locJ) continue;
            const recon::Json* f = locJ->get("file");
            const recon::Json* sl = locJ->get("startLine");
            const recon::Json* sc = locJ->get("startCol");
            const recon::Json* el = locJ->get("endLine");
            const recon::Json* ec = locJ->get("endCol");
            if (!f || !sl || !el || f->asStr() != p.loc.file) continue;
            // 位置级包含判断（半开区间，按行+列元组比较；单行实体行级判断会误判为空区间）
            int s = (int)sl->asNum(), e2 = (int)el->asNum();
            int scv = sc ? (int)sc->asNum() : 1;
            int ecv = ec ? (int)ec->asNum() : 1;
            bool afterStart = p.loc.sl > s || (p.loc.sl == s && p.loc.sc >= scv);
            bool beforeEnd = p.loc.sl < e2 || (p.loc.sl == e2 && p.loc.sc < ecv);
            if (afterStart && beforeEnd) {
                int span = e2 - s;
                // 可执行实体优先（span 相同时）；更小 span（更内层）优先
                bool isExec = e.get("signature") != nullptr;
                int score = span - (isExec ? 100000 : 0);
                if (score < bestSpan) {
                    bestSpan = score;
                    src = idJ->asStr();
                }
            }
        }
        std::map<std::string, std::string> md;
        if (p.target.empty() && !p.externalName.empty()) md["externalTarget"] = p.externalName;
        emitRelation(c, src, "REFERENCES", p.target, p.loc, md);
    }

    // 模板跳过聚合 issue（R12）
    if (c.skippedTemplates > 0) {
        c.issues.arr.push_back(makeIssue("INFO", "CPP.TEMPLATE_INSTANTIATION_SKIPPED",
                "模板实例化被跳过 " + std::to_string(c.skippedTemplates) +
                    " 处（templatePolicy=" + c.opts->templatePolicy + "）", f.path, 1, 1));
    }

    clang_disposeTranslationUnit(tu);
    clang_disposeIndex(index);
    return true;
}

int runSemantic(const recon::Json& req, const std::vector<FileReq>& files,
                const Options& opts, recon::Json& resp) {
    std::string rootDir = req.get("rootDir") ? req.get("rootDir")->asStr() : "";

    // compile_commands 索引：file 路径 → 条目
    std::map<std::string, const recon::Json*> ccIndex;
    if (const recon::Json* ccs = req.get("compileCommands")) {
        if (ccs->type == recon::Json::Arr) {
            for (const auto& e : ccs->arr) {
                if (const recon::Json* fp = e.get("file")) {
                    ccIndex[fp->asStr()] = &e;
                }
            }
        }
    }

    recon::Json fileArr = recon::Json::makeArr();
    int skippedTemplates = 0;
    bool anyError = false;
    auto t0 = std::chrono::steady_clock::now();

    // fallback 自动 include：本批文件所在目录（覆盖同目录/兄弟目录 #include "xx.h" 布局）
    std::set<std::string> autoIncludeDirs;
    for (const auto& f : files) {
        size_t slash = f.abs.find_last_of('/');
        if (slash != std::string::npos && slash > 0) {
            autoIncludeDirs.insert(f.abs.substr(0, slash));
        }
    }

    for (const auto& f : files) {
        TuCtx c;
        c.relPath = f.path;
        c.absPath = f.abs;
        c.rootDir = rootDir;
        c.lang = isCPath(f.path) ? "C" : "C++";
        c.opts = &opts;
        c.autoIncludeDirs.assign(autoIncludeDirs.begin(), autoIncludeDirs.end());

        const recon::Json* ccEntry = nullptr;
        auto it = ccIndex.find(f.path);
        if (it != ccIndex.end()) ccEntry = it->second;

        parseTu(c, f, ccEntry);

        recon::Json per = recon::Json::makeObj();
        per("path") = recon::Json::makeStr(f.path);
        per("entities") = c.entities;
        per("relations") = c.relations;
        per("issues") = c.issues;
        fileArr.arr.push_back(per);

        skippedTemplates += c.skippedTemplates;
        anyError = anyError || c.hasError;
    }

    resp("files") = fileArr;
    recon::Json stats = recon::Json::makeObj();
    stats("tuCount") = recon::Json::makeNum((double)files.size());
    long durMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - t0).count();
    stats("durationMs") = recon::Json::makeNum(durMs);
    stats("skippedTemplateInstantiations") = recon::Json::makeNum(skippedTemplates);
    resp("stats") = stats;
    return anyError ? 1 : 0;
}

#else

// ---------------- 协议骨架（无 libclang 时的可运行降级） ----------------

int runSkeleton(const std::vector<FileReq>& files, const Options& opts, recon::Json& resp) {
    recon::Json fileArr = recon::Json::makeArr();
    int skippedTemplates = 0;
    for (const auto& f : files) {
        recon::Json per = recon::Json::makeObj();
        per("path") = recon::Json::makeStr(f.path);
        recon::Json entities = recon::Json::makeArr();
        recon::Json relations = recon::Json::makeArr();
        recon::Json issues = recon::Json::makeArr();

        recon::Json fe = recon::Json::makeObj();
        fe("id") = recon::Json::makeStr("cpp:" + f.path);
        fe("type") = recon::Json::makeStr("File");
        fe("name") = recon::Json::makeStr(f.path.substr(f.path.find_last_of('/') + 1));
        fe("qualifiedName") = recon::Json::makeStr(f.path);
        fe("language") = recon::Json::makeStr(isCppPath(f.path) ? "C++" : "C");
        entities.arr.push_back(fe);

        issues.arr.push_back(makeIssue("INFO", "CPP.PROTOCOL_SCAFFOLD",
                "未链接 libclang：仅产出 File 实体（协议骨架降级）。请安装 libclang 后重新构建以启用语义级提取。"));
        if (opts.templatePolicy != "full") {
            skippedTemplates++;
            issues.arr.push_back(makeIssue("INFO", "CPP.TEMPLATE_INSTANTIATION_SKIPPED",
                    "模板实例化被跳过（templatePolicy=" + opts.templatePolicy + "）"));
        }

        per("entities") = entities;
        per("relations") = relations;
        per("issues") = issues;
        fileArr.arr.push_back(per);
    }
    resp("files") = fileArr;
    recon::Json stats = recon::Json::makeObj();
    stats("tuCount") = recon::Json::makeNum((double)files.size());
    stats("durationMs") = recon::Json::makeNum(1);
    stats("skippedTemplateInstantiations") = recon::Json::makeNum(skippedTemplates);
    resp("stats") = stats;
    return 0;
}

#endif // RECON_HAS_LIBCLANG

int main(int argc, char** argv) {
    bool selfTest = argc > 1 && std::string(argv[1]) == "--self-test";
    bool selfTestCount = argc > 1 && std::string(argv[1]) == "--count";

    std::string reqText = readStdin();
    recon::Json req;
    std::string err;
    if (reqText.size() > 0 && reqText[reqText.size() - 1] == '\n') reqText.pop_back();
    if (!recon::Json::parse(reqText, req, err)) {
        fprintf(stderr, "[clang-parser] 请求 JSON 解析失败: %s\n", err.c_str());
        return 2;
    }

    std::string mode = req.get("mode") ? req.get("mode")->asStr() : "cpp";
    (void)mode;

    Options opts;
    if (const recon::Json* options = req.get("options")) {
        if (const recon::Json* tp = options->get("templatePolicy")) opts.templatePolicy = tp->asStr();
        if (const recon::Json* rm = options->get("recordMacros")) opts.recordMacros = rm->asBool();
        if (const recon::Json* st = options->get("standard")) opts.standard = st->asStr();
        opts.templateWhitelist = jsonStrArray(options->get("templateWhitelist"));
        opts.fallbackIncludeDirs = jsonStrArray(options->get("fallbackIncludeDirs"));
        opts.fallbackDefines = jsonStrArray(options->get("fallbackDefines"));
    }

    std::vector<FileReq> files;
    if (const recon::Json* fs = req.get("files")) {
        if (fs->type == recon::Json::Arr) {
            for (const auto& f : fs->arr) {
                FileReq fr;
                fr.path = f.get("path") ? f.get("path")->asStr() : "";
                fr.abs = f.get("absolutePath") ? f.get("absolutePath")->asStr() : "";
                if (!fr.path.empty()) files.push_back(fr);
            }
        }
    }

    if (selfTest) {
        const recon::Json* msg = req.get("echo");
        recon::Json ok = recon::Json::makeObj();
        ok("ok") = recon::Json::makeBool(true);
        const recon::Json* val = (msg && msg->get("value")) ? msg->get("value") : nullptr;
        ok("echo") = val ? *val : recon::Json::makeStr("");
#ifdef RECON_HAS_LIBCLANG
        ok("libclang") = recon::Json::makeBool(true);
#else
        ok("libclang") = recon::Json::makeBool(false);
#endif
        printf("%s\n", ok.dump().c_str());
        return 0;
    }
    if (selfTestCount) {
        printf("%zu\n", files.size());
        return 0;
    }

    recon::Json resp = recon::Json::makeObj();
#ifdef RECON_HAS_LIBCLANG
    int exitCode = runSemantic(req, files, opts, resp);
#else
    int exitCode = runSkeleton(files, opts, resp);
#endif
    printf("%s\n", resp.dump().c_str());
    return exitCode;
}
