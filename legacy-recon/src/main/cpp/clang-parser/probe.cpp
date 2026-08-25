// 探针：验证 libclang 是否遍历 CXXBaseSpecifier 及其 semantic parent 归属
#include "clang-c/Index.h"
#include <cstdio>
#include <string>

static std::string cs(CXString s) { const char* p = clang_getCString(s); std::string r = p ? p : ""; clang_disposeString(s); return r; }

static enum CXChildVisitResult visit(CXCursor cursor, CXCursor parent, CXClientData data) {
    CXCursorKind k = clang_getCursorKind(cursor);
    std::string kn = cs(clang_getCursorKindSpelling(k));
    std::string name = cs(clang_getCursorSpelling(cursor));
    if (k == CXCursor_CXXBaseSpecifier) {
        CXCursor sp = clang_getCursorSemanticParent(cursor);
        printf("BASE SPECIFIER: kind=%d name=%s\n", (int)k, name.c_str());
        printf("  semantic parent kind=%d (%s) spelling=%s\n", (int)clang_getCursorKind(sp),
               cs(clang_getCursorKindSpelling(clang_getCursorKind(sp))).c_str(),
               cs(clang_getCursorSpelling(sp)).c_str());
        printf("  lexical parent kind=%d spelling=%s\n", (int)clang_getCursorKind(clang_getCursorLexicalParent(cursor)),
               cs(clang_getCursorSpelling(clang_getCursorLexicalParent(cursor))).c_str());
        CXCursor ref = clang_getCursorReferenced(cursor);
        printf("  referenced: null=%d kind=%d spelling=%s usr=%s\n", clang_Cursor_isNull(ref),
               (int)clang_getCursorKind(ref), cs(clang_getCursorSpelling(ref)).c_str(),
               cs(clang_getCursorUSR(ref)).c_str());
        printf("  parent(arg) kind=%d spelling=%s\n", (int)clang_getCursorKind(parent),
               cs(clang_getCursorSpelling(parent)).c_str());
    }
    if (name == "SavingsAccount") {
        printf("DERIVED CLASS FOUND kind=%d\n", (int)k);
        // 单独访问其子节点
        clang_visitChildren(cursor, [](CXCursor cur, CXCursor par, CXClientData) {
            std::string kn2 = cs(clang_getCursorKindSpelling(clang_getCursorKind(cur)));
            printf("  child of SavingsAccount: %s (%s)\n", kn2.c_str(), cs(clang_getCursorSpelling(cur)).c_str());
            return CXChildVisit_Continue;
        }, nullptr);
    }
    return CXChildVisit_Recurse;
}

int main(int argc, char** argv) {
    CXIndex idx = clang_createIndex(0, 0);
    const char* args[] = {"-x", "c++", "-std=c++17"};
    CXTranslationUnit tu = nullptr;
    CXErrorCode err = clang_parseTranslationUnit2(idx, argv[1], args, 3, nullptr, 0,
        CXTranslationUnit_DetailedPreprocessingRecord, &tu);
    if (err != CXError_Success || !tu) { printf("parse failed %d\n", (int)err); return 1; }
    clang_visitChildren(clang_getTranslationUnitCursor(tu), visit, nullptr);
    return 0;
}
