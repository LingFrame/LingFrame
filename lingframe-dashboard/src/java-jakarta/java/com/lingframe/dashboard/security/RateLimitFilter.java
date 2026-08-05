package com.lingframe.dashboard.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
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
 * 简易 API 限流 Filter（jakarta 栈薄壳）。
 * <p>
 * 只负责 Servlet 适配与响应写出，限流算法由 {@link RateLimiter} 承载；
 * 本 Filter 作为 Spring Bean 承载 {@code @Scheduled} 定时清理委托给 {@link RateLimiter}。
 *
 * @author lingframe
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimitProperties properties) {
        this.rateLimiter = new RateLimiter(properties);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        SecurityDecision decision = rateLimiter.check(new ServletRequestSnapshot(httpRequest));
        ServletResponses.applyHeaders(decision, httpResponse);
        if (decision.isProceed()) {
            chain.doFilter(request, response);
        } else {
            ServletResponses.applyBody(decision, httpResponse);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupIdleBuckets() {
        rateLimiter.cleanupIdleBuckets();
    }
}
