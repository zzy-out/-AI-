package com.legacyrecon.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 全局 Jackson 工具，统一序列化 UCM 模型与 DATABASE JSON 列。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String s, Class<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 反序列化失败: " + s, e);
        }
    }

    public static <T> T fromJson(String s, TypeReference<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 反序列化失败: " + s, e);
        }
    }
}