package com.legacyrecon.ucm.model;

/**
 * 01.6 SourceLocation 约定：行/列均从 1 开始；区间为半开 [start, end)。
 * file 必须为接入层文件清单中的相对路径。
 */
public class SourceLocation {
    /** 相对项目根的路径 */
    public String file;
    public int startLine;
    public int startCol;
    public int endLine;
    public int endCol;

    public SourceLocation() {
    }

    public SourceLocation(String file, int startLine, int startCol, int endLine, int endCol) {
        this.file = file;
        this.startLine = startLine;
        this.startCol = startCol;
        this.endLine = endLine;
        this.endCol = endCol;
    }

    public boolean contains(int line) {
        return line >= startLine && line < endLine;
    }

    /**
     * 位置级包含判断（半开区间 [start, end)，按 (line, col) 元组比较）。
     * 单行实体 startLine == endLine 时，仅比较行号的判断会把区间当成空集，
     * 必须结合列号判断。
     */
    public boolean contains(int line, int col) {
        boolean afterStart = line > startLine || (line == startLine && col >= startCol);
        boolean beforeEnd = line < endLine || (line == endLine && col < endCol);
        return afterStart && beforeEnd;
    }

    @Override
    public String toString() {
        return file + "#L" + startLine + "-" + (endLine - 1);
    }
}