package com.legacyrecon.ucm.model;

/**
 * 泛型声明参数（名称 + 上界），见 01.3 genericParameters。
 */
public class TypeParameter {
    public String name;
    public java.util.List<TypeRef> bounds = new java.util.ArrayList<>();

    public TypeParameter() {
    }

    public TypeParameter(String name) {
        this.name = name;
    }
}