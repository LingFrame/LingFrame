package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 插件安装异常
 */
public class PluginInstallException extends LingException {

    private final String pluginId;

    public PluginInstallException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public PluginInstallException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
