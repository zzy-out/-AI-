package com.legacyrecon.parser.cpp;

import com.legacyrecon.parser.api.*;
import com.legacyrecon.ucm.model.*;
import com.legacyrecon.util.Json;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
                    : "build/recon_clang_parser";
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
        req.put("compileCommands", Collections.emptyList());
        req.put("mode", "cpp");
        Map<String, Object> opts = new LinkedHashMap<>();
        if (request.config != null && request.config.cpp != null) {
            opts.put("recordMacros", request.config.cpp.recordMacros);
            opts.put("templatePolicy", request.config.cpp.templatePolicy == null ? "declarations" : request.config.cpp.templatePolicy);
        } else {
            opts.put("recordMacros", true);
            opts.put("templatePolicy", "declarations");
        }
        req.put("options", opts);

        try {
            Process proc = new ProcessBuilder(binaryPath)
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
                    "子进程启动/IO 失败（binary=" + binaryPath + "）：" + e.getMessage(), null));
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
        for (Object fObj : (List<?>) filesObj) {
            Map<String, Object> f = (Map<String, Object>) fObj;
            String path = String.valueOf(f.get("path"));
            List<String> idx = out.fileIndex.computeIfAbsent(path, k -> new ArrayList<>());
            List<Map<String, Object>> ents = (List<Map<String, Object>>) f.getOrDefault("entities", List.of());
            for (Map<String, Object> em : ents) {
                Entity e = Json.MAPPER.convertValue(em, Entity.class);
                if (e.id == null) {
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
                if (r.id != null) {
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