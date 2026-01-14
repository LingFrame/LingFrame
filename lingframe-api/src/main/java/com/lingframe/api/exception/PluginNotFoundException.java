package com.lingframe.api.exception;

/**
 * 插件未找到异常
 * 当请求的插件不存在时抛出此异常。
 */
public class PluginNotFoundException extends LingException {

    private final String pluginId;

    public PluginNotFoundException(String pluginId) {
        super("Plugin not found: " + pluginId);
        this.pluginId = pluginId;
    }

    public PluginNotFoundException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
