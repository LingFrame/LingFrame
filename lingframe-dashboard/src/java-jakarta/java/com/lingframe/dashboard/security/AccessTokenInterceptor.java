package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 访问令牌拦截器。
 */
@Slf4j
@RequiredArgsConstructor
public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AccessTokenProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!properties.isEnabled()) {
            return true;
        }
        String token = request.getHeader("X-Access-Token");
        if (properties.isValidToken(token)) {
            return true;
        }
        log.warn("Access token verification failed: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Unauthorized: invalid or missing access token\"}");
        return false;
    }
}
