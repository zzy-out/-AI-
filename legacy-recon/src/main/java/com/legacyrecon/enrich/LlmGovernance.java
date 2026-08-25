package com.legacyrecon.enrich;

import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.graph.Insight;
import com.legacyrecon.ucm.model.Entity;
import com.legacyrecon.util.Json;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 03.5 LLM 调用治理：配置、缓存、成本预算、并发/退避（脚手架提供缓存 + 预算 + 优先级降级适配）。
 * 缓存键 = hash(entityId, codeChecksum, promptVersion, model)；命中直接复用。
 */
@Component
public class LlmGovernance {

    // 配置由 GET/PUT /api/v1/config 读写；默认关闭 LLM（仅规则引擎）。
    private volatile LlmConfig config = new LlmConfig();
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicLong tokensUsedToday = new AtomicLong();
    private final GraphStore graph;

    public LlmGovernance(GraphStore graph) {
        this.graph = graph;
    }

    public LlmConfig getConfig() {
        return config;
    }

    public void updateConfig(LlmConfig c) {
        this.config = c == null ? new LlmConfig() : c;
    }

    public boolean isEnabled() {
        return config != null && config.enabled && config.baseUrl != null && !config.baseUrl.isEmpty();
    }

    /** 缓存键（R21 之外，LLM 级缓存，见 03.5）。 */
    public static String cacheKey(String entityId, String codeChecksum, String promptVersion, String model) {
        return LlmGovernance.sha(entityId + "|" + codeChecksum + "|" + promptVersion + "|" + model);
    }

    private static String sha(String raw) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 为高优先级实体生成 pending 洞察。遵守预算（超限只运行确定性规则，界面提示）。
     * MVP 默认以模板摘要占位；接入真实 openai 兼容端点时替换请求体即可。
     */
    public void generatePending(String projectId, List<Entity> entities, EnrichmentService.EnrichSummary summary) {
        if (!isEnabled()) {
            return;
        }
        // 优先级：入口类/核心方法粗略按类型权重排序（03.5：实体类型权重 × 扇出度 × 热度）
        List<Entity> prioritized = entities.stream()
                .filter(e -> e.type == com.legacyrecon.ucm.model.EntityType.Class
                        || e.type == com.legacyrecon.ucm.model.EntityType.Method)
                .sorted((a, b) -> Long.compare(priority(b), priority(a)))
                .limit(20)
                .toList();

        String promptVersion = "summary-v1";
        for (Entity e : prioritized) {
            if (config.dailyTokenBudget > 0 && tokensUsedToday.get() >= config.dailyTokenBudget) {
                summary.llmSkippedBudget++;
                continue;
            }
            String codeChecksum = EnrichmentService.checksum(e);
            String key = cacheKey(e.id, codeChecksum, promptVersion, config.model);
            Insight existing = findExisting(projectId, e.id, promptVersion);
            if (existing != null) {
                continue; // 缓存/已存在复用
            }
            if (cache.containsKey(key)) {
                continue;
            }
            Insight in = new Insight();
            in.id = "llm-" + projectId + "-" + UUID.randomUUID().toString().substring(0, 8);
            in.projectId = projectId;
            in.entityId = e.id;
            in.kind = Insight.Kind.summary;
            in.content.put("summary", "【脚手架占位】" + e.qualifiedName + " 的概要；接入 LLM 后可基于证据生成。");
            in.content.put("evidence", List.of(e.qualifiedName));
            in.confidence = 0.5;
            in.model = config.model;
            in.promptVersion = promptVersion;
            in.status = Insight.Status.pending;   // LLM 产出一律 pending（ADR-007）
            in.codeChecksum = codeChecksum;
            in.evidenceJson = Json.toJson(List.of(e.id));
            graph.upsertInsight(in);
            cache.put(key, in.id);
            tokensUsedToday.addAndGet(e.qualifiedName.length());
            summary.llmGenerated++;
        }
    }

    private Insight findExisting(String projectId, String entityId, String promptVersion) {
        for (Insight in : graph.listInsights(projectId, "pending", null, entityId)) {
            if (promptVersion.equals(in.promptVersion)) {
                return in;
            }
        }
        return null;
    }

    private long priority(Entity e) {
        long score = 0;
        if (e.type == com.legacyrecon.ucm.model.EntityType.Class) {
            score += 100;
        } else if (e.type == com.legacyrecon.ucm.model.EntityType.Method) {
            score += 10;
        }
        score += (e.docComment == null ? 50 : 0); // 缺失文档的对象更需洞察
        return score;
    }
}