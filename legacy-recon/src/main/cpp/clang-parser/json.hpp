// Minimal self-contained JSON parser/writer for the Clang subprocess protocol (ADR-002).
// No external dependency (nlohmann not guaranteed on target hosts).
#ifndef RECON_JSON_HPP
#define RECON_JSON_HPP

#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace recon {

struct Json;
using JsonObject = std::map<std::string, Json>;
using JsonArray = std::vector<Json>;

struct Json {
    enum Type { Null, Bool, Num, Str, Arr, Obj } type = Null;
    bool b = false;
    double num = 0;
    std::string s;
    JsonArray arr;
    JsonObject obj;

    static Json makeNull() { return Json(); }
    static Json makeBool(bool v) { Json j; j.type = Bool; j.b = v; return j; }
    static Json makeNum(double v) { Json j; j.type = Num; j.num = v; return j; }
    static Json makeStr(const std::string& v) { Json j; j.type = Str; j.s = v; return j; }
    static Json makeArr() { Json j; j.type = Arr; return j; }
    static Json makeObj() { Json j; j.type = Obj; return j; }

    bool isNull() const { return type == Null; }
    std::string asStr() const { return type == Str ? s : ""; }
    double asNum() const { return type == Num ? num : 0; }
    bool asBool() const { return type == Bool ? b : false; }

    Json& operator()(const std::string& key) { return obj[key]; }
    const Json* get(const std::string& key) const {
        auto it = obj.find(key);
        return it == obj.end() ? nullptr : &it->second;
    }

    // ---- serialize ----
    std::string dump() const {
        std::string out;
        write(out);
        return out;
    }
    void write(std::string& out) const {
        switch (type) {
            case Null: out += "null"; break;
            case Bool: out += b ? "true" : "false"; break;
            case Num: {
                char buf[32];
                if (num == (long long)num)
                    snprintf(buf, sizeof buf, "%lld", (long long)num);
                else
                    snprintf(buf, sizeof buf, "%.6g", num);
                out += buf;
                break;
            }
            case Str:
                out += '"';
                for (char c : s) {
                    switch (c) {
                        case '"': out += "\\\""; break;
                        case '\\': out += "\\\\"; break;
                        case '\n': out += "\\n"; break;
                        case '\t': out += "\\t"; break;
                        case '\r': out += "\\r"; break;
                        default:
                            if ((unsigned char)c < 0x20) {
                                char h[8];
                                snprintf(h, sizeof h, "\\u%04x", (unsigned char)c);
                                out += h;
                            } else {
                                out += c;
                            }
                    }
                }
                out += '"';
                break;
            case Arr: {
                out += '[';
                bool first = true;
                for (const auto& e : arr) { if (!first) out += ','; first = false; e.write(out); }
                out += ']';
                break;
            }
            case Obj: {
                out += '{';
                bool first = true;
                for (const auto& kv : obj) {
                    if (!first) out += ',';
                    first = false;
                    Json::makeStr(kv.first).write(out);
                    out += ':';
                    kv.second.write(out);
                }
                out += '}';
                break;
            }
        }
    }

    // ---- parse ----
    static bool parse(const std::string& text, Json& out, std::string& err) {
        size_t i = 0;
        if (!parseValue(text, i, out, err)) return false;
        skipWs(text, i);
        return i >= text.size();
    }
    static void skipWs(const std::string& s, size_t& i) {
        while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) ++i;
    }
    static bool parseValue(const std::string& s, size_t& i, Json& out, std::string& err) {
        skipWs(s, i);
        if (i >= s.size()) { err = "unexpected end"; return false; }
        char c = s[i];
        if (c == '{') return parseObj(s, i, out, err);
        if (c == '[') return parseArr(s, i, out, err);
        if (c == '"') { out = makeStr(parseStr(s, i, err)); return !err.empty() == false; }
        if (c == 't') { if (consume(s, i, "true")) { out = makeBool(true); return true; } err = "true"; return false; }
        if (c == 'f') { if (consume(s, i, "false")) { out = makeBool(false); return true; } err = "false"; return false; }
        if (c == 'n') { if (consume(s, i, "null")) return true; err = "null"; return false; }
        if (c == '-' || (c >= '0' && c <= '9')) { out = makeNum(parseNum(s, i, err)); return err.empty(); }
        err = "unexpected char";
        return false;
    }
    static bool consume(const std::string& s, size_t& i, const char* lit) {
        size_t n = 0;
        while (lit[n] && i + n < s.size() && s[i + n] == lit[n]) ++n;
        if (lit[n] == '\0') { i += n; return true; }
        return false;
    }
    static bool parseObj(const std::string& s, size_t& i, Json& out, std::string& err) {
        out = makeObj();
        ++i; // {
        skipWs(s, i);
        if (i < s.size() && s[i] == '}') { ++i; return true; }
        while (true) {
            skipWs(s, i);
            std::string key = parseStr(s, i, err);
            if (!err.empty()) return false;
            skipWs(s, i);
            if (i >= s.size() || s[i] != ':') { err = "expected :"; return false; }
            ++i;
            Json val;
            if (!parseValue(s, i, val, err)) return false;
            out.obj[key] = val;
            skipWs(s, i);
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == '}') { ++i; return true; }
            err = "expected , or }"; return false;
        }
    }
    static bool parseArr(const std::string& s, size_t& i, Json& out, std::string& err) {
        out = makeArr();
        ++i; // [
        skipWs(s, i);
        if (i < s.size() && s[i] == ']') { ++i; return true; }
        while (true) {
            Json val;
            if (!parseValue(s, i, val, err)) return false;
            out.arr.push_back(val);
            skipWs(s, i);
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == ']') { ++i; return true; }
            err = "expected , or ]"; return false;
        }
    }
    static std::string parseStr(const std::string& s, size_t& i, std::string& err) {
        err.clear();
        if (i >= s.size() || s[i] != '"') { err = "expected string"; return ""; }
        ++i;
        std::string out;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') return out;
            if (c == '\\' && i < s.size()) {
                char e = s[i++];
                switch (e) {
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'n': out += '\n'; break;
                    case 'r': out += '\r'; break;
                    case 't': out += '\t'; break;
                    default: out += e;
                }
            } else {
                out += c;
            }
        }
        err = "unterminated string";
        return "";
    }
    static double parseNum(const std::string& s, size_t& i, std::string& err) {
        size_t start = i;
        if (i < s.size() && (s[i] == '-' || s[i] == '+')) ++i;
        while (i < s.size() && (s[i] == '.' || (s[i] >= '0' && s[i] <= '9') || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) ++i;
        return std::stod(s.substr(start, i - start));
    }
};

} // namespace recon

#endif