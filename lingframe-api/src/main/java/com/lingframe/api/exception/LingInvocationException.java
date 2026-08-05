package com.lingframe.api.exception;

/**
 * Pipeline 出口的唯一异常类型，调用者最终只会看到 LingInvocationException。
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
        this(fqsid, kind, kind.code + " " + kind.name() + " for service: " + fqsid);
    }

    public LingInvocationException(String fqsid, ErrorKind kind, String message) {
        super(fqsid, message);
        this.fqsid = fqsid;
        this.kind = kind;
    }

    public LingInvocationException(String fqsid, ErrorKind kind, Throwable cause) {
        super(fqsid, kind.code + " " + kind.name() + " for service: " + fqsid, cause);
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
     * 异常类型枚举，每个类型携带 LING-XXXX 格式数字错误码，
     * 便于日志检索、告警配置和运维排障。
     */
    public enum ErrorKind {
        /** 找不到目标实例（路由失败） */
        ROUTE_FAILURE("LING-1001"),
        /** 宏观状态拒绝（如处于 STOPPING 或 DEGRADED 状态） */
        STATE_REJECTED("LING-1002"),
        /** 熔断器处于打开状态 */
        CIRCUIT_OPEN("LING-2001"),
        /** 触发限流保护 */
        RATE_LIMITED("LING-2002"),
        /** 舱壁（线程隔离池）已满，拒绝新执行（与限流语义分离） */
        BULKHEAD_FULL("LING-2003"),
        /** 安全校验未通过（权限不足、审计失败等） */
        SECURITY_REJECTED("LING-3001"),
        /** 类加载器/隔离层异常 */
        CLASSLOADER_ERROR("LING-4001"),
        /** 业务方法内部执行报错 */
        INVOKE_ERROR("LING-5001"),
        /** 调用执行超时 */
        TIMEOUT("LING-5002"),
        /** 框架底层内部异常 */
        INTERNAL_ERROR("LING-9001");

        private final String code;

        ErrorKind(String code) {
            this.code = code;
        }

        /** 获取数字错误码，如 LING-2001 */
        public String getCode() {
            return code;
        }
    }
}
