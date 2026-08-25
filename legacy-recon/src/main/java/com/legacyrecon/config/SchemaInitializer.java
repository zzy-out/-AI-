package com.legacyrecon.config;

import com.legacyrecon.facts.FactsStore;
import com.legacyrecon.graph.GraphStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动初始化：确保数据目录存在，并初始化事实层 + 图谱层 schema（幂等）。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private final FactsStore facts;
    private final GraphStore graph;

    public SchemaInitializer(FactsStore facts, GraphStore graph) {
        this.facts = facts;
        this.graph = graph;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Files.createDirectories(Path.of("data"));
        } catch (IOException e) {
            throw new RuntimeException("创建数据目录失败", e);
        }
        facts.initSchema();
        graph.initSchema();
    }
}