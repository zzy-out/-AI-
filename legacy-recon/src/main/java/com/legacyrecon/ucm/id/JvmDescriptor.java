package com.legacyrecon.ucm.id;

import java.util.List;

/**
 * 01 附录 A signature 规范化（Java 用 JVM 描述符，泛型按类型擦除）。
 * 解析器从 AST/绑定的全限定类型构建描述符；本类提供纯函数转换，保证确定性。
 */
public final class JvmDescriptor {

    private static final java.util.Map<String, String> PRIMITIVES = java.util.Map.of(
            "boolean", "Z", "byte", "B", "char", "C", "short", "S",
            "int", "I", "long", "J", "float", "F", "double", "D",
            "void", "V", "java.lang.Void", "Ljava/lang/Void;"
    );

    private JvmDescriptor() {
    }

    /** 将单个参数的 Java canonical 类型名转成 JVM 描述符（含数组维度）。 */
    public static String descriptor(String canonicalType) {
        if (canonicalType == null || canonicalType.isEmpty()) {
            return "Ljava/lang/Object;";
        }
        // 数组：先数前缀 '['
        int dims = 0;
        while (dims < canonicalType.length() && canonicalType.charAt(dims) == '[') {
            dims++;
        }
        String base = canonicalType.substring(dims);
        String baseDesc;
        String prim = PRIMITIVES.get(base);
        if (prim != null) {
            baseDesc = prim;
        } else {
            // 对象类型：'/' 包名分隔；嵌套类用 '$' 分隔
            baseDesc = "L" + base.replace('.', '/').replace('$', '$') + ";";
        }
        return "[".repeat(dims) + baseDesc;
    }

    /**
     * 方法描述符：(参数描述符)返回描述符。
     * 泛型一律按擦除编码；varargs 与数组同形，由调用方以 metadata.varargs=true 区分。
     */
    public static String methodDescriptor(List<String> paramCanonicalTypes, String returnCanonicalType) {
        StringBuilder sb = new StringBuilder("(");
        for (String p : paramCanonicalTypes) {
            sb.append(descriptor(p));
        }
        sb.append(")").append(descriptor(returnCanonicalType));
        return sb.toString();
    }

    /** 校验单段描述符是否匹配附录 A 的 FieldDescriptor 语法。 */
    public static boolean isValid(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return false;
        }
        return isValidFieldDescriptor(descriptor);
    }

    private static boolean isValidFieldDescriptor(String d) {
        if (d.isEmpty()) {
            return false;
        }
        char c = d.charAt(0);
        switch (c) {
            case 'Z': case 'B': case 'C': case 'D': case 'F':
            case 'I': case 'J': case 'S':            // 无 V（V 仅允许出现在返回）
                return d.length() == 1;
            case '[':
                return isValidFieldDescriptor(d.substring(1));
            case 'L': {
                int semi = d.indexOf(';');
                if (semi <= 1) {
                    return false;
                }
                return semi == d.length() - 1;
            }
            default:
                return false;
        }
    }
}