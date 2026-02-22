package com.lingframe.api.context;

import java.util.Map;

public class LingContextHolder {
    private static final ThreadLocal<String> CURRENT_Ling_ID = new ThreadLocal<>();

    // 用于存储灰度/金丝雀标签
    private static final ThreadLocal<Map<String, String>> LABELS = new ThreadLocal<>();

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
