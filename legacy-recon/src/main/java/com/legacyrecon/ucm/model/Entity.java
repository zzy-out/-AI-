package com.legacyrecon.ucm.model;

/**
 * Entity（01.3）。字段均对应文档 01.3 表格。
 * id 为全局唯一、跨运行稳定的确定性 ID（ADR-005）。
 */
public class Entity {
    public String id;
    public EntityType type;
    public String name;
    public String qualifiedName;
    /** 可执行实体的规范化签名（附录 A，JVM 描述符 / C++ canonical） */
    public String signature;
    public String language;                       // Java | C++ | C
    public SourceLocation location;
    public java.util.List<String> modifiers = new java.util.ArrayList<>();
    public TypeRef typeRef;
    public java.util.List<TypeParameter> genericParameters = new java.util.ArrayList<>();
    public java.util.List<TypeRef> typeArguments = new java.util.ArrayList<>();
    public String docComment;
    public java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();

    public Entity() {
    }
}