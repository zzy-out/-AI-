package com.legacyrecon.parser.cpp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.legacyrecon.parser.api.*;
import com.legacyrecon.ucm.model.*;
import com.legacyrecon.util.Json;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 02.3 C/C++ 解析器（Clang）子进程客户端（ADR-002）。
 * 通过 stdin/stdout JSON 协议与独立子进程交换 UCM 片段；崩溃/内存问题不影响主进程。
 * 退出码：0=全部成功；1=部分成功（响应带 issues）；2=协议/环境错误。
 */
@Component
public class ClangSubprocessClient implements LanguageParser {

    /** 可经配置覆盖的二进制路径（如环境变量 / 配置 legacy-recon.cpp.binary） */
    private volatile String binaryPath =
            System.getenv("RECON_CPP_BINARY") != null
                    ? System.getenv("RECON_CPP_BINARY")
                    : null; // null = 默认候选路径自动探测（见 resolveBinary）
    private final long perBatchTimeoutMs;

    public ClangSubprocessClient() {
        this(sysprop("legacy-recon.cpp.timeout-ms", 120000));
    }

    public ClangSubprocessClient(long perBatchTimeoutMs) {
        this.perBatchTimeoutMs = perBatchTimeoutMs;
    }

    static long sysprop(String key, long def) {
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    public void setBinaryPath(String p) {
        this.binaryPath = p;
    }

    @Override
    public String language() {
        return "cpp";
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        ParseResult out = new ParseResult("C++");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("schemaVersion", "1.0");
        List<Map<String, Object>> filesJson = new ArrayList<>();
        for (SourceFile sf : request.files) {
            if (sf.content == null) {
                continue; // 幂等
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", sf.path);
            f.put("absolutePath", request.workingDir != null ? request.workingDir.resolve(sf.path).toString() : "");
            filesJson.add(f);
        }
        req.put("files", filesJson);
        req.put("compileCommands", loadCompileCommands(request, filesJson, out));
        req.put("mode", "cpp");
        if (request.workingDir != null) {
            req.put("rootDir", request.workingDir.toAbsolutePath().normalize().toString());
        }
        CppConfig cpp = request.config != null && request.config.cpp != null
                ? request.config.cpp : new CppConfig();
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("recordMacros", cpp.recordMacros);
        opts.put("templatePolicy", cpp.templatePolicy == null ? "declarations" : cpp.templatePolicy);
        opts.put("fallbackIncludeDirs", cpp.fallbackIncludeDirs);
        opts.put("fallbackDefines", cpp.fallbackDefines);
        opts.put("standard", cpp.standard == null ? "c++17" : cpp.standard);
        opts.put("templateWhitelist", cpp.templateWhitelist);
        req.put("options", opts);

        try {
            Process proc = new ProcessBuilder(resolveBinary())
                    .redirectErrorStream(false)
                    .start();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8))) {
                w.write(Json.toJson(req));
            }
            String stdout;
            StringBuilder errBuf = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader er = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder sb = new StringBuilder();
                while ((line = r.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                stdout = sb.toString();
                while ((line = er.readLine()) != null) {
                    errBuf.append(line).append("\n");
                }
            }
            boolean done = proc.waitFor(perBatchTimeoutMs, TimeUnit.MILLISECONDS);
            int exit = done ? proc.exitValue() : -1;
            if (!done) {
                proc.destroyForcibly();
                out.issues.add(ParseIssue.error("CPP.TIMEOUT", "子进程超时（" + perBatchTimeoutMs + "ms）", null));
            } else if (exit == 2) {
                out.issues.add(ParseIssue.error("CPP.PROTOCOL_ERROR",
                        "协议/环境错误：" + errBuf + " " + stdout.trim(), null));
            } else {
                mergeResponse(out, stdout, exit);
            }
        } catch (IOException e) {
            out.issues.add(ParseIssue.error("CPP.CLIENT_IO",
                    "子进程启动/IO 失败（binary=" + resolveBinary() + "）：" + e.getMessage(), null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.issues.add(ParseIssue.error("CPP.INTERRUPTED", "解析被中断", null));
        }
        out.addStats("fileCount", filesJson.size());
        out.addStats("entityCount", out.entities.size());
        out.addStats("relationCount", out.relations.size());
        out.addStats("issueCount", out.issues.size());
        return out;
    }

    /** 显式配置 > 环境变量 > 默认候选路径（build-cpp/、build/）。 */
    private String resolveBinary() {
        if (binaryPath != null) {
            return binaryPath;
        }
        for (String candidate : new String[]{"build-cpp/recon_clang_parser", "build/recon_clang_parser"}) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return "build/recon_clang_parser";
    }

    /**
     * 读取 compile_commands.json（02.3 编译数据库），仅保留匹配本批文件的条目。
     * 文件缺失/解析失败 → 空列表 + CPP.COMPILE_DB_MISSING WARNING（子进程走 fallback 模式）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCompileCommands(ParseRequest request,
                                                          List<Map<String, Object>> filesJson,
                                                          ParseResult out) {
        CppConfig cpp = request.config != null && request.config.cpp != null ? request.config.cpp : null;
        if (cpp == null || cpp.compileCommandsPath == null || cpp.compileCommandsPath.isBlank()) {
            return Collections.emptyList();
        }
        Path dbPath = Path.of(cpp.compileCommandsPath);
        if (!Files.isRegularFile(dbPath)) {
            out.issues.add(ParseIssue.warning("CPP.COMPILE_DB_MISSING",
                    "compile_commands.json 不存在：" + dbPath + "，使用 fallback 编译参数", null));
            return Collections.emptyList();
        }
        try {
            String content = Files.readString(dbPath, StandardCharsets.UTF_8);
            List<Map<String, Object>> entries =
                    Json.fromJson(content, new TypeReference<List<Map<String, Object>>>() {});
            Set<String> absPaths = new HashSet<>();
            for (Map<String, Object> f : filesJson) {
                absPaths.add(String.valueOf(f.get("absolutePath")));
            }
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> e : entries) {
                String file = String.valueOf(e.get("file"));
                for (String abs : absPaths) {
                    if (abs.equals(file) || abs.endsWith("/" + file) || file.endsWith(abs)) {
                        matched.add(e);
                        break;
                    }
                }
            }
            return matched;
        } catch (Exception e) {
            out.issues.add(ParseIssue.warning("CPP.COMPILE_DB_MISSING",
                    "compile_commands.json 解析失败：" + e.getMessage() + "，使用 fallback 编译参数", null));
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeResponse(ParseResult out, String stdout, int exit) {
        if (stdout == null || stdout.trim().isEmpty()) {
            return;
        }
        Map<String, Object> resp = Json.fromJson(stdout, LinkedHashMap.class);
        Object filesObj = resp.get("files");
        if (!(filesObj instanceof List)) {
            return;
        }
        int skipped = 0;
        Map<String, Object> stats = (Map<String, Object>) resp.getOrDefault("stats", Map.of());
        Object skipObj = stats.get("skippedTemplateInstantiations");
        if (skipObj instanceof Number n) {
            skipped = n.intValue();
        }
        // 跨文件去重：C++ 头文件声明与实现文件定义会产出同 ID 实体（ODR），保留首个（头文件声明）
        Set<String> seenEntityIds = new HashSet<>();
        Set<String> seenRelationIds = new HashSet<>();
        for (Object fObj : (List<?>) filesObj) {
            Map<String, Object> f = (Map<String, Object>) fObj;
            String path = String.valueOf(f.get("path"));
            List<String> idx = out.fileIndex.computeIfAbsent(path, k -> new ArrayList<>());
            List<Map<String, Object>> ents = (List<Map<String, Object>>) f.getOrDefault("entities", List.of());
            for (Map<String, Object> em : ents) {
                Entity e = Json.MAPPER.convertValue(em, Entity.class);
                if (e.id == null || !seenEntityIds.add(e.id)) {
                    continue;
                }
                out.entities.add(e);
                idx.add(e.id);
                if (e.type == EntityType.File) {
                    out.symbolTable.putIfAbsent(e.qualifiedName, e.id);
                }
            }
            List<Map<String, Object>> rels = (List<Map<String, Object>>) f.getOrDefault("relations", List.of());
            for (Map<String, Object> rm : rels) {
                Relation r = Json.MAPPER.convertValue(rm, Relation.class);
                if (r.id != null && seenRelationIds.add(r.id)) {
                    out.relations.add(r);
                }
            }
            List<Map<String, Object>> iss = (List<Map<String, Object>>) f.getOrDefault("issues", List.of());
            for (Map<String, Object> im : iss) {
                out.issues.add(ParseIssue.valueOf(String.valueOf(im.get("severity")),
                        String.valueOf(im.get("code")), String.valueOf(im.get("message")), null));
            }
            if (exit == 1) {
                out.issues.add(ParseIssue.info("CPP.PARTIAL",
                        "batch 部分成功解析", new SourceLocation(path, 1, 1, 1, 1)));
            }
        }
        out.addStats("skippedTemplateInstantiations", skipped);
    }
}