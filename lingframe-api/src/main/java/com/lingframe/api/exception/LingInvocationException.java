package com.lingframe.api.exception;

/**
 * Pipeline 出口的唯一异常类型。调用者永远只看到 LingInvocationException。
 */
public class LingInvocationException extends LingRuntimeException {
    /**
     * 全限定服务标识 (Fully Qualified Service ID)
     */
    private final String fqsid;

    /**
     * 具体的错误分类
     */
    private final ErrorKind kind;

    public LingInvocationException(String fqsid, ErrorKind kind) {
        this(fqsid, kind, kind.name() + " for service: " + fqsid);
    }

    public LingInvocationException(String fqsid, ErrorKind kind, String message) {
        super(fqsid, message);
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

    /**
     * 异常类型枚举
     */
    public enum ErrorKind {
        /** 找不到目标实例（路由失败） */
        ROUTE_FAILURE,
        /** 宏观状态拒绝（如处于 STOPPING 或 DEGRADED 状态） */
        STATE_REJECTED,
        /** 熔断器处于打开状态 */
        CIRCUIT_OPEN,
        /** 触发限流保护 */
        RATE_LIMITED,
        /** 安全校验未通过（权限不足、审计失败等） */
        SECURITY_REJECTED,
        /** 类加载器/隔离层异常 */
        CLASSLOADER_ERROR,
        /** 业务方法内部执行报错 */
        INVOKE_ERROR,
        /** 调用执行超时 */
        TIMEOUT,
        /** 框架底层内部异常 */
        INTERNAL_ERROR
    }
}
