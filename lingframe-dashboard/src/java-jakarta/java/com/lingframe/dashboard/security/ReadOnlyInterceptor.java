package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 只读模式拦截器。
 */
@Slf4j
@RequiredArgsConstructor
public class ReadOnlyInterceptor implements HandlerInterceptor {

    private final ReadOnlyProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!properties.isEnabled()) {
            return true;
        }
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String uri = request.getRequestURI();
        if (properties.getAllowedPaths() != null) {
            for (String allowed : properties.getAllowedPaths()) {
                if (uri.startsWith(allowed)) {
                    return true;
                }
            }
        }
        log.warn("Write operation rejected in read-only mode: {} {}", method, uri);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"当前为只读模式，写操作已禁用\"}");
        return false;
    }
}
