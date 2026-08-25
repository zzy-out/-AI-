package com.legacyrecon.parser.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 02.1 JavaConfig（仅 Java 解析器使用）。
 */
public class JavaConfig {
    public List<String> sourceRoots = new ArrayList<>();
    /** "maven" | "gradle" | "manual" */
    public String classpathStrategy = "manual";
    public List<String> manualClasspath = new ArrayList<>();

    public JavaConfig() {
    }
}