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

/**
 * 安全响应头 Filter：防止点击劫持、MIME 嗅探、XSS 等
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 防止点击劫持
        httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
        // 防止 MIME 嗅探
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        // XSS 防护（旧浏览器）
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
        // HSTS（需配合 HTTPS 使用，此处预置）
        if (httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        // Referrer 策略
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // CSP：允许同源 + 白名单 CDN 资源
        httpResponse.setHeader("Content-Security-Policy",
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' cdn.jsdelivr.net cdn.tailwindcss.com; " +
            "style-src 'self' 'unsafe-inline' cdn.jsdelivr.net cdn.tailwindcss.com; " +
            "font-src 'self' cdn.jsdelivr.net cdn.tailwindcss.com; " +
            "img-src 'self' data:; " +
            "connect-src 'self' cdn.jsdelivr.net; " +
            "frame-ancestors 'self'");

        chain.doFilter(request, response);
    }
}
