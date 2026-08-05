package com.lingframe.dashboard.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Dashboard CORS + CSRF-Origin 过滤器（jakarta 栈薄壳）。
 * <p>
 * 只负责 Servlet 适配与响应写出，CORS/CSRF 决策由 {@link CorsPolicy} 承载。
 *
 * @author lingframe
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardCorsFilter implements Filter {

    private final CorsPolicy corsPolicy;

    public DashboardCorsFilter(CorsProperties corsProperties, AccessTokenProperties accessTokenProperties) {
        this.corsPolicy = new CorsPolicy(corsProperties, accessTokenProperties);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        SecurityDecision decision = corsPolicy.check(new ServletRequestSnapshot(request));
        ServletResponses.applyHeaders(decision, response);
        if (decision.isProceed()) {
            chain.doFilter(request, response);
        } else {
            ServletResponses.applyBody(decision, response);
        }
    }
}
