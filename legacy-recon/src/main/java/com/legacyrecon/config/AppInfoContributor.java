package com.legacyrecon.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生产监控 /actuator/info：应用与构建信息。
 */
@Component
public class AppInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("app", Map.of(
                "name", "legacy-recon",
                "version", "0.1.0",
                "description", "遗留系统 AI 重构与文档生成器"));
    }
}