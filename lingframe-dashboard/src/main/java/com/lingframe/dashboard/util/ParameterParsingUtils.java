package com.lingframe.dashboard.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 演练场参数解析与类型转换工具类
 */
@Slf4j
public class ParameterParsingUtils {

    public static Object[] convertArgs(String[] parameterTypes, Object[] args, ClassLoader classLoader, ObjectMapper objectMapper) throws Exception {
        if (parameterTypes == null || args == null || parameterTypes.length == 0) {
            return args;
        }
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (i >= parameterTypes.length) {
                converted[i] = args[i];
                continue;
            }
            String typeName = parameterTypes[i];
            Object value = args[i];
            if (value == null) {
                converted[i] = null;
                continue;
            }
            try {
                Class<?> targetClass = resolveClass(typeName, classLoader);
                converted[i] = convertValue(value, targetClass, objectMapper);
            } catch (Exception e) {
                throw new IllegalArgumentException("arg[" + i + "] (" + typeName + "): " + e.getMessage(), e);
            }
        }
        return converted;
    }

    public static Class<?> resolveClass(String typeName, ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> primitive = resolvePrimitiveType(typeName);
        if (primitive != null) {
            return primitive;
        }
        // 仅加载不做类初始化（initialize=false），避免恶意/意外传入的类名触发静态块副作用；
        // 真实业务执行阶段仍会按正常路径完成类的初始化。
        if (classLoader != null) {
            return Class.forName(typeName, false, classLoader);
        } else {
            return Class.forName(typeName, false, ParameterParsingUtils.class.getClassLoader());
        }
    }

    public static Class<?> resolvePrimitiveType(String typeName) {
        switch (typeName) {
            case "int": return int.class;
            case "long": return long.class;
            case "double": return double.class;
            case "float": return float.class;
            case "boolean": return boolean.class;
            case "char": return char.class;
            case "byte": return byte.class;
            case "short": return short.class;
            default: return null;
        }
    }

    public static Object convertValue(Object value, Class<?> targetClass, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        if (targetClass.isInstance(value)) {
            return value;
        }
        if (targetClass == int.class || targetClass == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        }
        if (targetClass == long.class || targetClass == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }
        if (targetClass == double.class || targetClass == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        }
        if (targetClass == float.class || targetClass == Float.class) {
            if (value instanceof Number) return ((Number) value).floatValue();
            return Float.parseFloat(value.toString());
        }
        if (targetClass == boolean.class || targetClass == Boolean.class) {
            return Boolean.parseBoolean(value.toString());
        }
        // 前端输入框传来的 JSON 文本会被解析为 String，需要先反序列化再转换。
        // Jackson 的 convertValue 不支持 String→POJO（除非有 String 构造方法），
        // 必须改用 readValue 走完整的反序列化流程。
        if (value instanceof String && targetClass != String.class) {
            String strValue = ((String) value).trim();
            if (strValue.startsWith("{") || strValue.startsWith("[")) {
                try {
                    return objectMapper.readValue(strValue, targetClass);
                } catch (Exception e) {
                    log.debug("Failed to readValue from JSON string to {}, fallback to convertValue", targetClass.getName());
                }
            }
        }
        try {
            return objectMapper.convertValue(value, targetClass);
        } catch (Exception e) {
            // 转换失败即抛：让上层返回明确的参数错误，避免把未转换的原始值
            // （如 LinkedHashMap）传给目标方法导致反射调用时报 IllegalArgumentException，
            // 错误信息更难定位。
            throw new IllegalArgumentException(
                    "cannot convert value to target class " + targetClass.getName() + ": " + e.getMessage(), e);
        }
    }
}
