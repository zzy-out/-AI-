package com.legacyrecon.parser.api;

/**
 * 02.1 ProjectConfig。
 */
public class ProjectConfig {
    public String encoding = "UTF-8";
    public JavaConfig java = new JavaConfig();
    public CppConfig cpp = new CppConfig();

    public ProjectConfig() {
    }
}