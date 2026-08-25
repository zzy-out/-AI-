// Clang 子进程协议实现（02.3 / ADR-002）。
//
// 传输约定：请求 JSON 从 stdin 读入（单个对象，一次请求对应一批翻译单元 TU）；
// 响应 JSON 写 stdout；stderr 仅作日志。
// 退出码：0=全部成功；1=部分成功（响应带 issues）；2=协议/环境错误。
//
// 说明：完整语义绑定依赖 libclang 链接（optionally 通过 clang 命令行或 LibTooling）。
// 本脚手架实现协议骨架与逐文件 UCM 片段（File 实体 + 语言/宏/模板策略 issue），
// 作为 C/C++ 解析器接入的协议占位与可运行样例。
#include "json.hpp"

#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

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

static bool isCpp(const std::string& path) {
    return path.size() >= 4 && (path.substr(path.size() - 4) == ".cpp" ||
                                path.substr(path.size() - 4) == ".cc" ||
                                path.substr(path.size() - 4) == ".cxx");
}

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
    const recon::Json* files = req.get("files");
    const recon::Json* options = req.get("options");
    std::string templatePolicy = "declarations";
    bool recordMacros = true;
    if (options) {
        if (const recon::Json* tp = options->get("templatePolicy")) templatePolicy = tp->asStr();
        if (const recon::Json* rm = options->get("recordMacros")) recordMacros = rm->asBool();
    }

    recon::Json resp = recon::Json::makeObj();
    recon::Json fileArr = recon::Json::makeArr();
    int skippedTemplates = 0;
    int fileCount = 0;
    bool partialSuccess = false;

    if (files) {
        for (const auto& f : files->arr) {
            std::string path = f.get("path") ? f.get("path")->asStr() : "";
            std::string abs = f.get("absolutePath") ? f.get("absolutePath")->asStr() : "";
            if (path.empty()) continue;
            fileCount++;

            recon::Json per = recon::Json::makeObj();
            per("path") = recon::Json::makeStr(path);
            recon::Json entities = recon::Json::makeArr();
            recon::Json relations = recon::Json::makeArr();
            recon::Json issues = recon::Json::makeArr();

            // File 实体（UCM：lang:path 为 File 确定性 ID，01.5）
            recon::Json fe = recon::Json::makeObj();
            fe("id") = recon::Json::makeStr("cpp:" + path);
            fe("type") = recon::Json::makeStr("File");
            fe("name") = recon::Json::makeStr(path.substr(path.find_last_of('/') + 1));
            fe("qualifiedName") = recon::Json::makeStr(path);
            fe("language") = recon::Json::makeStr(isCpp(path) ? "C++" : "C");
            entities.arr.push_back(fe);

            // 读取源码做轻量结构探测（可选的脚手架增强）
            std::string content = abs.empty() ? "" : readFile(abs);
            size_t nFunctions = 0;
            size_t pos = 0;
            const std::string sigs[] = {"::", "("};
            (void)sigs;
            // 简单计数函数签名出现的 "(" 并跟随 ")" 的行（脚手架启发式）
            std::string line;
            std::istringstream ls(content);
            while (std::getline(ls, line)) {
                if (line.find('(') != std::string::npos && line.find(')') != std::string::npos &&
                    (line.find("int") != std::string::npos || line.find("void") != std::string::npos ||
                     line.find("{") != std::string::npos)) {
                    nFunctions++;
                }
            }

            // 语义绑定需 libclang：脚手架以协议 issue 说明
            recon::Json issue = recon::Json::makeObj();
            issue("severity") = recon::Json::makeStr("INFO");
            issue("code") = recon::Json::makeStr("CPP.PROTOCOL_SCAFFOLD");
            issue("message") = recon::Json::makeStr(
                "协议骨架：术语级(CVD)提取需链接 libclang；本记录用于协议联通验证。functions≈" +
                std::to_string(nFunctions));

            // R12 模板策略：非 full 时对模板 TU 跳过实例化并计数
            if (templatePolicy != "full") {
                skippedTemplates++;
                recon::Json tpl = recon::Json::makeObj();
                tpl("severity") = recon::Json::makeStr("INFO");
                tpl("code") = recon::Json::makeStr("CPP.TEMPLATE_INSTANTIATION_SKIPPED");
                tpl("message") = recon::Json::makeStr("模板实例化被跳过（templatePolicy=" +
                                                      templatePolicy + "）");
                issues.arr.push_back(tpl);
            } else {
                issues.arr.push_back(issue);
            }

            per("entities") = entities;
            per("relations") = relations;
            per("issues") = issues;
            fileArr.arr.push_back(per);
        }
    }

    resp("files") = fileArr;
    recon::Json stats = recon::Json::makeObj();
    stats("tuCount") = recon::Json::makeNum(fileCount);
    stats("durationMs") = recon::Json::makeNum(1);
    stats("skippedTemplateInstantiations") = recon::Json::makeNum(skippedTemplates);
    resp("stats") = stats;

    if (selfTest) {
        // 自测：解析 stdin 中提供的三元组并回显 (message/ok)
        const recon::Json* msg = req.get("echo");
        recon::Json ok = recon::Json::makeObj();
        ok("ok") = recon::Json::makeBool(true);
        const recon::Json* val = (msg && msg->get("value")) ? msg->get("value") : nullptr;
        ok("echo") = val ? *val : recon::Json::makeStr("");
        printf("%s\n", ok.dump().c_str());
        return 0;
    }
    if (selfTestCount) {
        printf("%d\n", fileCount);
        return 0;
    }

    printf("%s\n", resp.dump().c_str());
    return partialSuccess ? 1 : 0;
}