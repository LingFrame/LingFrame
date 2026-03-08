package com.lingframe.api.context;

import java.util.Map;

/**
 * 灵元上下文持有者
 * 提供基于 ThreadLocal 的当前调用链路上下文（灵元ID、灰度标签等）存取支持。
 */
public class LingContextHolder {
    /** 当前执行线程所属的灵元 ID */
    private static final ThreadLocal<String> CURRENT_Ling_ID = new ThreadLocal<>();

    /** 用于存储灰度/金丝雀标签的上下文 */
    private static final ThreadLocal<Map<String, String>> LABELS = new ThreadLocal<>();

    /**
     * 设置当前线程关联的灵元 ID
     * 
     * @param lingId 灵元唯一标识
     */
    public static void set(String lingId) {
        CURRENT_Ling_ID.set(lingId);
    }

    public static String get() {
        return CURRENT_Ling_ID.get();
    }

    // 设置灰度/金丝雀标签
    public static void setLabels(Map<String, String> labels) {
        LABELS.set(labels);
    }

    // 获取灰度/金丝雀标签
    public static Map<String, String> getLabels() {
        return LABELS.get();
    }

    public static void clear() {
        CURRENT_Ling_ID.remove();
        LABELS.remove();
    }
}
