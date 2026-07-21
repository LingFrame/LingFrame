package com.lingframe.dashboard.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 安全响应头 Filter。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
        if (httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                        + "script-src 'self' 'unsafe-inline' 'unsafe-eval' cdn.tailwindcss.com; "
                        + "style-src 'self' 'unsafe-inline' cdn.tailwindcss.com; "
                        + "font-src 'self' data:; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'self'");

        chain.doFilter(request, response);
    }
}
