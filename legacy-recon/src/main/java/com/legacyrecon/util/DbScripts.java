package com.legacyrecon.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 加载 classpath 上的 SQL 脚本并幂等执行：先剥注释行再按 ';' 切分。 */
public final class DbScripts {

    private DbScripts() {
    }

    public static void run(JdbcTemplate jdbc, String classpathPath) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(classpathPath).getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                // 剥掉整行注释，避免注释行吞掉同一分号块的后续语句
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    continue;
                }
                content.append(line).append("\n");
            }
            for (String stmt : content.toString().split(";")) {
                String s = stmt == null ? "" : stmt.trim();
                if (!s.isEmpty()) {
                    jdbc.execute(s);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 脚本失败：" + classpathPath, e);
        }
    }
}