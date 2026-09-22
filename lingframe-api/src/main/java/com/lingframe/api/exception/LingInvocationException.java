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
        ROUTE_FAILURE("LING-1001", true),
        /** 宏观状态拒绝（如处于 STOPPING 或 DEGRADED 状态） */
        STATE_REJECTED("LING-1002", true),
        /** 熔断器处于打开状态 */
        CIRCUIT_OPEN("LING-2001", true),
        /** 触发限流保护 */
        RATE_LIMITED("LING-2002", true),
        /** 舱壁（线程隔离池）已满，拒绝新执行（与限流语义分离） */
        BULKHEAD_FULL("LING-2003", true),
        /** 安全校验未通过（权限不足、审计失败等） */
        SECURITY_REJECTED("LING-3001", true),
        /** 类加载器/隔离层异常 */
        CLASSLOADER_ERROR("LING-4001", false),
        /** 业务方法内部执行报错 */
        INVOKE_ERROR("LING-5001", false),
        /** 调用执行超时 */
        TIMEOUT("LING-5002", false),
        /** 框架底层内部异常 */
        INTERNAL_ERROR("LING-9001", false);

        private final String code;
        private final boolean governanceRejection;

        ErrorKind(String code, boolean governanceRejection) {
            this.code = code;
            this.governanceRejection = governanceRejection;
        }

        /** 获取数字错误码，如 LING-2001 */
        public String getCode() {
            return code;
        }

        /**
         * 是否为「治理/准入拒绝」。
         *
         * <p>这类错误是平台层在请求到达业务执行前主动拦截（限流、熔断、舱壁、宏观状态拒绝、
         * 权限拒绝、路由失败），并非实例自身执行故障。它们不应计入健康错误率
         * （{@code errorRate}）——否则高并发限流/熔断场景下会错误地把健康实例判为
         * {@code UNHEALTHY}（详见 {@code LingHealthMetrics} 的健康判定）。
         *
         * <p>反之，CLASSLOADER_ERROR / INVOKE_ERROR / TIMEOUT / INTERNAL_ERROR 才是实例真实故障，
         * 应当计入健康错误率。
         */
        public boolean isGovernanceRejection() {
            return governanceRejection;
        }
    }
}
