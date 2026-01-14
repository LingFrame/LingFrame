package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 服务不可用异常
 * <p>
 * 用于服务存在但当前不可用的场景
 */
public class ServiceUnavailableException extends LingException {

    private final String serviceName;
    private final String reason;

    public ServiceUnavailableException(String serviceName, String reason) {
        super("Service unavailable: " + serviceName + " - " + reason);
        this.serviceName = serviceName;
        this.reason = reason;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getReason() {
        return reason;
    }
}
