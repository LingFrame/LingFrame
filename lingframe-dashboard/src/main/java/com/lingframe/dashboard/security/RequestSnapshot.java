package com.lingframe.dashboard.security;

/**
 * 请求快照：与 Servlet 命名空间无关的请求视图。
 * <p>
 * 主干决策器（{@link AccessTokenVerifier} / {@link RateLimiter} / {@link ReadOnlyPolicy} / {@link CorsPolicy}）
 * 只依赖本接口，不引用 {@code javax.servlet.*} 或 {@code jakarta.servlet.*}，
 * 从而保证双栈（Spring Boot 2 / 3）共用同一份业务逻辑。
 * <p>
 * 分支薄壳（{@code AccessTokenInterceptor} 等）在入口处从 {@code HttpServletRequest}
 * 适配出本接口实例传给决策器。
 *
 * @author lingframe
 */
public interface RequestSnapshot {

    /** 请求方法（GET/POST/...） */
    String getMethod();

    /** 请求 URI（不含 query string） */
    String getRequestUri();

    /** 完整请求 URL（含 scheme/host/port），CORS 同源判断使用 */
    String getRequestUrl();

    /** 是否 HTTPS */
    boolean isSecure();

    /** 客户端 IP */
    String getRemoteAddr();

    /** 取指定请求头 */
    String getHeader(String name);
}
