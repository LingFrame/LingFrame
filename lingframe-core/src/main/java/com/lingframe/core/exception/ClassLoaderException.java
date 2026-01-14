package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 类加载器异常
 * 用于类加载器初始化、JAR加载等失败场景。
 */
public class ClassLoaderException extends LingException {

    private final String pluginId;
    private final String resource;

    public ClassLoaderException(String message) {
        super(message);
        this.pluginId = null;
        this.resource = null;
    }

    public ClassLoaderException(String message, Throwable cause) {
        super(message, cause);
        this.pluginId = null;
        this.resource = null;
    }

    public ClassLoaderException(String pluginId, String resource, String message) {
        super(message);
        this.pluginId = pluginId;
        this.resource = resource;
    }

    public ClassLoaderException(String pluginId, String resource, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
        this.resource = resource;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getResource() {
        return resource;
    }
}
