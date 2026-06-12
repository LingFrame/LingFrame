package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 访问令牌拦截器：校验请求中的 token 参数或 Header
 */
@Slf4j
@RequiredArgsConstructor
public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AccessTokenProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 未启用 token 时放行所有请求
        if (!properties.isEnabled()) {
            return true;
        }

        // 仅从 Header 获取 token（URL 参数方式不安全，已禁用）
        String token = request.getHeader("X-Access-Token");

        if (properties.isValidToken(token)) {
            return true;
        }

        log.warn("Access token verification failed: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized: invalid or missing access token\"}");
        return false;
    }
}
