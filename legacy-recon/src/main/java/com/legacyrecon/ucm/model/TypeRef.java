package com.legacyrecon.ucm.model;

/**
 * 01.3.1 TypeRef。类型引用有两种归宿：项目内类型（存在实体）与项目外类型（JDK/标准库/第三方依赖）。
 * kind: primitive | class | interface | enum | struct | union | array | pointer | reference | typeParameter | unknown
 */
public class TypeRef {
    public String kind = "unknown";
    /** 全限定名或短名；canonical name，如 java.lang.String */
    public String name;
    /** 项目内类型时指向实体 ID；external=true 时必须为空 */
    public String entityId;
    /** 项目外类型（JDK / 标准库 / 第三方依赖） */
    public boolean external;
    /** Java 数组维度；C/C++ 用 kind=pointer/reference 表达 */
    public int arrayDimensions;

    public TypeRef() {
    }

    public TypeRef(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    public static TypeRef primitive(String name) {
        return new TypeRef("primitive", name);
    }

    public static TypeRef unknown(String name) {
        return new TypeRef("unknown", name);
    }
}