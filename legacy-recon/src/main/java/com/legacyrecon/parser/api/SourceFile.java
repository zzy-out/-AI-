package com.legacyrecon.parser.api;

/**
 * 02.1 SourceFile。content 为 null 表示删除（幂等，仅用于让解析器更新内部依赖视图）。
 */
public class SourceFile {
    /** 相对项目根的路径 */
    public String path;
    /** 内容 SHA-256，接入层计算 */
    public String checksum;
    /** UTF-8 文本；null 表示该文件已删除 */
    public String content;

    public SourceFile() {
    }

    public SourceFile(String path, String checksum, String content) {
        this.path = path;
        this.checksum = checksum;
        this.content = content;
    }

    public boolean isDeleted() {
        return content == null;
    }
}