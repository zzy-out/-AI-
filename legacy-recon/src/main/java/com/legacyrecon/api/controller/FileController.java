package com.legacyrecon.api.controller;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.facts.Project;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 04.3 文件内容预览端点（支撑 source:// 链接 Web 预览浮层，R20）。
 * fileId 形如 {projectId}:{path}。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FactsStore facts;

    public FileController(FactsStore facts) {
        this.facts = facts;
    }

    @GetMapping("/{fileId}/content")
    public Map<String, Object> content(@PathVariable String fileId,
                                       @RequestParam(required = false) Integer startLine,
                                       @RequestParam(required = false) Integer endLine) {
        int idx = fileId.indexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("fileId 格式非法：" + fileId);
        }
        String projectId = fileId.substring(0, idx);
        String path = fileId.substring(idx + 1);
        Project p = facts.getProject(projectId);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }
        Path file = Path.of(p.rootPath).resolve(path);
        List<String> all;
        try {
            all = Files.readAllLines(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件：" + path);
        }
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int to = endLine == null ? all.size() : Math.min(all.size(), endLine);
        List<String> slice = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            slice.add(all.get(i - 1));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileId", fileId);
        out.put("path", path);
        out.put("startLine", from);
        out.put("endLine", to);
        out.put("content", String.join("\n", slice));
        return out;
    }
}