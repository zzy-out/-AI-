package com.legacyrecon.ucm.id;

import com.legacyrecon.ucm.model.SourceLocation;

/**
 * 01.5 确定性 ID 规则（ADR-005 / ADR-008）。
 * ID 仅含 ASCII 可打印字符；任何组件不得解析其内部结构做语义推断。
 */
public final class DeterministicId {

    public static final String EXT = "ext";

    private DeterministicId() {
    }

    /** 类型实体：lang:qualifiedName */
    public static String typeEntity(String lang, String qualifiedName) {
        return lang + ":" + qualifiedName;
    }

    /** 可执行实体：lang:qualifiedName#signature */
    public static String executableEntity(String lang, String qualifiedName, String signature) {
        return lang + ":" + qualifiedName + "#" + signature;
    }

    /** 字段：lang:qualifiedName.field */
    public static String fieldEntity(String lang, String qualifiedNameField) {
        return lang + ":" + qualifiedNameField;
    }

    /** 参数：父 ID + .param(index)，index 从 1 起 */
    public static String parameter(String parentId, int index) {
        return parentId + ".param(" + index + ")";
    }

    /** 局部/匿名/宏：lang:filePath:startLine:startCol（位置兜底） */
    public static String locationFallback(String lang, String filePath, int startLine, int startCol) {
        return lang + ":" + filePath + ":" + startLine + ":" + startCol;
    }

    /**
     * 关系 ID：srcId:TYPE:targetId@startLine:startCol[#n]（ADR-008）。
     * targetId 为空时该段记 "ext"。同位置多条按（列, 参数索引）升序子编号。
     *
     * @param startLine/startCol 必须取自关系 location（必填）。
     * @param subNum 同位置子序号；0 表示唯一（不追加 #n）。
     */
    public static String relation(String srcId, String type, String targetId, int startLine, int startCol, int subNum) {
        String target = (targetId == null || targetId.isEmpty()) ? EXT : targetId;
        return srcId + ":" + type + ":" + target + "@" + startLine + ":" + startCol + (subNum > 0 ? "#" + subNum : "");
    }

    /** 关系 ID 的便捷重载：以 location 起止位置生成。 */
    public static String relation(String srcId, String type, String targetId, SourceLocation loc, int subNum) {
        return relation(srcId, type, targetId, loc.startLine, loc.startCol, subNum);
    }
}