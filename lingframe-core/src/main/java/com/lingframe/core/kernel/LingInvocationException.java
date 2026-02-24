package com.lingframe.core.kernel;

import com.lingframe.core.exception.LingRuntimeException;

/**
 * Pipeline 出口的唯一异常类型。调用者永远只看到 LingInvocationException。
 */
public class LingInvocationException extends LingRuntimeException {
    private final String fqsid;
    private final ErrorKind kind;

    public LingInvocationException(String fqsid, ErrorKind kind) {
        super(fqsid, kind.name() + " for service: " + fqsid);
        this.fqsid = fqsid;
        this.kind = kind;
    }

    public LingInvocationException(String fqsid, ErrorKind kind, Throwable cause) {
        super(fqsid, kind.name() + " for service: " + fqsid, cause);
        this.fqsid = fqsid;
        this.kind = kind;
    }

    public String getFqsid() {
        return fqsid;
    }

    public ErrorKind getKind() {
        return kind;
    }

    public enum ErrorKind {
        ROUTE_FAILURE, // 找不到目标实例
        STATE_REJECTED, // 宏观状态拒绝（STOPPING/DEGRADED）
        CIRCUIT_OPEN, // 熔断器打开
        RATE_LIMITED, // 限流
        CLASSLOADER_ERROR, // 隔离层异常
        INVOKE_ERROR, // 业务方法本身抛出异常
        INTERNAL_ERROR // 框架内部 bug
    }
}
