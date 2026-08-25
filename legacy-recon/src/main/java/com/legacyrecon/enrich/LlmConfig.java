package com.legacyrecon.enrich;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 03.5 LLM 调用治理配置（OpenAI 与类 OpenAI 兼容接口，生产增强版）。
 * 经 GET/PUT /api/v1/config 读写。
 */
public class LlmConfig {
    public String baseUrl = "";
    public String model = "";
    public String apiKey = "";
    /** 可选：OpenAI 组织 ID / 多租户标识 */
    public String organizationId = "";
    /** 可选：自定义请求头（如供应商所需鉴权、灰度标签等） */
    public Map<String, String> headers = new LinkedHashMap<>();
    public boolean enabled = false;

    // ---- 用量与并发 ----
    public long dailyTokenBudget = 0;       // 0 = 不限
    public int concurrencyLimit = 4;
    public int retryLimit = 3;
    /** 退避指数起始 ms（生产默认 300ms，退避序列 300/600/1200/2400…） */
    public long backoffBaseMs = 300L;
    /** 总调用超时（含重试，ms） */
    public long overallTimeoutMs = 60_000L;
    /** 单请求 connect timeout */
    public int connectTimeoutMs = 5_000;
    /** 单请求 read timeout */
    public int readTimeoutMs = 45_000;

    // ---- 生成参数 ----
    public double temperature = 0.2;
    public double topP = 0.95;
    /** 输出最大 token；0 表示使用模型默认 */
    public int maxTokens = 1024;
    /** 输出格式："json_object" | "text"。结构化洞察强制 json_object */
    public String responseFormat = "json_object";
    /** 系统级 system prompt 注入（可用于品牌/合规指令） */
    public String globalSystemPrompt = "";

    // ---- Prompt 版本 ----
    /** 概要洞察 prompt 版本号（用于缓存 key 与治理统计） */
    public String promptVersionSummary = "summary-v2-prod";
    /** 架构角色洞察 prompt 版本 */
    public String promptVersionArchRole = "arch-role-v1-prod";
    /** 技术债洞察 prompt 版本 */
    public String promptVersionTechDebt = "tech-debt-v1-prod";
    /** 业务规则洞察 prompt 版本 */
    public String promptVersionBusinessRule = "business-rule-v1-prod";

    // ---- 治理 ----
    public boolean degradeOnLLMError = true;
    /** LLM 调用失败连续次数（连续多次失败自动关闭 LLM 直至人工复位） */
    public int circuitBreakerFailures = 10;
    /** 可重试 HTTP 状态码集合 */
    public List<Integer> retryStatusCodes = new ArrayList<>(List.of(408, 425, 429, 500, 502, 503, 504));

    public LlmConfig() {
    }
}
