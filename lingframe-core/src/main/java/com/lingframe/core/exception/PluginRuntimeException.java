package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 插件运行时异常
 * <p>
 * 用于插件执行过程中发生的错误
 */
public class PluginRuntimeException extends LingException {

    private final String pluginId;

    public PluginRuntimeException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public PluginRuntimeException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
