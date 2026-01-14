package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 服务调用异常
 * 用于服务执行过程中发生的错误。
 */
public class InvocationException extends LingException {

    private final String serviceName;
    private final String methodName;

    public InvocationException(String message) {
        super(message);
        this.serviceName = null;
        this.methodName = null;
    }

    public InvocationException(String message, Throwable cause) {
        super(message, cause);
        this.serviceName = null;
        this.methodName = null;
    }

    public InvocationException(String serviceName, String methodName, Throwable cause) {
        super("Invocation failed: " + serviceName + "." + methodName, cause);
        this.serviceName = serviceName;
        this.methodName = methodName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getMethodName() {
        return methodName;
    }
}
