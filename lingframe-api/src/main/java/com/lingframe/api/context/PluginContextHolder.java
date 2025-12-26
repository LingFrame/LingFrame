package com.lingframe.api.context;

import java.util.Map;

public class PluginContextHolder {
    private static final ThreadLocal<String> CURRENT_PLUGIN_ID = new ThreadLocal<>();

    // 用于存储灰度/金丝雀标签
    private static final ThreadLocal<Map<String, String>> LABELS = new ThreadLocal<>();

    public static void set(String pluginId) {
        CURRENT_PLUGIN_ID.set(pluginId);
    }

    public static String get() {
        return CURRENT_PLUGIN_ID.get();
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
        CURRENT_PLUGIN_ID.remove();
        LABELS.remove();
    }
}
