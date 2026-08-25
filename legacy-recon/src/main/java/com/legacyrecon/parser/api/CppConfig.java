package com.legacyrecon.parser.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 02.1 CppConfig（仅 C/C++ 解析器使用）。
 */
public class CppConfig {
    /** compile_commands.json 路径，可空 */
    public String compileCommandsPath;
    public List<String> fallbackIncludeDirs = new ArrayList<>();
    public List<String> fallbackDefines = new ArrayList<>();
    /** "c11" | "c++17" ... */
    public String standard = "c++17";
    /** "declarations" | "whitelist" | "full" | "off" */
    public String templatePolicy = "declarations";
    public List<String> templateWhitelist = new ArrayList<>();
    /** 宏记录开关（R13） */
    public boolean recordMacros = true;

    public CppConfig() {
    }
}