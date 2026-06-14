package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Dashboard 集中式 CORS + CSRF-Origin 过滤器
 * <p>
 * 替代各 Controller 上分散的 {@code @CrossOrigin(origins = "*")}，
 * 提供统一的、可配置的跨域管控入口。当 access-token 认证开启且
 * 未配置 allowed-origins 时，仅允许同源请求（生产安全默认值）。
 * <p>
 * 对状态变更请求（POST / DELETE / PUT / PATCH），即使非预检请求
 * 也会校验 {@code Origin} 头作为 CSRF 防护。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardCorsFilter implements Filter {

    private final CorsProperties corsProperties;
    private final AccessTokenProperties accessTokenProperties;

    public DashboardCorsFilter(CorsProperties corsProperties,
                               AccessTokenProperties accessTokenProperties) {
        this.corsProperties = corsProperties;
        this.accessTokenProperties = accessTokenProperties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // 仅拦截 Dashboard API 端点
        if (!path.startsWith("/lingframe/dashboard/")) {
            chain.doFilter(request, response);
            return;
        }

        if (!corsProperties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");

        // --- 预检请求 (OPTIONS) ---
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 无条件设置 Vary: Origin，确保代理缓存正确性
            response.setHeader("Vary", "Origin");
            if (origin != null && isOriginAllowed(origin)) {
                setCorsHeaders(response, origin);
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // --- 实际请求 ---
        if (origin != null) {
            // 跨域请求：校验 origin 是否在允许列表中
            if (!isOriginAllowed(origin)) {
                log.warn("CORS rejected: origin={}, path={}, method={}", origin, path, request.getMethod());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Origin not allowed\"}");
                return;
            }
            setCorsHeaders(response, origin);
        }

        // --- CSRF 防护：对状态变更请求校验 Origin 头 ---
        String method = request.getMethod().toUpperCase();
        if (isStateChangingMethod(method) && accessTokenProperties.isEnabled()) {
            if (origin == null) {
                // 认证开启时，状态变更请求缺少 Origin 头直接拒绝
                log.warn("CSRF rejected: missing Origin header, path={}, method={}", path, method);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Missing Origin header\"}");
                return;
            }
            // Origin 已在上方 isOriginAllowed 中校验通过
        }

        chain.doFilter(request, response);
    }

    private boolean isOriginAllowed(String origin) {
        List<String> allowed = corsProperties.getAllowedOrigins();

        // 已配置显式源列表时，逐一比对（大小写不敏感）
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(o -> o.equalsIgnoreCase(origin));
        }

        // 未配置显式源列表：
        // access-token 开启 → 仅允许同源（等效于拒绝所有跨域）
        if (accessTokenProperties.isEnabled()) {
            return false;
        }

        // 开发模式：access-token 未启用，无源配置 = 宽松放行
        return true;
    }

    private void setCorsHeaders(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Methods", String.join(", ", corsProperties.getAllowedMethods()));
        response.setHeader("Access-Control-Allow-Headers", String.join(", ", corsProperties.getAllowedHeaders()));
        response.setHeader("Access-Control-Max-Age", String.valueOf(corsProperties.getMaxAge()));
        response.setHeader("Vary", "Origin");
    }

    private static boolean isStateChangingMethod(String method) {
        return "POST".equals(method) || "DELETE".equals(method)
                || "PUT".equals(method) || "PATCH".equals(method);
    }
}
