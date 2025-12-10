package com.lingframe.api.context;

public class PluginContextHolder {
    private static final ThreadLocal<String> CURRENT_PLUGIN_ID = new ThreadLocal<>();

    public static void set(String pluginId) {
        CURRENT_PLUGIN_ID.set(pluginId);
    }

    public static String get() {
        return CURRENT_PLUGIN_ID.get();
    }

    public static void clear() {
        CURRENT_PLUGIN_ID.remove();
    }
}
