package com.lingframe.api.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生递归深拷贝工具。
 * <p>
 * 识别 Map / List / Set 等可变集合递归拷贝；
 * 其他类型视为不可变，保留引用（String / Number / Boolean / enum / 自定义值对象等）。
 * <p>
 * 设计原则：作为灵珑基础设施核心工具，零第三方依赖、纯原生实现，
 * 避免引入 Jackson 等序列化框架带来的反射开销与序列化兼容性问题。
 */
public final class DeepCopyUtils {

    private DeepCopyUtils() {}

    /**
     * 深拷贝 Map&lt;String, Object&gt;。
     * 递归处理嵌套的 Map / List / Set；其他 value 视为不可变保留引用。
     */
    public static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) return null;
        Map<String, Object> copy = new HashMap<>(source.size());
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map) {
            Map<?, ?> src = (Map<?, ?>) value;
            Map<String, Object> nested = new HashMap<>(src.size());
            for (Map.Entry<?, ?> e : src.entrySet()) {
                nested.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return nested;
        }
        if (value instanceof List) {
            List<Object> nested = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) {
                nested.add(deepCopyValue(item));
            }
            return nested;
        }
        if (value instanceof Set) {
            Set<Object> nested = new LinkedHashSet<>(((Set<?>) value).size());
            for (Object item : (Set<?>) value) {
                nested.add(deepCopyValue(item));
            }
            return nested;
        }
        // String / Number / Boolean / enum / null / 自定义不可变值对象：保留引用
        return value;
    }
}
