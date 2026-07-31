package com.lingframe.dashboard.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * 安全决策响应应用器（jakarta 栈）。
 * <p>
 * 把 {@link SecurityDecision} 应用到 HttpServletResponse。javax 栈有同名实现。
 *
 * @author lingframe
 */
public final class ServletResponses {

    private ServletResponses() {
    }

    /**
     * 应用决策携带的响应头（放行/终止均可能使用，如 CORS 头）。
     */
    public static void applyHeaders(SecurityDecision decision, HttpServletResponse response) {
        if (!decision.getHeaders().isEmpty()) {
            for (Map.Entry<String, String> e : decision.getHeaders().entrySet()) {
                response.setHeader(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 应用终止决策的状态码、Content-Type 与响应体。
     */
    public static void applyBody(SecurityDecision decision, HttpServletResponse response) throws IOException {
        if (decision.getStatus() != null) {
            response.setStatus(decision.getStatus());
        }
        if (decision.getContentType() != null) {
            response.setContentType(decision.getContentType());
        }
        if (decision.getBody() != null) {
            response.getWriter().write(decision.getBody());
        }
    }
}
