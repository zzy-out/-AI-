package com.legacyrecon.ucm.model;

/**
 * 02.1 ParseIssue。code 为稳定错误码，如 "JAVA.UNRESOLVED_SYMBOL"。
 */
public class ParseIssue {
    public enum Severity { ERROR, WARNING, INFO }

    public Severity severity;
    public String code;
    public String message;
    public SourceLocation location;

    public ParseIssue() {
    }

    public ParseIssue(Severity severity, String code, String message, SourceLocation location) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.location = location;
    }

    public static ParseIssue error(String code, String message, SourceLocation loc) {
        return new ParseIssue(Severity.ERROR, code, message, loc);
    }

    public static ParseIssue warning(String code, String message, SourceLocation loc) {
        return new ParseIssue(Severity.WARNING, code, message, loc);
    }

    public static ParseIssue info(String code, String message, SourceLocation loc) {
        return new ParseIssue(Severity.INFO, code, message, loc);
    }

    /** 按字符串严重级别构造（子进程协议反序列化）。 */
    public static ParseIssue valueOf(String severity, String code, String message, SourceLocation loc) {
        Severity s;
        try {
            s = Severity.valueOf(severity == null ? "INFO" : severity);
        } catch (IllegalArgumentException e) {
            s = Severity.INFO;
        }
        return new ParseIssue(s, code, message, loc);
    }
}