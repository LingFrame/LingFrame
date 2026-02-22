package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 服务未找到异常
 */
public class ServiceNotFoundException extends LingException {

    private final String serviceName;
    private final String lingId;

    public ServiceNotFoundException(String serviceName) {
        super("Service not found: " + serviceName);
        this.serviceName = serviceName;
        this.lingId = null;
    }

    public ServiceNotFoundException(String serviceName, String lingId) {
        super("Service not found: " + serviceName + " in ling: " + lingId);
        this.serviceName = serviceName;
        this.lingId = lingId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getLingId() {
        return lingId;
    }
}
