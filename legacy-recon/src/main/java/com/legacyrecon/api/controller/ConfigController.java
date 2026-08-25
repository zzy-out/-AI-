package com.legacyrecon.api.controller;

import com.legacyrecon.enrich.LlmConfig;
import com.legacyrecon.enrich.LlmGovernance;
import org.springframework.web.bind.annotation.*;

/**
 * 04.3/3.5 LLM 配置端点：GET/PUT /api/v1/config（openai/类 openai 的 baseUrl、model、key、开关、预算）。
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final LlmGovernance llm;

    public ConfigController(LlmGovernance llm) {
        this.llm = llm;
    }

    @GetMapping
    public LlmConfig get() {
        return llm.getConfig();
    }

    @PutMapping
    public LlmConfig put(@RequestBody LlmConfig config) {
        llm.updateConfig(config);
        return llm.getConfig();
    }
}