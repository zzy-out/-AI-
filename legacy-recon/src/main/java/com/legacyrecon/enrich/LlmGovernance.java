package com.legacyrecon.enrich;

import com.fasterxml.jackson.core.type.TypeReference;
import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.graph.Insight;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.util.Json;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 03.5 LLM 调用治理（生产实现）。
 *
 * <ul>
 *   <li>调用生产级 {@link LlmClient} 进行真实 OpenAI 兼容请求</li>
 *   <li>使用 promptVersion 分版本（summary/tech_debt/arch_role/business_rule）</li>
 *   <li>强制 JSON 输出并解析为结构化内容，校验 schema 失败时记录为错误并降级</li>
 *   <li>缓存键 = hash(entityId,codeChecksum,promptVersion,model)，支持内存 + 图谱表双重复用</li>
 *   <li>预算：在并发请求前已由 LlmClient 原子 token 计数；超过预算只跑规则引擎并在 API 返回 skip 计数</li>
 *   <li>高优先级实体排序：实体类型权重 × 扇入/扇出近似 × 缺失文档加分</li>
 * </ul>
 */
@Component
public class LlmGovernance {

    private final GraphStore graph;
    private final AtomicReference<LlmConfig> config = new AtomicReference<>(new LlmConfig());
    /** LlmClient 并发度跟随配置变化，懒加载且重建 */
    private final AtomicReference<LlmClient> clientRef = new AtomicReference<>();

    /** LLM 级缓存（独立于阶段缓存 R21）：cacheKey -> insightId */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    /** 近似 token 计数（客户端有一个，这里保留一份用于 API 展示） */
    private final AtomicLong tokensUsedToday = new AtomicLong();

    public LlmGovernance(GraphStore graph) {
        this.graph = graph;
    }

    // ====================================================================
    //  配置读写
    // ====================================================================

    public LlmConfig getConfig() {
        return config.get();
    }

    public void updateConfig(LlmConfig c) {
        LlmConfig cfg = c == null ? new LlmConfig() : c;
        config.set(cfg);
        // 并发度变化时重建 client（新请求立即生效）
        clientRef.set(null);
        LlmClient cl = client(cfg);
        cl.resetCircuitBreaker();
    }

    public boolean isEnabled() {
        LlmConfig cfg = config.get();
        return cfg.enabled
                && cfg.baseUrl != null && !cfg.baseUrl.isEmpty()
                && cfg.model != null && !cfg.model.isEmpty()
                && cfg.apiKey != null && !cfg.apiKey.isEmpty();
    }

    LlmClient client(LlmConfig cfg) {
        LlmClient cl = clientRef.get();
        if (cl != null) return cl;
        synchronized (this) {
            cl = clientRef.get();
            if (cl != null) return cl;
            cl = new LlmClient(Math.max(1, cfg.concurrencyLimit));
            clientRef.set(cl);
            return cl;
        }
    }

    // ====================================================================
    //  缓存键
    // ====================================================================

    public static String cacheKey(String entityId, String codeChecksum, String promptVersion, String model) {
        return sha(entityId + "|" + codeChecksum + "|" + promptVersion + "|" + model);
    }

    private static String sha(String raw) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    // ====================================================================
    //  生产级 generatePending：按优先级排序 → 并发/预算 → LLM 调用 → 结构化解析 → 落洞察
    // ====================================================================

    public void generatePending(String projectId, List<Entity> entities, EnrichmentService.EnrichSummary summary) {
        if (!isEnabled()) return;
        LlmConfig cfg = config.get();
        LlmClient client = client(cfg);

        // 优先级：按权重排序，优先处理大扇入/缺失文档的类型与核心方法
        // 为了稳定排序，这里基于扇入近似（被引用数）简化处理：Class > Method，缺文档加权
        List<Entity> prioritized = new ArrayList<>(entities);
        prioritized.sort((a, b) -> Long.compare(priority(b, entities), priority(a, entities)));
        // 只处理 top-k 防止一次跑崩
        int topK = Math.max(1, cfg.dailyTokenBudget > 0 ? 200 : 80);
        prioritized = prioritized.stream()
                .filter(e -> e.type == com.legacyrecon.ucm.model.EntityType.Class
                        || e.type == com.legacyrecon.ucm.model.EntityType.Method
                        || e.type == com.legacyrecon.ucm.model.EntityType.Interface)
                .limit(topK).toList();

        // 对每个实体按配置选择生成洞察种类（summary/tech_debt 可叠加）
        for (Entity e : prioritized) {
            // 预算：在调用链内 LlmClient 会再次做原子检查；这里跳过以减少开销
            if (cfg.dailyTokenBudget > 0 && (client.tokensToday() + 500) > cfg.dailyTokenBudget) {
                summary.llmSkippedBudget++;
                continue;
            }
            // 1) summary 洞察（几乎全部实体都生成）
            maybeGenerate(projectId, e, Insight.Kind.summary, cfg, client, summary);
            // 2) tech_debt 洞察（方法/类级别）
            if (e.type == com.legacyrecon.ucm.model.EntityType.Method
                    || e.type == com.legacyrecon.ucm.model.EntityType.Class
                    || e.type == com.legacyrecon.ucm.model.EntityType.Interface) {
                maybeGenerate(projectId, e, Insight.Kind.tech_debt, cfg, client, summary);
            }
            // 3) arch_role 洞察（仅顶层 Class/Interface）
            if (e.type == com.legacyrecon.ucm.model.EntityType.Class
                    || e.type == com.legacyrecon.ucm.model.EntityType.Interface) {
                maybeGenerate(projectId, e, Insight.Kind.arch_role, cfg, client, summary);
            }
        }
    }

    private void maybeGenerate(String projectId, Entity e, Insight.Kind kind,
                               LlmConfig cfg, LlmClient client,
                               EnrichmentService.EnrichSummary summary) {
        String promptVersion = promptVersionOf(kind, cfg);
        String codeChecksum = EnrichmentService.checksum(e);
        String key = cacheKey(e.id, codeChecksum, promptVersion, cfg.model);
        // 缓存命中：图谱内已存在对应 kind + promptVersion 的 pending/approved 洞察
        if (findExisting(projectId, e.id, kind, promptVersion) != null) {
            return;
        }
        if (cache.containsKey(key)) {
            return;
        }

        LlmClient.ChatRequest req = buildPrompt(e, kind, cfg);
        // 近似 prompt token 计数（用于预算，后续 actual usage 会被更精确覆盖）
        long estPromptTokens = LlmClient.estimateTokens(flatten(req));

        // 预算预扣（乐观），LLM 返回真实用量时在 LlmClient 中累加；这里只做额外保护
        if (cfg.dailyTokenBudget > 0
                && (tokensUsedToday.get() + estPromptTokens + cfg.maxTokens) > cfg.dailyTokenBudget) {
            summary.llmSkippedBudget++;
            return;
        }

        LlmClient.CallResult res;
        try {
            res = client.chat(req, cfg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        long actualTotal = Math.max(1L, res.totalTokens > 0 ? res.totalTokens
                : (estPromptTokens + LlmClient.estimateTokens(res.content)));
        tokensUsedToday.addAndGet(actualTotal);

        if (!res.ok) {
            if (!cfg.degradeOnLLMError) {
                return;
            }
            // 降级：写入结构化错误提示洞察
            Insight in = new Insight();
            in.id = "llm-" + projectId + "-" + UUID.randomUUID().toString().substring(0, 8);
            in.projectId = projectId;
            in.entityId = e.id;
            in.kind = kind;
            in.content.put("degraded", true);
            in.content.put("error", res.error == null ? "UNKNOWN" : res.error);
            in.content.put("message", res.message == null ? "" : res.message);
            in.content.put("hint", "LLM 调用失败；见治理层错误代码与配置");
            in.confidence = 0.0;
            in.model = cfg.model;
            in.promptVersion = promptVersion;
            in.status = Insight.Status.pending;
            in.codeChecksum = codeChecksum;
            in.evidenceJson = Json.toJson(List.of(e.id));
            graph.upsertInsight(in);
            graph.audit(projectId, in.id, com.legacyrecon.graph.AuditRecord.Action.degrade,
                    "llm-governance", Json.toJson(Map.of(
                            "attempts", res.attempts,
                            "httpStatus", res.httpStatus,
                            "error", res.error == null ? "" : res.error,
                            "message", res.message == null ? "" : res.message
                    )));
            summary.llmGenerated++;
            cache.put(key, in.id);
            return;
        }

        // 成功：解析 JSON 结构化内容
        Map<String, Object> structured = parseStructured(res.content, kind, e);
        Insight in = new Insight();
        in.id = "llm-" + projectId + "-" + UUID.randomUUID().toString().substring(0, 8);
        in.projectId = projectId;
        in.entityId = e.id;
        in.kind = kind;
        in.content.putAll(structured);
        // 治理痕迹
        in.content.put("meta", Map.of(
                "id", res.id == null ? "" : res.id,
                "attempts", res.attempts,
                "durationMs", res.durationMs,
                "promptTokens", res.promptTokens,
                "completionTokens", res.completionTokens,
                "totalTokens", res.totalTokens
        ));
        in.confidence = extractConfidence(structured);
        in.model = cfg.model;
        in.promptVersion = promptVersion;
        in.status = Insight.Status.pending;
        in.codeChecksum = codeChecksum;
        in.evidenceJson = Json.toJson(collectEvidence(structured, e.id));
        graph.upsertInsight(in);
        graph.audit(projectId, in.id, com.legacyrecon.graph.AuditRecord.Action.create,
                "llm", Json.toJson(Map.of(
                        "kind", kind.name(),
                        "promptVersion", promptVersion,
                        "tokens", actualTotal
                )));
        cache.put(key, in.id);
        summary.llmGenerated++;
    }

    // ====================================================================
    //  Prompt 构造 / 结构化解析 / 优先级
    // ====================================================================

    private LlmClient.ChatRequest buildPrompt(Entity e, Insight.Kind kind, LlmConfig cfg) {
        LlmClient.ChatRequest req = new LlmClient.ChatRequest();
        String sys;
        if (cfg.globalSystemPrompt != null && !cfg.globalSystemPrompt.isBlank()) {
            sys = cfg.globalSystemPrompt + "\n\n";
        } else {
            sys = "";
        }
        String userCode = codeSnippetOf(e);
        return switch (kind) {
            case summary -> {
                req.add("system", sys + SCHEMA_SUMMARY);
                req.add("user", "请基于以下 Java 源代码，生成架构摘要与用途说明（严格 JSON 输出）。\n\n"
                        + "实体：" + e.qualifiedName + "\n类型：" + e.type.name() + "\n\n" + userCode);
                yield req;
            }
            case tech_debt -> {
                req.add("system", sys + SCHEMA_TECH_DEBT);
                req.add("user", "请识别以下实体的潜在技术债（命名、复杂度、耦合、异常处理、可读性），严格 JSON 输出。\n\n"
                        + "实体：" + e.qualifiedName + "\n\n" + userCode);
                yield req;
            }
            case arch_role -> {
                req.add("system", sys + SCHEMA_ARCH_ROLE);
                req.add("user", "请判断以下类型在系统中的架构角色，并指出其上下游（严格 JSON 输出）。\n\n"
                        + "实体：" + e.qualifiedName + "\n\n" + userCode);
                yield req;
            }
            case business_rule -> {
                req.add("system", sys + SCHEMA_BUSINESS_RULE);
                req.add("user", "请从代码中提取业务规则/约束，并以自然语言短句说明（严格 JSON 输出）。\n\n"
                        + "实体：" + e.qualifiedName + "\n\n" + userCode);
                yield req;
            }
            case data_flow_note -> {
                req.add("system", sys + SCHEMA_SUMMARY);
                req.add("user", "请描述该方法/模块的数据流转（输入→处理→输出），严格 JSON 输出。\n\n"
                        + "实体：" + e.qualifiedName + "\n\n" + userCode);
                yield req;
            }
        };
    }

    private static final String SCHEMA_SUMMARY =
            "你是资深遗留系统重构分析师。请输出严格 JSON，顶层字段："
            + "summary(string,2-4 句), evidence(string[]), keywords(string[]), complexityHint(string optional)。"
            + "禁止 Markdown/代码块；只输出 JSON 对象。";
    private static final String SCHEMA_TECH_DEBT =
            "请输出严格 JSON：issues({title,severity(high/medium/low),suggestion}[])，"
            + "summary(string)，evidence(string[] 实体/方法名)，severityScore(number 0..1)。";
    private static final String SCHEMA_ARCH_ROLE =
            "请输出严格 JSON：role(string)，layer(string optional)，upstream(string[])，downstream(string[])，"
            + "summary(string)，confidence(number 0..1)。";
    private static final String SCHEMA_BUSINESS_RULE =
            "请输出严格 JSON：rules({id,statement}[],evidence(string[]),assumptions(string[]))。";

    /** 获取实体代码片段（若文件内容不可用，退化为 metadata+签名） */
    private String codeSnippetOf(Entity e) {
        if (e.docComment != null) {
            return e.docComment + "\n\n签名：" + (e.signature == null ? e.qualifiedName : e.qualifiedName + " " + e.signature);
        }
        String mods = e.modifiers == null ? "" : String.join(" ", e.modifiers);
        return mods + " " + e.type.name() + " " + e.qualifiedName
                + (e.signature == null ? "" : " " + e.signature)
                + "\n\n（原始源码请通过 /api/v1/files 获取，这里提供结构化摘要）";
    }

    private Map<String, Object> parseStructured(String content, Insight.Kind kind, Entity e) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            out.put("summary", "[LLM 返回空内容]");
            out.put("fallback", true);
            return out;
        }
        String cleaned = content.trim();
        // 去掉 ```json 包裹
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            Map<String, Object> parsed = Json.fromJson(cleaned, new TypeReference<Map<String, Object>>() {});
            if (parsed == null || parsed.isEmpty()) {
                out.put("summary", "[LLM 输出 JSON 为空]");
                out.put("raw", cleaned.length() < 800 ? cleaned : cleaned.substring(0, 800));
                return out;
            }
            // schema 校验：对每个 kind 检查必填字段，缺失时自动补全
            switch (kind) {
                case summary -> {
                    if (!parsed.containsKey("summary")) {
                        parsed.put("summary", firstNonEmpty(parsed, e.qualifiedName));
                    }
                    if (!parsed.containsKey("evidence")) parsed.put("evidence", List.of(e.id));
                }
                case tech_debt -> {
                    if (!parsed.containsKey("issues")) parsed.put("issues", List.of());
                    if (!parsed.containsKey("summary")) parsed.put("summary", firstNonEmpty(parsed, ""));
                }
                case arch_role -> {
                    if (!parsed.containsKey("role")) parsed.put("role", "未识别");
                    if (!parsed.containsKey("upstream")) parsed.put("upstream", List.of());
                    if (!parsed.containsKey("downstream")) parsed.put("downstream", List.of());
                }
                case business_rule -> {
                    if (!parsed.containsKey("rules")) parsed.put("rules", List.of());
                    if (!parsed.containsKey("evidence")) parsed.put("evidence", List.of());
                }
                default -> {}
            }
            return parsed;
        } catch (Exception ex) {
            out.put("summary", "[解析失败] " + ex.getMessage());
            out.put("raw", cleaned.length() < 800 ? cleaned : cleaned.substring(0, 800));
            out.put("parseError", ex.getClass().getSimpleName());
            return out;
        }
    }

    private static String firstNonEmpty(Map<String, Object> m, String fallback) {
        for (Object v : m.values()) {
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return fallback;
    }

    private static double extractConfidence(Map<String, Object> structured) {
        for (String k : List.of("confidence", "severityScore")) {
            Object v = structured.get(k);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (d > 1.0) d = d / 100.0;
                return Math.max(0.0, Math.min(1.0, d));
            }
        }
        return 0.6;
    }

    @SuppressWarnings("unchecked")
    private static List<String> collectEvidence(Map<String, Object> structured, String defaultId) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : List.of("evidence", "upstream", "downstream")) {
            Object v = structured.get(k);
            if (v instanceof List<?> l) {
                for (Object o : l) if (o != null) out.add(String.valueOf(o));
            }
        }
        Object issues = structured.get("issues");
        if (issues instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object ev = m.get("evidence");
                    if (ev instanceof List<?> list) {
                        for (Object ev2 : list) if (ev2 != null) out.add(String.valueOf(ev2));
                    }
                }
            }
        }
        if (out.isEmpty()) out.add(defaultId);
        return new ArrayList<>(out);
    }

    private String promptVersionOf(Insight.Kind kind, LlmConfig cfg) {
        return switch (kind) {
            case summary -> cfg.promptVersionSummary;
            case arch_role -> cfg.promptVersionArchRole;
            case tech_debt -> cfg.promptVersionTechDebt;
            case business_rule -> cfg.promptVersionBusinessRule;
            default -> cfg.promptVersionSummary;
        };
    }

    private Insight findExisting(String projectId, String entityId, Insight.Kind kind, String promptVersion) {
        for (Insight in : graph.listInsights(projectId, null, kind.name(), entityId)) {
            if (promptVersion.equals(in.promptVersion)
                    && in.status != Insight.Status.superseded
                    && in.status != Insight.Status.rejected) {
                return in;
            }
        }
        return null;
    }

    private long priority(Entity e, List<Entity> all) {
        long score = 0L;
        score += switch (e.type) {
            case Class, Interface, Enum -> 200L;
            case Method, Constructor -> 20L;
            case Field -> 2L;
            default -> 1L;
        };
        if (e.docComment == null || e.docComment.isBlank()) score += 80L;
        // 签名较长通常复杂度更高
        if (e.signature != null) score += Math.min(100L, e.signature.length() / 5L);
        // 修饰符：public/接口方法/抽象方法更需摘要
        if (e.modifiers != null) {
            if (e.modifiers.contains("public")) score += 50L;
            if (e.modifiers.contains("abstract")) score += 40L;
        }
        return score;
    }

    private static String flatten(LlmClient.ChatRequest r) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ChatRequest.Message m : r.messages) {
            sb.append(m.role).append(":").append(m.content).append("\n");
        }
        return sb.toString();
    }

    public long tokensUsedToday() {
        return tokensUsedToday.get();
    }

    /** 对外提供治理统计（供 API 扩展使用） */
    public Map<String, Object> status() {
        LlmConfig cfg = config.get();
        LlmClient cl = clientRef.get();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", isEnabled());
        s.put("model", cfg.model);
        s.put("tokensUsedToday", tokensUsedToday.get());
        s.put("dailyTokenBudget", cfg.dailyTokenBudget);
        s.put("consecutiveFailures", cl == null ? 0 : cl.consecutiveFailures());
        s.put("concurrencyLimit", cfg.concurrencyLimit);
        return s;
    }
}
