package com.example;

import java.util.List;

/**
 * 通用工具：泛型方法与校验规则。
 * @param <T> 校验对象类型
 */
public class Validator<T> {
    private final T value;
    private final List<String> errors;

    public Validator(T value) {
        this.value = value;
        this.errors = new java.util.ArrayList<>();
    }

    public static <T> Validator<T> of(T value) {
        return new Validator<>(value);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public void check(boolean condition, String message) {
        if (!condition) {
            this.errors.add(message);
        }
    }
}