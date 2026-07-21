package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易 API 限流 Filter：基于 IP + 路径的令牌桶限流。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter implements Filter {

    private final RateLimitProperties properties;
    private final Map<String, TokenBucket> perIpBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        if (!path.startsWith("/lingframe/dashboard/") || path.startsWith("/lingframe/dashboard/ui")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(httpRequest);
        TokenBucket bucket = perIpBuckets.computeIfAbsent(clientIp,
                k -> new TokenBucket(properties.getMaxRequestsPerSecond()));

        if (!bucket.tryAcquire()) {
            log.warn("Request rate limited: ip={}, path={}", clientIp, path);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"success\":false,\"message\":\"请求过于频繁，请稍后重试\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (properties.isTrustedProxy(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
        }
        return remote;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupIdleBuckets() {
        long now = System.currentTimeMillis();
        perIpBuckets.entrySet().removeIf(e ->
                now - e.getValue().lastAccessTime > properties.getIpIdleThresholdMs());
    }

    private static class TokenBucket {
        private final int capacity;
        private long tokens;
        private long lastRefillTime = System.currentTimeMillis();
        volatile long lastAccessTime = System.currentTimeMillis();

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            refillLocked();
            lastAccessTime = System.currentTimeMillis();
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        private void refillLocked() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed >= 1000) {
                long newTokens = (elapsed / 1000) * capacity;
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillTime = now;
            }
        }
    }
}
