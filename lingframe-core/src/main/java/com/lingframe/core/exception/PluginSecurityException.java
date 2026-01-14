package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 插件安全异常
 * <p>
 * 用于插件安全验证失败的场景，如危险 API 检测
 */
public class PluginSecurityException extends LingException {

    private final String pluginId;

    public PluginSecurityException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public PluginSecurityException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
