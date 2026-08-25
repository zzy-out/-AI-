package com.legacyrecon;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.graph.GraphStore;
import com.legacyrecon.pipeline.IncrementalAnalyzer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 遗留系统 AI 重构与文档生成器 —— 应用入口。
 * 启动时初始化 SQLite 事实层 + 图谱层（SQLite 图模式，ADR-004）。
 */
@SpringBootApplication
public class LegacyReconApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyReconApplication.class, args);
    }

    @Bean
    public IncrementalAnalyzer incrementalAnalyzer(FactsStore facts) {
        return new IncrementalAnalyzer(facts);
    }
}