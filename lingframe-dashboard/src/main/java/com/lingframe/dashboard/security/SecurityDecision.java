package com.lingframe.dashboard.security;

import java.util.Collections;
import java.util.Map;

/**
 * 安全决策结果：主干决策器返回，分支薄壳据此操作 Servlet 响应。
 * <p>
 * 不引用 Servlet 类型，保证双栈共用。薄壳的统一处理：
 * <pre>
 * if (!decision.getHeaders().isEmpty()) {
 *     decision.getHeaders().forEach(response::setHeader);
 * }
 * if (decision.isProceed()) {
 *     chain.doFilter(request, response);
 * } else {
 *     response.setStatus(decision.getStatus());
 *     response.setContentType(decision.getContentType());
 *     response.getWriter().write(decision.getBody());
 * }
 * </pre>
 *
 * @author lingframe
 */
public final class SecurityDecision {

    /** 是否继续过滤器链：true=放行，false=终止并写出响应 */
    private final boolean proceed;
    /** 终止时的 HTTP 状态码；放行时为 null */
    private final Integer status;
    /** 终止时的 Content-Type；放行时为 null */
    private final String contentType;
    /** 终止时的响应体；放行时为 null */
    private final String body;
    /** 需设置的响应头（放行/终止均可能携带，如 CORS 头） */
    private final Map<String, String> headers;

    private SecurityDecision(boolean proceed, Integer status, String contentType, String body, Map<String, String> headers) {
        this.proceed = proceed;
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
    }

    /** 放行：继续过滤器链 */
    public static SecurityDecision proceed() {
        return new SecurityDecision(true, null, null, null, Collections.emptyMap());
    }

    /** 放行并设置响应头（如 CORS 放行带 Access-Control-* 头） */
    public static SecurityDecision proceedWithHeaders(Map<String, String> headers) {
        return new SecurityDecision(true, null, null, null, headers);
    }

    /** 终止链并写出错误响应 */
    public static SecurityDecision terminate(int status, String contentType, String body) {
        return new SecurityDecision(false, status, contentType, body, Collections.emptyMap());
    }

    /** 终止链并设置响应头（如 OPTIONS preflight 返回 200 + CORS 头） */
    public static SecurityDecision terminateWithHeaders(int status, Map<String, String> headers) {
        return new SecurityDecision(false, status, null, null, headers);
    }

    public boolean isProceed() {
        return proceed;
    }

    public Integer getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
