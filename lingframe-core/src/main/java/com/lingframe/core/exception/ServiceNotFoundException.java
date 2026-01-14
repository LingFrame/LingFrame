package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 服务未找到异常
 */
public class ServiceNotFoundException extends LingException {

    private final String serviceName;
    private final String pluginId;

    public ServiceNotFoundException(String serviceName) {
        super("Service not found: " + serviceName);
        this.serviceName = serviceName;
        this.pluginId = null;
    }

    public ServiceNotFoundException(String serviceName, String pluginId) {
        super("Service not found: " + serviceName + " in plugin: " + pluginId);
        this.serviceName = serviceName;
        this.pluginId = pluginId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPluginId() {
        return pluginId;
    }
}
