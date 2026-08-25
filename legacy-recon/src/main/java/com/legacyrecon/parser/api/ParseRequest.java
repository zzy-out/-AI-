package com.legacyrecon.parser.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 02.1 ParseRequest。
 */
public class ParseRequest {
    /** 待解析文件；content 为 null 表示删除 */
    public List<SourceFile> files = new ArrayList<>();
    public ProjectConfig config = new ProjectConfig();
    /** 可空；见 02.4 */
    public IncrementalInfo incremental;
    /** 项目根目录 */
    public Path workingDir;

    public ParseRequest() {
    }
}