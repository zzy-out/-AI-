package com.legacyrecon.enrich;

/**
 * 03.5 LLM 调用治理配置（开放 openai 与类 openai 兼容接口）。经 GET/PUT /api/v1/config 读写。
 */
public class LlmConfig {
    public String baseUrl = "";
    public String model = "";
    public String apiKey = "";
    public boolean enabled = false;
    public long dailyTokenBudget = 0;      // 0 = 不限
    public int concurrencyLimit = 4;
    public int retryLimit = 3;
    /** 失败降级开关（全局/按项目/按 kind 可配，MVP 提供全局开关） */
    public boolean degradeOnLLMError = true;

    public LlmConfig() {
    }
}