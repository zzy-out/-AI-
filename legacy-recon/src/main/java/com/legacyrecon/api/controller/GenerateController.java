package com.legacyrecon.api.controller;

import com.legacyrecon.generate.DocType;
import com.legacyrecon.generate.GeneratorService;
import com.legacyrecon.generate.GeneratorService.ExportArtifact;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 04.3 生成端点：生成文档、下载导出产物、类图/调用图 DSL。
 */
@RestController
@RequestMapping("/api/v1/projects/{id}")
public class GenerateController {

    private final GeneratorService generator;

    public GenerateController(GeneratorService generator) {
        this.generator = generator;
    }

    public static class GenerateRequest {
        public DocType docType = DocType.architecture;
        public String format = "md";
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@PathVariable("id") String projectId,
                                        @RequestBody(required = false) GenerateRequest req) {
        GenerateRequest r = req == null ? new GenerateRequest() : req;
        ExportArtifact art = generator.generate(projectId, "snapshot", r.docType, r.format);
        return Map.of(
                "exportId", art.exportId,
                "docType", art.docType.name(),
                "format", art.format,
                "title", art.title,
                "markdown", art.markdown,
                "validationFailures", art.validationFailures);
    }

    @GetMapping(value = "/exports/{exportId}", produces = MediaType.TEXT_MARKDOWN_VALUE + ";charset=UTF-8")
    public String export(@PathVariable("id") String projectId, @PathVariable String exportId) {
        byte[] bytes = generator.getExportBytes(exportId);
        if (bytes == null) {
            throw new IllegalArgumentException("导出产物不存在：" + exportId);
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 类图 / 调用图 DSL（Mermaid），供图谱浏览器。 */
    @GetMapping(value = "/diagram", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String diagram(@PathVariable("id") String projectId,
                          @RequestParam(defaultValue = "class") String type) {
        return generator.diagram(projectId, type);
    }
}